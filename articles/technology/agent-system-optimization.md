---
title: "你的 Agent 在摸鱼：系统侧才是真正的性能战场"
slug: "agent-system-optimization"
category: "技术"
tags: [Agent, LLM, 系统优化, KVCache, 推理加速, 论文笔记]
author: "Wynne"
date: "2026-08-01 20:00:00"
summary: "Autellix 测过：MCTS agent 里 91.3% 的时间在排队等待，只有 8.7% 在真正推理。换更快的 GPU 不如先把系统税收回来。本文梳理 KV Cache、工具调度、沙箱启动三个战场的前沿论文。"
published: true
---

# 你的 Agent 在摸鱼：系统侧才是真正的性能战场

大家都在卷更好的 prompt、更强的 reasoning、更聪明的 harness。但有一个数字很少被提到：

**MCTS agent 跑起来，91.3% 的时间在排队等待 LLM 响应，只有 8.7% 在真正推理。**

这是 Autellix（UC Berkeley，NSDI 2026）实测出来的。换句话说，你精心调教的 agent，绝大多数时间都在"摸鱼"——不是因为模型慢，是因为系统没把它当回事。

这篇文章聊的是系统侧优化：不换模型、不改 prompt，纯靠系统层面的工程手段把 Agent E2E latency 砍下来。基于 2025-2026 年的 7 篇论文。

---

## 先把时间花在哪里搞清楚

在讨论怎么优化之前，得先知道时间花在哪。下面这张分解图是三个方向的典型数据：

| 瓶颈 | 数据来源 | 典型占比 |
|------|---------|---------|
| 工具执行（串行等待） | PASTE 实测 gemini-cli | ~60% E2E latency |
| LLM 请求排队等待 | Autellix MCTS workload | 91.3% E2E latency |
| 容器/沙箱冷启动 | AgentCgroup 144 任务 | 31%–48% E2E latency |
| prefix cache 未命中 | GAIATrace GAIA benchmark | 可带来 1.67×–3.82× 提升空间 |

看完这张表应该有一个感受：**模型推理本身，在 Agent 的 E2E latency 里占比少得可怜**。三个真正的大头是——工具在等、请求在排队、容器在冷启动。

这就是为什么我认为，当前 Agent 系统优化的核心战场不在 GPU 利用率，而在这三件事。

---

## 误区一："开了 prefix cache 就够了"

prefix cache 对 Agent 确实有大用——但有一个非常容易被忽视的副作用，让很多人以为开了就万事大吉。

**GAIATrace**（arxiv:2606.01725，Penn State + SK Hynix + KAIST）是目前第一个 token 级 Agent trace 数据集，记录了两个 SOTA agentic system 在 GAIA benchmark 上的完整执行轨迹。配套的 Vidur-Agent 可以在不同系统配置下回放这些 trace，低成本评估优化效果。

他们发现 prefix cache 带来了 **1.67×–3.82× 的任务级加速**——比此前简单 Agent 研究报告的 15.7% 大得多。但问题在这里：

| 组件 | prefix cache 开启后 |
|------|-------------------|
| MiroThinker Main-LLM TTFT | 大幅下降 ✅ |
| MiroThinker Sub-LLM TTFT | **反而恶化** ❌ |
| OWL Sub-LLM | 改善有限（KV 命中率 0.3%）|

为什么会反向恶化？逻辑很直白：Main-LLM 因为 cache 命中变快了，单位时间内向 Sub-LLM 提交的请求变多了，Sub-LLM 的 prefill 队列被撑长，反而更慢。

> **这个"加速上游反而拖垮下游"的现象，在只研究单 LLM 的工作里完全看不到。** 它说明 Agent 系统里，任务级 latency 和组件级 TTFT 可以根本性地背离——优化一个点，可能在另一个点制造更大的问题。

多模型协作的 Agent pipeline 需要系统级统筹，不是给每个 LLM 单独调参。

---

