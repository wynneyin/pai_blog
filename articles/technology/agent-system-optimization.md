---
title: "Agent 系统侧优化全景：KV Cache、工具调度与沙箱启动的三大战场"
slug: "agent-system-optimization"
category: "技术"
tags: [Agent, LLM, 系统优化, KVCache, 推理加速]
author: "Wynne"
date: "2026-08-01 20:00:00"
summary: "从 GPU 侧 KV Cache 驻留、CPU 侧工具调度到 harness 启动开销，系统地梳理当前 Agent 执行延迟的三大瓶颈及对应的前沿论文方案。"
published: true
---

# Agent 系统侧优化全景：KV Cache、工具调度与沙箱启动的三大战场

> 本文基于知乎作者 Arsmart 的梳理框架，结合 GAIATrace、Autellix、PASTE、SPAgent、KVFlow、TokenCake、AgentCgroup 等近期论文展开。

Agent 的优化方向和 LLM 类似，分算法侧（更好的 reasoning、更强的 harness）和系统侧（更低的执行延迟、更少的 token 消耗）。本文只聚焦系统侧，且只讨论延迟，不讨论 token 消耗。

当前 Agent 执行时间的瓶颈主要集中在三处：

| 瓶颈 | 典型占比 | 代表论文 |
|------|---------|---------|
| GPU 侧：KV Cache 驻留与命中 | 随任务而异，prefix cache 可带来 1.67×–3.82× 提升空间 | GAIATrace、KVFlow、TokenCake、Continuum |
| CPU 侧：tool 调度（LLM-call 排队 + tool execution overlap） | tool execution 占 E2E 45%–57%；高负载排队占 80%–91% | PASTE、Autellix、SPAgent |
| harness 本身：启动开销与失败重试 | 容器/沙箱初始化占 E2E 31%–48% | AgentCgroup、Fault-Tolerant Sandboxing |

下面逐一拆解。

---

## 一、GPU 侧：KV Cache 的驻留与命中

### 为什么 Agent 特别依赖 KV Cache？

普通 LLM 推理每次请求独立，KV Cache 只在 batch 内复用。但 Agent 是多轮的——每一轮都携带完整的 system prompt、历史对话和 tool 返回结果，前缀高度重叠。如果每轮都重新计算 prefill，时间浪费极大。

**GAIATrace**（arxiv:2606.01725）是第一个 token 级 Agent trace 数据集，记录了在 GAIA benchmark 上运行的两个 SOTA agentic system（MiroThinker 和 OWL）的完整推理轨迹，包括每个 LLM call 的 token 数、任务结构和多模型协作关系。

配套的 **Vidur-Agent** 是 trace-driven simulator，可以在不同系统配置下回放 GAIATrace，低成本地评估系统优化效果。

论文的核心发现之一是：开启 prefix cache 后，Agent 任务级 E2E latency 可提升 **1.67×–3.82×**。但并非所有组件都受益——在 MiroThinker 中，Main-LLM 的 TTFT 大幅下降，但 Sub-LLM 的 TTFT 反而恶化：因为 Main-LLM 因 cache 命中变快，向 Sub-LLM 提交请求的频率上升，Sub-LLM 的 prefill 队列反而被撑长了。这个"加速一个 LLM 反而拖慢另一个"的现象在简单 Agent 研究中很少被观察到。

> **核心启示**：prefix cache 对 Agent 的收益远超单轮推理，但多 LLM 协作场景下需要系统级统筹，而非单点优化。

---

### KVFlow：用 Agent Step Graph 驱动缓存调度

**KVFlow**（arxiv:2507.07400，NeurIPS 2025）是专为 multi-agent workflow 设计的 KV cache 管理框架。

**核心问题**：传统 LRU 淘汰策略不知道哪个 agent 下一步会被激活，导致即将被用到的 KV 被淘汰，而闲置 agent 的 KV 却长期占据 GPU 显存。

**核心设计**：

1. **Agent Step Graph**：将 workflow 的执行计划抽象为图结构，每个 agent 被分配一个 `steps-to-execution` 值，表示它距离下次激活还有几步。这个值驱动细粒度的 KV 节点级淘汰——越快被用到的 KV，优先级越高，越不应该被淘汰。

2. **全重叠 KV 预取**：在后台线程中，提前将下一步将被激活的 agent 所需 KV 从 CPU 搬到 GPU，与当前 agent 的生成阶段完全重叠，避免 cache miss 时的阻塞等待。

**效果**：
- 单 workflow 大 prompt 场景：比 SGLang 的 hierarchical radix cache **快 1.83×**
- 多 workflow 并发场景：**快 2.19×**

