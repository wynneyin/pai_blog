# 商城与中转站后端说明

商城页面（`/shop`）的商品与订单都由后端提供。Java 后端和 Node 后端实现同一套接口，
生产环境用哪套就生效哪套。

## 数据文件

| 文件 | 作用 | 环境变量 | 默认路径 |
| --- | --- | --- | --- |
| `shop/catalog.json` | 商品分类、商品、公告 | `SHOP_CATALOG_PATH` | Node：仓库根目录下 `shop/catalog.json`；Java：启动目录下 `shop/catalog.json` |
| `data/shop-orders.json` | 订单持久化（自动创建） | `SHOP_DATA_PATH` | 同上，`data/shop-orders.json` |

- 商品目录每次请求都会重新读取，改完 JSON 不需要重启服务。
- Node 后端的默认路径按 `src/` 相对仓库根目录解析，PM2 从其他目录启动也不会读错文件。
- 订单文件必须放在持久化目录。Docker 部署已挂载 `./data:/app/data`；
  如果服务器上的 `docker-compose.yml` 是本地修改过的旧版本，请手动补上这个挂载，
  否则容器重建后订单会丢失。
- `data/` 已加入 `.gitignore`，不要把订单提交进仓库。

## 接口

所有接口都在 `/api` 下，响应沿用各后端现有的信封格式
（Node: `{success, data, message}`；Java: `{code, message, data}`）。

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/api/shop/catalog` | 商品目录 |
| POST | `/api/shop/orders` | 下单，body `{productId, quantity, contact, note}`；响应含一次性 `lookupToken` |
| GET | `/api/shop/orders/{orderNo}` | 查询订单，需要查询凭证或绑定账户的 Bearer |
| POST | `/api/shop/orders/{orderNo}/pay` | 确认支付（幂等），body `{method}`，鉴权同上 |
| GET | `/api/admin/shop/orders?status=&limit=` | 后台订单列表，需要 `X-Admin-Token` |
| POST | `/api/admin/shop/orders/{orderNo}/status` | 后台改状态 `{status}` |

- 下单成功会在响应里返回一次性查询凭证 `lookupToken`（`so_` 加 64 位十六进制）。
  服务端只保存它的 SHA-256 哈希（`lookupTokenHash`），之后任何接口、后台列表都不会
  再返回明文；前端只把凭证保存在买家本机。凭证丢失时，可登录下单邮箱对应的中转站账号查询。
- 查询与支付订单必须二选一鉴权：
  - 请求头 `X-Order-Token: <lookupToken>`；或
  - 请求头 `Authorization: Bearer <中转站会话令牌>`，且会话账号就是下单时绑定的
    中转站账户（订单里的 `relayUserId`）。
  未携带凭证、凭证错误、订单不存在、订单不属于该账户，统一返回 404「订单不存在」，
  不区分具体原因，避免被用来枚举订单号。非中转商品的订单没有绑定账户，只能凭查询凭证访问。
  后台订单列表与状态变更响应同样会去掉 `lookupTokenHash`，凭证哈希永远不会离开后端。
- 限流按客户端 IP 计数，60 秒固定窗口：下单 10 次、查询 30 次、支付 10 次、
  商品目录 120 次、后台接口 60 次；超出返回 429「请求过于频繁，请稍后再试」并带
  `Retry-After`。只有当直连对端是回环、站点内网或链路本地地址时才采信
  `X-Forwarded-For`，并且从右往左跳过可信代理取第一个非代理地址——nginx 会把
  真实客户端追加在右侧，公网客户端伪造左侧条目不会改变限流桶。HEAD 与 GET
  共用额度，OPTIONS 预检不计入；路径匹配会忽略 Servlet 矩阵参数（`;`）。
- 限流窗口表有容量上限（Java 20,000 个键的 LRU；Node 20,000 个键后淘汰最旧），
  避免轮换地址把内存撑爆。
- 商城请求体与中转站接口共用 100 KiB 上限，超出返回 413，不会先读入内存再拒绝。

- 价格、金额、订单号全部由服务端计算与生成，前端传的价格不生效。
- 目录响应里每个商品带 `availableStock`：`stock` 减去未取消订单
  （待支付、已支付、已发货）占用的数量，最小为 0。
- 目录响应还带 `allowSelfPay`：生产环境为 `false`，前端据此隐藏自助确认按钮，
  改为提示买家联系管理员确认。
- 订单状态：`pending`（待支付）、`paid`（已支付）、`delivered`（已完成）、
  `cancelled`（已取消）。除 `cancelled` 外都占用库存；取消订单会释放库存。
  已取消订单不能支付（返回 409「订单已取消」），后台可改回 `paid` 重新占用库存。
- 商品带 `relayPlanId` 时，`contact` 必须是已注册的中转站邮箱；下单会校验账户
  是否存在并预先算出 `relayCreditCents`，不会给未注册邮箱创建订单。
- 后台把这类订单改为 `paid` 或 `delivered` 时，会在同一次请求内给该邮箱的中转站
  账户充值，并写入一条 `SHOP-<商城订单号>` 的充值记录。充值记录号是确定的，
  重复确认、进程重启或重试都不会重复加钱；已到账订单不能取消（返回 409），
  需要管理员人工退款。
- 后台令牌就是现有的 `BLOG_ADMIN_TOKEN`。Compose 部署必须设置该变量，未设置会拒绝启动；
  `scripts/deploy-lite.sh` 会自动生成随机令牌写入 `.env`。直接运行源码时变量为空则不校验令牌，
  只适合本地开发，线上务必配置。

## 管理入口

浏览器打开 `/shop/admin`，输入管理员令牌即可查看订单、标记已支付/已发货。

## 支付说明

`POST /orders/{orderNo}/pay` 默认是「用户点了我已支付」的模拟确认。
接入真实支付时，应改为支付平台回调验签后再把订单置为 `paid`，
并把前端按钮换成跳转支付链接或展示动态收款码。

生产 Compose 已设置 `SHOP_ALLOW_SELF_PAY=false`，该接口直接返回 403
「请支付后联系管理员确认到账」，不会写任何数据；买家转账后联系管理员，
由管理员在 `/shop/admin` 点「确认收款并到账」，额度才会进入中转站账户。
本地演示可以设成 `true` 或删除该变量，恢复浏览器自助确认。

| 环境变量 | 说明 | 默认 |
| --- | --- | --- |
| `SHOP_CATALOG_PATH` | 商品目录路径 | `shop/catalog.json` |
| `SHOP_DATA_PATH` | 订单数据路径 | `data/shop-orders.json` |
| `SHOP_ALLOW_SELF_PAY` | 是否允许浏览器自助确认支付；生产必须为 `false` | `true` |

## 本地联调

```bash
# Node 后端
cd blog-backend-node
ARTICLES_PATH=../articles \
SHOP_CATALOG_PATH=../shop/catalog.json \
SHOP_DATA_PATH=../data/shop-orders.json \
BLOG_ADMIN_TOKEN="$BLOG_ADMIN_TOKEN" \
node src/index.js

