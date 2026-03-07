---
title: JAVA
date: 2024-12-14
tags: [Java, 基础]
---

## 基本语法
<!--more-->
- 严格区分大小写；`true`、`false`、`null` 为字面量。
- 命名：类名大驼峰；变量名/方法名小驼峰。

方法重写<br>1.子类重写父类方法之后,权限必须要保证大于等于父类权限(权限指的是访问权限)<br>  public -> protected -> 默认 -> private<br>2.子类方法重写父类方法,方法名和参数列表要一样<br>3.私有方法不能被重写,构造方法不能被重写,静态方法不能被重写<br>4.子类重写父类方法之后,返回值类型应该是父类方法返回值类型的子类类型  



## 继承

1.继承只支持单继承,不能多继承<br>  public class A extends B,C{}  -> 错误<br>2.继承支持多层继承<br>  public class A extends B{}<br>  public class B extends C{}<br>3.一个父类可以有多个子类<br>  public class A extends C{}<br>  public class B extends C{}

4.构造方法不能继承,也不能重写<br>  私有方法可以继承,但是不能被重写<br>  静态方法可以继承,但是不能被重写

## 封装（为 private 字段赋值）

- 使用 setter 方法。
- 使用构造方法（本质通过 `this` 赋值）。

## 抽象

抽象类 就是写一个父类 比如eat这个动作 作为父类 然后猫狗 分别去继承这个eat 然后重写自己的eat 具体细节由每个子类方法决定  这个重写是必须的要不然编译报错<br>抽象类不能直接 new

![image](http://wynneyin.oss-cn-hangzhou.aliyuncs.com/20241215132956.png)

## 接口

静态方法和默认方法 小技巧

### 接口的定义以及使用

1.定义接口:<br>  public interface 接口名{}<br>2.实现:<br>  public class 实现类类名 implements 接口名{}<br>3.使用:<br>  a.实现类实现接口<br>  b.重写接口中的抽象方法<br>  c.创建实现类对象(接口不能直接new对象)<br>  d.调用重写的方法    

### 接口的特点

接口可以多继承<br>接口可以多实现   接口的默认方法有重名的必须重写<br>一个子类可以继承一个父类然后实现多个接口

### 接口和抽象类的区别

相同点:<br>  a.都位于继承体系的顶端,用于被其他类实现或者继承<br>  b.都不能new<br>  c.都包含抽象方法,其子类或者实现类都必须重写这些抽象方法

不同点:<br>  a.抽象类:一般作为父类使用,可以有成员变量,构造,成员方法,抽象方法等<br>  b.接口:成员单一,一般抽取接口,抽取的都是方法,视为功能的大集合<br>  c.类不能多继承,但是接口可以

![1703144030947](https://wynneyin.oss-cn-hangzhou.aliyuncs.com/1703144030947.png)

## 多态

1.多态的前提是必须有子父类的继承和接口实现关系
2.必须有方法的重写
3.new对象：父类引用指向子类对象

```java
public class Test01 {
    public static void main(String[] args) {
        //原始方式
        Dog dog = new Dog();
        dog.eat();//重写的
        dog.lookDoor();//特有的

        Cat cat = new Cat();
        cat.eat();//重写的
        cat.catchMouse();//特有的

        System.out.println("==================");
        //多态形式new对象
        Animal animal = new Dog();//相当于double b = 10
        animal.eat();//重写的 animal接收的是dog对象,所以调用的是dog中的eat
//      animal.lookDoor();   多态前提下,不能直接调用子类特有成员

        Animal animal1 = new Cat();
        animal1.eat();//cat重写的


    }
}
```

用子类去重写父类 看new的是谁,先调用谁中的成员方法,子类没有,找父类

但是多态方式new对象,只能调用重写的,不能直接调用子类特有的成员,那为啥还要用多态呢?

### 多态中的转型

1.如果等号左右两边类型不一致,会出现类型转换异常(ClassCastException)<br>2.解决:<br>  在向下转型之前,先判断类型<br>3.怎么判断类型: instanceof<br>  判断结果是boolean型

4.使用:<br>  对象名 instanceof 类型 -> 判断的是关键字前面的对象是否符合关键字后面的类型

## 权限类型

|   | public | protected | default（包内） | private |
| --- | --- | --- | --- | --- |
| 同类 | yes | yes | yes | yes |
| 同包不同类 | yes | yes | yes | no |
| 不同包子父类 | yes | yes | no | no |
| 不同包非子父类 | yes | no | no | no |

## final

1. final 修饰类不能被继承
2. final 修饰的方法不能被重写
3. final 修饰的变量不能被更改
4. final 修饰的对象地址值不能被改变，但是对象中的属性值可以被改变

## 内部类

```
1.格式:直接在定义内部类的时候加上static关键字
public class A {
    static class B {
        // ...
    }
}
```

### 静态内部类

调用静态内部类成员:<br>  外部类.内部类 对象名 = new 外部类.内部类()

### 非静态内部类

去掉内部B的static 的 就是非静态的

**调用非静态内部类成员:**<br>  外部类.内部类 对象名 = new 外部类().new 内部类()

## 匿名内部类

```
1.问题描述:我们如果想实现接口,简单使用一次抽象方法,我们就需要创建一个实现类,实现这个接口,重写抽象方法,还要new实现类对象,所以我们在想如果就单纯的想使用一次接口中的方法,我们能不能不这么麻烦呢?可以
  a.创建实现类,实现接口
  b.重写方法
  c.创建实现类对象
  d.调用方法
    
2.如果就想单纯的使用一下接口中的方法,我们就没必要经过以上四步了,我们可以四合一
    
3.匿名内部类怎么学:就按照一种格式来学,这一种格式就代表了实现类对象或者子类对象
    
4.格式:
  new 接口/抽象类(){
      重写方法
  }.重写的方法();

  =================================

  类名 对象名 = new 接口/抽象类(){
      重写方法
  }
  对象名.重写的方法();
```

![1703505756427](https://wynneyin.oss-cn-hangzhou.aliyuncs.com/1703505756427.png)

1.什么时候使用匿名内部类:

​    当简单调用一次接口中的方法,我们就可以使用匿名内部类

2.将一种格式代表实现类对象或者子类对象来看待,来学习

3.匿名内部类会编译生成的,咱们不要管,我们只需要利用咱们讲的格式去new对象,调用重写的方法即可

### 匿名内部类当参数传递

![1703507635855](https://wynneyin.oss-cn-hangzhou.aliyuncs.com/1703507635855.png)

### 匿名内部类当返回值

![1703507885149](https://wynneyin.oss-cn-hangzhou.aliyuncs.com/1703507885149.png)