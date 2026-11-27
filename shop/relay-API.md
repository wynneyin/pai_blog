# 中转站 API 契约 v1

Java 与 Node 两个后端必须实现完全相同的路径、请求字段、响应字段、状态码和错误文案。
前端会对两种信封做归一化：Node `{success,data,message}`，Java `{code,message,data}`。

## 文件与配置

| 文件 | 说明 | 环境变量 | 默认值 |
| --- | --- | --- | --- |
| `shop/relay.json` | 中转站展示目录（品牌、模型、充值档位、FAQ、代码示例） | `RELAY_CATALOG_PATH` | `<repo>/shop/relay.json` |
| `data/relay-store.json` | 用户、会话、令牌、充值单、用量记录 | `RELAY_DATA_PATH` | `<repo>/data/relay-store.json` |

- 目录每次请求重新读取；订单/账户文件缺失视为空库，结构损坏时返回 500 且绝不覆盖。
- 写文件必须使用「同目录临时文件 + rename」原子替换。
- 上游代理：`RELAY_UPSTREAM_URL`（例如 `https://api.openai.com/v1`，末尾斜杠去掉）、
  `RELAY_UPSTREAM_KEY`。未配置 URL 时代理返回 503。
- 后台令牌沿用 `BLOG_ADMIN_TOKEN` / `X-Admin-Token`；变量为空时不校验（仅限本地开发）。

## 通用错误

业务接口错误尽量使用以下文案：

| 状态 | 文案 |
| --- | --- |
| 400 | 请求参数不合法（JSON 无法解析） |
| 400 | 邮箱格式不正确 |
| 400 | 密码长度需为 8-72 位 |
| 400 | 该邮箱已注册 |
| 401 | 邮箱或密码错误 |
| 401 | 登录已失效，请重新登录 |
| 400 | 备注不合法（令牌名称非字符串） |
| 400 | 令牌数量已达上限 |
| 404 | 令牌不存在 |
| 400 | 充值档位不存在 |
| 404 | 充值订单不存在 |
| 403 | 无权操作该订单 |
| 409 | 订单已取消 |
| 409 | 充值订单状态不可变更 |
| 429 | 请求过于频繁，请稍后再试 |
| 500 | 中转目录加载失败: <原因> |
| 500 | 账户数据读取失败 |
| 500 | 账户数据保存失败 |

## 数据格式

所有时间为 ISO-8601 UTC（毫秒 + `Z`），例如 `2026-11-26T04:30:00.000Z`。

用户（公开字段，绝不返回密码/盐）：
```json
{"id":"u_ab12cd34ef56","email":"a@b.com","balanceCents":5500,"createdAt":"..."}
```

会话令牌：`rs_` + 48 位小写十六进制。数据库只存 `sha256(token)` 的十六进制。
有效期 7 天。请求头 `Authorization: Bearer <token>`。

API Key：`sk-relay-` + 48 位小写十六进制。数据库只存 `sha256(key)` 与
`prefix`（key 的前 16 个字符）。完整 key 只在创建响应里返回一次。

密码：PBKDF2WithHmacSHA256，120000 次，16 字节随机盐，32 字节输出，十六进制存储。
校验使用常量时间比较。

ID：`u_` / `k_` / `g_` 加 12 位小写十六进制。
充值单号：`RT` + 本地时间 `yyyyMMddHHmmss`（14 位）+ 4 位大写字母数字
（字母表 `ABCDEFGHJKLMNPQRSTUVWXYZ23456789`），必须全库唯一。

### relay-store.json

```json
{
  "users": [
    {"id":"u_...","email":"a@b.com","passwordSalt":"hex","passwordHash":"hex",
     "balanceCents":0,"disabled":false,"createdAt":"..."}
  ],
  "sessions": [
    {"tokenHash":"hex","userId":"u_...","createdAt":"...","expiresAt":"..."}
  ],
  "keys": [
    {"id":"k_...","userId":"u_...","name":"默认令牌","prefix":"sk-relay-abcd1234",
     "keyHash":"hex","createdAt":"...","lastUsedAt":null,"revokedAt":null}
  ],
  "topups": [
    {"orderNo":"RT...","userId":"u_...","planId":"p50","planName":"标准充值",
     "amountCents":5000,"bonusCents":500,"creditCents":5500,"status":"pending",
     "method":null,"createdAt":"...","paidAt":null,"cancelledAt":null}
  ],
  "usage": [
    {"id":"g_...","userId":"u_...","keyId":"k_...","model":"gpt-4o-mini",
     "promptTokens":10,"completionTokens":20,"costCents":1,"createdAt":"..."}
  ]
}
```

