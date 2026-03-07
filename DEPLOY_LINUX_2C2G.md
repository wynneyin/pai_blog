# Linux 2核2G 部署文档（个人博客）

## 1. 你的服务器能不能跑

可以跑，适合个人博客和中低流量场景。

建议资源分配：
- 系统 + Docker: 400MB ~ 700MB
- 后端 Java: 300MB ~ 700MB（已限制 `-Xmx512m`）
- 前端 Nginx: 30MB ~ 80MB
- 预留缓冲: 400MB+

## 2. 服务器初始化（Ubuntu 22.04/24.04）

```bash
sudo apt update && sudo apt -y upgrade
sudo timedatectl set-timezone Asia/Shanghai
```

建议开 2G swap（2G 内存机器很重要）：

```bash
sudo fallocate -l 2G /swapfile
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
sudo ufw enable
sudo ufw status
```

## 3. 安装 Docker 与 Compose

```bash
curl -fsSL https://get.docker.com | sh
sudo usermod -aG docker $USER
newgrp docker
docker --version
docker compose version
```

如果 `docker compose` 提示 `buildx isn't installed`，可先禁用 Bake：

```bash
echo 'export COMPOSE_BAKE=false' >> ~/.bashrc
source ~/.bashrc
```

如果拉取 Docker Hub 超时（`registry-1.docker.io ... i/o timeout`），配置镜像加速：

```bash
sudo mkdir -p /etc/docker
sudo tee /etc/docker/daemon.json > /dev/null <<'EOF'
{
  "registry-mirrors": [
    "https://docker.1panel.live",
    "https://docker.m.daocloud.io",
    "https://hub-mirror.c.163.com",
    "https://mirror.ccs.tencentyun.com"
  ]
}
EOF
sudo systemctl daemon-reload
sudo systemctl restart docker
docker info | grep -A 5 "Registry Mirrors"
```

## 4. 上传项目并配置生产环境

```bash
cd /opt
sudo mkdir -p blog && sudo chown -R $USER:$USER blog
cd /opt/blog
```

把本项目代码上传到 `/opt/blog` 后执行：

```bash
cp .env.prod.example .env.prod
```

编辑 `.env.prod`：

```env
BLOG_CORS_ALLOWED_ORIGINS=https://你的域名
BLOG_ADMIN_TOKEN=改成强密码token
```

## 5. 启动生产服务

```bash
cd /opt/blog
docker compose --env-file .env.prod -f docker-compose.prod.yml up -d --build
docker compose -f docker-compose.prod.yml ps
```

访问：
- 前端：`http://服务器IP`
- 后端接口：`http://服务器IP/api/articles`

查看日志：

```bash
docker logs -f blog-backend
docker logs -f blog-frontend
```

## 6. 绑定域名与 HTTPS（推荐）

1. 域名 A 记录指向你的服务器 IP  
2. 先确认 `http://你的域名` 可访问  
3. 申请证书（Nginx/Caddy/宝塔任选一种）

如果你要我给你一份“基于 Nginx + Certbot”的一键命令版，我可以按你服务器系统直接写。

## 7. 更新文章（无需重启）

文章目录是挂载目录：`/opt/blog/articles`

新增文章示例：

```bash
cd /opt/blog
./scripts/new-post.sh 技术 "Linux 部署记录" linux-deploy-note
```

后端会自动检测文件变化并刷新缓存。  
如果你希望立即强制刷新，也可调用：

```bash
curl -X POST http://127.0.0.1/api/admin/cache/refresh \
  -H "X-Admin-Token: 你的BLOG_ADMIN_TOKEN"
```

## 8. 常用运维命令

重启服务：

```bash
docker compose -f docker-compose.prod.yml restart
```

更新代码后重建：

```bash
docker compose --env-file .env.prod -f docker-compose.prod.yml up -d --build
```

磁盘清理：

```bash
docker system df
docker image prune -f
```