---

### TokenCake：工具调用期间的 KV 卸载与预上传

**TokenCake**（arxiv:2510.18586）观察到一个被忽视的问题：agent 在等待 tool 执行结果（比如等搜索返回、等代码跑完）时，它的 KV cache 静静地躺在 GPU 显存里，什么事都不做，但又占着宝贵的空间。

TokenCake 提出 **Temporal Scheduler**：

- **机会式卸载**：当 agent 进入 tool call 等待状态时，主动将其 KV 卸载到 CPU，释放 GPU 显存给其他 agent 使用
- **预测性上传**：根据 tool 执行时间预测，提前将 KV 搬回 GPU，在 agent 恢复执行前完成数据传输，隐藏传输延迟

配合 Spatial Scheduler 处理空间竞争（避免关键 agent 的 KV 被挤掉），TokenCake 在 multi-agent 高并发场景下显著提升吞吐量。

---

### Continuum：跨 tool call 的 TTL 固定

**Continuum** 采用更简单的策略：对于执行时间极短的 tool call，重新计算 KV 的开销比卸载/恢复还高，因此用 **time-to-live pinning** 把 KV 固定在显存里，跨越短暂的 tool 等待期，直接复用。

KVFlow 侧重 workflow 级调度，Continuum 侧重短 tool call 的 KV 保留，两者互补。

---

## 二、CPU 侧：工具调度的三层优化

### 问题有多严重？

**PASTE**（arxiv:2603.18897）在 deep research、coding、scientific agent 三类场景中做了实测：**tool execution 占 E2E latency 的 45%–57%**。这意味着大模型在生成的时候，工具在等；工具在跑的时候，大模型在等。两者几乎从不重叠。

**Autellix**（arxiv:2502.13965）从 serving 侧看到了另一面：**高负载（load=0.9）下，不同 agent workload 的 LLM 请求等待时间占 80%–91%**。Agent 程序的 LLM call 被当作独立请求排队，系统完全不知道它们属于同一个 program，也不知道哪个 call 是关键路径。

---

### PASTE：投机执行，把工具调用提前

PASTE 的核心洞察：**Agent 程序的控制流具有稳定的模式**。同一个 agent 在不同 task 上倾向于按相同顺序调用同一组 tool。如果这个模式可以被预测，就可以在 LLM 还在生成上一步输出的时候，提前投机性地发射下一个 tool call——就像 CPU 的乱序执行把内存访问提前一样。

**系统设计**：
1. **Pattern Miner**：从历史执行轨迹中挖掘 tool call 序列的重复模式，建立预测模型
2. **Speculative Executor**：在 LLM generation 期间，根据预测模式提前发起 tool 请求
3. **Verification & Rollback**：LLM 输出确认后，若与预测一致，直接使用结果；若不一致，丢弃并正常执行

PASTE 作为 sidecar 部署在现有 agent runtime 旁边，无需修改底层基础设施。

**效果**：
- 平均任务完成时间降低 **43.5%**
- 工具平均延迟降低 **1.8×**
- p95/p99 工具延迟降低 **59.3%/60.6%**

---

### Autellix：以程序为单位调度 LLM 请求

现有 LLM serving 系统（vLLM 等）把每个 LLM call 当作独立请求处理，完全丢失了"这些请求属于同一个 agent program"的上下文信息。Autellix 的核心思路是把 **program** 作为调度单元，而不是单个 LLM call。

**两个调度算法**：

- **PLAS（Program-Level Attained Service）**：用于单线程 agent。优先调度"已消耗 LLM 服务时间最少"的 program，让短任务优先完成，减少平均等待时间——本质是 SRPT（Shortest Remaining Processing Time）在 program 级的实现。

- **ATLAS（Adaptive Thread-Level Attained Service）**：用于多线程 agent（如 multi-agent DAG）。以 program 内最长线程的累计服务时间作为优先级，优先调度关键路径上的 LLM call，减少整个 program 的 makespan。

**架构**：Autellix 在 LLM serving 层拦截所有 agent 发出的 LLM call，维护全局 process table 跟踪每个 program 的状态，调度器根据 program-level context 决定优先级。

**效果**：相比 vLLM 等 SOTA serving 系统，**program 吞吐量提升 4–15×**（相同延迟下）。

---

### SPAgent：搜索 Agent 的投机加速

**SPAgent**（arxiv:2511.20048）针对 multi-step search agent 做了算法-系统协同设计。

