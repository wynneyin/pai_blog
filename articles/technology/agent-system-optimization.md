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

**GAIATrace**（arxiv:2606.01725，Penn State + SK Hynix + KAIST）是第一个 token 级 Agent trace 数据集，记录了两个 SOTA agentic system 在 GAIA benchmark 上的完整执行轨迹：

- **MiroThinker**：ReAct 风格，有一个 Main-LLM（编排器）和多个 Sub-LLM（工具/执行器），随着对话轮数增加，推理 token 快速累积，prefill 增长迅速
- **OWL**：Sub-LLM 频繁处理长文本或图像，Sub-LLM 本身是性能瓶颈

配套的 **Vidur-Agent** 是 trace-driven simulator，支持多模型异构架构、KV prefix cache、prefill-decode 分离等特性，可以低成本地在不同系统配置下回放 GAIATrace。

**核心发现：prefix cache 带来 1.67×–3.82× 的任务级加速**——远超此前简单 Agent 研究中报告的约 15.7% 的提升。但收益高度不均匀，且存在反直觉的副作用：

| 组件 | prefix cache 开启后 |
|------|-------------------|
| MiroThinker Main-LLM TTFT | 大幅下降 ✅ |
| MiroThinker Sub-LLM TTFT | **反而恶化** ❌ |
| OWL Sub-LLM | 改善有限（本身 KV 命中率极低）|

MiroThinker 的 Sub-LLM TTFT 恶化的机制：Main-LLM 因 cache 命中变快，向 Sub-LLM 提交请求的频率升高，Sub-LLM 的 prefill 队列被撑长，反而拖慢了。这个"加速上游反而拖垮下游"的现象在只研究单 LLM 的工作中完全观察不到。

> **核心启示**：任务级 latency 和组件级 TTFT 可以根本性地背离——优化一个 LLM 的 serving 不等于优化整个 Agent 的 E2E 延迟，多模型协作需要系统级统筹。

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

### TokenCake：时间 + 空间双维度协同

**TokenCake**（arxiv:2510.18586，北大 + 阿里）针对 multi-agent 应用的两个 KV cache 失效模式：

- **空间竞争**：多个 agent 同时抢 GPU 显存，LRU 淘汰策略不知道哪个 agent 是关键路径，可能正好淘汰了下一步要用的 cache
- **时间闲置**：agent 等 tool 执行结果期间，它的 KV cache 白白占着 GPU 显存，什么都不做

TokenCake 用两个调度器协同解决：

**Temporal Scheduler（时间维度）**：
- 事件驱动，agent 发出 function call 时立即将其 KV 卸载到 CPU DRAM
- 根据 tool 执行时间预测，提前将 KV 搬回 GPU，传输延迟被 tool 执行时间隐藏

**Spatial Scheduler（空间维度）**：
- 基于 workflow DAG 的关键路径 + 运行时状态计算优先级
- 为关键路径 agent 预留显存分区，防止被低优先级 agent 挤掉

**效果**：相比 vLLM，E2E latency 降低 **47.06%**，GPU 显存利用率提升 **16.9%**。

---

### Continuum：TTL 固定，跨 tool call 保留 KV

**Continuum**（arxiv:2511.02230，UC Berkeley，ICLR 2026）解决的问题更直接：标准 inference engine 在 agent 进入 tool call 等待时立即淘汰其 KV cache，下一轮恢复时不得不重算——而这个代价往往远高于把 KV 留着的代价。

**核心设计**：计算一个 TTL（time-to-live），在 TTL 期间将 KV 固定在显存里：

```
TTL = f(重载成本, 预期 tool 延迟, 当前队列延迟)
```

只有当等待时间短于重载成本时才固定，TTL 到期自动释放，高负载时优雅退化。

同时配合 **program-level FCFS**：将同一个 agent job 的所有 turn 作为整体调度，防止单轮等待饿死整个 job。

**效果**：在真实 SWE-agent workload 上延迟最高降低 **8.18×**，跨 benchmark 平均任务完成时间提升 **1.12×–3.66×**，吞吐量提升 **1.10×–3.22×**。

---

## 二、CPU 侧：工具调度的三层优化

### 问题有多严重？

