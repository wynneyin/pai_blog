# Linux 2核512M 部署文档（轻量版博客）

## 1. 为什么要用轻量版

| 版本 | 后端 | 后端内存 | 总内存占用 |
|------|------|----------|-----------|
| 原版 | Java Spring Boot | 300-500MB | 超出 512MB |
| 轻量版 | Node.js Express | 30-60MB | 约 200-250MB |

轻量版 API 接口完全兼容原版，前端代码零改动。

## 2. 服务器初始化

```bash
sudo apt update && sudo apt -y upgrade
sudo timedatectl set-timezone Asia/Shanghai
```

**必须开 swap（512M 机器关键）：**

```bash
sudo fallocate -l 1G /swapfile
sudo chmod 600 /swapfile
sudo mkswap /swapfile
sudo swapon /swapfile
echo '/swapfile none swap sw 0 0' | sudo tee -a /etc/fstab
free -h
```

开放端口（如果启用 UFW）：

```bash
sudo ufw allow 22
sudo ufw allow 80
sudo ufw allow 443
sudo ufw allow 1194/udp   # VPN 端口
sudo ufw enable
```

## 3. 安装 Docker 与 Compose

```bash
curl -fsSL https://get.docker.com | sh
sudo usermod -aG docker $USER
newgrp docker
```

如果拉取 Docker Hub 超时，配置镜像加速：

```bash
sudo mkdir -p /etc/docker
sudo tee /etc/docker/daemon.json > /dev/null <<'EOF'
{
  "registry-mirrors": [
    "https://docker.1panel.live",
    "https://docker.m.daocloud.io",
    "https://mirror.ccs.tencentyun.com"
  ]
}
EOF
sudo systemctl daemon-reload && sudo systemctl restart docker
```

## 4. 一键部署

把项目上传到服务器后，执行以下命令完成所有配置并启动：

```bash
cd /opt
sudo mkdir -p blog && sudo chown -R $USER:$USER blog
cd /opt/blog

# 上传项目文件到 /opt/blog 后执行：
cp .env.prod.example .env.prod

# 编辑配置（改域名和 token）
sed -i "s|https://你的域名|https://your-domain.com|g" .env.prod
# 或手动编辑：
# nano .env.prod

# 启动（使用轻量版 compose 文件）
docker compose --env-file .env.prod -f docker-compose.lite.yml up -d --build
```

## 5. 真正的一键部署脚本（首次部署）

```bash
bash <(curl -fsSL https://get.docker.com) && \
sudo usermod -aG docker $USER && \
newgrp docker && \
sudo fallocate -l 1G /swapfile && \
sudo chmod 600 /swapfile && \
sudo mkswap /swapfile && \
sudo swapon /swapfile && \
echo '/swapfile none swap sw 0 0' | sudo tee -a /etc/fstab && \
cd /opt/blog && \
cp .env.prod.example .env.prod && \
echo "请编辑 .env.prod 填写域名和 token，完成后运行：" && \
echo "docker compose --env-file .env.prod -f docker-compose.lite.yml up -d --build"
```

## 6. 配置 .env.prod

```env
BLOG_CORS_ALLOWED_ORIGINS=https://你的域名
BLOG_ADMIN_TOKEN=改成强密码token
```

## 7. 启动与验证

```bash
cd /opt/blog
docker compose --env-file .env.prod -f docker-compose.lite.yml up -d --build

# 验证服务
docker compose -f docker-compose.lite.yml ps
curl http://127.0.0.1/api/articles
```

## 8. 更新文章（无需重启）

```bash
cd /opt/blog
./scripts/new-post.sh 技术 "新文章标题" article-slug
```

后端会自动检测文件变化并刷新缓存。强制刷新：

```bash
curl -X POST http://127.0.0.1/api/admin/cache/refresh \
  -H "X-Admin-Token: 你的BLOG_ADMIN_TOKEN"
```

## 9. 常用运维命令

```bash
# 查看日志
docker logs -f blog-backend
docker logs -f blog-frontend

# 重启
docker compose -f docker-compose.lite.yml restart

# 查看内存占用
docker stats --no-stream

# 更新代码后重建
docker compose --env-file .env.prod -f docker-compose.lite.yml up -d --build

# 磁盘清理
docker image prune -f
```

## 10. 内存占用参考

启动后正常值：

```
blog-backend   ~30-60MB
blog-frontend  ~10-30MB
系统 + Docker  ~100-150MB
VPN 服务       ~30-80MB
合计           ~200-320MB（512MB 内有余量）
```
