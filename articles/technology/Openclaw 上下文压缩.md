---
title: Openclaw 上下文压缩
date: 2026-03-25
tags: [Agent]
---

# Openclaw 上下文压缩

随着 agent 长回话场景中，上下文窗口会增大并溢出，随着对话越来越长，工具调用越来越多，大模型的上下文窗口最终会被填满，因为 OpenClaw 设计了一套来压缩上下文的算法，上下文的管理分为三个阶段。



## 整体架构

![image-20260402104921767](https://wynneyin.oss-cn-hangzhou.aliyuncs.com/image-20260402104921767.png)



### 第一层 预防性裁剪，消息发送给LLM前

1.在消息发送给 LLM 前，尽可能的裁剪到不再需要的冗余内容，工作原理很简单就是直接限制对话的轮数，从消息列表从末尾向前遍历，当计数超过 limit 的时候，丢弃更早的消息。截断是在一个 user 的轮次完整的边界上。

2.渐进式的裁剪旧的 Tool Result ， 这个更像是瘦身，他是一个运行时的扩展，注册在 context 事件上，每次构造 LLM 的请求拦截消息列表。 默认是每五分钟触发一次。

核心逻辑在 pruneContextMessages() 中，使用**字符占比**作为触发条件：

| 阶段       | 触发条件                      | 行为                                                         | 效果                                                         |
| :--------- | :---------------------------- | :----------------------------------------------------------- | :----------------------------------------------------------- |
| Soft Trim  | totalChars / charWindow > 0.3 | 对超过 4000 字符的旧 tool result，保留首 1500 + 尾 1500 字符，中间用 ... 替代 | 保留关键信息（开头通常是命令/文件头，结尾通常是结论/错误），丢弃中间冗余 |
| Hard Clear | totalChars / charWindow > 0.5 | 将整个 tool result 替换为 "[Old tool result content cleared]" | 彻底释放空间，仅保留"这里曾经有一个工具调用"的标记           |

其中 charWindow = contextWindowTokens × 4（使用 1 token ≈ 4 字符的粗略估算）。

Soft Trim 的截断实现很精巧——它不是简单地 slice，而是在文本块（text content block）的层面操作，分别从头部和尾部取字符，保持换行符的完整性。

**裁剪范围与保护规则**

并非所有消息都会被裁剪，有几条硬性保护规则：

1. **第一条 user 消息之前的内容永远不裁剪。**因为会话开头通常包含身份文件的读取（如 SOUL.md、USER.md 等），这些 tool result 是 AI 理解用户身份的基础。
2. **最近 N 条 assistant 消息关联的 tool result 不裁剪**（默认 N=3）。通过 findAssistantCutoffIndex() 从末尾向前数 3 个 assistant 消息，这些消息之后的 tool result 被标记为受保护区域。
3. **含图片的 tool result 不裁剪。**图片无法被部分截断且通常直接与用户需求相关。
4. **可配置的工具白/黑名单。**通过 tools.allow / tools.deny 配置，可以精确控制哪些工具的结果可以被裁剪。



### 2.核心压缩机制，基于上下文的压缩机制，调用另一次LLM来生成对话历史摘要，用摘要替代历史消息

核心：摘要算法

当检测到上下文窗口接近上限时，自动触发压缩时间，溢出后显式的触发。

**分段摘要（summarizeInStages）**

当消息量较大时，不能一次性把所有消息送给 LLM 做摘要（摘要请求自身也有上下文窗口限制）。

结构化输出，OpenClaw 要在摘要文本之后附加了结构化的信息，确保压缩后 AI 知道自己做过什么。

## 3.溢出后恢复

即使有了前面的两层的上下文压缩，但是还是可能导致出现上下文的溢出，检测模块，检测到 LLM 返回的错误为 413，或者 LLM 开始生成但是在报告上下文溢出。

``

```javascript
检测到 context overflow 错误
    │
    ├── 分支 A: 本次 attempt 内 SDK 已自动 compaction？
    │     └── 是 → 增加 overflowCompactionAttempts 计数
    │           └── 直接重试 prompt（不再额外 compact，避免重复压缩）
    │
    ├── 分支 B: 本次 attempt 内无 auto-compact？
    │     └── overflowCompactionAttempts < 3？
    │           ├── 是 → 执行显式 compaction（trigger: "overflow"）
    │           │     ├── 成功 → 重试 prompt
    │           │     └── 失败 → 进入 Fallback
    │           └── 否 → 进入 Fallback
    │
    ├── Fallback: 检测是否有超大 tool result
    │     └── sessionLikelyHasOversizedToolResults()
    │           ├── 有 → truncateOversizedToolResultsInSession()
    │           │     ├── 截断成功 → 重试 prompt
    │           │     └── 截断无效 → 放弃
    │           └── 无 → 放弃
    │
    └── 所有手段用尽 → 返回错误：
          "Context overflow: prompt too large for the model.
           Try /reset (or /new) to start a fresh session,
           or use a larger-context model."
```



上下文管理方案不可避免地会改变发送给 LLM 的消息序列。而主流 LLM Provider（Anthropic、OpenAI、Google 等）都提供了 **Prompt Caching** 机制——如果新请求的 prompt 前缀与前一次请求相同，Provider 可以复用已有的 KV Cache，大幅降低延迟和计费。



OpenClaw 明确知晓并利用了 Provider 的 Prompt Caching 能力：

1. **Cache Retention 配置:** 通过 cacheRetention 参数（"short" = 5 min / "long" = 1 hour）向 Anthropic 声明缓存保留策略。直接 Anthropic 调用默认为 "short"。
2. **System Prompt Cache Control:** 对 OpenRouter 的 Anthropic 模型，通过 createOpenRouterSystemCacheWrapper 主动在 system message 上注入 cache_control: { type: "ephemeral" }，确保系统提示被缓存。
3. **Usage 追踪:** UsageAccumulator 明确追踪 cacheRead cacheWrite lastCacheRead / lastCacheWrite，可以观察每次调用的 cache 命中情况。
4. **Cache TTL 感知的 Pruning:** Context Pruning 扩展通过 isCacheTtlEligibleProvider() 检查 Provider 是否支持 cache TTL，**仅对支持的 Provider（Anthropic、Moonshot、ZAI 等）启用 pruning。**

   9.2 各层操作对 KV Cache 的影响

**1. History Turn Limit — 对 cache 无直接影响**

这个操作只会在**首次构建消息列表时**截断最老的轮次。由于每次 LLM 调用的消息列表都是从 session 文件重建的，截断行为在每次请求间是一致的。**只要 limit 不变，每次调用的 prompt 前缀是稳定的**，不会导致 cache miss。

但如果 limit 触发了截断（消息数超过限制），被截断的那一次请求的 prompt 前缀会与前一次完全不同——这一次一定是 cache miss。不过这通常只发生在长时间运行的会话中。

**2. Context Pruning — 会导致 cache 失效，但有刻意的缓解设计**

这是 **cache 影响最大的操作**。Soft Trim 和 Hard Clear 会修改旧 tool result 的内容，改变 prompt 中间的文本。由于 KV Cache 是严格前缀匹配的，一旦修改了 prompt 中间的某条消息内容，从修改点到末尾的所有 token 都会 cache miss。

**OpenClaw 的缓解设计——Cache TTL 对齐:**

Context Pruning 的 5 分钟 TTL 不是随意选择的。它与 Anthropic 的 "short" cache retention（也是 5 分钟）精确对齐：