**PASTE**（arxiv:2603.18897，上交 + Microsoft Research，v3 更名为 "Parallelizing Tool Execution and LLM Generation for Low-Latency Agent Serving"）在三类真实 agent 上做了实测，tool execution 在 E2E latency 中的占比：

| Agent | 类型 | Tool 占比 |
|-------|------|---------|
| gemini-cli（Google 官方开源） | coding / SWE-bench | ~60% |
| Qwen Deep Research | 深度研究 / DeepResearchBench | ~50% |
| VirtualLab | 科学研究 / ScholarQA | ~36%–45% |

工具执行全程躺在关键路径上，大模型生成时工具在等，工具跑完时大模型再生成——两者几乎从不重叠。

**Autellix**（arxiv:2502.13965，NSDI 2026）从 serving 侧看到了另一面：高负载（load=0.9）下，程序的 E2E latency 被分解后，**等待时间占比触目惊心**：

| Workload | 等待时间占比 | 执行时间占比 |
|----------|------------|------------|
| Chatbot  | 80.4%      | 19.6%      |
| ReAct    | 87.0%      | 13.0%      |
| MCTS     | 91.3%      | 8.7%       |

Agent 程序越复杂（LLM call 链越长），等待占比越高——因为每一个 call 都要重新排队，积累的延迟是乘法效应。

---

### PASTE：投机执行，把工具调用提前

PASTE 的核心洞察：**Agent 程序的控制流具有稳定的模式**。同一个 agent 在不同 task 上倾向于按相同顺序调用同一组 tool。如果这个模式可以被预测，就可以在 LLM 还在生成上一步输出的时候，提前投机性地发射下一个 tool call——就像 CPU 的乱序执行把内存访问提前一样。

**系统设计**：

PASTE 的核心抽象是 **Pattern Tuple**，同时捕获两类规律：
- **控制流规律**：哪个 tool 之后通常跟着哪个 tool（如 `git clone` 后几乎必然跟 `git checkout`）
- **数据流规律**：tool 参数如何从之前的 tool 输出中派生

基于此，PASTE 由三个组件构成：

- **Pattern Analyzer**：从历史执行轨迹中挖掘控制流和数据流规律，构建预测模型，在服务阶段根据当前 session 状态生成具体的下一步 tool call 预测
- **Tool Speculation Scheduler**：协调正式调用和投机调用——在 LLM 生成期间发射投机 tool call，隔离结果直到 LLM 确认，保障正式调用的优先级不受影响
- **LLM-Tool Co-Scheduler**：控制返回 LLM session 的节奏，防止大量投机调用完成后同时涌入 GPU 导致瓶颈转移

投机执行管线：
1. Pattern Analyzer 预测下一个最可能的 tool call，提前发射
2. 投机结果隔离存放，等 LLM 确认
3. 若匹配：直接提交，工具延迟完全被隐藏；若不匹配：丢弃，零正确性影响

**两个正确性保证**：
- **Promotion 协议**：LLM 确认的 tool call 若与正在投机执行的任务匹配，立即提升其优先级并提交结果
- **Non-interference 保证**：投机任务只使用"松弛资源"（transient idle compute），资源紧张时立即抢占，绝不影响正式执行

PASTE 作为 sidecar 部署，无需修改底层 LLM serving 或 agent runtime。

**效果**：
- 平均任务完成时间降低 **43.5%–48.5%**
- 工具平均延迟降低 **1.8×**（最高 55.2%）
- p95 尾延迟降低最高 **59.3%**，p99 降低最高 **60.6%**

---

### Autellix：以程序为单位调度 LLM 请求

现有 LLM serving 系统（vLLM 等）把每个 LLM call 当作独立请求处理，完全丢失了"这些请求属于同一个 agent program"的上下文信息。Autellix 的核心思路是把 **program** 作为调度单元，而不是单个 LLM call。

**两个调度算法**：

- **PLAS（Program-Level Attained Service）**：用于单线程 agent。优先调度"已消耗 LLM 服务时间最少"的 program，让短任务优先完成，减少平均等待时间——本质是 SRPT（Shortest Remaining Processing Time）在 program 级的实现。

- **ATLAS（Adaptive Thread-Level Attained Service）**：用于多线程 agent（如 multi-agent DAG）。以 program 内最长线程的累计服务时间作为优先级，优先调度关键路径上的 LLM call，减少整个 program 的 makespan。

