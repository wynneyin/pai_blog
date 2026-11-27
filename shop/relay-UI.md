# 中转站前端重做（对齐 micu / New API）

目标：把 `/relay` 从「商城绿色风格的单页」改成 micuapi.ai 那种 New API 风格的浅色蓝白站点，
并补齐模型广场、文档、登录注册、侧边栏控制台。后端接口不变，严格复用 `blog-frontend/src/api/relay.js`
与 `shop/relay-API.md`。

## 视觉参考（实现前必须看图）

| 页面 | 参考图 |
| --- | --- |
| 首页 | `/tmp/micu-home-clean.png` |
| 注册页 | `/tmp/micu-signup.png` |
| 控制台数据看板 | `/tmp/newapi-dashboard.zh-CN.jpg` |
| 模型广场 | `/tmp/newapi-models.zh-CN.jpg` |
| 使用日志 | `/tmp/newapi-usage-logs.zh-CN.jpg` |

整体印象：白底 + 极浅蓝紫渐变光晕；顶部胶囊导航；大号黑字标题（第二行蓝紫渐变）；
蓝色主按钮（`#1677ff` 附近）；白色圆角卡片 + 极细边框；大量留白；Inter / 系统中文字体。

## 设计令牌

新文件 `src/assets/relay.css`，全部规则限定在 `.relay-shell` 下，不要把商城绿色主题带进来。

```css
.relay-shell {
  --r-bg: #ffffff;
  --r-soft: #f7f9fc;
  --r-text: #0b1220;
  --r-muted: #5b6b84;
  --r-line: #e6ecf5;
  --r-primary: #1677ff;
  --r-primary-dark: #0958d9;
  --r-purple: #7c5cff;
  --r-success: #12b76a;
  --r-danger: #f04438;
  --r-radius: 14px;
  --r-shadow: 0 12px 40px rgba(23, 58, 110, 0.08);
}
```

- 字体：`"Inter", "DM Sans", "Noto Sans SC", system-ui, sans-serif`；标题 700-800，字距 -0.03em。
- 页宽：公共页面 `min(1200px, 92vw)`；控制台 `min(1400px, 100%)`。
- 按钮：主按钮蓝底白字圆角 10px，次按钮白底细边框；不要用商城的绿色。
- 卡片：白底、1px `--r-line`、14-18px 圆角、hover 轻微上浮。
- 页面背景：`linear-gradient(180deg,#eef6ff 0%,#ffffff 42%)` 的柔和光晕，可用伪元素径向渐变。

## 路由与文件

| 路由 | 组件 | 说明 |
| --- | --- | --- |
| `/relay` | `views/RelayView.vue` | 新首页 |
| `/relay/models` | `views/RelayModelsView.vue` | 模型广场 |
| `/relay/docs` | `views/RelayDocsView.vue` | 接入文档 |
| `/relay/sign-in` | `views/RelayAuthView.vue` | 登录 |
| `/relay/sign-up` | `views/RelayAuthView.vue` | 注册（按 route.name 区分） |
| `/relay/console` | `views/relay/RelayConsoleLayout.vue` | 控制台外壳，含侧边栏 |
| `/relay/console` index | `views/relay/RelayDashboardView.vue` | 概览 |
| `/relay/console/keys` | `views/relay/RelayKeysView.vue` | API 密钥 |
| `/relay/console/topup` | `views/relay/RelayTopupView.vue` | 钱包充值 |
| `/relay/console/logs` | `views/relay/RelayLogsView.vue` | 使用日志 |
| `/relay/admin` | `views/RelayAdminView.vue` | 后台（浅色重做） |

公共组件：`components/relay/RelayHeader.vue`、`components/relay/RelayFooter.vue`、`components/relay/RelayCodeCard.vue`（可选）。
鉴权 session key 保持 `pai_relay_session`，跳转登录时带 `?redirect=<fullPath>`，登录成功回跳。

## 首页 `/relay`

1. **导航**（全局 RelayHeader）：左侧渐变 logo + `Wynne Relay`；中间：主页、控制台、模型广场、文档、商务合作
   （商务合作指到 GitHub）；右侧：未登录显示蓝色「登录」胶囊，已登录显示「控制台」。
   移动端 820px 以下收起为汉堡菜单或横向滚动，禁止页面横向溢出。
