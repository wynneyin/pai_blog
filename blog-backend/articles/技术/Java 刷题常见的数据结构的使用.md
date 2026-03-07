---
title: Java 刷题常见的数据结构的使用
date: 2026-01-26
tags: [Java, 数据结构, 刷题]
---

# Java 刷题常见的数据结构的使用
<!--more-->
## 目录

- [Java 刷题常见的数据结构的使用](#java-刷题常见的数据结构的使用)
  - [目录](#目录)
  - [数组](#数组)
    - [创建](#创建)
  - [常用操作](#常用操作)
  - [字符串](#字符串)
  - [动态数组ArrayList](#动态数组arraylist)
    - [创建](#创建-1)
    - [增删改查](#增删改查)
  - [栈 Stack/Deque](#栈-stackdeque)
    - [基本操作](#基本操作)
  - [队列 Deque](#队列-deque)
    - [双端队列](#双端队列)
  - [哈希表](#哈希表)
    - [创建 增删改查](#创建-增删改查)
    - [遍历](#遍历)
  - [集合](#集合)
    - [创建 和操作](#创建-和操作)
  - [堆 优先队列](#堆-优先队列)
    - [自定义比较器](#自定义比较器)



## 数组

### 创建

```java
int [] a= new int[5];
int [] b= {1,2,3};
int [][] grid = new int [m][n];
```

## 常用操作

```
int [] c1= Arrays.copyOf(b,b.length);

int [] c2 =b.clone();

Arrays.sort(b);
Arrays.binaryASearch(b,target); //已经排序的数组

```

## 字符串

```java
String s ="abc";

char ch =s.charAt(0);

int n= s.length();

```

String 不可变

```java

String sub=s.substring(l,r); // [l,r)

StingBuilder sb = new StringBuilder();

sb.append('a').append(1);

sb.setLength(0);              // 清空

String res = sb.toString();

sb.reverse();
```



## 动态数组ArrayList

### 创建

```java
List<Integer> list = new ArrayList<>();

List<Integer> list= new ArrayList<>(100);
```

### 增删改查

```java
list.add(1);
list.add(idx,2);

int v= list.get(idx);

list.remove(idx);
boolean has = list.contains(1);
int size =list.size();
```



## 栈 Stack/Deque 

###  基本操作

```java
Deque<Integer> st = new ArrayDeque<>();

st.push(1);      // 入栈
int top = st.peek();
int pop = st.pop();
boolean empty = st.isEmpty();
```



## 队列 Deque

```java
Queue<Integer> q = new ArrayDeque<>();

q.offer(1);          // 入队
int head = q.peek(); // 看队头
int out = q.poll();  // 出队（空则返回 null）
```



### 双端队列

```java
Deque<Integer> dq = new ArrayDeque<>();

dq.offerLast(1);
dq.offerFirst(2);
dq.pollLast();
dq.pollFirst();
dq.peekFirst();
dq.peekLast();
```

## 哈希表

### 创建 增删改查

```java
Map<Integer,Integer> map=. new HashMap<>();

map.put(1,10);

int val = map.getOrDefault(1,0);

boolean key = map.containKey(1);

map.remove(1);

int sz = map.size();
```

### 遍历

```java
for (var e : map.entrySet()) {
    int k = e.getKey();
    int v = e.getValue();
}
for (int k : map.keySet()) {}
for (int v : map.values()) {}
```



## 集合

### 创建 和操作

```java
Set<Integer> set = new HashSet<>();
set.add(1);
set.remove(1);
boolean ok = set.contains(1);
int sz = set.size();
```



## 堆 优先队列 

```java
PriorityQueue<Integer> pq = new PriorityQueue<>();

pq.offer(3);

pq.offer(1);

int x = pq.peek();
int y= pq.poll();
```

### 自定义比较器

```java
PriorityQueue<Integer> maxHeap =
    new PriorityQueue<>((a, b) -> b - a);
```

