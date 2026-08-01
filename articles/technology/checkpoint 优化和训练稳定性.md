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