# Java 后端
cd blog-backend
SHOP_CATALOG_PATH=$PWD/../shop/catalog.json \
SHOP_DATA_PATH=$PWD/../data/shop-orders.json \
BLOG_ADMIN_TOKEN="$BLOG_ADMIN_TOKEN" \
mvn spring-boot:run
```

前端 `npm run dev` 会把 `/api` 代理到 `http://localhost:8080`。

## 中转站

中转站页面（`/relay`）与 OpenAI 兼容代理（`/v1`）由同一套后端提供。Java 与 Node
实现完全相同的路径、字段、状态码与错误文案，详细契约见 [`relay-API.md`](relay-API.md)。

### 数据文件与环境变量

| 文件 | 作用 | 环境变量 | 默认路径 |
| --- | --- | --- | --- |
| `shop/relay.json` | 中转站展示目录（品牌、模型价目、充值档位、FAQ、代码示例） | `RELAY_CATALOG_PATH` | Node：仓库根目录下 `shop/relay.json`；Java：启动目录下 `shop/relay.json` |
| `data/relay-store.json` | 用户、会话、API Key、充值单、用量记录（自动创建） | `RELAY_DATA_PATH` | 同上，`data/relay-store.json` |

| 环境变量 | 说明 | 默认 |
| --- | --- | --- |
| `RELAY_UPSTREAM_URL` | OpenAI 兼容上游地址，例如 `https://api.openai.com/v1`，末尾斜杠会自动去掉 | 空，`/v1` 代理返回 503 |
| `RELAY_UPSTREAM_KEY` | 转发到上游时使用的 `Authorization: Bearer` 密钥 | 空，不带该请求头 |
| `BLOG_ADMIN_TOKEN` | 后台接口令牌，与商城共用；Compose 部署必填（未设置会拒绝启动），`deploy-lite.sh` 会自动生成随机值；直接运行源码且为空时不校验 | Compose 无默认，必须配置 |

