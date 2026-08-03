# LLM 推理系统中的 KV Cache 机制深度解析

> 从一次困惑出发，把 Attention、KV Cache、Prefix Caching、FlashAttention、CUDA Graph 串成一条线

---

## 从一个问题开始

大模型在生成文字时，每次只输出一个 token，却需要"记住"之前说过的所有内容。这是怎么做到的？答案是 KV Cache。但 KV Cache 到底存的是什么，为什么能复用，为什么又有上限——这些问题串联起来，恰好构成了理解 LLM 推理系统的一条主线。

---

## 一、Attention 是怎么工作的

以一句话 `"猫 吃 鱼"` 为例，假设模型只有 1 个 attention head，head_dim = 4。

每个 token 先经过 embedding 变成向量，再分别经过三个线性层得到 Q、K、V：

```
token "猫" → embedding → W_K → K[0] = [0.1,  0.5, -0.3,  0.8]
token "吃" → embedding → W_K → K[1] = [0.4, -0.1,  0.7,  0.2]
token "鱼" → embedding → W_K → K[2] = [0.9,  0.3,  0.1, -0.5]

token "猫" → embedding → W_V → V[0] = [1.2,  0.0, -0.4,  0.6]
token "吃" → embedding → W_V → V[1] = [0.3,  0.8,  0.2, -0.1]
token "鱼" → embedding → W_V → V[2] = [0.5, -0.3,  0.9,  0.4]
```

**KV Cache 存的就是这些向量**，不是原始 token id，也不是 embedding，而是过了线性层之后的中间表示。

Attention 的计算过程是：用当前 token 的 Q 和所有历史 token 的 K 做点积，得到相关度分数，softmax 归一化之后对 V 加权求和：

```
score[i] = Q_current · K[i]
output   = Σ softmax(score[i]) × V[i]
```

这个过程让当前 token 能"看到"所有历史 token 的语义，这就是大模型理解上下文的核心机制。

---

## 二、KV 矩阵的行是独立的吗

每一行的**计算过程**是独立的，但行的**内容**会随着层数加深而融合上下文信息。

Decoder-only 模型有 causal mask，token `i` 只能 attend 到 `0..i`，不能看未来。所以在第 0 层（输入是纯 embedding）：

```
K_layer0[0] = W_K × emb("猫")   ← 只有自己
K_layer0[1] = W_K × emb("吃")   ← 只有自己
K_layer0[2] = W_K × emb("鱼")   ← 只有自己
```

经过第 0 层 attention 之后，每个 token 的 hidden state 已经融合了它能看到的上下文：

```
hidden("猫")[1] = attention(Q_猫, K[0:1], V[0:1])   ← 只有自己，没变化
hidden("吃")[1] = attention(Q_吃, K[0:2], V[0:2])   ← 混入了"猫"的信息
hidden("鱼")[1] = attention(Q_鱼, K[0:3], V[0:3])   ← 混入了"猫"和"吃"
```

第 1 层的 K/V 就是用这个融合后的 hidden state 算出来的：

```
K_layer1[2] = W_K × hidden("鱼")[1]   ← 里面已经浓缩了整句话的信息
```

越靠后的 token，KV 里融合的上下文越丰富。这也是 causal mask 带来的天然特性：每个 token 只能看到自己之前的内容，所以 **新增 token 不会改变已有 token 的 KV**，历史 KV 永远不需要重算，只需要追加新行。如果是双向 attention（如 BERT），这个性质就不成立，KV Cache 也就没法用了。

---

## 三、Prefill 和 Decode 的区别

一次完整的推理分两个阶段：

**Prefill**：把整个 prompt 一次性送进去，并行计算所有 token 的 KV，存进 KV Cache。这是一次大矩阵运算，计算密集。

**Decode**：每次只生成 1 个新 token。新 token 的 Q 与 KV Cache 里所有历史 K 做 attention，得到输出，然后采样出下一个 token，再把这个 token 的 KV 追加进 Cache，如此循环直到 `<eos>`。

Decode 阶段的 attention 形状：

```
Prefill: QK^T → [seq_len, seq_len]   ← GEMM，矩阵×矩阵，算力密集
Decode:  QK^T → [1, seq_len]         ← GEMV，向量×矩阵，带宽密集
```

Decode 的瓶颈不是算力，而是**显存带宽**——每步都要把整个 KV Cache 从 HBM 搬一遍，计算单元大量空闲。这也是为什么 batching 对 decode 吞吐那么重要：多条序列共用一次权重读取，同样的带宽消耗换来更多输出 token。

---

## 四、KV Cache 的显存压力

KV Cache 本身是逃不掉的显存开销：

```
每层每个 token 的 KV 大小 = 2 × num_kv_heads × head_dim × dtype_bytes
```

以一个 32 层、32 KV heads、head_dim=128 的模型为例，context 128k tokens，bf16：

```
128k × 32层 × 2 × 32 × 128 × 2 bytes ≈ 67 GB
```

这就是大模型 context window 不能无限大的根本原因——不是算力不够，是显存装不下。

---

## 五、FlashAttention 解决了什么

Prefill 阶段有一个隐藏的显存炸弹：

```
QK^T → [seq_len, seq_len]
```