## 公共接口

### GET /api/relay/catalog
返回 `relay.json` 解析后的对象（原样透传），200。

### POST /api/relay/auth/register
Body `{email, password}`。
- email 先 trim + 转小写；格式 `^[^\s@]+@[^\s@]+\.[^\s@]+$`，长度 ≤ 120。
- password 必须是字符串，长度 8-72（按字符数）。
- 邮箱已存在 → 400 `该邮箱已注册`。
- 成功：200 `{token, user}`，`balanceCents = 0`，同时写入会话。
- 这是新用户唯一的自动登录入口。

### POST /api/relay/auth/login
Body `{email, password}`。邮箱不存在或密码错误统一 401 `邮箱或密码错误`。
成功：200 `{token, user}`。

### POST /api/relay/auth/forgot-password
Body `{email}`。需要邮件服务已配置，否则 503 `邮件服务未配置，请联系管理员`。
- 成功始终返回 200 `{sent:true}`，无论邮箱是否存在、账户是否禁用。
- 未知邮箱不生成令牌也不发信；同一账户 60 秒内只生成一封，每小时最多 5 封。
- 已发出的旧链接在过期前保持有效，不会被新申请顶掉。
- 令牌 `pr_` + 64 位十六进制，库中只存 SHA-256，30 分钟有效。
- 邮件正文里的链接为 `${SITE_BASE_URL}/relay/reset-password#token=<token>`。

### POST /api/relay/auth/reset-password
Body `{token, password}`。
- token 为空、格式不符、已使用或已过期 → 400 `重置链接无效或已过期`。
- password 长度非 8-72 → 400 `密码长度需为 8-72 位`。
- 成功 200 `{reset:true}`；更新盐与哈希，删除该用户全部会话和重置令牌，
  并吊销其全部未吊销 API Key（记录保留，`revokedAt` 有值）。

### POST /api/relay/auth/logout
需要 Bearer。删除当前会话（找不到也返回成功）。200 `{ok:true}`。

### GET /api/relay/me
需要 Bearer。200 `{user}`；无效/过期 401 `登录已失效，请重新登录`。

## 用户接口（均需 Bearer）

### GET /api/relay/keys
200 `{list:[record...]}`，按 `createdAt` 倒序。record：
`{id,prefix,name,createdAt,lastUsedAt,revokedAt}`。包含已吊销记录。

### POST /api/relay/keys
Body `{name?}`。name 缺省/空 → `默认令牌`；非字符串 → 400 `备注不合法`；
trim 后最长 40。活跃令牌（`revokedAt` 为空）达到 10 个 → 400 `令牌数量已达上限`。
200 `{key, record}`，record 同上。写库前生成唯一 id。

### DELETE /api/relay/keys/{id}
只能吊销自己的令牌；不存在或不属于当前用户 → 404 `令牌不存在`。
已吊销时幂等，200 `{ok:true}`。设置 `revokedAt`。

### GET /api/relay/topups
200 `{list:[order...], total}`，当前用户，按 `createdAt` 倒序，最多 100 条。
order 字段与存储一致，另加 `canPay`？不需要，前端按 status 判断。

### POST /api/relay/topups
Body `{planId}`。从目录查找档位；不存在/非字符串 → 400 `充值档位不存在`。
目录档位字段：`id,name,price,credit,hot,perks,bonus`（`price`/`credit` 为元，数字）。
金额换算：`amountCents = round(price*100)`，`creditCents = round(credit*100)`，
`bonusCents = creditCents - amountCents`（不得为负；目录校验时保证 `credit >= price`）。
状态 `pending`。200 返回 order。

### POST /api/relay/topups/{orderNo}/pay
Body `{method}`。大小写不敏感？按 `trim()` 后小写匹配，仅 `alipay`/`wechat` 记录，
其他忽略。仅订单所有者可操作，否则 403 `无权操作该订单`；不存在 404。
- pending → paid：设置 `status/method/paidAt`，同一笔写库中给用户
  `balanceCents += creditCents`。200 返回 order。
- paid → 直接返回 order（幂等，不重复加钱）。
- cancelled → 409 `订单已取消`。
- 生产环境默认关闭用户自助确认：设置 `RELAY_ALLOW_SELF_TOPUP=false` 后，此接口返回
  403 `请支付后联系管理员确认到账`；管理员在 `/relay/admin` 将 pending 订单改为 paid
  后再到账。默认值 `true` 仅用于本地演示。

### GET /api/relay/usage?limit=
200 `{list:[...], total}`，当前用户，按 `createdAt` 倒序。
limit 默认 50，最大 200；非法值用默认。

## 后台接口（X-Admin-Token）

