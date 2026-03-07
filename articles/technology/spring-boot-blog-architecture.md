---
title: "Spring Boot + Vue 博客架构设计"
slug: "spring-boot-vue-blog-architecture"
category: "技术"
tags: [Java, SpringBoot, Vue]
author: "Wynne"
date: "2026-03-01 10:30:00"
summary: "从后端 API 到前端页面分层，整理一个适合个人维护的博客架构。"
published: true
---

# 为什么做前后端分离

前后端分离可以让内容和界面独立演进。后端只负责文章数据、分类、标签和分页能力，前端专注视觉与交互。

## 关键点

- 文章以 Markdown 存储，避免数据库维护成本
- API 统一输出 JSON，前端复用成本更低
- 通过 Docker Compose 一键上线，流程清晰

## 结论

这套结构适合个人博客，也方便后续扩展评论、鉴权或全文搜索。