## GPU 侧：KV Cache 调度的三种思路

知道了 prefix cache 的局限，下面看三个更精细的 KV cache 管理方案。

### KVFlow：给每个 Agent 算一个"距离值"

传统 LRU 淘汰策略的问题是盲目的——它不知道哪个 agent 下一步会被激活，可能正好淘汰了马上要用的 KV，留着一个要等好久才用的 KV。

**KVFlow**（arxiv:2507.07400，UCSD + AWS，NeurIPS 2025）的解法是引入一个 **Agent Step Graph**：把 workflow 的执行计划抽象成图，给每个 agent 节点算一个 `steps-to-execution` 值——距离下次激活还有几步。

这个值直接驱动淘汰策略：`steps-to-execution` 越大（越久才用），越先被淘汰；越小（马上要用），越受保护。同时在后台线程提前把"下一步"要用的 KV 从 CPU 搬到 GPU，与当前 agent 的生成完全重叠。

**效果**：单 workflow 大 prompt 场景比 SGLang hierarchical radix cache **快 1.83×**，多 workflow 并发 **快 2.19×**。

### TokenCake：工具调用是"搬运 KV 的黄金窗口"

**TokenCake**（arxiv:2510.18586，北大 + 阿里）把 agent 等待工具执行的时间变成了一个机会——这段时间里 agent 什么都不做，KV cache 白白占着 GPU 显存。

两个调度器协同工作：
- **Temporal Scheduler**：agent 发出 function call 的瞬间，立即把它的 KV 卸载到 CPU DRAM；同时预测工具执行时间，提前把 KV 搬回来，让传输延迟藏在工具等待时间里
- **Spatial Scheduler**：基于 workflow DAG 的关键路径 + 运行时状态动态分配显存分区，关键路径 agent 的 KV 不会被低优先级 agent 挤掉

**效果**：相比 vLLM，E2E latency 降低 **47.06%**，GPU 显存利用率提升 **16.9%**。

### Continuum：有时候最好的策略是什么都不动

**Continuum**（arxiv:2511.02230，UC Berkeley，ICLR 2026）提出了一个更简单的问题：agent 进入短暂的 tool call 等待时，到底该不该淘汰它的 KV？

标准 inference engine 的答案是"立即淘汰"。但如果 tool call 很短，淘汰后重新计算 KV 的代价反而更高。

Continuum 的做法是算一个 TTL：

```
TTL = f(重载成本, 预期 tool 延迟, 当前队列延迟)
```

只有当等待时间短于重载成本时才固定 KV，TTL 到期自动释放。配合 program-level FCFS 把同一个 agent job 的所有 turn 作为整体调度，防止单轮等待饿死整个 job。

**效果**：真实 SWE-agent workload 上延迟最高降低 **8.18×**，平均任务完成时间提升 **1.12×–3.66×**。

---

## 误区二："工具慢是工具的问题，不是 Agent 的问题"

工具慢，不代表没法优化。关键在于：**工具执行和 LLM 生成现在是完全串行的——LLM 生成时工具在等，工具跑完后 LLM 再生成**。这两件事理论上可以大幅重叠。

PASTE 在三类真实 agent 上的实测数据：

| Agent | 类型 | 工具执行占 E2E 比例 |
|-------|------|----------------|
| gemini-cli（Google 官方开源） | coding / SWE-bench | ~60% |
| Qwen Deep Research | 深度研究 | ~50% |
| VirtualLab | 科学研究 | ~36%–45% |

**这部分时间不是在推理，全都在等工具。** 而这些等待时间，是可以被利用的。

---

## CPU 侧：工具调度的三种玩法

### PASTE：像 CPU 乱序执行一样提前发工具请求

**PASTE**（arxiv:2603.18897，上交 + Microsoft Research）的核心洞察：Agent 程序的控制流是有规律的。同一个 agent 在不同任务上，倾向于按相同顺序调用同一组 tool——比如 `git clone` 后几乎必然跟 `git checkout`。

