#!/bin/bash
# publish.sh - 博客发布脚本
# 用法: ./scripts/publish.sh <文章路径> [推特文案（可选）]
# 例如: ./scripts/publish.sh articles/technology/my-post.md

set -e

ARTICLE_PATH="$1"
TWEET_TEXT="$2"

if [ -z "$ARTICLE_PATH" ]; then
  echo "用法: $0 <文章路径> [推特文案]"
  exit 1
fi

if [ ! -f "$ARTICLE_PATH" ]; then
  echo "文件不存在: $ARTICLE_PATH"
  exit 1
fi

# 从 frontmatter 提取 title 和 slug
TITLE=$(grep '^title:' "$ARTICLE_PATH" | head -1 | sed 's/title: *//;s/^"//;s/"$//')
SLUG=$(grep '^slug:' "$ARTICLE_PATH" | head -1 | sed 's/slug: *//;s/^"//;s/"$//')
SUMMARY=$(grep '^summary:' "$ARTICLE_PATH" | head -1 | sed 's/summary: *//;s/^"//;s/"$//')

echo "📝 文章: $TITLE"
echo "🔗 Slug: $SLUG"

# git add + commit + push
cd "$(dirname "$0")/.."
git add "$ARTICLE_PATH"
git commit -m "feat: 新增文章 - $TITLE"
git push origin main
echo "✅ 已推送到 GitHub"

# 加载 Twitter 凭证
source ~/.config/twitter-cli/credentials.env
export TWITTER_AUTH_TOKEN
export TWITTER_CT0

# 发推
if [ -z "$TWEET_TEXT" ]; then
  TWEET_TEXT="${TITLE}

${SUMMARY}

🔗 wynneyin.com/articles/${SLUG}"
fi

echo "🐦 发推: $TWEET_TEXT"
twitter post "$TWEET_TEXT"
echo "✅ 推特已发出"