seq_len=8k，32 heads，fp16 的情况下这个矩阵就需要几十 GB 显存。FlashAttention 的解法是**不把这个矩阵显式写出来**：

把 Q 和 K/V 都切成小块（tile），每次只算一小块的 attention score，用 online softmax 累积结果：

```
for q_tile in Q.split(block_size):
    for kv_tile in K.split(block_size):
        局部 score = q_tile @ kv_tile^T    ← 只有 [block, block] 大小
        online softmax 更新累积结果
```

显存从 O(n²) 降到 O(n)，计算量不变，只是把随机访问变成了对 SRAM 友好的顺序访问，实际上还更快了。

---

## 六、Prefix Caching：跨请求的 KV 复用

KV Cache 解决了单次请求内部的效率问题，而 Prefix Caching 解决的是**不同请求之间的重复计算**。

最典型的场景是 system prompt：

```
请求1: [system prompt 512 tokens] + [你好]
请求2: [system prompt 512 tokens] + [你能做什么]
```

两个请求的 system prompt 完全相同，位置相同，在 causal mask 下它们每一层的 KV 计算结果也完全相同。Prefix Caching 就是把第一次算出来的 system prompt KV 存起来，第二次直接跳过，只 prefill 新增的几个 token。

一个常见的误解是：复用的是"融合了后面用户输入的 KV"。其实不是，只有**前缀部分**的 KV 能复用，因为 causal mask 保证了前缀 token 的 KV 不受后续 token 影响。用户问题 B 的 token 在做 prefill 时，Q 仍然会 attend 到所有系统提示词的 K/V，语义完全连续，位置编码也是从 `len(system_prompt)` 开始续的——只是省掉了重新计算系统提示词 KV 的开销。

---

## 七、多轮对话的本质

大模型本身是无状态的，没有"记忆"上一轮对话。多轮对话的实现方式是：**每次都把完整历史拼进 prompt 重新发**：

```
第一轮输入: [系统提示词][你好]
第一轮输出: [我很好]

第二轮输入: [系统提示词][你好][我很好][你能做什么]  ← 全部重新发
```

第二轮就是一次更长的 prefill，"第一轮的结果"是以 token 的形式拼进来的，不是以 KV 的形式传递的。结合 Prefix Caching，`[系统提示词][你好][我很好]` 这段在第二轮可以直接复用 KV，只需要 prefill 新增的几个 token，大幅节省计算。

---

## 八、Paged KV Cache 和页分配策略

KV Cache 的物理管理借鉴了操作系统的分页机制。每个请求的 KV 不连续存储，而是分散在固定大小的"页"里，通过 page table 映射，避免显存碎片。

页的分配时机是按需的，但有 page size 粒度：

- **Prefill 阶段**：一次性分配 `prompt_len` 个页
- **Decode 阶段**：每步分配 1 个新页（page_size=1 时），或每 `page_size` 个 token 分配一次

page_size 越大，显存碎片越少，但每个请求最多浪费 `page_size - 1` 个 token 的空间。这是 paged KV cache 经典的 tradeoff。

---

## 九、CUDA Graph：消掉 Decode 的 CPU 开销

Decode 阶段 GPU kernel 本身跑得很快（GEMV 只需几微秒），但 CPU 每步都要：

1. 执行 Python 代码
2. 调用 CUDA API 下发几十个 kernel（`cudaLaunchKernel`）
3. 等 GPU 确认

在 kernel 执行时间极短时，这些 launch overhead 占比非常显著。CUDA Graph 的做法是在初始化阶段把一次完整 decode forward 的所有 GPU kernel 调用**录成一张剧本**，之后每步只发一条 `cuGraphLaunch` 指令：

```
不用 CUDA Graph：
  CPU: launch k1 → launch k2 → ... → launch kN   （N 次往返）

用 CUDA Graph：
  CPU: graph.replay()                              （1 次往返）
```

因为 decode 每步的 batch size 可能变化，所以按 batch size 分桶预录多张 graph（比如 bs=1,2,4,8,16,...），运行时找最近的桶对应的 graph replay。

Prefill 不能用 CUDA Graph，因为每次输入长度不同，kernel shape 变了，固定形状录制的 graph 无法复用。

---

## 十、一张图串起来

```
用户发送请求
    ↓
Prefill：一次性计算所有 prompt token 的 KV，存入 KV Cache
    ↓
Decode 循环：
    每步取出最新 token 的 Q
    与 KV Cache 里所有历史 K/V 做 attention（FlashAttention 保证不 OOM）
    采样出下一个 token → 追加 KV → 循环
    （CUDA Graph 消掉每步的 CPU launch overhead）
    ↓
生成 <eos>，释放 KV Cache 页

下一个请求：
    若前缀与历史请求相同 → Prefix Caching 直接复用 KV，跳过重复 prefill
```

每一个优化点都在针对这条链路上的某一个瓶颈：FlashAttention 解决 prefill 的显存问题，KV Cache 避免 decode 重算，Paged KV Cache 解决显存碎片，Prefix Caching 复用跨请求的重复前缀，CUDA Graph 消掉 decode 的 CPU overhead。理解了这条链路，LLM 推理系统的设计就基本清晰了。