2. **Hero**：左侧 badge「人工智能应用基座」；H1 两行「统一 API 网关，服务于 / 海量 AI 模型」，第二行蓝紫渐变；
   副文案复用 micu 语义；按钮「开始使用」→ `/relay/sign-up`，「查看定价」→ `#pricing`，「文档」→ `/relay/docs`。
   下方「常用应用支持」：Cherry Studio、CC Switch、ChatBox、Dify、更多（纯文字/圆点即可，不要用受版权保护的 logo 图片）。
3. **右侧代码卡**：白卡，顶部标签 cURL / Python / Node.js（来自 catalog.codeSamples），中间显示
   `POST /v1/chat/completions` 与请求/响应代码（用 catalog 示例替换 baseUrl），底部 `168 MS · 31 TOKENS · COST ¥0.0009 · STREAM · SSE`。
4. **统计条**：4 个大数字（来自 `catalog.stats`），上下细边框。
5. **核心功能**：标题「为开发者打造，为规模而设计」；bento 网格 8 项（极速/安全可靠/全球覆盖/开发者友好/
   高性能/透明计费/团队协作/开源项目），每项编号、图标（内联 SVG 或简单几何）、标题、一句描述。文案见附录。
6. **三步上手**：配置 / 连接 / 监控，三列，圆形编号图标。
7. **充值档位** `#pricing`：4 张 plan 卡（来自 catalog.plans），价格、到账额度、perks、hot 高亮；按钮「立即充值」
   → 未登录 `/relay/sign-in?redirect=/relay/console/topup?plan=<id>`，已登录直接去该地址。
8. **FAQ**：catalog.faqs 折叠列表。
9. **底部 CTA**：渐变浅蓝卡片，「准备好简化你的 AI 集成了吗？」+ 开始使用/查看定价。
10. **Footer**：`© 2026 Wynne Relay` + 主页/模型广场/文档/控制台；不要出现 micuapi、hongyuhub、New API 字样。

营销文案可以硬编码在组件里；模型、价格、档位、FAQ、代码示例必须来自 catalog API。加载中显示骨架/提示，失败显示重试。

## 模型广场 `/relay/models`

- 顶部大标题「模型广场」，副标题「本站当前已启用 N 个模型」，说明一句。
- 搜索框（名称/厂商/标签）；左侧筛选：所有厂商、所有标签（来自 models 去重）；移动端筛选折叠为横向 chips。
- 卡片网格 3 列（960px 以下 2 列，640px 以下 1 列）：厂商首字母圆形图标、模型名、厂商名、上下文、
  `输入 ¥x / 1M`、`输出 ¥y / 1M`、标签、按钮「创建令牌」→ console keys（未登录先登录）。
- 空状态：「没有找到匹配的模型」。

## 文档 `/relay/docs`

- 标题「接入文档」，三步说明：注册充值 → 创建令牌 → 改 base_url。
- Base URL 卡片：`brand.baseUrl` 为空时用 `location.origin + '/v1'`，一键复制。
- 代码示例 tabs（catalog.codeSamples），替换 `{baseUrl}`。
- 常见问题（catalog.faqs）。
- 右侧/底部 CTA 去控制台创建令牌。

## 登录 / 注册

- 极简页：左上角 Relay logo（可点击回 `/relay`），中间 420px 表单，白底。
- 登录：标题「登录」，副标题「没有账号？注册」；邮箱、密码；蓝色整宽按钮「登录」。
- 注册：标题「创建一个账户」，副标题「已有账户？登录」；邮箱、密码、确认密码；前端校验两次密码一致、
  密码 8-72 位；蓝色按钮「创建账户」。
- 不展示 GitHub/LinuxDO 等未接入的第三方登录，不展示虚假的邮箱验证码。
- 错误信息显示在表单上方；提交中禁用按钮；成功后写入 session 并回跳 redirect 或 `/relay/console`。

## 控制台

