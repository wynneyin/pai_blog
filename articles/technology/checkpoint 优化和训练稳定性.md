---
title: checkpoint 和训练稳定性
date: 2026-08-05
tags: [训练,checkpoint]
---
# Checkpoint
## checkpoint 的大小
Checkpoint 的内容大致是：模型参数 + 优化器状态 + RNG 状态 + 训练 step / 数据迭代器位置。前两项占绝对大头。

对一个P参数、Adam 优化器、混合精度训练的模型，fp32 master weight + fp32 的 m 和 v，每参数需要 12 字节；bf16 的工作参数额外 2 字节；bf16 梯度 2 字节。粗算 16 字节/参数。另外需要注意，除了模型参数和优化器，Activation Checkpointing 和部分 CPU Offload 机制占用的 Pinned Memory（见后文 13.2 节）在万卡集群上也极大占用空间，其规划不足极易引发 OOM 或写盘竞争。

## 写入模式
1.全量单文件写入，rank0 gather 所有的参数，写一个大文件，rank0 成为带宽瓶颈，gather 自身也是O(P)的通信开销。
2.分片写入，每个 DP rank 写自己的那个一份 shard，不做聚合，恢复的时候按照相同的分片拓扑加载，缺点是分片拓扑改了，就很难加载，需要 reshard 工具。
3.拓扑无关分片，pytorch DCP 就是这种，把每个 tensor 的分片元信息单独记录，加载时按目标拓扑重新切分，最主流的方法。

