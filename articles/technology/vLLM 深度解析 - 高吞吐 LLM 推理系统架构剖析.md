# vLLM 深度解析:一套高吞吐 LLM 推理系统的完整架构剖析

> 从 PagedAttention、Continuous Batching、Prefix Caching、Speculative Decoding,一路到多 GPU、多节点、动态弹性伸缩的大规模在线服务

**原作者:** Aleksa Gordić
**发布日期:** 2025 年 8 月 29 日
**原文链接:** https://www.aleksagordic.com/blog/vllm
**译者视角:** AI Infra 工程师,结合 vLLM 内部实现与 HPC/分布式系统术语进行专业翻译

---

## 译者前言

本文是一篇非常硬核的 vLLM V1 引擎源码级剖析,原作者按照"倒金字塔"方式,从最基础的 `LLMEngine` 逐层展开,一直讲到多节点分布式在线服务。作为 AI Infra 工程师,我在翻译过程中尽量做到:

- **术语一致**:vLLM 内部的类名、函数名、状态字段(如 `EngineCoreRequest`、`allocate_slots`、`free_block_queue`、`WAITING_FOR_FSM`)保持英文原样,避免歧义
- **HPC 语境对齐**:TP/PP/DP/EP 分别翻译为张量并行 / 流水线并行 / 数据并行 / 专家并行,`world_size`、`rank`、`driver worker` 等采用 MPI/NCCL 社区通行叫法
- **系统语义传达**:`compute-bound` / `memory-bandwidth-bound` / `roofline model` / `continuous batching` 等术语在中文语境下补充直觉性解释
- **代码块原样保留**:所有 Python/Bash 示例保留原文,便于对照 vLLM 源码调试