- 目录文件每次请求重新读取，改完直接生效，不需要重启；结构或价格不合法时返回
  500「中转目录加载失败: <原因>」，不会带着坏数据继续服务。
- 账户文件缺失视为空库自动创建；结构损坏返回 500「账户数据读取失败」，且绝不覆盖原文件。
- 写库统一使用「同目录临时文件 + rename」原子替换；余额变更在锁内完成读、改、写。
- `data/` 已加入 `.gitignore`，不要把 `relay-store.json` 提交进仓库。
- Docker 部署沿用 `./shop` 与 `./data` 挂载；容器重建前请确认挂载存在，否则账户与订单会丢失。
- 密码、会话令牌、API Key 都只存哈希；完整 API Key 只在创建响应里返回一次。

### 公共接口

除特别说明外，响应沿用各后端现有的信封格式
（Node: `{success, data, message}`；Java: `{code, message, data}`）。

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/api/relay/catalog` | 返回 `relay.json` 解析后的内容（原样透传） |
| POST | `/api/relay/auth/register` | 注册 `{email, password}`，成功返回 `{token, user}` 并自动登录 |
| POST | `/api/relay/auth/login` | 登录 `{email, password}`，成功返回 `{token, user}` |
| POST | `/api/relay/auth/logout` | 退出当前会话，需要 Bearer |
| GET | `/api/relay/me` | 当前用户信息，需要 Bearer |

账户行为：

- 邮箱先 trim 再转小写，格式需合法且长度 ≤ 120；密码长度 8-72 个字符。
- 密码使用随机盐哈希存储，校验采用常量时间比较，任何响应都不会返回密码或盐。
- 会话令牌以 `rs_` 开头，有效期 7 天；请求头 `Authorization: Bearer <token>`。
  令牌无效或过期返回 401「登录已失效，请重新登录」。
- 注册是新用户唯一的自动登录入口；邮箱重复返回 400「该邮箱已注册」；
  登录时邮箱不存在与密码错误统一返回 401「邮箱或密码错误」。
- 登录/注册按 IP 限流：60 秒 30 次，超出返回 429「请求过于频繁，请稍后再试」。
- 用户响应只含 `{id, email, balanceCents, createdAt}`，余额单位为分。

### 令牌（API Key）

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/api/relay/keys` | 当前用户的令牌列表，按创建时间倒序，含已吊销记录 |
| POST | `/api/relay/keys` | 创建令牌，body `{name?}`，返回 `{key, record}` |
| DELETE | `/api/relay/keys/{id}` | 吊销令牌（幂等） |

