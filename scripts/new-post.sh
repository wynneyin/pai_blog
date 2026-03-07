#!/usr/bin/env bash
set -euo pipefail

if [ "$#" -lt 2 ]; then
  echo "Usage: ./scripts/new-post.sh <category> <title> [slug]"
  echo "Example: ./scripts/new-post.sh 技术 SpringBoot实战 spring-boot-practice"
  exit 1
fi

CATEGORY_INPUT="$1"
TITLE="$2"
SLUG_INPUT="${3:-}"

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

if [ -n "$SLUG_INPUT" ]; then
  SLUG="$SLUG_INPUT"
else
  SLUG="$(echo "$TITLE" | tr '[:upper:]' '[:lower:]' | sed 's/[^a-z0-9]/-/g' | sed 's/-\+/-/g' | sed 's/^-//' | sed 's/-$//')"
  if [ -z "$SLUG" ]; then
    SLUG="post-$(date +%s)"
  fi
fi

case "$CATEGORY_INPUT" in
  技术|technology|tech)
    CATEGORY_DIR="technology"
    CATEGORY_NAME="技术"
    ;;
  生活|life)
    CATEGORY_DIR="life"
    CATEGORY_NAME="生活"
    ;;
  随笔|thinking|notes)
    CATEGORY_DIR="thinking"
    CATEGORY_NAME="随笔"
    ;;
  *)
    CATEGORY_DIR="$(echo "$CATEGORY_INPUT" | tr '[:upper:]' '[:lower:]' | sed 's/[^a-z0-9]/-/g' | sed 's/-\+/-/g' | sed 's/^-//' | sed 's/-$//')"
    CATEGORY_NAME="$CATEGORY_INPUT"
    ;;
esac

mkdir -p "$ROOT_DIR/articles/$CATEGORY_DIR"
TARGET_FILE="$ROOT_DIR/articles/$CATEGORY_DIR/$SLUG.md"

if [ -f "$TARGET_FILE" ]; then
  echo "File already exists: $TARGET_FILE"
  exit 1
fi

CURRENT_TIME="$(date '+%Y-%m-%d %H:%M:%S')"

cat > "$TARGET_FILE" <<EOF
---
title: "$TITLE"
slug: "$SLUG"
category: "$CATEGORY_NAME"
tags: [待补充]
author: "Wynne"
date: "$CURRENT_TIME"
summary: "请填写摘要"
published: true
---

# $TITLE

在这里开始写作。
EOF

echo "Created: $TARGET_FILE"