分析基于 vLLM 提交 [`42172ad`](https://github.com/vllm-project/vllm/tree/42172ad)(2025 年 8 月 9 日)。

---

## 目录

本文分为五大部分:

1. [LLMEngine 与 Engine Core](#一llmengine-与-engine-core):vLLM 的核心机制(调度、PagedAttention、Continuous Batching 等)
2. [进阶特性](#二进阶特性对-engine-core-的能力扩展):Chunked Prefill、Prefix Caching、Guided Decoding、Speculative Decoding、Disaggregated P/D
3. [从单卡到多卡](#三从-uniprocexecutor-到-multiprocexecutor):`UniprocExecutor` → `MultiProcExecutor`
4. [分布式服务层](#四vllm-的分布式服务栈):Data Parallel、DP Coordinator、API Server、请求生命周期
5. [Benchmark 与自动调优](#五benchmark-与自动调优延迟-vs-吞吐):延迟与吞吐的取舍

### 📝 说明

- 分析基于提交 `42172ad`(2025-08-09)
- 目标读者:对 SOTA LLM 推理引擎工作机制感兴趣的工程师,以及 vLLM/SGLang 的潜在贡献者
- 本文聚焦 V1 引擎;V0 已被官方标记为 [deprecated](https://github.com/vllm-project/vllm/issues/18571),但许多概念仍可迁移
- 第一部分(Engine Core)信息密度较高,后续章节配有大量示例和图示

---

## 一、LLMEngine 与 Engine Core

`LLMEngine` 是 vLLM 的核心组件。仅凭它自身,就已经能实现高吞吐的**离线推理(offline inference)**——但还不能通过 Web 接口对外提供服务。

我们以下面这段离线推理代码作为贯穿全文的示例(改编自 vLLM 官方仓库中的 [`basic.py`](https://github.com/vllm-project/vllm/blob/main/examples/offline_inference/basic/basic.py)):

```python
from vllm import LLM, SamplingParams

prompts = [
    "Hello, my name is",
    "The president of the United States is",
]

sampling_params = SamplingParams(temperature=0.8, top_p=0.95)

def main():
    llm = LLM(model="TinyLlama/TinyLlama-1.1B-Chat-v1.0")

    outputs = llm.generate(prompts, sampling_params)

if __name__ == "__main__":
    main()
```

### 📝 环境变量

- `VLLM_USE_V1="1"`:启用 V1 引擎
- `VLLM_ENABLE_V1_MULTIPROCESSING="0"`:强制单进程运行

上述配置的特征:

- **离线**(没有 Web 层与分布式调度层的介入)
- **同步**(所有执行都发生在单个阻塞进程中)
- **单卡**(不使用任何并行:DP/TP/PP/EP 都等于 1)
- **标准 Transformer** [2]。像 Jamba 这样的混合模型(Transformer + SSM)需要更复杂的**异构 KV Cache 分配器(hybrid KV-cache allocator)**才能支持

从这个最小配置出发,我们会逐步扩展到**在线、异步、多卡、多节点**的推理系统——但依然只服务于标准 Transformer 结构。

在这个示例中我们做了两件事:

1. 构造一个 `LLM` 引擎
2. 对给定的 prompts 调用 `generate` 进行采样

下面我们先来分析构造函数。

---

### `LLMEngine` 构造函数

引擎的主要组件包括:

- **vLLM Config**:承载了所有配置旋钮(model、cache、并行度等)
- **Processor**:把原始输入(prompt/tokens/embeds)通过校验、tokenize、后处理,转换成 `EngineCoreRequest`
- **Engine Core Client**:在我们的示例中是 `InprocClient`(几乎等价于直接持有一个 `EngineCore`);我们最终会演化到 `DPLBAsyncMPClient`,以支撑大规模在线服务
- **Output Processor**:把底层的 `EngineCoreOutput` 转换成用户可见的 `RequestOutput`

### 📝 说明

由于 V0 已被弃用,类名和实现细节可能会漂移。译者会侧重讲清核心思想,而不是死抠某一版的函数签名;但关键接口和数据结构名会保留下来。

**Engine Core 内部**又由几个子组件构成:

- **Model Executor**:驱动模型 forward pass。当前我们看到的是 `UniProcExecutor`——单进程、单 `Worker`、单 GPU。后面会演化到 `MultiProcExecutor`,支持多卡
- **Structured Output Manager**:负责 Guided Decoding(结构化输出),稍后详述
- **Scheduler**:决定哪些 request 进入下一个 engine step。它内部又包含:
  1. **调度策略**:FCFS(先来先服务)或 Priority(高优先级抢占)
  2. **`waiting` / `running` 两个队列**
  3. **KV Cache Manager**:PagedAttention [3] 的心脏

KV Cache Manager 维护一个 **`free_block_queue`**——一个可用 KV Cache Block 的对象池。这个池通常有数十万个块(具体数量取决于 VRAM 容量和 block 大小)。在 PagedAttention 中,这些 block 充当索引结构,把 token 映射到对应的 KV Cache 存储位置(可以类比操作系统里的**页表**)。

![LLM engine constructor](https://www.aleksagordic.com/blog/vllm/engine_constructor.png)
*本节所述核心组件与它们之间的关系*

标准 Transformer 层(非 MLA [4])单个 block 的大小计算公式为:

```
2 (K/V 两份) × block_size (默认 16) × num_kv_heads × head_size × dtype_num_bytes (bf16 为 2)
```

例如 `2 × 16 × 64 × 128 × 2 = 524,288 字节 ≈ 512 KiB` 一个 block。

在 Model Executor 构造过程中,会创建一个 `Worker` 对象,并执行三个关键流程。后续在 `MultiProcExecutor` 场景下,这三个流程会**独立地**在每张 GPU 对应的 worker 进程中执行一遍。

**1. Init Device(设备初始化)**

- 为 worker 绑定 CUDA 设备(比如 `"cuda:0"`),检查 dtype(如 bf16)在该设备上受支持
- 依据 `gpu_memory_utilization`(比如 0.8,即使用 80% 的 VRAM)校验剩余显存是否足够
- 设置分布式相关的参数(DP / TP / PP / EP 等)
- 实例化 `model_runner`,内部持有 sampler、KV Cache、以及 forward 用的缓冲区(`input_ids`、`positions` 等)
- 实例化 `InputBatch`,承载 CPU 侧的 forward 缓冲区、KV Cache 索引所需的 block table、采样元数据等

**2. Load Model(加载模型)**

- 实例化模型架构
- 载入权重
- 调用 `model.eval()`(PyTorch 推理模式)
- 可选:对模型进行 `torch.compile()`

**3. Initialize KV Cache(初始化 KV Cache)**

- 拿到每一层的 KV Cache 规格。历史上一直只有 `FullAttentionSpec`(同构 Transformer),但随着混合架构(Sliding Window、Transformer/SSM 混合如 Jamba)的兴起,这一步变得复杂——参见 Jenga [5]
- 跑一次 dummy/profiling forward pass,拿到 GPU 显存快照,推算 VRAM 中能塞下多少个 KV Cache Block
- 分配、reshape,并将 KV Cache 张量**绑定**到每一层 attention module 上
- 准备 attention metadata(比如指定 backend 为 FlashAttention),供后续 forward 中的 kernel 使用
- 除非用户指定了 `--enforce-eager`,否则会针对一批预设的 warmup batch size,各跑一次 dummy forward 并**捕获 CUDA Graph**。CUDA Graph 会把整段 GPU 工作序列录制成一个 DAG,后续 forward 直接**回放**这个预先烘焙好的图,大幅降低 kernel launch 的 CPU 开销

上面我把很多底层细节做了抽象——但这些是后文会反复引用的关键构件。

引擎已经初始化完毕,下面进入 `generate` 函数。

---

### `generate` 函数

第一步是把请求**注入**到引擎里。对于每一条 prompt,我们:

1. 分配一个唯一的 `request_id`,记录到达时间
2. 调用 input preprocessor,把 prompt 进行 tokenize,返回一个包含 `prompt`、`prompt_token_ids`、`type`(text / tokens / embeds 等)的字典
3. 把上述信息打包为一个 `EngineCoreRequest`,附加优先级、采样参数等元数据
4. 把 request 送入 engine core,后者将其包装成 `Request` 对象,状态置为 `WAITING`,并加入调度器的 `waiting` 队列(FCFS 用 `append`,priority 用堆插入)

此时引擎已经就绪,可以开始执行。在**同步引擎**这个示例中,这批初始 prompts 就是整轮任务的全部内容——**没有**中途注入新请求的机制。相对地,**异步引擎**支持这种行为,也就是所谓的 **Continuous Batching**(连续批处理)[6]:每一个 step 结束后,新请求和老请求会被一起纳入调度考虑。

由于 forward pass 会把整个 batch 展平(flatten)为一条超长序列、由自定义 kernel 高效处理,**Continuous Batching 在同步引擎中同样是原生支持的**——它不依赖异步框架,只依赖 kernel 的展平能力。

接下来,只要还有 request 没跑完,引擎就会反复调用 `step()`。每个 step 分三个阶段:

1. **Schedule**:决定本 step 要跑哪些 request(decode 和/或 (chunked) prefill)
2. **Forward pass**:执行模型前向,采样 token
3. **Postprocess**:把采样出的 token id 追加到对应 `Request`,做 detokenize,检查 stop 条件。如果某个 request 已经结束,做清理(比如把它占用的 KV Cache Block 归还给 `free_block_queue`)并提前返回结果

### 📝 Stop 条件

- Request 超过长度上限(`max_model_length` 或它自身的 `max_tokens`)
- 采样出的 token 是 EOS id(除非启用了 `ignore_eos`——benchmark 时常用,强制生成到指定 token 数)
- 采样出的 token 命中 `stop_token_ids` 中任意一个
- 输出中出现了 stop string。此时我们把输出截断到 stop string 首次出现的位置,并在引擎侧 abort 该请求(注意:`stop_token_ids` 会保留在输出里,但 stop string 不会)

![Engine loop](https://www.aleksagordic.com/blog/vllm/engine_loop.png)
*Engine 主循环*

在**流式(streaming)模式**下,我们会边采样边把中间 token 推给客户端;但本文暂不展开。

下面详细看调度器。

---

### 调度器(Scheduler)

推理引擎面对的负载有两大类:

1. **Prefill 请求**:对整个 prompt 的所有 token 做一次前向。通常是**计算受限(compute-bound)**——具体阈值取决于硬件与 prompt 长度。前向结束后,只从最后一个位置的概率分布中采样出一个 token
2. **Decode 请求**:仅对最新的一个 token 做前向,更早的 KV 已经缓存在显存中。这类负载是**显存带宽受限(memory-bandwidth-bound)**——即使只算一个 token,也得把整份模型权重(以及 KV Cache)从 HBM 搬到片上

后面的 [Benchmark 章节](#五benchmark-与自动调优延迟-vs-吞吐)会用 Roofline 模型细讲 prefill/decode 的性能画像。

**V1 调度器可以在同一个 step 里混合 prefill 和 decode**;而 V0 只能在一个 step 中要么只跑 prefill、要么只跑 decode——这是 V1 的关键设计改进之一。

调度器**优先调度 decode 请求**——也就是 `running` 队列里的请求。对每一个这样的请求:

1. 计算本 step 要生成多少个 token(不一定是 1——受 Speculative Decoding 和异步调度影响,后面讲)
2. 调用 KV Cache Manager 的 `allocate_slots`(细节见下)
3. 从 token budget 中扣掉这一步用掉的 token 数

处理完 decode 后,再处理 `waiting` 队列里的 prefill 请求:

1. 拿到已计算 block 数(如果没开 prefix caching 就返回 0——稍后详述)
2. 调用 `allocate_slots`
3. 把该 request 从 `waiting` 弹出、挂入 `running`,状态置为 `RUNNING`
4. 更新 token budget

`allocate_slots` 的行为:

1. **计算所需 block 数** — 决定需要新分配的 KV Cache Block 数量 `n`。每个 block 默认容纳 16 个 token。比如一个 prefill 请求要塞 17 个新 token,就需要 `ceil(17/16) = 2` 个 block
2. **检查可用性** — 如果池里没有足够的 block,提前返回。根据是 decode 还是 prefill,引擎可能会尝试**重算式抢占(recompute preemption)**——V0 时代还支持**换出式抢占(swap preemption)**——踢掉低优先级的 request(调用 `kv_cache_manager.free`,把 block 还回池子);或者直接跳过本次调度、继续执行
3. **分配 block** — 通过 KV Cache Manager 的 coordinator,从 `free_block_queue`(前面提到的双向链表)左边弹出前 `n` 个 block,存入 `req_to_blocks` 字典(把 `request_id` 映射到它的 block 列表)

![KV cache blocks](https://www.aleksagordic.com/blog/vllm/kv_cache_blocks.png)
*KV Cache Block 列表*

到这里,我们终于可以真正执行 forward pass 了!

---

### 执行 forward pass

我们调用 Model Executor 的 `execute_model`,它委派给 `Worker`,`Worker` 又委派给 `ModelRunner`。

主要步骤如下:

1. **更新状态** — 从 `input_batch` 中剔除已结束的 request,更新 forward 相关的元数据(比如每个 request 的 KV Cache Block 列表——后续要拿它索引到 paged KV Cache 内存中)
2. **准备输入** — 把 CPU 上的缓冲区拷贝到 GPU;计算 position;构建 `slot_mapping`(下面例子会看到);构造 attention metadata
3. **前向计算** — 用自定义的 PagedAttention kernel 跑模型。所有序列被**展平并拼接**成一条"超长序列",通过 position 索引和 attention mask,保证每条序列只关注自己的 token——这就是 Continuous Batching 能做到**免 right-padding** 的关键
4. **提取末位隐状态** — 抽出每条序列最后一个 token 的隐状态,计算 logits
5. **采样** — 根据采样配置(greedy / temperature / top-p / top-k 等)从 logits 采样

Forward 本身有两种执行模式:

1. **Eager 模式** — 走标准的 PyTorch 前向
2. **Captured 模式** — 回放构造阶段预先捕获好的 CUDA Graph(见上文"Initialize KV Cache")

下面这张图直观展示了 Continuous Batching 与 PagedAttention:

![fwd pass - continuous batching & paged attn](https://www.aleksagordic.com/blog/vllm/fwd_pass.png)
*Forward pass:Continuous Batching 与 PagedAttention*

---

## 二、进阶特性:对 Engine Core 的能力扩展

Engine Core 的基本流转搞清楚后,我们可以往上叠加进阶特性了。

前面已经涉及了**抢占(preemption)**、**PagedAttention**、**Continuous Batching**。

接下来我们逐一剖析:

1. Chunked Prefill(分块预填充)
2. Prefix Caching(前缀缓存)
3. Guided Decoding(基于 FSM 的结构化解码)
4. Speculative Decoding(推测解码)
5. Disaggregated P/D(Prefill / Decode 解耦部署)

---

### 1. Chunked Prefill(分块预填充)

Chunked Prefill 用于处理**长 prompt**——把一次巨大的 prefill 拆成若干小块。如果不这么做,一条超长请求会**独占**整个 engine step,阻塞其他 prefill 请求,拉高整体队列时延。

举个例子:假设每个 chunk 有 `n = 8` 个 token,用小写字母加"-"表示。长 prompt `P` 可以拆成 `x-y-z`,其中 `z` 是一个不完整的 chunk(比如只有 2 个 token)。完整跑完 `P` 的 prefill 至少需要 ≥ 3 个 engine step(如果中间被调度器跳过就更多),并且只有在**最后一个 chunk 完成后**才会采样出一个新 token。

看图更直观:

![Chunked prefilling - pt 1](https://www.aleksagordic.com/blog/vllm/chunked_pt1.png)

实现非常简洁:**对单个 step 内的新 token 数设一个上限**。当请求的新 token 数超过 `long_prefill_token_threshold` 时,把它截到该阈值即可。前面讲过的索引/调度机制会自然处理剩下的逻辑。

在 vLLM V1 里,只要把 `long_prefill_token_threshold` 设为正整数就能启用 Chunked Prefill。(严格来说,即便没显式开启,只要 prompt 长度超过 token budget,它也会被截断成 chunk 跑。)

---

### 2. Prefix Caching(前缀缓存)

拿最初的示例稍作改造,来讲清 Prefix Caching:

```python
from vllm import LLM, SamplingParams

long_prefix = "<a piece of text that is encoded into more than block_size tokens>"

prompts = [
    "Hello, my name is",
    "The president of the United States is",
]

sampling_params = SamplingParams(temperature=0.8, top_p=0.95)

def main():
    llm = LLM(model="TinyLlama/TinyLlama-1.1B-Chat-v1.0")

    outputs = llm.generate(long_prefix + prompts[0], sampling_params)
    outputs = llm.generate(long_prefix + prompts[1], sampling_params)

if __name__ == "__main__":
    main()
```

Prefix Caching 的核心思想:**多个 prompt 共享的前缀,只算一次**——所以叫"前缀"缓存。

关键点在 `long_prefix`:任何长度超过一个 KV Cache Block(默认 16 token)的前缀都算长前缀。为简化讨论,假设 `long_prefix` 长度正好是 `n × block_size`(`n ≥ 1`),即**恰好对齐 block 边界**。否则末尾 `long_prefix_len % block_size` 个 token 会因为落在**不完整 block** 上而无法缓存,只能重算。

- **不开 Prefix Caching**:每次带 `long_prefix` 的请求都要重算全部 `n × block_size` 个 token
- **开 Prefix Caching**:这些 token 只算一次,KV 写入 paged 内存后被后续请求**复用**,新请求只需要处理**新增部分**。这只加速 prefill,对 decode 无帮助

vLLM 内部是怎么做的?

第一次 `generate` 调用,在**调度阶段**,`kv_cache_manager.get_computed_blocks` 会调用 `hash_request_tokens`:

1. 把 `long_prefix + prompts[0]` 切成 16-token 的 chunk
2. 对每个**完整的** chunk 计算哈希(默认用内建 hash;可切成 SHA-256,慢但冲突率更低)。哈希输入包括:**前一个 block 的哈希 + 当前 token + 可选元数据**
3. 可选元数据包括:多模态 hash(MM hash)、LoRA ID、cache salt(cache salt 只注入到**首块**的哈希里,确保只有携带同一 salt 的请求才能复用这些块——用于多租户隔离)
4. 每个哈希结果被封装为一个 `BlockHash` 对象(包含哈希值和该块的 token id),返回一个 `BlockHash` 列表

这个列表被存进 `self.req_to_block_hashes[request_id]`。

随后,引擎调用 `find_longest_cache_hit`,在 `cached_block_hash_to_block` 里做**线性搜索**看有没有命中。第一次请求当然什么都没命中。

![Prefix caching logic - pt 1](https://www.aleksagordic.com/blog/vllm/prefix_pt1.png)

然后就调用 `allocate_slots`,后者又调用 `coordinator.cache_blocks`,把新的 `BlockHash` 关联到刚分配的 KV Block 上,并登记到 `cached_block_hash_to_block`。

之后 forward pass 会把对应的 KV 写入 paged KV Cache 内存中我们刚分配的这些 block。

再往后跑若干 step 也会继续分配更多 block,但对本例已经不重要——因为**在 `long_prefix` 之后前缀就发散了**。

![Prefix caching logic - pt 2](https://www.aleksagordic.com/blog/vllm/prefix_pt2.png)

**第二次** `generate` 调用同样的前缀时,步骤 1-3 重复;但这次 `find_longest_cache_hit` 会通过线性搜索命中全部 `n` 个 block。引擎直接**复用**这些 KV Block。

![Prefix caching logic - pt 3](https://www.aleksagordic.com/blog/vllm/prefix_pt3.png)

如果**第一个请求还在**,这些 block 的引用计数会自增(比如 1 → 2)。在本例中,第一个请求已经结束,它对应的 block 已经**归还给对象池**(引用计数归零)。但因为它们**仍然登记在 `cached_block_hash_to_block`**——KV Cache Manager 的设计保证了这些"归还但仍可复用"的块是有效的——所以我们只是把它们从 `free_block_queue` 里再**摘掉**一次。

### 📝 深入说明:block 什么时候真正失效?

KV Cache Block **只有在即将被从 `free_block_queue` 的左端弹出并重新分配时**,才会真正失效——如果这时发现它还挂着一个哈希、还挂在 `cached_block_hash_to_block` 里,vLLM 就会清掉这个 block 的哈希、从 `cached_block_hash_to_block` 中摘除,保证它**不会再被那个旧前缀命中复用**。

这就是 Prefix Caching 的全部:**别重算见过的前缀,直接复用它的 KV Cache**。

如果你理解了这个例子,你也理解了 PagedAttention 是怎么工作的。

Prefix Caching 默认开启,禁用方式:`enable_prefix_caching = False`。

---

### 3. Guided Decoding(FSM 引导解码)

Guided Decoding 的核心:**每一步解码时,用一个基于文法(grammar)的有限状态机(FSM)约束 logits**,保证采样出的 token 一定满足文法。

这是一套非常强大的机制——从**正则文法**(Chomsky Type-3,任意 regex)一直到**上下文无关文法**(Type-2,覆盖绝大多数编程语言)都能约束。

先给一个最小示例(基于前面的代码稍作改造):

```python
from vllm import LLM, SamplingParams
from vllm.sampling_params import GuidedDecodingParams

prompts = [
    "This sucks",
    "The weather is beautiful",
]

guided_decoding_params = GuidedDecodingParams(choice=["Positive", "Negative"])
sampling_params = SamplingParams(guided_decoding=guided_decoding_params)

def main():
    llm = LLM(model="TinyLlama/TinyLlama-1.1B-Chat-v1.0")

    outputs = llm.generate(prompts, sampling_params)

if __name__ == "__main__":
    main()
```

假设是**字符级 tokenization**:prefill 阶段,FSM 会 mask 掉除 `"P"` 和 `"N"` 之外所有 token 的 logits。如果采样出 `"P"`,FSM 就走到 "Positive" 分支,下一步只允许 `"o"`,如此类推。

![FSM](https://www.aleksagordic.com/blog/vllm/fsm.png)
*玩具级 FSM 示例*

vLLM 内部实现流程:

1. `LLMEngine` 构造时会创建 `StructuredOutputManager`,持有 tokenizer,并维护一个 `_grammar_bitmask` tensor
2. 请求进来时,状态置为 `WAITING_FOR_FSM`,由 `grammar_init` 选定后端编译器(比如 `xgrammar` [7],这是第三方库)
3. 该 request 的文法被**异步编译**
4. 调度阶段:如果异步编译完成,状态从 `WAITING_FOR_FSM` 切到 `WAITING`,`request_id` 加入 `structured_output_request_ids`;否则放到 `skipped_waiting_requests`,下个 step 再试
5. 调度循环结束后(仍在调度阶段),如果有 FSM 请求,`StructuredOutputManager` 让后端**准备 / 更新** `_grammar_bitmask`
6. Forward pass 出 logits 之后,`xgr_torch_compile` 中的函数把 bitmask 扩展成 `vocab_size`(用 32 位整数,所以扩展比 32×),并把**不允许 token 的 logit 置为 -∞**
7. 采样出下一个 token 后,通过 `accept_tokens` 把 FSM 推进到下一个状态——对应到图上就是**沿着 FSM 边走一步**

第 6 步值得展开:

如果 `vocab_size = 32`,`_grammar_bitmask` 就是一个 32 位整数,它的二进制表示直接编码了每个 token 是否允许("1" = 允许,"0" = 禁止)。比如 `"101...001"` 展开成长度 32 的数组 `[1, 0, 1, ..., 0, 0, 1]`,值为 0 的位置对应的 logit 被置为 -∞。当 `vocab_size` 更大时,就把多个 32 位字**拼接**起来。生成这些 bit pattern 的责任在后端(如 xgrammar),它会基于当前 FSM 状态推理出下一步允许的 token 集合。

### 📝 说明

这里的大部分复杂度其实**藏在 xgrammar 这样的第三方库里**,vLLM 自己只做"胶水层"。

再看一个 `vocab_size = 8`、8 位整数的更小示例:

![FSM](https://www.aleksagordic.com/blog/vllm/fsm2.png)
*玩具级示例*

通过传入 `guided_decoding` 配置就能启用这个特性。

---

### 4. Speculative Decoding(推测解码)

自回归生成中,每采样一个 token 都要过一次大模型 forward——非常昂贵!每一步都要把整份模型权重从 HBM 搬到片上,却只算出 1 个 token(batch size = 1 时;一般是 `B` 个)。

**Speculative Decoding** [8] 通过引入一个**小的 draft 模型**来加速:让 draft 便宜地提出 `k` 个候选 token。但我们**不真的从小模型采样**——它只用来"猜"下文,最终能否接受,由大模型说了算。

流程:

1. **Draft**:小模型基于当前上下文,提出 `k` 个候选 token
2. **Verify**:大模型对 (context + `k` draft tokens) 做**一次** forward,同时输出这 `k` 个位置的概率**以及**再往后一个位置的概率(共 `k+1` 个候选)
3. **Accept / Reject**:从左到右扫这 `k` 个 draft token:
   - 若大模型概率 ≥ draft 概率:**接受**
   - 否则以概率 `p_large(token) / p_draft(token)` 接受
   - 一旦某个位置被拒绝,或者全部 `k` 个都接受了就停下
     - 全部接受时,可以**免费**多得一个 token——从已经算好的第 `(k+1)` 位分布采样
     - 出现拒绝时,在被拒绝的位置**重采样**——用一个重新平衡的分布 `p_large - p_draft`,负值裁到 0,归一化后采样

**为什么这样是等价的?** 虽然候选来自小模型,但 accept/reject 规则从数学上保证:输出序列在期望上与直接从大模型逐 token 采样**分布完全一致**。换句话说,Speculative Decoding 在统计意义上**等价于**普通自回归采样,但可能**快得多**——一次大模型 forward 最多能"吐出" `k+1` 个 token。

### 📝 建议

想吃透这个机制,推荐读 [gpt-fast](https://github.com/meta-pytorch/gpt-fast)(简洁实现)和 [原论文](https://arxiv.org/abs/2302.01318)(有等价性证明和数学细节)。

**vLLM V1 不支持"独立小 LM 作为 draft model"这条路线**,而是实现了几种更快但精度略降的候选生成机制:n-gram、EAGLE [9]、Medusa [10]。

一句话说明:

1. **n-gram**:取最后 `prompt_lookup_max` 个 token,在**已有序列**中找一个匹配;如果匹配上,就把匹配位置**之后的** `k` 个 token 拿来作为 draft;找不到就缩窗口,直到 `prompt_lookup_min`
2. 当前实现返回的是**第一次匹配**之后的 `k` 个 token。译者与原作者的直觉一致——引入**局部性偏置(recency bias)** 从后往前找似乎更合理(即取**最后一次匹配**)
3. **EAGLE**:对大 LM 做"模型手术"——保留 embedding 层和 LM head,把中间的 Transformer stack 换成一个轻量的 MLP,微调后作为廉价 draft
4. **Medusa**:在大模型(embedding 层之上、LM head 之前的位置)训练若干个辅助的线性 head,**并行**预测下 `k` 个 token,比再跑一个小 LM 高效

在 vLLM 里用 n-gram draft 的调用方式:

```python
from vllm import LLM, SamplingParams

prompts = [
    "Hello, my name is",
    "The president of the United States is",
]

sampling_params = SamplingParams(temperature=0.8, top_p=0.95)

speculative_config = {
    "method": "ngram",
    "prompt_lookup_max": 5,
    "prompt_lookup_min": 3,
    "num_speculative_tokens": 3,
}

def main():
    llm = LLM(model="TinyLlama/TinyLlama-1.1B-Chat-v1.0", speculative_config=speculative_config)

    outputs = llm.generate(prompts, sampling_params)

if __name__ == "__main__":
    main()
```

vLLM 内部实现:

**引擎构造阶段:**

1. Init device 时创建 `drafter`(draft 提议器,如 `NgramProposer`)和 `rejection_sampler`(部分逻辑用 Triton 写的 GPU kernel)
2. Load model 时载入 draft 模型的权重(n-gram 无权重,是个 no-op)

**`generate` 执行阶段**(假设是一个全新请求):

1. 大模型跑一次常规的 prefill
2. Forward 完、标准采样出下一个 token 之后,调用 `propose_draft_token_ids(k)` 从 draft 提议 `k` 个 token
3. 把它们存到 `request.spec_token_ids`(更新 request 元数据)
4. 下一个 engine step:该 request 在 `running` 队列被调度时,把 `len(request.spec_token_ids)` 加到"要生成的新 token 数"里——这样 `allocate_slots` 会为 draft token 预留足够的 KV Block
5. 把 `spec_token_ids` 拷贝进 `input_batch.token_ids_cpu`,拼成 (context + draft) 的输入
6. 调用 `_calc_spec_decode_metadata` 构造 metadata(拷贝 token、准备 logits 位置等),然后跑一次**大模型 forward**,一次性把 draft 的位置全部算出来
7. 不走常规采样,而是用 `rejection_sampler` 从左到右做接受/拒绝,得到 `output_token_ids`
8. 重复 2-7,直到 stop 条件命中

最直接的方式是打开 debugger 跟一遍代码。这两张图也能帮到你:

![Drafting stage](https://www.aleksagordic.com/blog/vllm/specdec_pt1.png)
![Verify stage & rejection sampling stage](https://www.aleksagordic.com/blog/vllm/specdec_pt2.png)

---

### 5. Disaggregated P/D(Prefill / Decode 解耦部署)

前面已经暗示过做 Disaggregated P/D 的动机。

Prefill 与 Decode 的性能画像截然不同(**计算受限 vs 显存带宽受限**),把二者**拆到不同的实例上执行**是很合理的架构选择。这样能对延迟做更精细的控制——**TTFT**(time-to-first-token)与 **ITL**(inter-token latency)——更多细节留到 [Benchmark 章节](#五benchmark-与自动调优延迟-vs-吞吐)。

生产上,通常跑 `N` 个 vLLM prefill 实例、`M` 个 decode 实例,并根据实时请求组合**自动伸缩**这两个池子。Prefill worker 把 KV 写入一个**专用的 KV Cache 服务**;decode worker 从这个服务读取。这就把**长尾且突发的 prefill**和**稳定且延迟敏感的 decode**在物理层面隔离开。

vLLM 里怎么做?

为了讲清机制,下面示例用的是 `SharedStorageConnector`——这是一个**调试用**的 connector 实现。

**Connector** 是 vLLM 抽象出来的、专门用于**跨实例 KV 传输**的接口。这一接口目前**尚未稳定**,官方计划在近期做一些改进,部分改动可能是 breaking change。

我们启动 2 个 vLLM 实例(GPU 0 做 prefill、GPU 1 做 decode),在它们之间传输 KV Cache:

```python
import os
import time
from multiprocessing import Event, Process
import multiprocessing as mp

from vllm import LLM, SamplingParams
from vllm.config import KVTransferConfig

prompts = [
    "Hello, my name is",
    "The president of the United States is",
]

def run_prefill(prefill_done):
  os.environ["CUDA_VISIBLE_DEVICES"] = "0"

  sampling_params = SamplingParams(temperature=0, top_p=0.95, max_tokens=1)

  ktc=KVTransferConfig(
      kv_connector="SharedStorageConnector",
      kv_role="kv_both",
      kv_connector_extra_config={"shared_storage_path": "local_storage"},
  )

  llm = LLM(model="TinyLlama/TinyLlama-1.1B-Chat-v1.0", kv_transfer_config=ktc)
  llm.generate(prompts, sampling_params)

  prefill_done.set()  # 通知 decode 实例:KV Cache 已就绪

  # 让 prefill 进程保持存活;否则脚本可能提前退出、decode 尚未跑完
  try:
      while True:
          time.sleep(1)
  except KeyboardInterrupt:
      print("Script stopped by user.")

def run_decode(prefill_done):
  os.environ["CUDA_VISIBLE_DEVICES"] = "1"

  sampling_params = SamplingParams(temperature=0, top_p=0.95)

  ktc=KVTransferConfig(
      kv_connector="SharedStorageConnector",
      kv_role="kv_both",
      kv_connector_extra_config={"shared_storage_path": "local_storage"},
  )

  llm = LLM(model="TinyLlama/TinyLlama-1.1B-Chat-v1.0", kv_transfer_config=ktc)

  prefill_done.wait()  # 阻塞等待 prefill 侧的 KV Cache

  # 内部会先 fetch KV,再进入 decode 循环
  outputs = llm.generate(prompts, sampling_params)

if __name__ == "__main__":
  prefill_done = Event()
  prefill_process = Process(target=run_prefill, args=(prefill_done,))
  decode_process = Process(target=run_decode, args=(prefill_done,))

  prefill_process.start()
  decode_process.start()

  decode_process.join()
  prefill_process.terminate()
```

### 📝 关于 LMCache

译者(注:此处指原作者)也试过 `LMCache` [11]——目前**最快、最接近生产级**的 connector,底层用 NVIDIA 的 NIXL 传输。但它还处于快速迭代期,踩了几个 bug。由于大部分复杂度散落在外部仓库,用 `SharedStorageConnector` 来讲解**机制**更合适。

vLLM 里 Disaggregated P/D 的完整步骤:

1. **实例化(Instantiation)** — 引擎构造时,connector 会在**两个地方**分别被创建:
   - Worker 的 init device(准确说是 init worker distributed environment)里,role 为 `"worker"`
   - Scheduler 构造器里,role 为 `"scheduler"`
2. **Cache 查询(Cache lookup)** — 调度器处理 `waiting` 队列中的 prefill 请求时(在本地 Prefix Cache 命中检查**之后**),调用 connector 的 `get_num_new_matched_tokens`,查询外部 KV 服务是否有缓存命中。**Prefill 侧永远返回 0**;**decode 侧**才可能命中。命中数被加到本地已计算 token 数上,再传入 `allocate_slots`
3. **状态更新(State update)** — 调度器接着调用 `connector.update_state_after_alloc`,登记有命中的 request(prefill 侧是 no-op)
4. **元数据构建(Meta build)** — 调度末尾调用 `meta = connector.build_connector_meta`:
   - Prefill 侧把所有请求以 `is_store=True` 加入 meta(要上传 KV)
   - Decode 侧把请求以 `is_store=False` 加入 meta(要下载 KV)
5. **Context Manager** — Forward 前后各触发一次:
   - **Enter**:`kv_connector.start_load_kv`——decode 从外部服务拉取 KV 注入到 paged 内存;prefill 是 no-op
   - **Exit**:`kv_connector.wait_for_save`——prefill **阻塞等待** KV 上传完成;decode 是 no-op

流程图:

![disaggregated P/D](https://www.aleksagordic.com/blog/vllm/pd.png)
*Disaggregated P/D*

### 📝 补充说明

- `SharedStorageConnector` 里的"外部服务"其实就是**本地文件系统**
- 依配置不同,KV 传输可以做成**逐层**的(每个 attention 层前/后做一次)
- Decode 侧**只在请求的第一个 step** 拉取外部 KV,之后就本地算/存了

---

## 三、从 `UniprocExecutor` 到 `MultiProcExecutor`

核心技术都讲完之后,我们开始谈**规模化(scale up)**。

设想一下:模型权重装不下单张 GPU 的 VRAM 了怎么办?

第一选择是**张量并行(Tensor Parallelism, TP)**——把模型切分到同一节点的多张 GPU 上(比如 `TP=8`)。若还是塞不下,就再叠加**流水线并行(Pipeline Parallelism, PP)**,把不同的层切到不同的节点上。

### 📝 说明

- **节点内(intranode)带宽显著高于节点间(internode)带宽**(NVLink/NVSwitch vs InfiniBand/以太网),因此实践中 TP 通常优先于 PP(尽管 PP 传输的数据量更少)
- 本文不涉及**专家并行(Expert Parallelism, EP)**,因为我们聚焦标准 Transformer 而非 MoE。也不涉及**序列并行(Sequence Parallelism, SP)**,TP 和 PP 是生产上最常用的两种

到了这个层级,我们需要**多个 GPU 进程(worker)** 以及一个**编排层**协调它们。这就是 `MultiProcExecutor` 的职责。

![MultiProcExecutor](https://www.aleksagordic.com/blog/vllm/multiprocexecutor.png)
*`MultiProcExecutor` 在 TP=8 场景下的形态(rank 0 是 driver worker)*

vLLM 里的具体机制:

1. `MultiProcExecutor` 初始化一个 `rpc_broadcast_mq` 消息队列(底层用**共享内存**实现)
2. 构造器循环 `world_size` 次(如 `TP=8 ⇒ world_size=8`),通过 `WorkerProc.make_worker_process` **spawn 一个守护进程**
3. 对每个 worker,父进程先创建 reader/writer 一对 pipe
4. 新进程运行 `WorkerProc.worker_main`,内部会实例化一个 worker,执行同样的"init device"、"load model"等流程(和 `UniprocExecutor` 一样,只是这次是并发地在多张卡上跑)
5. 每个 worker 判断自己是**driver**(TP group 内的 rank 0)还是普通 worker。每个 worker 都会建两个队列:
   - `rpc_broadcast_mq`(与父进程共享):**接收**工作项
   - `worker_response_mq`:**返回**执行结果
6. 初始化过程中,每个子进程通过 pipe 把它的 `worker_response_mq` 句柄发给父进程。父进程收齐所有句柄后解除阻塞——**协调握手完成**
7. Worker 随后进入**忙轮询循环**,阻塞在 `rpc_broadcast_mq.dequeue` 上。有工作项到来时就执行(和 `UniprocExecutor` 一样,只不过现在是按 TP/PP 切好的分片工作),结果通过 `worker_response_mq.enqueue` 送回
8. 运行时:每当有请求进来,`MultiProcExecutor` 把工作项非阻塞地 `enqueue` 到 `rpc_broadcast_mq`(所有 worker 都能拿到自己的分片),然后阻塞等待**指定输出 rank** 的 `worker_response_mq.dequeue`,收齐结果返回

**从引擎的视角看,什么都没变**——所有多进程的复杂度都被封装在 Model Executor 的 `execute_model` 调用后面。

- `UniProcExecutor` 场景:`execute_model` 直接调用 worker 上的 `execute_model`
- `MultiProcExecutor` 场景:`execute_model` 通过 `rpc_broadcast_mq` **间接**触发所有 worker 上的 `execute_model`

至此,只要资源允许,我们可以用**同一套 engine 接口**跑任意大小的模型。

下一步是**规模化外扩(scale out)**:开启 **数据并行(Data Parallelism, DP > 1)**,在多个节点上复制模型;加一个轻量的 DP 协调层;在副本之间做负载均衡;前面挂一个或多个 API Server 接收流量。

---

## 四、vLLM 的分布式服务栈

服务化部署有很多种拓扑。为了讲得具体,举一个例子:两台 H100 节点,跑 4 个 vLLM 引擎实例。

如果模型需要 `TP=4`,可以把两个节点这样配置:

![server configuration with 2 8xH100 nodes](https://www.aleksagordic.com/blog/vllm/server_setup.png)
*两台 8×H100 节点组成的服务(一台 headless、一台 API server)*

在第一个节点(headless 模式,不起 API server)运行:

```bash
vllm serve <model-name>
  --tensor-parallel-size 4
  --data-parallel-size 4
  --data-parallel-size-local 2
  --data-parallel-start-rank 0
  --data-parallel-address <master-ip>
  --data-parallel-rpc-port 13345
  --headless
```

在另一个节点跑同样的命令,做两处修改:

- 不加 `--headless`
- 修改 DP start rank

```bash
vllm serve <model-name>
  --tensor-parallel-size 4
  --data-parallel-size 4
  --data-parallel-size-local 2
  --data-parallel-start-rank 2
  --data-parallel-address <master-ip>
  --data-parallel-rpc-port 13345
```

### 📝 说明

前提是网络已经打通,所有节点能访问指定的 IP 和端口。

vLLM 里怎么实现的?

---

### 4.1 Headless 节点侧

Headless 节点上,`CoreEngineProcManager` 会启动 2 个进程(数量 = `--data-parallel-size-local`),每个跑 `EngineCoreProc.run_engine_core`。这个函数创建一个 `DPEngineCoreProc`(engine core)并进入其忙循环。

`DPEngineCoreProc` 初始化时会先初始化父类 `EngineCoreProc`(其父类是 `EngineCore`),流程:

1. 创建 `input_queue` 和 `output_queue`(`queue.Queue` 类型的**线程安全**队列)
2. 通过 **ZMQ `DEALER` socket**(异步消息库)与另一个节点上的 frontend 完成**初始握手**,收到协调地址信息
3. 初始化 DP group(比如用 NCCL 后端)
4. 用 `MultiProcExecutor` 初始化 `EngineCore`(如前所述,4 GPU 上跑 `TP=4`)
5. 创建 `ready_event`(`threading.Event`)
6. 起一个**input 守护线程**(`threading.Thread`)跑 `process_input_sockets(..., ready_event)`。类似地起一个 **output 线程**
7. 主线程继续等待 `ready_event`,直到跨 2 个节点的 4 个进程里所有 input 线程都完成握手,最后由某个线程执行 `ready_event.set()` 唤醒主线程
8. 一旦解除阻塞,给 frontend 发一条"ready"消息,附带元数据(比如 paged KV Cache 里可用的 `num_gpu_blocks`)
9. 主线程、input 线程、output 线程各自进入稳态忙循环

**TL;DR**:最终会得到 4 个子进程(每个 DP 副本对应一个),每个进程内跑 3 个线程(main / input / output)。它们与 DP Coordinator、frontend 完成协调握手后,三个线程都进入稳态忙循环。

![distributed system with 4 DPEngineCoreProc](https://www.aleksagordic.com/blog/vllm/dpenginecoreproc.png)
*分布式系统:4 个 DP 副本,4 个 `DPEngineCoreProc`*

**当前稳态下三个线程的行为:**

- **Input 线程**:阻塞在 input socket 上;有请求路由过来就 decode 消息、通过 `input_queue.put_nowait(...)` 入队,然后继续阻塞
- **主线程**:阻塞在 `input_queue.get(...)`;拿到后喂进 engine;`MultiProcExecutor` 做完 forward,把结果 push 到 `output_queue`
- **Output 线程**:阻塞在 `output_queue.get(...)`;拿到就通过 output socket 发回 API server,继续阻塞

**额外机制:**

- **DP wave 计数器** — 系统会跟踪"wave"的概念:所有引擎空闲时进入 quiesce 状态,有新工作进来时 wave 计数器自增(用于协调 / 观测)
- **控制消息** — API server 传的可不只是推理请求,还有 abort、utility RPC 等控制消息
- **锁步同步的 dummy step** — 只要**任一** DP 副本有工作,**所有**副本都必须走一次 forward。没请求的副本会跑一次 **dummy step** 参与必要的同步点(避免阻塞活跃副本)

> **锁步的边界:** 严格说来只有 **MoE 模型** 才需要锁步——因为 expert 层跨 EP/TP group、attention 层则可能是 DP-only。目前 DP 一律走锁步,只是因为在非 MoE 场景下"内建 DP"的实际用处有限——你完全可以起多个独立 vLLM 再在外层做常规负载均衡。

下面看 API server 节点这一侧。

---

### 4.2 API Server 节点侧

我们实例化一个 `AsyncLLM` 对象(对 LLM engine 的一个 asyncio 包装)。它内部创建一个 `DPLBAsyncMPClient`——**D**ata-**P**arallel、**L**oad-**B**alancing、**A**sync、**M**ulti-**P**rocessing client。

在 `MPClient` 父类里,`launch_core_engines` 函数会:

1. 创建启动握手用的 ZMQ 地址(前面 headless 节点侧也看到过)
2. **Spawn 一个 `DPCoordinator` 进程**
3. 创建 `CoreEngineProcManager`(和 headless 节点一样)

在 `AsyncMPClient`(`MPClient` 的子类)里:

1. 创建 `outputs_queue`(`asyncio.Queue`)
2. 起一个 asyncio task `process_outputs_socket`,通过 output socket 与 4 个 `DPEngineCoreProc` 的 output 线程通信,把消息写入 `outputs_queue`
3. 再起一个 asyncio task `output_handler`(在 `AsyncLLM` 里),从这个队列读取,最终把内容送给 `create_completion` 函数

在 `DPAsyncMPClient` 里,起一个 asyncio task `run_engine_stats_update_task`,与 DP Coordinator 通信。

**DP Coordinator** 是 frontend(API server)与 backend(engine core)之间的中介,它:

- 周期性把负载均衡信息(队列长度、waiting/running 计数)推给 frontend 的 `run_engine_stats_update_task`
- 处理 frontend 发来的 `SCALE_ELASTIC_EP` 命令,动态调整 engine 副本数(**仅 Ray backend 支持**)
- 向 backend 广播 `START_DP_WAVE` 事件(由 frontend 触发),并把 wave 状态更新报回给 frontend

回顾一下,**Frontend(`AsyncLLM`)运行的 asyncio task**(并发,不是并行):

- **一类**处理输入请求的 task,走 `generate` 路径——每个客户端请求会 spawn 一个
- **两个** task(`process_outputs_socket`、`output_handler`)处理来自底层引擎的输出消息
- **一个** task(`run_engine_stats_update_task`)与 DP Coordinator 通信:发 wave 触发信号、拉取 LB 状态、处理动态扩缩容请求

最后,主服务进程会创建一个 **FastAPI app**,挂载 `OpenAIServingCompletion`、`OpenAIServingChat` 等 endpoint(暴露 `/completion`、`/chat/completion` 等),由 **Uvicorn** 承接。

---

### 4.3 完整的请求生命周期

把所有环节串起来。你从终端发出:

```bash
curl -X POST http://localhost:8000/v1/completions -H "Content-Type: application/json" -d '{
  "model": "TinyLlama/TinyLlama-1.1B-Chat-v1.0",
  "prompt": "The capital of France is",
  "max_tokens": 50,
  "temperature": 0.7
}'
```

发生了什么:

1. 请求命中 API server 上 `OpenAIServingCompletion` 的 `create_completion` 路由
2. 该函数**异步 tokenize** prompt,准备元数据(request id、采样参数、时间戳等)
3. 调用 `AsyncLLM.generate`,与同步 engine 走一样的流程,最终触发 `DPAsyncMPClient.add_request_async`
4. `add_request_async` 内部调用 `get_core_engine_for_request`,根据 DP Coordinator 的状态做**负载均衡**——选负载分数最低(`score = len(waiting) * 4 + len(running)`)的那个 engine
5. `ADD` 请求通过 input socket 送到被选中的 engine
6. 到达 engine 后:
   - **Input 线程**:解除阻塞,decode input socket 数据,把 work item 塞进 `input_queue` 给主线程
   - **主线程**:从 `input_queue` 唤醒,把 request 加进 engine,反复调用 `engine_core.step()`,把中间结果推到 `output_queue`,直到 stop 条件触发
   - 提醒:`step()` 会调度、调用 model executor(也可能是 `MultiProcExecutor`!)等等,这些我们前面都讲过
   - **Output 线程**:从 `output_queue` 唤醒,通过 output socket 把结果推回
7. 结果到达 `AsyncLLM` 的 asyncio task(`process_outputs_socket` 与 `output_handler`),token 一路回传到 FastAPI 的 `create_completion` 路由
8. FastAPI 附上 metadata(finish reason、logprobs、usage 等),通过 Uvicorn 返回 `JSONResponse`,一路推回到你的终端

一次 completion 就这样出来了——整套分布式机器藏在一条 `curl` 后面!

### 📝 补充说明

- **扩展多个 API server 时**,负载均衡在 **OS/socket 层**完成;应用层几乎不用改动
- 使用 Ray 作为 DP 后端时,可以暴露 `/scale_elastic_ep` 端点,实现引擎副本数的**自动弹性伸缩**

---

## 五、Benchmark 与自动调优:延迟 vs 吞吐

前面我们一直在追踪"气体分子"——一条请求在引擎/系统内部的流动细节。现在换个视角,把系统当一个整体来看:**如何衡量一套推理系统的性能?**

在最高层,有两个此消彼长的指标:

1. **Latency(延迟)** — 从请求提交到 token 返回所用的时间
2. **Throughput(吞吐)** — 系统每秒能生成/处理的 token 或请求数

**Latency** 对交互式应用最关键——用户在等着响应。

**Throughput** 对离线工作负载最关键——如预/后训练用的合成数据生成、数据清洗/处理、以及任何离线批量推理任务。

在讲清楚"延迟与吞吐为什么互相冲突"之前,先定义几个常用推理指标:

| 指标 | 定义 |
|:---|:---|
| `TTFT`(time to first token,首 token 时延) | 请求提交到第一个输出 token 到达的时间 |
| `ITL`(inter-token latency,token 间时延) | 相邻两个 token 之间的间隔时间(比如第 i-1 与第 i 个 token) |
| `TPOT`(time per output token,单 token 时延) | 一个请求内所有 output token 的 ITL 平均值 |
| `Latency / E2E`(端到端延迟) | 处理一个请求的总时间,即 `TTFT + Σ ITL`,等价于"提交请求到收到最后一个 token"的时间 |
| `Throughput`(吞吐) | 每秒处理的 token 数(input / output / total 三种口径),或每秒完成的 request 数 |
| `Goodput`(有效吞吐) | 满足 **SLO** 的那部分吞吐——比如"TTFT / TPOT / E2E 不超过阈值"的请求才被计入 |

![ttft, itl, e2e latency](https://www.aleksagordic.com/blog/vllm/latency_diagram.png)
*TTFT、ITL、E2E Latency 示意*

下面用一个简化模型解释这两个指标为什么互相冲突。

**假设**:权重 I/O 占主导,而非 KV Cache I/O——也就是说我们处理的是**短序列**场景。

从 batch size `B` 对**单个 decode step** 的影响入手:

- `B ↓ → 1`:ITL 下降。step 内工作量小,token 之间不"抢占"资源
- `B ↑ → ∞`:ITL 上升(每 step 的 FLOPs 更多),但吞吐提升(权重 I/O 被更多 token 摊薄)——直到硬件性能触顶

用 **Roofline 模型**理解会更清晰:

- 当 `B < B_sat`(饱和 batch)时,step 时间被 **HBM 带宽**主导(权重要一层层从 HBM 流到 on-chip 内存),step latency **近似恒定**——算 1 个 token 和算 10 个 token 花的时间差不多
- 当 `B > B_sat` 时,kernel 变成**计算受限**,step 时间近似**线性增长**于 `B`——每多一个 token 都会挤占 ITL

![roofline perf model](https://www.aleksagordic.com/blog/vllm/roofline.png)
*Roofline 性能模型*

### 📝 说明

更严谨的分析要考虑 **kernel auto-tuning**:随着 `B` 增大,runtime 可能切换到更适合当前 shape 的高效 kernel,`P_kernel` 会跟着变。Step latency 满足 `t = FLOPs_step / P_kernel`,其中 `FLOPs_step` 是本 step 的计算量。可以看到:一旦 `P_kernel` 触顶 `P_peak`,每一 step 多做的算力就会**直接线性映射到延迟**上。

---

### 如何在 vLLM 里 Benchmark

vLLM 提供 CLI `vllm bench {serve, latency, throughput}`,底层封装 `vllm/benchmarks/{server,latency,throughput}.py`。

各脚本行为:

- **latency** — 短输入(默认 32 token),采样 128 个 output token,小 batch(默认 8)。跑若干轮,报告整个 batch 的 E2E 延迟
- **throughput** — 一次性提交一组固定 prompt(默认 1000 条 ShareGPT 样本),相当于 `QPS = Inf` 模式。报告 input / output / total token 与 request per second
- **serve** — 起一个 vLLM server,用 **Poisson**(或更一般的 **Gamma**)分布采样请求**到达间隔**,模拟真实工作负载,在一段时间窗口内发送请求。测量前述所有指标,支持通过 **信号量** 强制 server 端的最大并发数(比如限制为 64 并发)

一个 `latency` 脚本的例子:

```bash
vllm bench latency
  --model <model-name>
  --input-tokens 32
  --output-tokens 128
  --batch-size 8
```

CI 里用到的 benchmark 配置放在 `.buildkite/nightly-benchmarks/tests` 下。

此外还有一个 **auto-tune 脚本**,基于 `serve` benchmark 自动搜索满足目标 SLO 的参数组合(例如"在 p99 E2E < 500 ms 的前提下最大化吞吐"),返回一个推荐配置。

---

## 六、结语

我们从最小的 engine core(`UniprocExecutor`)出发,叠加了 Speculative Decoding、Prefix Caching 等进阶特性;规模上做了 scale up(`MultiProcExecutor`,`TP/PP > 1`),又做了 scale out——用 `AsyncLLM` 包装、构建分布式服务栈;最后收尾于**如何衡量性能**。

vLLM 里还有一些本文没有深入的部分,比如:

- **多硬件后端**:TPU、AWS Neuron(Trainium / Inferentia)等
- **架构与技术**:`MLA`、`MoE`、encoder-decoder(如 Whisper)、pooling/embedding 模型、`EPLB`、`m-RoPE`、`LoRA`、`ALiBi`、attention-free 变体、sliding-window attention、多模态 LM、状态空间模型(Mamba / Mamba-2 / Jamba)
- **TP / PP / SP**
- **异构 KV Cache 逻辑**(Jenga)、beam sampling 等更复杂的采样方法
- **实验特性**:异步调度(async scheduling)

好消息是:上面这些大多是**正交**于本文主干的——几乎可以按"插件"的心态去理解(实践中当然会有一些耦合)。

我热爱理解系统本身。当然,在这个高度上,分辨率不可避免地会损失一些。后续文章会**下钻到具体子系统**,把细节讲透。

### 💡 联系原作者

如果发现错误,欢迎在 [X](https://x.com/gordic_aleksa) 或 [LinkedIn](https://www.linkedin.com/in/aleksagordic/) 私信,或者通过[匿名反馈表](https://docs.google.com/forms/d/1z1fEirrN2xtGxAsJvptpM7yV4ByT5SF25S-XiMPrXNA/edit)。

### 致谢

感谢 [Hyperstack](https://www.hyperstack.cloud/) 过去一年提供的 H100 资源!

感谢 [Nick Hill](https://www.linkedin.com/in/nickhillprofile/)(vLLM 核心贡献者、RedHat)、[Mark Saroufim](https://x.com/marksaroufim)(PyTorch)、[Kyle Krannen](https://www.linkedin.com/in/kyle-kranen/)(NVIDIA、Dynamo)以及 [Ashish Vaswani](https://www.linkedin.com/in/ashish-vaswani-99892181/) 在发表前对本文提出的反馈。

---

## 参考文献

1. vLLM,https://github.com/vllm-project/vllm
2. "Attention Is All You Need",https://arxiv.org/abs/1706.03762
3. "Efficient Memory Management for Large Language Model Serving with PagedAttention",https://arxiv.org/abs/2309.06180
4. "DeepSeek-V2: A Strong, Economical, and Efficient Mixture-of-Experts Language Model",https://arxiv.org/abs/2405.04434
5. "Jenga: Effective Memory Management for Serving LLM with Heterogeneity",https://arxiv.org/abs/2503.18292
6. "Orca: A Distributed Serving System for Transformer-Based Generative Models",https://www.usenix.org/conference/osdi22/presentation/yu
7. "XGrammar: Flexible and Efficient Structured Generation Engine for Large Language Models",https://arxiv.org/abs/2411.15100
8. "Accelerating Large Language Model Decoding with Speculative Sampling",https://arxiv.org/abs/2302.01318
9. "EAGLE: Speculative Sampling Requires Rethinking Feature Uncertainty",https://arxiv.org/abs/2401.15077
10. "Medusa: Simple LLM Inference Acceleration Framework with Multiple Decoding Heads",https://arxiv.org/abs/2401.10774
11. LMCache,https://github.com/LMCache/LMCache

---

## 译者附录:关键术语速查表

作为快速索引,把全文出现的高频术语按类别整理如下。

### 引擎与调度

| 英文 | 中文 | 说明 |
|:---|:---|:---|
| `LLMEngine` / `EngineCore` | LLM 引擎 / 引擎核心 | 顶层引擎与其"发动机"内核 |
| `EngineCoreRequest` / `Request` | 引擎级请求 | 输入经 Processor 处理后打包成的调度单元 |
| `Scheduler` | 调度器 | 决定每个 step 跑哪些 request |
| `waiting` / `running` queue | 等待队列 / 运行队列 | 调度器的两个核心队列 |
| FCFS / Priority | 先到先服务 / 优先级调度 | 调度策略 |
| Continuous Batching | 连续批处理 | 每个 step 动态注入新请求,避免同步 batch 的 padding 浪费 |
| Chunked Prefill | 分块预填充 | 将超长 prompt 的 prefill 切成 chunk,避免长请求垄断 step |
| `step()` | 引擎步进函数 | Schedule → Forward → Postprocess |

### KV Cache 与 PagedAttention

| 英文 | 中文 | 说明 |
|:---|:---|:---|
| PagedAttention | 分页注意力 | 用 OS 分页思想管理 KV Cache |
| KV Cache Block | KV 缓存块 | 默认 16 token 一块,PagedAttention 的最小单位 |
| `free_block_queue` | 空闲块队列 | KV Block 的对象池(双向链表) |
| `block_table` / `slot_mapping` | 块表 / 槽位映射 | Token → KV Block 存储位置的索引 |
| `req_to_blocks` | 请求→块 映射 | request_id → 该请求持有的 KV Block 列表 |
| Prefix Caching | 前缀缓存 | 复用共享前缀的 KV,避免重算 |
| `cached_block_hash_to_block` | 哈希→块 索引 | Prefix Caching 的核心索引结构 |
| `BlockHash` | 块哈希对象 | 包含哈希值和 token id |
| Preemption(recompute / swap) | 抢占(重算 / 换出) | 显存不足时释放低优先级 request 的 KV |

### 采样与解码

| 英文 | 中文 | 说明 |
|:---|:---|:---|
| Guided Decoding | 引导式(结构化)解码 | 基于文法约束采样 |
| FSM | 有限状态机 | 引导解码的约束载体 |
| `_grammar_bitmask` | 文法位掩码 | 编码"哪些 token 允许"的 bitmap |
| Speculative Decoding | 推测解码 | 用小模型 draft、大模型 verify |
| Draft / Verify / Accept-Reject | 起草 / 验证 / 接受-拒绝 | Speculative 三阶段 |
| Rejection Sampler | 拒绝采样器 | 从数学上保证输出分布与大模型一致 |
| N-gram / EAGLE / Medusa | 三种 draft 方案 | vLLM V1 支持的候选生成机制 |

### 并行与分布式

| 英文 | 中文 | 说明 |
|:---|:---|:---|
| TP / PP / DP / EP / SP | 张量 / 流水线 / 数据 / 专家 / 序列并行 | 五类并行策略 |
| `UniProcExecutor` / `MultiProcExecutor` | 单进程 / 多进程执行器 | vLLM 的两种执行器 |
| `world_size` / `rank` / `driver worker` | 全局进程数 / 进程序号 / 主 worker | MPI/NCCL 惯用语义 |
| `rpc_broadcast_mq` / `worker_response_mq` | 广播队列 / 响应队列 | Executor 与 worker 之间的共享内存队列 |
| CUDA Graph | CUDA 计算图 | 提前录制 GPU 工作流,消除 kernel launch 开销 |

### 服务化

| 英文 | 中文 | 说明 |
|:---|:---|:---|
| `AsyncLLM` | 异步 LLM | LLM engine 的 asyncio 包装 |
| `DPLBAsyncMPClient` | DP + LB + 异步 + 多进程 客户端 | 分布式服务里的核心客户端类型 |
| `DPEngineCoreProc` | DP 引擎核心进程 | 每个 DP 副本对应的进程 |
| `CoreEngineProcManager` | 核心引擎进程管理器 | 负责起/停 engine 子进程 |
| `DPCoordinator` | DP 协调器 | 前后端之间的负载/wave/伸缩中介 |
| DP wave | DP 波次 | 所有引擎"忙-闲"周期的抽象单位 |
| ZMQ `DEALER` / `ROUTER` | ZMQ 异步 socket 类型 | 用于握手与请求路由 |
| Uvicorn / FastAPI | Python ASGI 组合 | HTTP 入口栈 |
| Disaggregated P/D | Prefill / Decode 解耦 | 把两类负载拆到不同实例 |
| KV Connector | KV 连接器 | 跨实例 KV 传输的抽象接口 |

### 性能指标

| 英文 | 中文 | 说明 |
|:---|:---|:---|
| TTFT | 首 token 时延 | 请求提交 → 第一个 token |
| ITL | Token 间时延 | 相邻两个 token 之间 |
| TPOT | 单 token 时延 | 一次请求内 ITL 平均值 |
| E2E Latency | 端到端时延 | TTFT + Σ ITL |
| Throughput | 吞吐 | Token/s 或 Request/s |
| Goodput | 有效吞吐 | 满足 SLO 的吞吐部分 |
| Compute-bound | 计算受限 | Prefill 的典型画像 |
| Memory-bandwidth-bound | 显存带宽受限 | Decode 的典型画像 |
| Roofline | 屋顶线模型 | 分析计算 vs I/O 谁是瓶颈的经典模型 |
| `B_sat` | 饱和 batch | Roofline 拐点,越过后进入 compute-bound 区 |

---

*本翻译面向 AI Infra 工程师、SGLang/vLLM 潜在贡献者、以及所有想从源码级别理解现代 LLM 推理系统的工程师。若你在阅读中发现术语翻译不当或与 vLLM 主线源码演进不一致,欢迎指出。*