- Key 格式为 `sk-relay-` 加 48 位小写十六进制；库里只存哈希与前 16 位 `prefix`，
  完整 Key 只在创建响应里出现一次，请提示用户立即保存。
- `name` 缺省或为空时使用「默认令牌」，trim 后最长 40 字符；非字符串返回 400「备注不合法」。
- 未吊销令牌最多 10 个，超出返回 400「令牌数量已达上限」。
- 吊销只设置 `revokedAt`，记录保留；不存在或不属于当前用户返回 404「令牌不存在」。
- 列表与创建响应中的记录字段为 `{id, prefix, name, createdAt, lastUsedAt, revokedAt}`。

### 充值

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/api/relay/topups` | 当前用户的充值单，按创建时间倒序，最多 100 条 |
| POST | `/api/relay/topups` | 创建充值单，body `{planId}` |
| POST | `/api/relay/topups/{orderNo}/pay` | 确认支付，body `{method}` |

- 档位来自 `relay.json` 的 `plans`；不存在的 `planId` 返回 400「充值档位不存在」。
- 金额全部由服务端按目录换算：`amountCents`、`creditCents`、`bonusCents`；
  前端传的价格不生效。
- 订单状态：`pending`（待支付）、`paid`（已支付）、`cancelled`（已取消）。
  充值单号格式为 `RT` 加 14 位本地时间再加 4 位大写字母数字，全库唯一。
- 支付接口目前是「用户点了我已支付」的模拟确认：`method` 只记录 `alipay` / `wechat`，
  其他值忽略。`pending` → `paid` 时在同一笔写库中给用户 `balanceCents += creditCents`，
  重复支付幂等、不会重复加钱；已取消订单返回 409「订单已取消」；
  操作他人订单返回 403「无权操作该订单」。
- 接入真实支付时，应改为支付平台回调验签后再把订单置为 `paid`。
- 余额单位是分，调用 `/v1` 时按实际用量扣减。

### 用量

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/api/relay/usage?limit=` | 当前用户的用量记录，按创建时间倒序 |

`limit` 默认 50，最大 200，非法值按默认处理。每条记录包含模型、输入/输出 token
数与本次费用（分）。

### 后台接口

后台接口使用与商城相同的 `X-Admin-Token` 请求头（值为 `BLOG_ADMIN_TOKEN`）。

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/api/admin/relay/users?limit=` | 用户列表，含 `keyCount`、`paidTopupCents`；默认 100，最大 500 |
| GET | `/api/admin/relay/topups?status=&limit=` | 充值单列表（附带用户邮箱），可按状态筛选 |
| POST | `/api/admin/relay/topups/{orderNo}/status` | 修改充值单状态，body `{status}` |

- `paidTopupCents` 是该用户所有 `paid` 充值单的 `amountCents` 之和，不含赠送金额。
- 后台只允许把 `pending` 改为 `paid` 或 `cancelled`；其他状态返回 409
  「充值订单状态不可变更」。改为 `paid` 同样给用户加 `creditCents`，改为
  `cancelled` 只记录 `cancelledAt`，不加钱。

### OpenAI 兼容代理（`/v1`）

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/v1/models` | 模型列表，由 `relay.json` 的 `models` 生成，`owned_by` 为厂商 |
| POST | `/v1/chat/completions` | 原样转发到 `${RELAY_UPSTREAM_URL}/chat/completions` |

- 鉴权使用 Relay API Key（`Authorization: Bearer sk-relay-...`），不是登录会话令牌；
  无效或已吊销返回 401 OpenAI 错误结构。
- 用户被禁用或余额 `<= 0` 返回 402「余额不足，请先充值」；未配置
  `RELAY_UPSTREAM_URL` 返回 503「中转上游未配置」；请求体不是合法 JSON 返回 400；
  `model` 不在目录返回 400「模型不存在」；上游网络失败返回 502「上游请求失败」。