外壳：顶部仍用 RelayHeader；下方左侧 220px 白色 sidebar（移动端变横向 tab 条，可滚动）：
- 概览 `/relay/console`
- API 密钥 `/relay/console/keys`
- 钱包充值 `/relay/console/topup`
- 使用日志 `/relay/console/logs`
- 分隔线；返回官网 `/relay`；退出登录。
侧边栏顶部显示用户邮箱首字母头像、邮箱、余额。

鉴权：layout onMounted 调 `fetchMe()`；401 清除 session 并 `router.replace('/relay/sign-in?redirect=...')`。
子页面不要再各自重复请求 me，通过 provide/inject 或 layout 插槽传递 user/refresh 方法。

### 概览
- 顶部 4 张统计卡：余额（元）、累计充值（paid topups amountCents 之和）、活跃令牌数、调用次数（usage total）。
- 「最近 7 天消耗」简单 SVG 柱状图：按 usage.createdAt 分天聚合 costCents；无数据时显示空状态。
- 「快速接入」卡：base URL、创建令牌按钮、文档链接。
- 最近 5 条用量表。

### API 密钥
- 右上「创建令牌」按钮，展开内联表单（名称可选）→ 创建成功后展示一次性完整 key 的蓝色提示卡 + 复制按钮，
  关闭提示后不再显示完整 key。
- 表格列：名称、前缀、创建时间、最近使用、状态、操作（吊销）。已吊销显示灰色标签，吊销前 confirm。
- 空状态 + 10 个上限提示。

### 钱包充值
- 计划卡 4 张（同首页样式），选中后点「立即充值」创建订单；创建成功展示支付面板：
  订单号、金额、支付宝/微信切换、「我已完成支付」。
- 支付成功刷新 user 余额和 topup 列表，显示成功提示。
- 充值记录表：订单号、档位、实付、到账、状态（待支付/已支付/已取消）、时间；pending 可继续支付。
- 支持 `?plan=<id>` 自动创建一次订单（避免刷新重复创建）。

### 使用日志
- 表：时间、模型、输入 tokens、输出 tokens、费用；空状态。
- 顶部显示总调用次数与总费用。

### 后台 `/relay/admin`
- 浅色重做：标题「中转后台」，token 输入卡片；用户/充值订单 tabs。
- 用户表：邮箱、余额、令牌数、累计实付、注册时间。
- 订单表：订单号、邮箱、档位、实付/到账、状态、时间；pending 可「标记已支付」「取消」。
- 操作后刷新；401 提示令牌错误。

## 响应式

- 390px：导航不溢出（可横向滚动或汉堡）；首页 hero 单列、代码卡在下方；统计 2×2；bento 单列；
  模型卡单列；控制台 sidebar 变顶部横向 tabs；所有表格外层 `overflow-x:auto`。
- 断点：1100 / 960 / 820 / 640。

## 验收

1. `npm run build` 成功。
2. 首页、模型广场、文档、登录、注册、控制台四个子页、后台全部可打开；无 micuapi/hongyuhub 字符串。
3. 浏览器流程：注册新账号 → 充值 50 → 微信支付 → 余额 55 → 创建 Key → 吊销 → 退出 → 登录 → 余额仍在。
4. 后台：用户与订单列表、pending 标记支付后余额增加。
5. 1440px 与 390px 截图；390px `scrollWidth <= innerWidth`。
6. 商城 `/shop` 不参与本次中转部署，relay.css 仍限定在 `.relay-shell` 下。

## 附录：首页文案

核心功能：
- 01 极速：优化的网络架构，确保毫秒级响应时间
- 02 安全可靠：企业级安全，提供全面的权限管理
- 03 全球覆盖：多区域部署，实现稳定的全球访问
- 04 开发者友好：兼容常见 AI 应用工作流的 API 路由
- 05 高性能：支持高并发和自动负载均衡
- 06 透明计费：按量付费，实时监控使用情况
- 07 团队协作：多用户管理，灵活分配权限
- 08 开源项目：社区驱动，可自托管、易于扩展

三步：
1. 配置：注册账户，充值并创建 API 密钥
2. 连接：通过 OpenAI、Claude、Gemini 以及其他兼容 API 路由接入
3. 监控：通过实时分析跟踪用量、成本和性能
