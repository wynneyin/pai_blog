---
title: "Java 17 在个人项目中的实用特性"
slug: "java-17-practice"
category: "技术"
tags: [Java17, Backend]
author: "Wynne"
date: "2026-02-20 21:15:00"
summary: "记录 Text Block、Record 等特性在日常后端项目里的使用经验。"
published: true
---

# Java 17 的价值

升级到 Java 17 后，代码可读性和稳定性都有明显提升，尤其是对长期维护项目非常友好。

## 日常最常用的点

- Text Block：多行字符串更清晰
- Record：DTO 更轻量
- 更成熟的 G1/ZGC 参数

## 建议

新项目直接上 Java 17，老项目可先从模块化迁移和测试覆盖开始。