- 上游请求使用 `RELAY_UPSTREAM_KEY` 作为 Bearer；为空则不带 `Authorization` 头。
- 上游非 2xx 原样透传且不扣费。2xx 且响应带 `usage` 时按目录中的输入/输出单价
  （元/百万 token）折算成分并扣余额；有 token 消耗时最低按 1 分计，余额最低截到 0。
  同时追加用量记录并更新 Key 的 `lastUsedAt`；非流式响应额外返回
  `X-Relay-Cost-Cents` 响应头。
- 流式请求（`stream: true`）边转发边扫描 SSE 的 `data:` 行，取最后一个非空
  `usage`，流结束后扣费；流式不加费用响应头。
- 扣费失败不影响已经返回给客户端的响应。

### 上游配置

`RELAY_UPSTREAM_URL` 填上游的 OpenAI 兼容根地址（不要带 `/chat/completions`），
例如 `https://api.openai.com/v1` 或自建网关的 `/v1` 地址；`RELAY_UPSTREAM_KEY`
填该上游签发的密钥。两者都可以为空：不配 URL 时 `/v1` 返回 503，不配 Key 时
请求不带 `Authorization` 头，适合无鉴权的内网网关。修改后重启后端生效。

### nginx 必须代理 `/v1`

仓库内的 `blog-frontend/nginx.conf` 和 `scripts/deploy-lite.sh` 生成的站点配置都已包含
`/api/` 与 `/v1/` 两段反向代理；`/v1/` 段关闭了 `proxy_buffering` 并延长了读超时，
流式响应可以正常转发。使用仓库自带的 Nginx 配置即可，不需要再手动添加。

如果服务器上用的是本地修改过的旧版配置，请确认存在下面这段，否则 `/v1/models`、
`/v1/chat/completions` 会被 SPA 的 `try_files` 回退成 `index.html`，
OpenAI 客户端收到的是 HTML 而不是 JSON：

```nginx
location /v1/ {
    proxy_pass http://backend:8080/v1/;   # PM2 部署改为 http://127.0.0.1:8080/v1/
    proxy_http_version 1.1;
    proxy_set_header Host $host;
    proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
    proxy_set_header X-Forwarded-Proto $scheme;
    proxy_buffering off;          # 流式响应必须关闭缓冲
    proxy_read_timeout 300s;
}
```

本机开发时 Vite 已同时代理 `/api` 与 `/v1` 到 `http://localhost:8080`，
可以直接访问 `http://localhost:5173/v1/models` 验证代理链路。

### 中转站本地联调

```bash
# Node 后端
cd blog-backend-node
ARTICLES_PATH=../articles \
SHOP_CATALOG_PATH=../shop/catalog.json \
SHOP_DATA_PATH=../data/shop-orders.json \
RELAY_CATALOG_PATH=../shop/relay.json \
RELAY_DATA_PATH=../data/relay-store.json \
RELAY_UPSTREAM_URL=https://api.openai.com/v1 \
RELAY_UPSTREAM_KEY=$UPSTREAM_KEY \
BLOG_ADMIN_TOKEN=$BLOG_ADMIN_TOKEN \
node src/index.js

# Java 后端
cd blog-backend
SHOP_CATALOG_PATH=$PWD/../shop/catalog.json \
SHOP_DATA_PATH=$PWD/../data/shop-orders.json \
RELAY_CATALOG_PATH=$PWD/../shop/relay.json \
RELAY_DATA_PATH=$PWD/../data/relay-store.json \
RELAY_UPSTREAM_URL=https://api.openai.com/v1 \
RELAY_UPSTREAM_KEY=$UPSTREAM_KEY \
BLOG_ADMIN_TOKEN=$BLOG_ADMIN_TOKEN \
mvn spring-boot:run
```

不传上游变量也能启动，`/api/relay/*` 与页面正常使用，只有 `/v1` 代理会返回 503。
