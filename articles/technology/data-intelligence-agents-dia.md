---
title: "把"会写代码的 Agent"当一等公民：DIA 如何用一个 Agent 通吃企业数据集成"
slug: "data-intelligence-agents-dia"
category: "技术"
tags: [AI, Agent, 论文, LLM, Text-to-SQL, 数据工程, ACA]
author: "Wynne"
date: "2026-06-18 10:00:00"
summary: "C3 AI 的新论文 DIA 把"自主编码 Agent (ACA)"作为核心抽象——一个 Agent + 共享 workspace + 共享 memory，就在 7 个 SQL 基准上把现有的专门系统全部追平或超越，最大优势 +33 分。"
published: true
cover: "https://wynneyin.oss-cn-hangzhou.aliyuncs.com/arxiv/2026-06-18-DIA-fig1_benchmarks.png"
---

> 论文：**Data Intelligence Agents: Interpreting, Modeling, and Querying Enterprise Data via Autonomous Coding Agents**
> 作者：Anoushka Vyas, Aarushi Dhanuka, Sina Khoshfetrat Pakazad, Henrik Ohlsson（C3 AI）
> arXiv：[2606.19319](https://arxiv.org/abs/2606.19319) · 提交于 2026-06-17

## 一句话总结

DIA 把"能写代码、能跑代码、能改错"的自主编码 Agent（Autonomous Coding Agent，ACA）当成系统设计的**第一公民**——不是把 LLM 当大脑，而是把"LLM + 沙箱 + 文件系统"整体当大脑——结果在 7 个 SQL 基准、4 种方言、4 类任务上一个不落地追平或超过当前 SOTA，最大领先 **+33 分**。

![*图 1：DIA 在 7 个 SQL 基准上对比此前最佳系统，按领先幅度排序，每个基准用其官方指标。*](https://wynneyin.oss-cn-hangzhou.aliyuncs.com/arxiv/2026-06-18-DIA-fig1_benchmarks.png)

## 为什么值得一看

过去两年 LLM Agent 的研究路径几乎都在做"垂类专精"：text-to-SQL 是一类，schema 生成是一类，对话式查询是一类，每个赛道里都堆了一堆精巧的多 Agent 流水线、RL 微调小模型、专属 prompting tricks。每篇论文都在自己那块表上把数字往上推一两个点。

DIA 的态度恰恰相反：**这些系统其实一个 Agent 就够了**——只要你换对抽象。

它没有提出什么新模型，也没有微调，**用 Claude Sonnet 4.5 + OpenHands 现成的 ACA**，靠一个 Agent 跨四种任务（生成、调试、对话、dbt 项目补全）跨四种方言（SQLite、PostgreSQL、Snowflake、DuckDB），把行业里散落各处的最强系统**全部碾过去**。

这件事如果是真的（论文已经在 C3 AI 的客户生产环境跑），那对 Agent 工程的影响很大：意味着我们之前堆的那些"多 Agent 流水线"很多是过度工程，是在用复杂度补偿抽象不足。

## 核心思想：什么叫"把 ACA 当一等抽象"

先解释一下术语。**ACA（Autonomous Coding Agent）** = LLM + 沙箱执行环境 + 文件系统。它和"普通 LLM call"的区别在于：

| LLM call | ACA |
| --- | --- |
| 输入文本，输出文本 | 输入指令 + 工件引用，输出可执行工件 |
| 错了你只能 retry | 错了它自己跑一下、看 traceback、改、再跑 |
| 中间过程 = 思考链文本 | 中间过程 = 真实代码 + 真实执行结果 |

DIA 的核心论断是：**企业数据这一整条流水线的所有 Agent，本质都是"ACA + 不同的自然语言指令"**。论文把它实例化成三个 Agent，但底层是同一个 ACA：

- **Data Interpreter（数据解释员）**：拿到一堆 CSV/JSON/Excel 原始数据，写代码 profile 它们——推断 schema、列语义、空值、候选主外键、可能的连接路径。
- **Schema Creator（建模员）**：根据上面的解释，写 DDL 和加载脚本，把数据物化成关系型数据库，并跑四类校验（行数、列覆盖、键有效性、加载完整性）。
- **Query Generator（查询员）**：拿到自然语言问题 + 数据库，写 SQL、跑、自检、自修。

三个 Agent 共享一个工作区 W（持久化文件）和一个记忆 M（跨任务复用经验）。

![*图 2：DIA 系统架构。同一个 ACA 在共享 workspace 上扮演三种角色，工件以文件形式持久化、被下游消费；所有 Agent 共享一个 memory；每个工件都给领域专家审阅。*](https://wynneyin.oss-cn-hangzhou.aliyuncs.com/arxiv/2026-06-18-DIA-fig2_arch.png)

## 三个关键设计

### 1. 输出"工件"，不输出"文本"

传统数据流水线是：

> 数据 → 工程师写文档描述数据 → 分析师读文档写 SQL → 出错 → 回头改 → ...

每一步交接都丢信息。DIA 把所有 Agent 的输出都改成**可执行、可检查的工件**——schema.json、DDL 文件、加载脚本、validation report、SQL 文件、查询结果……领域专家直接审视工件，而不是审阅 Agent 的"自然语言陈述"。

这背后的工程哲学是：**自然语言是有损压缩的，代码不是。** 一个写出来能跑的脚本比一句"我已经处理好了空值"靠谱十倍。

### 2. 三层记忆 + Pull-based 验证

记忆分三层：
- **检索式样例（episodic）**：过往相似问题与解的对照。
- **会话内规则（session lessons）**：在当前数据库验证过的条件性规则。
- **跨会话规则（cross-session lessons）**：能跨数据库泛化的语义规则。

但有意思的不是分层，而是**记忆是 pull-based、用前必验**：Agent 只在认为相关时才打开规则正文，而且必须先在当前数据上跑一个探针确认前提成立，才允许用这条规则改答案。论文给了一个很可爱的例子：

> 在 `california_schools` 库上数学校时，`COUNT(*)` 通过一对多 join 返回 9977，`COUNT(DISTINCT CDSCode)` 返回 1。Agent 由此自己总结出一条规则——「通过重复 join 数实体，要用 `COUNT(DISTINCT pk)`，不要用 `COUNT(*)`」——并在后续问题中验证、推广。

这等于让 Agent 自己积累了一套**带证据的、可验证的、可解释的**经验库，而完全不需要人写、也不需要训练（training-free）。

### 3. Shape Declaration + Self-Verification

Query Generator 在写 SQL 之前会先从问题里推出一个**预期结果形状** κ：

```
κ = (列名 Cκ, 粒度 gκ, 排序 oκ, 过滤条件 fκ)
```

然后写 SQL → 跑 → 拿到结果 R → 用一个指示函数 V(R, κ) 检查 R 是否符合声明的形状（每一项分别校验）。不符合，就诊断、改、再跑——**全部基于执行结果，不靠 LLM 自评，更不靠人。**

这一步特别像**编译原理里的"先声明类型再赋值"**。Agent 在动手前先告诉自己"我要交出一个什么形状的答案"，然后用执行去验证形状有没有交对。

## 实验结果：单一模型，全面碾压

直接看表（论文的 Table 1）：

| 基准 | 任务类型 | 此前最强 | DIA | Δ |
| --- | --- | --- | --- | --- |
| BIRD-Dev (SQLite) | 生成 | MARS-SQL **77.8** | 77.7 | -0.1 |
| BIRD-Critic (SQLite) | **调试** | Gemini 3.1 Pro 48.8 | **64.2** | **+15.4** |
| LiveSQLBench (PG) | 生成 | OpenHands+Opus 4.6 38.0 | **50.7** | **+12.7** |
| BIRD-Interact (PG) | **对话** | MERIT+GPT-5.4 22.7 | **55.7** | **+33.0** |
| Spider2-Lite | 生成 | ReFoRCE+o3 55.2 | **71.3** | **+16.1** |
| Spider2-Snow | 生成 | DSR-SQL+R1 63.8 | **69.5** | **+5.7** |
| Spider2-DBT (DuckDB) | 项目补全 | Spider-Agent+GPT-5.4 35.3 | **37.5** | **+2.2** |

注意到：
- **越是"非传统"的任务，DIA 越强**——对话式 +33、调试 +15、Lite +16。这正是传统流水线最尴尬、专精系统最难做的场景，也最能体现 ACA + 共享 memory 的优势。
- BIRD-Dev 上和 RL 训练的专才平手，但 DIA 是用一个**通用、零微调**的 Agent，工程意义远大于 -0.1 这个数字。
- **一个模型 + 一份脚手架打四类任务、四种方言**，对比每个基准都换不同系统，这才是真正的"通用 Agent"。

## 错误分析：剩下的失败几乎都是"语义"

DIA 的失败几乎都不是 SQL 跑不起来，而是**跑得起来、但答错了**。论文把它们分三类：

![*图 3：每个基准上的失败成分构成。reasoning（推理错）在所有基准上都是最大头。*](https://wynneyin.oss-cn-hangzhou.aliyuncs.com/arxiv/2026-06-18-DIA-fig3_failures.png)

- **reasoning（推理错）**——拿了错的 join、错的过滤、错的公式。最大头。
- **output convention（输出约定错）**——格式、单位、列序对不上。
- **grounding（标识对不上）**——题目里的"客户经理姓名"和库里的列名对不上。

这部分论文很坦诚：DIA 的 self-verification 是**形状级**而不是**语义级**，所以一旦它从问题里读错了意图，写出来的查询和后面的自检会**继承同一个误解**——错得心安理得。

## 对话式任务上的 scaling：互动确实有用

BIRD-Interact 上的对话任务，论文还做了一个**交互轮数的 scaling 曲线**：

![*图 4：BIRD-Interact 上的交互时间 scaling——前 k 轮内通过的实例占比。15 轮内就大幅领先此前最强系统的 22.7。*](https://wynneyin.oss-cn-hangzhou.aliyuncs.com/arxiv/2026-06-18-DIA-fig4_scaling.png)

会问、会问得好，是 Agent 在对话式数据查询里巨大的优势——而 DIA 把这个能力让一个通用 ACA 自然而然地用上了，没有额外训练。

## 局限与未来工作

论文自己列得很清楚：

1. **慢、贵**。每个问题都要跑生成-执行-校验-修复的循环，对话式任务还得多轮。每个问题平均 1~10 分钟，成本/token 论文没披露。这对交互场景或大流量场景可能不划算。
2. **Self-verification 只到形状级**。前面说过——没法发现"它把问题理解错了"这种深层错误，是当前误差的主要来源。下一步显然是把语义级验证做进去（也许借助第二个 Agent 做对抗式审查？）。
3. **评测的广度够、深度还不够**。三个 Agent 里只深入评了 Query Generator；只测了一个 LLM；对话用的是 LLM 模拟用户而不是真人；memory 只做了定性分析。
4. **memory 还可以做得更结构化**——目前是文件式存储，作者提到考虑做成**图结构**让经验更可挖掘。这点很合理，目前的 ARIA、ReasoningBank 思路也都在往这方向走。

## 个人看法

这篇论文我读完是有点兴奋的，几个原因：

**第一，它戳穿了"多 Agent 是必要的"这个迷思。** 业界一年多以来流行多 Agent 编排，但多 Agent 的真正合理性应该是「这些 Agent 有不同的能力 / 工具 / 权限 / 视角」。DIA 直接用结果说明：在数据智能这条流水线上，"多 Agent" 只是同一个 ACA 在不同 prompt 下的实例化——**抽象等级到位了，多 Agent 自然消失了**。

这和我之前在博客里写过的几篇（[Agent 工程踩坑](./agent-engineering-pitfalls)、[多卡 GPU 加速 ZKP] 那种过度工程化思路）是一脉相承的反面教材：很多时候多 Agent 不是优雅，是没找到正确的抽象层。

**第二，"输出工件而不是文本"这个原则被验证得非常充分。** 这其实是 CodeAct、OpenHands 这一脉一直在推的，但 DIA 把它推到了"整个流水线"的尺度。我觉得这条原则会在未来一两年成为 Agent 系统设计的默认：**只要任务的输出本来就是某种工件（代码、SQL、配置、报告），就不要让 Agent 输出对工件的描述，让它直接输出工件。**

**第三，pull-based + verify-before-use 的 memory 设计很优雅。** 现在很多 Agent 的"长期记忆"做得很笨——要么塞一堆 embedding 进 context，要么自动追加 reflection。DIA 的做法是：**记忆只是引用，用前现验**——对当前数据跑一个探针，过了才信。这把 memory 的价值锚定在"可验证的经验"上，而不是"曾经成功过的回忆"。

**第四，留给后人的最大空间是语义级 verification。** 形状级自检已经能解决一大半问题了，但剩下的错误是 reasoning 错——本质是"Agent 没理解人类到底想问什么"。这条路要么走对抗式审查（再来个 Agent 专门挑刺），要么真的要做意图建模——这块大有可为。

总的来说：**这是一篇典型的"工程胜利推动抽象前进"的论文**——没有花哨的 idea，没有新模型，但用对的抽象 + 充分的执行 + 简洁的 memory，把一个看似复杂的工业问题做到了 SOTA。这种风格我特别喜欢。

---

## 参考

- 论文：[arXiv:2606.19319](https://arxiv.org/abs/2606.19319)
- ACA 范式相关：CodeAct (Wang et al., 2024b)、OpenHands-Versa (Soni et al., 2025)
- 经验记忆：ARIA (He et al., 2025)、Voyager、Reflexion、ReasoningBank
- DIA 的部署：C3 AI 企业客户的生产环境（Appendix G）

---

## 相关阅读

- [[mindgames-multiagent-llm-arena]] — 多 Agent 评估的另一个角度：竞技场 benchmark
- [[agent-engineering-pitfalls]] — ACA 落地时会踩的工程坑
- [[tencent-agent-interview-knowledge]] — Agent + 数据工程在面试中的考察点
