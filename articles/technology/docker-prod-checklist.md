---
title: "个人博客上线前的 Docker 检查清单"
slug: "docker-prod-checklist"
category: "技术"
tags: [Docker, DevOps]
author: "Wynne"
date: "2026-01-18 09:00:00"
summary: "上线前把镜像、端口、日志和重启策略检查到位，能少掉很多故障。"
published: true
---

# 上线前必看清单

个人博客最怕不是代码复杂，而是上线后没人值守。以下几项尽量标准化。

1. 镜像可重复构建
2. 端口和反向代理路径稳定
3. 容器异常自动重启
4. 日志可追踪

## 经验

把文章目录通过 volume 挂载，就能做到快速更新内容，不需要每次重建镜像。