### GET /api/admin/relay/users?limit=
200 `{list:[{id,email,balanceCents,disabled,createdAt,keyCount,paidTopupCents}], total}`。
按 `createdAt` 倒序，limit 默认 100，最大 500。`paidTopupCents` 是该用户所有
`status=paid` 充值单的 `amountCents` 之和（不含赠送）。

### GET /api/admin/relay/topups?status=&limit=
200 `{list:[order + email], total}`，按 `createdAt` 倒序。

### POST /api/admin/relay/topups/{orderNo}/status
Body `{status}`，仅允许 `paid` / `cancelled`。
- 只有 `pending` 可变更；其他状态 → 409 `充值订单状态不可变更`。
- pending → paid：设置 paidAt，并给用户加 `creditCents`。
- pending → cancelled：设置 cancelledAt，不加钱。
- 200 返回 order。

## OpenAI 兼容代理

### GET /v1/models
Bearer 为 Relay API Key。返回：
```json
{"object":"list","data":[{"id":"gpt-4o-mini","object":"model","created":0,"owned_by":"OpenAI"}]}
```
从目录 models 生成，`owned_by` = vendor。无效 key → 401 OpenAI 错误。

### POST /v1/chat/completions
Bearer 为 Relay API Key。请求体原样转发到
`${RELAY_UPSTREAM_URL}/chat/completions`，使用 `RELAY_UPSTREAM_KEY` 作为上游
`Authorization: Bearer`（为空则不带该头）。
- key 无效/吊销：401 `{"error":{"message":"无效的 API Key","type":"invalid_request_error","code":"invalid_api_key"}}`
- 用户 `disabled` 或 `balanceCents <= 0`：402
  `{"error":{"message":"余额不足，请先充值","type":"insufficient_quota","code":"insufficient_quota"}}`
- 未配置上游：503
  `{"error":{"message":"中转上游未配置","type":"server_error","code":"upstream_not_configured"}}`
- 请求体不是合法 JSON：400
  `{"error":{"message":"请求体不是合法 JSON","type":"invalid_request_error","code":"invalid_json"}}`
- `model` 不在目录：400
  `{"error":{"message":"模型不存在","type":"invalid_request_error","code":"model_not_found"}}`
- 上游网络失败：502
  `{"error":{"message":"上游请求失败","type":"server_error","code":"upstream_error"}}`

上游响应处理：
- 透传状态码、Content-Type、响应体。
- 非 2xx：不扣费。
- 2xx 且响应含 `usage.prompt_tokens` / `usage.completion_tokens`：
  计算费用、扣余额（最低 0）、追加用量记录、更新 key 的 `lastUsedAt`。
  非流式响应额外加响应头 `X-Relay-Cost-Cents`。
- 流式（请求 `stream:true`）：边转发边扫描 SSE 的 `data: {...}`，取最后一个非空
  `usage`；流结束后扣费。流式不额外加头。
- 费用公式（整数分）：
  `inputCentsPerMillion = round(model.input * 100)`
  `outputCentsPerMillion = round(model.output * 100)`
  `numerator = promptTokens*inputCentsPerMillion + completionTokens*outputCentsPerMillion`
  `costCents = numerator > 0 ? max(1, ceil(numerator / 1_000_000)) : 0`
- 用量记录 `id = g_ + 12 hex`。

## 目录 relay.json 结构

```json
{
  "brand": {"name":"Wynne Relay","baseUrl":"","consoleUrl":"/relay/console","supportUrl":""},
  "stats": [{"value":"40+","label":"在售模型"}],
  "models": [{"name":"gpt-4o-mini","vendor":"OpenAI","context":"128K",
              "input":1.08,"output":4.32,"tag":"性价比"}],
  "plans": [{"id":"p10","name":"入门充值","price":10,"credit":10,
             "bonus":"到账 10 元额度","hot":false,"perks":["全模型可用"]}],
  "faqs": [{"q":"...","a":"..."}],
  "codeSamples": [{"label":"cURL","language":"bash","code":"..."}],
  "notices": ["..."]
}
```
校验：models/plans 必须是数组；model.name 非空且唯一，input/output 为 ≥0 数字；
plan.id 非空唯一，price/credit 为 ≥0 数字且 credit ≥ price。否则 500 目录错误。

## 安全与并发

- 密码、会话、API Key 都只存哈希；响应与日志不得出现明文。
- 登录/注册按 IP 做简单限流：60 秒 30 次，超出 429。
- 所有余额变更必须在同一把锁内完成「读库 → 修改 → 原子写」。
- 代理扣费失败不能影响已经返回给客户端的响应；扣费时余额最低截到 0。
