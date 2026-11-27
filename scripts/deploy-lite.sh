#!/bin/bash
set -e

BLOG_DIR="$(cd "$(dirname "$0")/.." && pwd)"
BACKEND_DIR="$BLOG_DIR/blog-backend-node"
FRONTEND_DIR="$BLOG_DIR/blog-frontend"
DIST_DIR="/var/www/blog"
ARTICLES_DIR="$BLOG_DIR/articles"

GREEN='\033[0;32m'
NC='\033[0m'
log() { echo -e "${GREEN}[deploy]${NC} $1"; }

# 生成随机后台令牌；只写入 .env，不打印到终端
gen_admin_token() {
  node -e "process.stdout.write(require('crypto').randomBytes(32).toString('hex'))"
}

# 1. 安装 Node.js
if ! command -v node &>/dev/null; then
  log "安装 Node.js 20..."
  curl -fsSL https://deb.nodesource.com/setup_20.x | bash -
  apt install -y nodejs
fi
log "Node.js $(node -v)"

# 2. 安装 Nginx
if ! command -v nginx &>/dev/null; then
  log "安装 Nginx..."
  apt install -y nginx
fi

# 3. 复制前端（dist 已在 git 中，无需编译）
log "复制前端静态文件..."
mkdir -p "$DIST_DIR"
cp -r "$FRONTEND_DIR/dist/." "$DIST_DIR/"
log "前端文件就绪 -> $DIST_DIR"

# 4. 安装后端依赖
log "安装后端依赖..."
cd "$BACKEND_DIR"
npm install --omit=dev --silent

# 5. 安装 pm2
if ! command -v pm2 &>/dev/null; then
  log "安装 pm2..."
  npm install -g pm2 --silent
fi

# 6. 读取或创建环境变量
ENV_FILE="$BLOG_DIR/.env"
if [ ! -f "$ENV_FILE" ]; then
  log "创建 .env 文件..."
  SERVER_IP=$(curl -s ifconfig.me || echo "127.0.0.1")
  cat > "$ENV_FILE" << EOF
BLOG_CORS_ALLOWED_ORIGINS=http://${SERVER_IP}
BLOG_ADMIN_TOKEN=$(gen_admin_token)
EOF
  chmod 600 "$ENV_FILE"
  echo ""
  echo ">>> 已自动生成 .env，BLOG_ADMIN_TOKEN 为随机令牌，请妥善保管（不会再次显示）。"
  echo ""
fi

source "$ENV_FILE"

# 旧版 .env 可能没有后台令牌；为空时后台接口不鉴权，必须补一个随机值。
if [ -z "${BLOG_ADMIN_TOKEN:-}" ]; then
  log "BLOG_ADMIN_TOKEN 为空，生成随机令牌并写入 .env..."
  printf '\nBLOG_ADMIN_TOKEN=%s\n' "$(gen_admin_token)" >> "$ENV_FILE"
  source "$ENV_FILE"
fi

# .env 含后台令牌，确保仅所有者可读。
chmod 600 "$ENV_FILE" 2>/dev/null || true

# 7. 启动/重启后端
log "启动后端..."
pm2 delete blog-backend 2>/dev/null || true
ARTICLES_PATH="$ARTICLES_DIR" \
BLOG_CORS_ALLOWED_ORIGINS="$BLOG_CORS_ALLOWED_ORIGINS" \
BLOG_ADMIN_TOKEN="$BLOG_ADMIN_TOKEN" \
pm2 start "$BACKEND_DIR/src/index.js" --name blog-backend

pm2 save
pm2 startup systemd -u root --hp /root 2>/dev/null || true

# 8. 配置 Nginx
log "配置 Nginx..."
cat > /etc/nginx/sites-available/blog << 'NGINX'
server {
    listen 80;
    server_name _;

    root /var/www/blog;
    index index.html;

    location / {
        try_files $uri $uri/ /index.html;
    }

    location /api/ {
        proxy_pass http://127.0.0.1:8080/api/;
        proxy_http_version 1.1;
        proxy_set_header Host $host;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
    }

    location /v1/ {
        proxy_pass http://127.0.0.1:8080/v1/;
        proxy_http_version 1.1;
        proxy_set_header Host $host;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_buffering off;
        proxy_read_timeout 300s;
    }
}
NGINX

ln -sf /etc/nginx/sites-available/blog /etc/nginx/sites-enabled/blog
rm -f /etc/nginx/sites-enabled/default
nginx -t && systemctl reload nginx

log "==============================="
log "部署完成！"
log "访问地址: http://$(curl -s ifconfig.me 2>/dev/null || echo '服务器IP')"
log "后端状态: pm2 list"
log "更新文章后刷新缓存:"
log "  curl -X POST http://127.0.0.1/api/admin/cache/refresh -H 'X-Admin-Token: \$BLOG_ADMIN_TOKEN'"
log "==============================="