## 存储层
本地 NVMe -> 分布式文件系统 -> 对象存储
为什么一定要有本地 NVMe 这层？因为训练节点写本地盘时带宽能吃到接近硬件上限（H100 节点 Gen5 NVMe 随便 10 GB/s），而写并行文件系统要走 RDMA / TCP，一窝蜂并发容易打爆存储网络，反过来影响训练通信本身。![image-20260802122418401](https://wynneyin.oss-cn-hangzhou.aliyuncs.com/image-20260802122418401.png)

## 异步和流式checkpoint 

异步 checkpoint 的核心思路：**把”打快照”和”写盘”解耦**。

- 快照阶段（在主 stream 上、毫秒级）：调用 `tensor.clone()` 或 pinned-host copy，把 GPU tensor 拷贝到 CPU 固定内存。这步必须和训练同步，因为下一 step 就要覆盖这些参数了。
- 写盘阶段（在 background thread / process）：从 CPU buffer 往 NVMe / PFS 写，这步完全异步，训练 step 继续跑。

## PyTorch DCP异步保存

```python
# ckpt_dcp.py
# PyTorch >= 2.3，推荐 2.4+
import os
import torch
import torch.distributed as dist
import torch.distributed.checkpoint as dcp
from torch.distributed.checkpoint.state_dict import (
    get_state_dict, set_state_dict,
    StateDictOptions,
)
from torch.distributed.checkpoint import FileSystemWriter, FileSystemReader

CKPT_ROOT = "/mnt/pfs/runs/llama3-405b/ckpt"

def save_async(model, optimizer, step: int, writer_threads: int = 8):
    """异步分布式 checkpoint：快照在主线程同步，写盘在后台。"""
    path = os.path.join(CKPT_ROOT, f"step-{step:09d}")
    model_sd, optim_sd = get_state_dict(
        model, optimizer,
        options=StateDictOptions(full_state_dict=False, cpu_offload=True),
    )
    state = {"model": model_sd, "optim": optim_sd, "step": step}
    writer = FileSystemWriter(path, thread_count=writer_threads, single_file_per_rank=True)
    # async_save 返回一个 Future，训练线程立刻可以继续
    fut = dcp.async_save(state, storage_writer=writer)
    return fut

def load_latest(model, optimizer) -> int:
    """按目录中最新 step 恢复，返回下一步的 step。"""
    steps = sorted(
        int(d.split("-")[1]) for d in os.listdir(CKPT_ROOT)
        if d.startswith("step-")
    )
    if not steps:
        return 0
    step = steps[-1]
    path = os.path.join(CKPT_ROOT, f"step-{step:09d}")
    model_sd, optim_sd = get_state_dict(
        model, optimizer,
        options=StateDictOptions(full_state_dict=False, cpu_offload=True),
    )
    state = {"model": model_sd, "optim": optim_sd, "step": 0}
    dcp.load(state, storage_reader=FileSystemReader(path))
    set_state_dict(
        model, optimizer, model_state_dict=state["model"], optim_state_dict=state["optim"]
    )
    if dist.get_rank() == 0:
        print(f"[ckpt] resumed at step={step}")
    return step + 1

# 训练主循环
def train_loop(model, optimizer, dataloader, max_steps, save_every=200):
    start_step = load_latest(model, optimizer)
    pending_fut = None
    for step in range(start_step, max_steps):
        batch = next(dataloader)
        loss = model(batch).loss
        loss.backward()
        optimizer.step()
        optimizer.zero_grad(set_to_none=True)

        if step > 0 and step % save_every == 0:
            if pending_fut is not None:
                pending_fut.result()  # 上一轮必须写完再发新快照
            pending_fut = save_async(model, optimizer, step)
    if pending_fut is not None:
        pending_fut.result()
```

- `cpu_offload=True` 在 `get_state_dict` 里是关键——它把分片从 GPU 拷到 CPU pinned memory，这是异步化的前提。
- `single_file_per_rank=True` 让每个 rank 独立写一个文件，避免并发写同一 HDF5/tar。
- **必须在发下一个 async_save 之前 `.result()` 上一个**，否则两个后台写会同时抢 NVMe 带宽，还可能把 CPU 快照 buffer 冲掉。
- 分片拓扑（FSDP rank 数、TP 大小）改变后重启，DCP 会自动 reshard；但张量名字不能改。

## 恢复时间（RTO）优化

![一张图看故障 + 恢复时间线](https://quant67.com/post/llm-infra/10-checkpoint-fault/images/10-checkpoint-fault-fig1.svg)

一次故障的总恢复时间可以拆分为四段:
$$
T_{recover} =T_{detect}+T_{reschedule}+T_{warmup}
$$

| 阶段       | 主要耗时                                             | 优化手段                                     |
| ---------- | ---------------------------------------------------- | -------------------------------------------- |
| detect     | NCCL timeout 默认 30 分钟；进程挂掉后 k8s/slurm 上报 | 把 NCCL_TIMEOUT 调到 2–5 分钟；心跳 watchdog |
| reschedule | 找到替换节点、拉镜像、起容器                         | 热备池（standby nodes）、镜像本地缓存        |
| load       | 读 checkpoint + 建 NCCL communicator                 | 本地 staging + 并发读；NCCL lazy init        |
| warmup     | torch.compile / CUDA graph 重建                      | 编译 cache 持久化                            |

### Detect：别傻等 NCCL timeout

默认 `NCCL_IB_TIMEOUT=18`、`NCCL_TIMEOUT=1800000` 毫秒——一次挂起要等 30 分钟才被发现。万卡训练里这绝对不可接受。工程做法：

- 把 NCCL timeout 调到 2–5 分钟；
- 训练脚本里单起一个 watchdog 线程，监控 step time 的滑窗 P99，连续 N 步超阈值就强制 abort；
- 节点级 agent 订阅 `nvidia-smi dmon`、ib_diag、SMART、`ipmitool sel`，发现硬件异常直接发 SIGTERM。

### Reschedule：热备池

Meta / xAI 的做法都是预留 2%–5% 的**热备节点（standby pool）**。它们平时在跑健康检查，一旦某台挂了，调度器（基于 k8s Volcano / Slurm / 自研）从热备池抽一台顶上，镜像和 dataset 已经预热，分钟级切换。阿里 PAI 的 DLC、火山 veMLP 都有类似设计。

### Load：并发读 + 本地预热

- **并发读**：分布式 checkpoint 可以让每个 rank 只读自己那一份，不走 rank-0 broadcast。DCP 默认如此。
- **本地预热**：训练过程中后台持续把最新 checkpoint 预热到每个节点的本地 NVMe（类似 DRAM cache），故障时从本地盘直接读。
- **lazy comm init**：NCCL communicator 的构建本身在万卡上要几十秒，上次通信计划能复用的尽量复用。

### 一个好指标：**Resume at nearest step**

Meta 在 LLaMA-3 文中用了一个朴素但好用的指标：**从故障发生到恢复到第一个有效训练 step 的墙钟时间**。他们的目标是 < 10 分钟；做到后，每天损失的有效训练时间从十几小时压到两小时以内。



### Pickle、safetensors、zarr、自研二进制

早期 PyTorch 训练全栈基本都是 `torch.save` 一把梭，底层是 Python `pickle`。到了大模型时代，pickle 的几个弱点被放大：

1. **安全问题**：pickle 允许反序列化出任意对象，Hugging Face 上就发生过数次被植入恶意代码的”后门 checkpoint”事件。safetensors 由此诞生——只存 tensor 数据和元信息（JSON header + 连续 byte blob），**没有代码执行路径**。
2. **随机 I/O 不友好**：pickle 是流式的，想只读取某一层必须从头 parse。safetensors 的 header 里直接记录每个 tensor 的 byte offset 和 length，支持 mmap + 零拷贝加载。这对万卡场景非常重要——每个 rank 只加载自己那一份分片时，不希望把整个文件都读进来。
3. **多语言互操作**：pickle 是 Python 独占的；safetensors 在 Rust 里有一等公民实现，Triton Inference Server、vLLM、TGI 都能直接读。

safetensors 已经是推理世界的事实标准。但**训练 checkpoint**（带优化器状态、LR scheduler、RNG、step 等）通常比推理 checkpoint 复杂得多，社区还没有完全统一。主流选择：

- **PyTorch DCP**：自研二进制格式，每个 rank 一个 `.distcp` 文件 + 一个全局 `.metadata`。Meta 内部就在用这个；也是未来 PyTorch 官方方向。
- **DeepSpeed `universal checkpoint`**：支持在不同 ZeRO stage / TP / PP 拓扑间 reshard。
- **Megatron `distributed_checkpoint`**：类似 DCP，针对 TP/PP 拓扑做了优化。
- **自研**：Meta 的 Bellow、字节 veOmni 的 checkpoint 层、DeepSeek HAI-LLM 的 ckpt 模块都是自家重写的。

### 写入路径里的几个”看不见的坑”

踩过万卡作业的人会共鸣这些：

- **filesystem metadata 压力**：一次全量 checkpoint 如果每个 rank 一个文件，16K rank 就是 16K 个文件；加上优化器、一层一层的切分，实际可能几十万个小文件。Lustre 的 MDS（metadata server）经常被打爆。对策：按 rank group 聚合，或者 `single_file_per_rank` 用大文件。
- **fsync 成本**：把数据推到 NVMe 是一回事，保证断电不丢是另一回事。`fsync` 一个几百 GB 的文件要十几秒；多数实现会在最外层 barrier 前只做一次全局 fsync。
- **pinned memory 不够用**：启用 CPU offload 异步 checkpoint 时，需要的 pinned memory = checkpoint size / DP world size。一个 405B 模型 DP=128，每个 rank pinned buffer ≈ 50 GB。节点如果总共就 1 TB DRAM，这就挤占得很紧，要预先 `torch.cuda.memory.set_per_process_memory_fraction` 并预留足够大的 pinned pool。
- **写放大**：很多并行文件系统的 stripe size 不对时，1 MB 的小 I/O 会打成若干个 4 MB 的 OST 写，实际带宽只能吃到标称的 30%。要针对 Lustre 的 `lfs setstripe -c -1 -S 4M` 做作业级调优。
- **对象存储 multipart**：上 S3/OSS 的 checkpoint 单文件通常 100 GB+，必须 multipart upload；part size 推荐 64 MB–512 MB，并发度 8–32；遇到 5xx 要指数退避重试，否则一个瞬时抖动就废了。

### checkpoint 的正确性：完整性校验与幂等

一个惨痛教训是：“checkpoint 写成功了” ≠ “checkpoint 可以恢复”。常见翻车：

- 写到一半作业被 OOM kill，产生了**不完整但看起来存在**的 checkpoint 目录；
- PFS 客户端 cache 没 flush，某几个 rank 的文件在 PFS 上还没真正落盘就被覆盖；
- 对象存储的 multipart upload 成功了一半，`CompleteMultipartUpload` 失败；
- 两个作业并发写同一路径（比如重启后旧 job 还没死透）造成交错。

生产上的做法：

1. **写入走临时目录，rename 原子提交**：`path.tmp-<uuid>` → 全部完成 → `rename` 成 `step-XXXXXXXXX`。rename 在大多数 POSIX FS 上是原子的。
2. **记录 manifest**：checkpoint 目录里放一个 `manifest.json`，列每个 shard 文件的路径、字节数、sha256。恢复时先校验 manifest。
3. **多 checkpoint 链式保留**：`latest` 是个软链或游标文件，只有新 checkpoint 完全写入并校验后才更新。
4. **幂等恢复**：恢复脚本对同一 step 多次运行结果应一致（这条对弹性场景特别重要，一个节点重启了可能触发多次 load）。