核心观察：**搜索 agent 早期步骤往往是简单的信息收集**，正确动作可以不经过完整推理就被预测出来（例如"下一步肯定还是搜索，关键词大概率是什么"）。

**两层设计**：
1. **两阶段自适应投机**：算法侧，引入置信度估计，对高置信度预测跳过 verification，减少不必要的 LLM call
2. **两级调度器**：系统侧，根据 serving engine 负载动态调节投机的激进程度——高负载时保守投机（减少浪费），低负载时激进投机（最大化加速）

**效果**：在 Qwen2.5、Gemma-3 等多个模型上，E2E 加速 **1.65×**，且准确率持平甚至略有提升（因为投机执行了更多动作，获取了更多信息）。

---

## 三、harness 本身：启动开销与失败重试

### 容器启动是个大坑

对于代码修改类、沙箱执行类 agent（自动修 bug、跑 pytest、执行 bash 命令），每次 tool call 都可能需要一个隔离的执行环境。

**AgentCgroup** 的测量结果：**container/agent 初始化开销占 E2E latency 的 31%–48%**。完整虚拟机的启动延迟通常达到数十秒，直接破坏了 agent 的 think-act-observe 循环节奏。

**核心优化思路**：

1. **热沙箱复用**：预先启动一批容器（hot pool），agent 需要时直接分配，而不是从零创建
2. **预构建依赖**：常用 Python 包、项目依赖、镜像层提前构建好，避免每次安装
3. **跨任务环境复用**：如果两个任务的依赖环境相同，直接共享已初始化的容器状态

---

### Fault-Tolerant Sandboxing：事务性快照

**Fault-Tolerant Sandboxing**（arxiv:2512.12806）提出用**事务性文件系统快照**来解决 agent 代码执行的安全与效率双重问题：

- **策略拦截层**：在 cgroup 级别强制执行资源限制（应用层限制可被 agent 生成的代码绕过）
- **快照机制**：每次执行前做文件系统快照，执行失败可以秒级回滚，而不需要重新初始化整个容器
- **开销**：原型实现的每次事务开销约 1.8 秒（14.5% performance overhead）

这让 agent 可以激进地尝试代码修改，失败了直接回滚，比"谨慎地不敢执行"的策略端到端反而更快。

---

## 总结：三个战场的优化策略对比

```
Agent E2E Latency
│
├── GPU 侧（KV Cache）
│   ├── GAIATrace：量化 prefix cache 对 Agent 的收益（1.67×–3.82×）
│   ├── KVFlow：Agent Step Graph + 细粒度淘汰 + 预取（1.83×–2.19×）
│   ├── TokenCake：tool 等待期间 KV 卸载 + 预上传
│   └── Continuum：短 tool call 期间 TTL 固定
│
├── CPU 侧（工具调度）
│   ├── PASTE：模式挖掘 + 投机执行 tool（-43.5% 完成时间）
│   ├── Autellix：program 级调度，PLAS/ATLAS（4–15× 吞吐）
│   └── SPAgent：两阶段自适应投机 + 负载感知调度（1.65× E2E）
│
└── harness 侧（启动开销）
    ├── AgentCgroup：热沙箱复用 + 跨任务环境共享
    └── Fault-Tolerant Sandboxing：事务快照 + cgroup 隔离
```

这三个方向目前基本是独立研究的，真正能打的系统应该是三层协同——serving 层感知 workflow 调度 KV，执行层投机预取 tool 结果，沙箱层用热池消除启动延迟。这个方向的系统整合研究目前还比较少，是值得关注的空白地带。

---

## 参考论文

- GAIATrace：[Characterization of Multi-Model Agentic AI Systems on General Tasks via Trace-Driven Simulation](https://arxiv.org/abs/2606.01725)
- KVFlow：[Efficient Prefix Caching for Accelerating LLM-Based Multi-Agent Workflows](https://arxiv.org/abs/2507.07400)（NeurIPS 2025）
- TokenCake：[A KV-Cache-centric Serving Framework for LLM-based Multi-Agent Applications](https://arxiv.org/abs/2510.18586)
- PASTE：[Parallelizing Tool Execution and LLM Generation for Low-Latency Agent Serving](https://arxiv.org/abs/2603.18897)
- Autellix：[An Efficient Serving Engine for LLM Agents as General Programs](https://arxiv.org/abs/2502.13965)
- SPAgent：[Reducing Latency of LLM Search Agent via Speculation-based Algorithm-System Co-Design](https://arxiv.org/abs/2511.20048)
- Fault-Tolerant Sandboxing：[A Transactional Approach to Safe Autonomous Execution](https://arxiv.org/abs/2512.12806)
