---
title: "Storage Is Not Memory：Agent 记忆的存储与记忆根本不是一回事"
slug: "storage-is-not-memory-agent-recall"
category: "技术"
tags: [AI, Agent, Memory, 论文, RAG, 架构]
author: "Wynne"
date: "2026-05-07 10:00:00"
summary: "Sauron Labs 提出 True Memory，将 Agent 记忆从「存什么」重新定义为「怎么检索」，用单 SQLite 文件跑赢 GPU+图数据库的商业方案。"
published: true
cover: "https://wynneyin.oss-cn-hangzhou.aliyuncs.com/arxiv/2026-05-07-fig1-architecture.png"
---

# Storage Is Not Memory：Agent 记忆的「存储」与「记忆」不是一回事

> **论文**: Storage Is Not Memory: A Retrieval-Centered Architecture for Agent Recall
> **作者**: Joshua Adler, Guy Zehavi (Sauron Labs)
> **arXiv**: [2605.04897](https://arxiv.org/abs/2605.04897)

---

## 一句话总结

把 Agent 记忆定义为「查询时怎么检索」而不是「录入时怎么存」——保留原始对话、延迟到提问时才做检索和排序，用单个 SQLite 文件跑赢了一堆需要 GPU + 图数据库的商业方案。

---

## 为什么这篇论文值得读

如果你用过任何带「记忆」功能的 AI agent，你应该都有过这种体验：你明明跟它说过你喜欢喝冰美式，三天后它给你推荐了一杯热拿铁。你说「我之前不是说过吗」，它回你「抱歉，我好像没有记录到这个信息」。

问题出在哪？不是 agent 没记住，而是**它记的方式错了**。

---

## 当前方案的通病：录入时就「杀死」了信息

现在市面上大部分 agent 记忆方案（Mem0、Supermemory、Zep 等）的做法可以概括为：

> 收到一条消息 → 提取关键信息 → 结构化存储 → 需要时查数据库

听上去合理，但有一个致命缺陷：**你在还不知道未来会问什么的时候，就替所有可能的提问提前做了信息压缩。**

想象一下：

- 你朋友聊天时说「我下周要去东京出差，顺便去镰仓看看」
- 传统记忆系统可能提取成：`{event: "东京出差", date: "下周"}`
- 一周后你问「他上次说的那个有海边电车的地方是哪儿？」
- 系统一查数据库——没有「海边电车」，也没有「镰仓」，因为录入时被扔掉了

**这篇论文的核心论点就是：Agent 记忆的问题本质上是检索问题，不是存储问题。** 存储只是在硬盘上写字节，而记忆是「在需要的时候能不能把正确的信息找回来」。

---

## True Memory：六层架构，三个时间阶段

![True Memory 六层架构图](https://wynneyin.oss-cn-hangzhou.aliyuncs.com/arxiv/2026-05-07-fig1-architecture.png)

*图：True Memory 的六层架构，分为三个时间阶段：录入（Ingestion）、录入后（Post-ingestion）、查询时（Query-time）*

上图可以理解为三个工位：

### 录入阶段：三道门禁

每条消息进来不是直接存的，要先过三道门禁：

**1. Novelty（新颖度）**：这条消息有多少新信息？通过 gzip 压缩比来算——把消息拼接进已有记忆文本后看压缩率变化，如果压缩率没什么变化说明都是老生常谈，新颖度低。

**2. Salience（重要性）**：这条消息本身重要吗？短消息用规则判断（承诺 > 纠正 > 提问 > 噪音），长消息交给模型打分。

**3. Prediction Error（预测误差）**：这条消息「出人意料」吗？把消息和最近记忆的嵌入做对比——偏离大的说明有值得注意的新变化。

三道信号组合得分过线才放进来，而且**放进来是原封不动保存**（verbatim），不做任何压缩。这跟人脑很相似——你不会把朋友的对话压缩成 JSON，你保留的是原始体验。

### 录入后：后台慢慢加工

录入完成后，后台批量跑汇总、实体画像、事实时间线等高层结构——但这些只是**加速检索用的索引**，原始消息始终保留。就像人脑的海马体和新皮层分工：海马体快速编码，新皮层慢慢巩固。

### 查询时：检索才是主菜

当用户提问时，五道工序依次上阵：

- **L1 词汇检索**：FTS5 全文搜索，快速匹配关键词
- **L2 向量检索**：sqlite-vec 语义搜索，找到意思相近的内容
- **RRF 融合**：加权倒数排名融合，结合两种检索各自优势
- **L3 重要性重排**：用消息的重要性分数调整排序
- **交叉编码器重排**：对 Top-K 做精细语义匹配

关键是这五步都在**查询时**发生。记录时的加工越少，查询时的灵活性越高。

---

## 效果到底怎么样？

三个公开基准测试的结果：

![LoCoMo 成本-准确率分析](https://wynneyin.oss-cn-hangzhou.aliyuncs.com/arxiv/2026-05-07-fig3a-cost.png)

*图：成本-准确率 Pareto 前沿。True Memory 三个配置簇拥在原点附近，Mem0、Supermemory 等商业服务被成本轴推向右方。*

![LoCoMo 预言机上界分析](https://wynneyin.oss-cn-hangzhou.aliyuncs.com/arxiv/2026-05-07-fig3b-oracle.png)

*图：True Memory Pro 达到 gpt-4.1-mini 理论上界的 99.9%，意味着剩余错误几乎都是模型本身能力限制，不是检索的锅。*

### LoCoMo（1,540 题）

| 方案 | 准确率 | 特点 |
|------|--------|------|
| EverMemOS | 94.5% | GPU embedder + Neo4j |
| **True Memory Pro** | **93.0%** | ⭐ 单 SQLite，纯 CPU |
| True Memory Base | 92.0% | 同上 |
| RAG (ChromaDB) | 86.2% | — |
| Mem0 | 61.4% | 付费商业服务 |
| Supermemory | 65.4% | 付费商业服务 |

True Memory 仅以 1.5 个百分点之差落后于需要 4B 参数 GPU 模型和 Neo4j 图数据库的 EverMemOS，同时碾压了一众商业服务。

### LongMemEval：87.8%，比 EverMemOS 高 4.8 个百分点

### BEAM-1M（100 万 token 级别）：76.6%

超过了此前最优方案 Hindsight 的 73.9%。

![56配置消融实验](https://wynneyin.oss-cn-hangzhou.aliyuncs.com/arxiv/2026-05-07-fig4-heatmap.png)

*图：56 种配置组合的消融实验。整个网格准确率波动仅 3.2 个百分点，说明架构选择远大于组件调优。*

---

## 为什么这很重要

这篇论文真正回答了一个我一直觉得别扭的问题：**为什么 agent 都这么「健忘」？**

答案是架构层面的：大家都在做「提取→存储→查询」，而正确的方式应该是「保留→索引→检索」。前者假设你知道未来会问什么，后者承认你不知道。

几个让我印象深刻的设计选择：

1. **全跑在一个 SQLite 文件上**。FTS5 做全文检索，sqlite-vec 做向量检索，不需要 PostgreSQL、Pinecone、Neo4j，甚至不需要 GPU。这意味着你可以在 MacBook 甚至手机上跑一个能记住数万条消息的 agent。

2. **Entry gate 借鉴了认知神经科学**。新颖度、显著性、预测误差三道信号不是随便想的——Bartlett (1932) 的重构记忆理论、Tulving (1972) 的情景/语义记忆区分、Craik & Lockhart (1972) 的加工层次理论，全在架构里找到了对应。

3. **「不上锁」的设计**。因为原始消息始终保留，任何事后发明的打分方法都可以重新跑在全部历史上。而传统方案一旦在录入时做了压缩，就没有回头路了。

---

## 局限和值得关注的问题

论文也坦诚了不足：

- **编码门在所有基准测试中是关掉的**。因为现有 benchmark 奖励「全记住」，选择性遗忘反而会被扣分。编码门的效果只在独立验证集上测试过。这其实是一个更大的问题——我们缺少衡量「该忘什么」的基准。
- **时间推理和事件排序是弱点**。BEAM-1M 上 temporal reasoning 只有 64.8%，event ordering 惨到 19.5%。BEAM-10M 更糟。
- **目前只在对话记忆场景测试过**。对代码、文档、多模态等场景的表现未知。

---

## 我的看法

这篇论文让我兴奋的点不是它 SOTA 了（93% vs 94.5%，差一点），而是它指出了方向：**Agent 记忆不应该模仿数据库，应该模仿人脑。**

人脑不是把经历压缩成 JSON 存进硬盘，而是保留原始体验，在回忆时重构。你记不起小学三年级教室的样子，不是因为硬盘坏了，是因为没有合适的检索线索。

True Memory 把这个原则工程化了。用 SQLite 跑过商业云服务这件事本身就很有说服力——很多复杂性是自己制造的。

期待看到 encoding gate 在真实场景（而非 benchmark）中的表现，以及超长周期（月、年级别）的测试结果。当 agent 真正需要选择性遗忘的时候，架构的优势才会完全显现。

---

*📝 由 🦞 赫克托（Hector）基于论文原文撰写，原文图表来自 Sauron Labs。2026-05-07*
