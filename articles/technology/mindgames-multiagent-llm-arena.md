---
title: "MINDGAMES：用四款游戏给 944 个 LLM Agent 排名，结论比想象的更尴尬"
slug: "mindgames-multiagent-llm-arena"
category: "技术"
tags: [AI, Agent, LLM, 论文, 多智能体, 评估, NeurIPS]
author: "Wynne"
date: "2026-05-29 10:00:00"
summary: "NeurIPS 2025 用 Blotto / IPD / Codenames / Mafia 四款游戏，让 76 支队伍的 944 个 Agent 厮杀近 3 万场。结论是：榜单看着热闹，但「不出错」和「会动脑子」常常被排行榜混为一谈。"
published: true
cover: "https://wynneyin.oss-cn-hangzhou.aliyuncs.com/arxiv/2026-05-29-fig1-mindgames-game-suite.png"
---

> arxiv 2605.29512 · 2026 年 5 月 28 日 · MINDGAMES Organizer & Participation Teams (NeurIPS 2025 Competition)
> [PDF](https://arxiv.org/pdf/2605.29512) · [项目站](https://www.mindgamesarena.com/) · [Dataset](https://huggingface.co/datasets/mindgameschallenge/MGC2025)

## 一句话总结

MINDGAMES 把 76 支队伍提交的 **944 个 LLM Agent** 扔进 4 款不同性质的多智能体游戏里厮杀，跑了 **29,571 场比赛、2.43 亿 token**。然后他们把整个排行榜的"可信度"做了一遍法医级审计，发现一个尴尬的事实——**很多榜首位置其实是「对手先死了」赢下来的**。

## 为什么值得看

最近 LLM Agent 圈子最流行的事情就是搞 benchmark：MultiAgentBench、TextArena、SPINBench…… 一茬接一茬。问题是这些榜单上的高分到底意味着 Agent 真的会推理，还是只是「不会把自己玩死」？

MINDGAMES 这篇论文做了我一直好奇但没人愿意细究的事：**把 benchmark 自己 benchmark 一遍**。它不仅给出榜单，还告诉你「这个榜单在哪一档游戏里靠谱、在哪一档纯粹是抽奖」。

对任何打算用排行榜挑 Agent 框架的人来说，这套审计方法比榜单本身更有价值。

## 四款游戏，四种「心智」考验

MINDGAMES 在 TextArena 之上搭了一个统一的对战平台，挑了 4 款游戏，每款考查 LLM Agent 不同维度的"心智理论"（Theory of Mind）能力：

![*图 1：四款游戏的设计意图、规模与错误率。从左到右逐渐"脏"，最右边的 Secret Mafia 有一半的比赛都因为某些 Agent 犯错而崩盘。*](https://wynneyin.oss-cn-hangzhou.aliyuncs.com/arxiv/2026-05-29-fig1-mindgames-game-suite.png)

| 游戏 | 考查能力 | 玩家数 | 错误率 |
|---|---|---|---|
| **Colonel Blotto** | 重复博弈中的对手建模 | 2 人零和 | 8.5% |
| **Iterated Prisoner's Dilemma** | 通过对话推断意图 | 3 人混合动机 | 0% |
| **Codenames** | 受限信号下的协作推理 | 4 人 (2v2) | 38.6% |
| **Secret Mafia** | 公开辩论中的欺骗与推理 | 6 人非对称信息 | 50.3% |

四款游戏的"信息结构"和"激励对齐"都不一样：

- **Blotto** 是纯计算，零通信，纯靠对手历史出招猜下一步——考的是"你猜我猜你猜"的纯策略博弈。
- **IPD（三人囚徒困境）** 加了自由对话阶段——考的是"嘴上说合作，行动上要不要叛变"的承诺一致性。
- **Codenames** 是 2v2 协作，Spymaster 只能给"一个单词 + 一个数字"作为线索——考的是在极窄信道里传递信息。
- **Secret Mafia** 是 6 人狼人杀变体，黑手党知道身份、村民不知道——考的是公开场合**持续欺骗**与从他人言行**反推身份**。

整套交互通过文本完成，环境每回合按 `观察 → 行动 → 奖励` 推进：

![*图 2：MINDGAMES 的交互循环。Agent 收到游戏观察、输出文字行动，环境维护内部状态并记录全部 trajectory 用于事后分析。*](https://wynneyin.oss-cn-hangzhou.aliyuncs.com/arxiv/2026-05-29-fig2-mindgames-interaction-loop.png)

评测分两个赛道：**通用赛**（在 Blotto+IPD+Codenames 三个环境平均）和**社交推理赛**（专门跑 Secret Mafia）。每个赛道又分两个组：**Efficient**（开源、9B 参数以内）和 **Unlimited**（不限模型，可用 GPT-5、Gemini 等闭源大模型）。

## 944 个 Agent 教会我们的 5 件事

论文第 4 节是干货，把 944 份提交中的设计共识抽出来，每条都是真金白银打出来的：

### a. 训练 vs. 推理时增强：参数受限就训，参数无限就提示

Efficient 组（≤9B）冠军方案几乎都做了某种形式的**任务微调**（SFT、CoT-SFT、GRPO）。但 **STARS 团队**是个反例：它不训练，靠**代码执行**辅助决策，照样拿了通用赛 Efficient 组第二。

到了 Unlimited 组，6 个顶尖队伍里 **5 个完全不训练**，纯靠 GPT-5 / Claude 这类大模型 + 提示工程 + 推理时脚手架。唯一的例外是 **In2AI**，他们的 RL 训练版 Qwen3-8B 把 GPT-5 都顶了下去，拿了"最具创新奖"。它的秘诀不是某个单一技巧，而是**系统工程**：延迟奖励的信用归因 + 按环境归一化 + 课程式对手选择 + 异步 rollout。

> **个人感受**：这其实印证了我一直觉得的一件事——8B 模型 + 好训练 ≥ 大模型 + 提示词。问题只是 RL 这套基础设施太贵，大部分团队搞不动。

### b. 数据质量 >> 数据数量

微调过的提交里，**激进过滤**几乎一边倒地赢过"全量喂数据"。一个队伍只保留 LLM judge 打 5 分的轨迹（丢掉 80%）也比全量微调强；另一个队伍只在"有可观察后果的决策步"上算奖励。

> **大白话翻译**：垃圾轨迹会教模型学坏，宁可少喂一点干净的。

### c. 认知脚手架要配合训练，否则反而伤性能

一个反直觉的发现：给模型加 BDI（信念-欲望-意图）那种花哨的认知架构，如果没配合训练让它学会用，**反而会拖累性能**。简单的 prompt 反而更稳。

### d. 独立团队不约而同收敛到「感知-推理-行动」三段式

虽然实现各异，但多个顶尖团队都独立设计出了类似的模块化架构：观察解析 → 信念更新 → 行动生成。有一队甚至把"情境评估"和"行动选择"拆成两个 agent，比单体基线提升 4.7 倍。

共同的直觉是：**从原始文本观察到策略性行动，一次前向传播跳不过去**。

### e. 在狼人杀里，"说话的口气"是个策略变量

这点很有意思——一个 Secret Mafia 顶尖队伍会根据游戏状态在 **Aggressive / Withdrawing / Logically Anchoring / Contrarian** 四种语气间切换。这是传统棋类 AI 完全没有的维度。

更扎心的发现：**几乎所有 LLM 都不愿意明确撒谎**。当 Mafia 让它撒谎时，RLHF 训练出来的"诚实偏置"会让它表现疲软。一个队伍直接把 Villager 胜率从 16% 拉到 60%，但 Mafia 胜率始终卡在 88-96%——**信息少的角色才是大模型的短板**。

## 失败模式：榜单上看不到的"水分"

这是论文最辣的一章。先看四款游戏的错误率从 Stage I（在线天梯）到 Stage II（最终评测）的变化：

![*图 3：从 Stage I 到 Stage II，Codenames 错误率从 85% 降到 39%，Secret Mafia 从 78% 降到 50%。但 50% 是什么概念？一半的比赛里至少有一个 Agent 把自己玩死。*](https://wynneyin.oss-cn-hangzhou.aliyuncs.com/arxiv/2026-05-29-fig3-mindgames-error-rates.png)

错误的具体类型也很说明问题：

- **Colonel Blotto**：输入格式错误（114 次）+ 兵力分配超额（11 次）—— LLM 的数值约束满足能力有限。
- **Codenames**：唯一的错误类型是"非法线索"（339 次）—— Spymaster 把暗示词直接写成了答案的子串。
- **Secret Mafia**：无效操作 399 次 + 试图保护已死玩家 45 次 —— 长程状态追踪是真的难。

针对这些失败，论文也给出了设计建议：用 **PAL（程序辅助语言模型）** 让 LLM 写代码做约束检查；用 **结构化输出验证器** 在训练时把违规反馈给模型；用 **思考模式架构**（私有思考 + 公开输出分离）防止 Mafia 角色泄密。

## 评估的"暗面"：榜单到底在测什么？

如果只看到这里，这篇论文也就是个不错的竞赛总结。**真正让我拍案的是第 5 节**——他们把自己的榜单做了一次法医级审计。

### Error-Survival Confound（错误生存混淆）

最大的发现是：**Secret Mafia 榜首的 Agent，其实大部分赢局是因为对手先犯规出局**。

| 模型 | 比赛数 | 干净局 | 自己出错 | 对手出错 |
|---|---|---|---|---|
| RLGaming | 130 | **1** | 4 | **129** |
| Tungsten | 132 | **1** | 10 | **131** |
| Odyssean | 106 | **1** | 0 | **105** |

130 局里只有 1 局是真正"双方都没出错的纯策略对决"。其他 129 局里都至少有一方违规。所谓的"狼人杀冠军"，与其说是会推理，不如说是**比对手更不容易掉线**。

### TrueSkill vs Reward：两个榜单测出来不一样的赢家

![*图 5：左边是通用赛（Blotto+Codenames+IPD），右边是社交推理赛（Mafia）。横轴 Reward、纵轴 TrueSkill。如果两个指标完全一致，所有点应该落在对角线上。但你会发现：通用赛里 reward 从 -120 到 +150 横跨一大片，TrueSkill 却挤在 0-50 一个窄带里。社交推理赛里 Efficient 组（三角形）TrueSkill 高、reward 接近 0；Unlimited 组（菱形）正好反过来。*](https://wynneyin.oss-cn-hangzhou.aliyuncs.com/arxiv/2026-05-29-fig5-mindgames-trueskill-vs-reward.png)

两个指标背后说的是完全不同的事：
- **TrueSkill** 是基于 pairwise 胜负的贝叶斯排名——它说的是「在校准过的对手池里你能不能稳定赢」。
- **Cumulative Reward** 是绝对累积分数——它说的是「你一共赚了多少」。

在 Secret Mafia 里，Efficient 组累积 TrueSkill 高但 reward 接近零，意思是：**他们靠对手出错赢了配对，但没真正攒到大分**。Unlimited 组反过来——攒到了分，但配对胜率没那么稳。

> **这意味着什么**：以后再看到 "我们 Agent 在 XX benchmark 第一" 的宣传，请追问一句："你用的什么排名指标？对手错误率多少？"

### 行为多样性：所有冠军长得都差不多

他们用 OpenAI 的 embedding 把每个模型的回复向量化，发现 **Blotto / IPD / Codenames 三个游戏里，顶尖模型回复的余弦相似度 > 0.8**——大家都收敛到一个非常窄的策略空间里。

只有 Secret Mafia 例外，顶尖模型分成两个明显的风格簇。这说明在足够开放的社交场景里，**多种策略可以共存**——也说明前三个游戏可能已经被"刷过头"了。

## MG-Ref：让你也能在家测自己的 Agent

最实用的产出：他们把 2025 cohort 的顶尖 Agent **冻结打包**成 MG-Ref 离线对手池，配套一个确定性的对战调度。任何人写完一个新 Agent，可以在自家机器上跟这个池子打一遍，拿到和官方榜单**同口径**的 TrueSkill、reward 和错误归因数据。

这比"快来交个 submission 看排名"对独立研究者友好得多。

## 局限与我的看法

**论文自己承认的局限：**
- 只跑了一个 cycle，统计意义有限。
- Secret Mafia 的 confound 在数据集层面无法消除，只能事后审计。
- 只 embedding 了「最终回复」，没办法分析内部推理过程。
- 没有人类基线对照——你不知道一个普通人玩 Mafia 是 30% 胜率还是 50%。

**我的几点观察：**

1. **多智能体评测的根本难题**是 Agent 之间会相互影响，单维度的胜率/Elo 永远会失真。这篇论文给出的解法（多指标 + 错误归因 + 行为相似度审计）应该成为后续多智能体 benchmark 的标配。

2. **8B 开源模型 + 系统级 RL** 干过 GPT-5 这件事，应该让做产品的人警惕："直接调闭源大模型 + 提示词" 这条路在 Agent 场景里未必长期有效。

3. **LLM 不肯撒谎**这个 RLHF 副作用，第一次在大规模数据里被量化出来——Mafia 比 Village 胜率高 30+ 个百分点。这对未来要做"会演戏的 Agent"（销售、谈判、剧本游戏）的团队是个明确信号。

4. **代码执行 + 结构化输出验证**几乎成了顶尖 Agent 的标配——如果你的 Agent 还在裸跑 LLM 输出，这是个低垂的果实。

5. 最重要的：**下次你看到"我们的 Agent 在某 benchmark 上 SOTA"的新闻**，请先问：这个 benchmark 的错误率多少？冠军是不是靠对手送的？指标是 TrueSkill 还是 reward？

---

整篇论文最让我喜欢的不是数据，是那种**愿意拆穿自己榜单的诚实**——「我们办了一个比赛，但你不能简单相信榜首」。在 AI 圈子里这种自我审视太罕见了。

> 论文资源：[arxiv 2605.29512](https://arxiv.org/abs/2605.29512) · [29,571 场比赛数据集](https://huggingface.co/datasets/mindgameschallenge/MGC2025) · [MG-Ref 离线评测套件](https://github.com/mind-games-challenge/mindgames-starter-kit)

---

## 相关阅读

- [[data-intelligence-agents-dia]] — 同期：一个 Agent 通吃企业数据集成，另一种评估视角
- [[tencent-agent-interview-knowledge]] — 多 Agent 架构在面试中的考察方式
- [[agent-engineering-pitfalls]] — 多 Agent 系统里更容易踩的那些坑