**架构**：Autellix 在 LLM serving 层拦截所有 agent 发出的 LLM call，维护全局 process table 跟踪每个 program 的状态，调度器根据 program-level context 决定优先级。

**效果**：相比 vLLM 等 SOTA serving 系统，**program 吞吐量提升 4–15×**（相同延迟下）。

---

### SPAgent：搜索 Agent 的两阶段投机

**SPAgent**（arxiv:2511.20048，清华 NICS-EFC + Infinigence）针对 ReAct 式搜索 agent 做了算法-系统协同设计。

每个 agent step 有两个串行瓶颈：**LLM 推理**（生成 thought + action）和**工具执行**（Wikipedia API 约 1.5s）。SPAgent 分别对这两个瓶颈出手：

**Phase 1 — 激进投机（针对 LLM 推理时间）**：
- 用于早期简单的信息收集步骤
- **直接跳过 LLM reasoning**，不生成 chain-of-thought，直接预测下一个动作
- 若预测错误则回退到完整推理（rollback）
- 消除了早期不必要的 token 生成开销

**Phase 2 — 验证型投机（针对工具执行时间）**：
- 用于需要推理的复杂步骤
- **在 LLM 推理期间同步发出推测的 tool call**，工具执行与 LLM 生成重叠
- 若 LLM 最终决定与预测一致：工具结果已就绪，直接使用
- 若不一致：丢弃投机结果，重新执行正确调用

对比前序工作 "Speculative Actions"（只做了 Phase 2），SPAgent 补上了 Phase 1，避免了前者因额外推理调用导致推理时间反增 26% 的问题。

**两级调度器（系统侧）**：根据 engine 当前负载动态决定是否触发投机——高负载时保守，低负载时激进，避免投机浪费加剧排队。

**效果（vLLM + Wikipedia API，Qwen2.5/Gemma-3 多模型验证）**：
- E2E 加速最高 **1.65×**，平均 **24.2%**
- LLM 推理时间减少 **23.8%**（Phase 1）
- 工具执行等待时间减少 **29.4%**（Phase 2）
- 准确率持平，部分模型（Qwen2.5-32B）在 TriviaQA 上准确率提升超 5%

---

## 三、harness 本身：启动开销与失败重试

### 容器启动是个大坑

**AgentCgroup**（arxiv:2602.09345，UC Santa Cruz + Virginia Tech）对 144 个 SWE-rebench 任务做了细粒度的 eBPF 级 OS profiling，得到了这张 E2E latency 分解图：

| 组件 | 占 E2E 时间比例 |
|------|--------------|
| 容器 + agent 初始化（冷启动） | **31%–48%** |
| 工具执行 | ~26% |
| LLM 推理 | 26%–44% |

冷启动之所以这么重——AI coding agent 的容器镜像是多 GB 级别的（捆绑了编译器、测试运行器、包管理器、语言服务器等），每次 OOM kill 后都要重新拉取镜像层、重初始化容器框架、重建 agent 状态。这个代价远超传统 serverless 冷启动。

更糟糕的是资源需求极度不可预测：
- 同一任务不同运行之间，内存需求差异可达 **1.8×**
- 不同任务之间差异高达 **20×**
- 峰值/均值内存比：**15.4×**

**AgentCgroup 的系统设计**：基于 eBPF 的 OS 资源控制器，三个核心原则：

1. **粒度对齐**：用 cgroup v2 层级结构对齐 tool call 边界——每个 agent 是父节点，每个 tool call 执行期间获得一个临时子 cgroup，透明 bash wrapper 自动拦截，agent 代码零改动
2. **内核级响应**：用 `sched_ext`（CPU 调度）和 `memcg_bpf_ops`（内存控制）在微秒级内核层直接执行，无用户态上下文切换开销
3. **意图驱动的自适应**：不用静态限额，而是接收高层意图（"这个任务高优先级"），根据实时 tool call 行为动态调整分配

**效果**（多租户内存竞争场景）：
- 高优先级任务 p95 分配延迟降低 **29%**（70.97ms → 50.14ms）
- 内存紧张时任务存活率 **100%**（通过压制低优先级分配）
- 高优先级任务额外开销仅 **+2.8%**

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