如果这个模式可以被预测，就可以在 LLM 还在生成当前步骤输出的时候，提前投机性地发射下一个 tool call。工具执行延迟被完全隐藏在 LLM 生成时间里。

系统由三个组件构成：
- **Pattern Analyzer**：从历史执行轨迹挖掘控制流和数据流规律，实时预测下一个 tool call
- **Tool Speculation Scheduler**：在 LLM 生成期间发射投机调用，隔离结果，正式调用优先级不受影响
- **LLM-Tool Co-Scheduler**：防止大量投机调用同时完成涌入 GPU，避免把瓶颈转移到推理侧

如果预测对了，工具延迟被完全隐藏；如果预测错了，丢弃结果，零正确性影响。PASTE 作为 sidecar 部署，不需要改底层 serving 或 agent runtime。

**效果**：平均任务完成时间降低 **43.5%–48.5%**，工具 p99 延迟降低 **60.6%**。

### Autellix：把 Program 当作调度单元，而不是单个 LLM call

这是目前我觉得最有启发性的一个工作。

**Autellix**（arxiv:2502.13965，NSDI 2026）的核心问题：vLLM 这类 serving 系统把每个 LLM call 当作独立请求排队，完全不知道这些 call 属于同一个 agent program。结果是什么？

高负载下，一个 program 里的每个 LLM call 都要重新排一次队。多排几次，时间就全没了：

| Workload | 等待时间占比 | 真正推理占比 |
|----------|-----------|-----------|
| Chatbot  | 80.4%     | 19.6%     |
| ReAct    | 87.0%     | 13.0%     |
| MCTS     | **91.3%** | **8.7%**  |

Autellix 的解法是把 **program** 作为调度单元，提出两个调度算法：

- **PLAS**（单线程 agent）：优先调度"已消耗 GPU 时间最少"的 program，短任务先跑完，减少平均等待——本质是 OS 调度里 LAS 策略的 program 级实现
- **ATLAS**（多线程 agent DAG）：以 program 内关键路径的累计服务时间为优先级，优先推进关键路径上的 LLM call，减少整个 program 的 makespan

**效果**：相比 vLLM，**program 吞吐量提升 4–15×**（相同 latency 下）。

### SPAgent：搜索 Agent 的两阶段投机

**SPAgent**（arxiv:2511.20048，清华 NICS-EFC）针对 ReAct 式搜索 agent 做了更精细的拆分。

它把 agent 的每一步分成两个串行瓶颈——LLM 推理和工具执行——然后分别出手：

**Phase 1（针对推理时间）**：早期信息收集步骤往往很简单，正确动作不需要完整的 chain-of-thought 就能预测。直接跳过 reasoning，预测错了再回退。消除了大量不必要的 token 生成。

**Phase 2（针对工具执行时间）**：复杂步骤仍然需要推理，但可以在 LLM 推理期间同步发出预测的 tool call，两者重叠执行。

对比之前的 "Speculative Actions"（只做了 Phase 2），SPAgent 补上了 Phase 1，避免了前者因额外推理调用反而让推理时间增加 26% 的问题。系统侧还有一个两级调度器，根据 engine 负载动态调节投机激进程度。

**效果**：E2E 加速最高 **1.65×**，LLM 推理时间减少 23.8%，工具等待时间减少 29.4%，准确率持平甚至略有提升。

---

## 第三战场：容器冷启动，被忽视的最大单项开销

如果你跑的是代码类 agent（修 bug、跑测试、执行 bash），这个数字应该让你坐直：

**容器 + agent 初始化（冷启动）占 E2E latency 的 31%–48%。**

这是 **AgentCgroup**（arxiv:2602.09345，UC Santa Cruz）对 144 个 SWE-rebench 任务做 eBPF 级 OS profiling 得出的结论。完整分解：

| 组件 | 占 E2E 时间比例 |
|------|--------------|
| 容器 + agent 初始化 | **31%–48%** |
| 工具执行 | ~26% |
| LLM 推理 | 26%–44% |

