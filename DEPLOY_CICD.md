# GitHub 自动构建与部署

每次 push 到 `main` 都会运行 **Build and Deploy Blog**，不限制改动目录。
Pull Request 运行构建检查；合并到 `main` 后才部署。Actions 页面也支持手动运行
`Run workflow`（选择 `main`）。

## 自动执行的步骤

1. 验证部署脚本并运行故障场景测试。
2. 使用 `npm ci` 构建 Vue 前端，检查 Node 后端，并使用 Java 17 / Maven 构建和测试 Java 后端。
3. 将同一提交的前端产物上传到服务器，然后使用现有 SSH 配置进入部署目录。
4. 仅快进更新 `main`，识别并沿用线上实际的 Docker Compose 或 PM2 部署方式。
5. 检查首页与 `/api/articles`；再从 GitHub 访问公网域名，确认首页引用了本次构建的资源且 API 正常。失败会让 Actions 显示红色，可点击步骤查看日志。

Docker 部署沿用当前容器记录的项目名和 Compose 文件：先顺序构建前后端镜像，
全部成功后再重建应用容器。前端镜像仍在服务器上构建，服务器需要有足够的构建内存。
PM2 部署直接发布 GitHub 构建的前端产物，重新安装 Node 生产依赖并 reload `blog-backend`。
保留旧前端资源，最后原子替换 `index.html`，兼容已经打开的页面。

## GitHub 配置

沿用已有 Repository Secrets，无需在仓库写入密钥：

| Secret | 用途 |
| --- | --- |
| `SERVER_HOST` | 服务器地址 |
| `SERVER_USER` | SSH 用户 |
| `SERVER_SSH_KEY` | SSH 私钥 |
| `SERVER_SSH_FINGERPRINT` | 可选：已核对的服务器 SHA256 主机密钥指纹 |

支持以下 Repository Variables，未填写时沿用原工作流和部署脚本的路径：

| Variable | 默认值 | 用途 |
| --- | --- | --- |
| `SERVER_PORT` | `22` | SSH 端口 |
| `DEPLOY_PATH` | `/root/pai_blog` | 服务器上的现有仓库目录 |
| `DEPLOY_HEALTHCHECK_URL` | 从 Nginx 自动识别 | 健康检查的站点根地址，包含端口（如需）；默认通过本机地址检查实际域名与 HTTPS |
| `DEPLOY_FRONTEND_DIR` | `/var/www/blog` | PM2 部署对应的 Nginx 静态目录 |
| `PUBLIC_SITE_URL` | `https://wynneyin.site` | 部署后从 GitHub 验证的公网根地址 |

设置入口：仓库 Settings → Secrets and variables → Actions。
服务器需已有正常运行的服务、Git、Bash、`flock`、`curl`，并能读取 GitHub 仓库。
Docker 模式需 Docker Compose v2；PM2 模式需现有的 `blog-backend` 进程和 npm。

## 部署保护与限制

- 同一分支的运行不并行，不会为了新 push 中断正在执行的部署。
- 使用现有自动拉取脚本相同的文件锁，避免同时修改服务器工作区。
- 过期提交不会覆盖更新的 `main`；不能快进、源代码有本地修改或运行方式未知时停止。
- 保留服务器 `.env` / `.env.prod`、`blog-frontend/nginx.conf` 和 `docker-compose.yml` 的本地配置；不强制覆盖 Git 合并冲突。部署前将 Nginx 和 Compose 配置备份到 `.git/deploy-config-backups/`；`.env.prod` 存在时供 Compose 使用。
- 前置检查失败不会进入部署；镜像构建失败不会执行容器替换。
- 更新服务时可能有短暂中断；健康检查失败会标记失败，目前不自动回滚。
- 不执行 `git reset --hard`、`docker compose down`、volume 删除或全局镜像清理。

排查顺序：打开仓库 Actions → 最近一次 Build and Deploy Blog → 失败的步骤。
如果 SSH 凭据或路径已变更，在上述设置入口更新后重新运行即可。