为什么冷启动这么重？AI coding agent 的容器镜像是多 GB 级别的——编译器、测试运行器、包管理器、语言服务器全打包进去。每次 OOM kill 后要重新拉取镜像层、重初始化框架、重建 agent 状态，远比传统 serverless 冷启动贵。

更麻烦的是资源需求极度不可预测：同一任务不同运行之间内存差 **1.8×**，不同任务之间差 **20×**，峰值/均值比高达 **15.4×**。任何基于历史预测的静态分配在这里都不管用。

AgentCgroup 的解法是用 eBPF 在 OS 层做细粒度资源控制：

1. **粒度对齐到 tool call 边界**：用 cgroup v2 层级结构，每个 tool call 执行期间获得临时子 cgroup，透明 bash wrapper 拦截，agent 代码零改动
2. **内核级响应**：`sched_ext` + `memcg_bpf_ops` 在微秒级内核层直接执行，无用户态上下文切换
3. **意图驱动而非预测驱动**：接收"这个任务高优先级"这样的高层意图，根据实时行为动态调整，不依赖历史预测

**效果**：高优先级任务 p95 延迟降低 **29%**，内存紧张时任务存活率 **100%**，高优先级任务额外开销仅 +2.8%。

还有一个配套思路：**Fault-Tolerant Sandboxing**（arxiv:2512.12806）提出用事务性文件系统快照来避免冷启动。执行失败不重建容器，直接秒级回滚到快照。这样 agent 可以激进地尝试代码修改，比谨慎地不敢执行端到端反而更快。

---

## 真正值得关注的空白地带

这三个方向——KV Cache 调度、工具调度、沙箱管理——目前基本是独立在研究的。

但真正能打的 Agent serving 系统应该是三层协同的：serving 层感知 workflow 调度 KV，执行层投机预取工具结果，沙箱层用热池消除冷启动延迟。

目前这三层有任何一层整合做得好的系统，我还没看到。这是真正的空白地带，也是接下来最值得关注的方向。

如果你现在要在自己的 agent pipeline 里做系统优化，可以问自己三个问题：

1. **我的 serving 层有没有 prefix cache，有没有意识到多 LLM 协作下的队列效应？**
2. **我的工具调用是完全串行的吗？有没有机会把工具执行和 LLM 生成重叠起来？**
3. **我的沙箱/容器每次都是冷启动吗？这一项单独可能比推理优化空间更大。**

---

## 参考论文

- [GAIATrace](https://arxiv.org/abs/2606.01725) — Characterization of Multi-Model Agentic AI Systems on General Tasks via Trace-Driven Simulation
- [KVFlow](https://arxiv.org/abs/2507.07400) — Efficient Prefix Caching for Accelerating LLM-Based Multi-Agent Workflows（NeurIPS 2025）
- [TokenCake](https://arxiv.org/abs/2510.18586) — A KV-Cache-centric Serving Framework for LLM-based Multi-Agent Applications
- [Continuum](https://arxiv.org/abs/2511.02230) — Efficient and Robust Multi-Turn LLM Agent Scheduling with KV Cache Time-to-Live（ICLR 2026）
- [PASTE](https://arxiv.org/abs/2603.18897) — Parallelizing Tool Execution and LLM Generation for Low-Latency Agent Serving
- [Autellix](https://arxiv.org/abs/2502.13965) — An Efficient Serving Engine for LLM Agents as General Programs（NSDI 2026）
- [SPAgent](https://arxiv.org/abs/2511.20048) — Reducing Latency of LLM Search Agent via Speculation-based Algorithm-System Co-Design
- [AgentCgroup](https://arxiv.org/abs/2602.09345) — Understanding and Controlling OS Resources of AI Agents
- [Fault-Tolerant Sandboxing](https://arxiv.org/abs/2512.12806) — A Transactional Approach to Safe Autonomous Execution
