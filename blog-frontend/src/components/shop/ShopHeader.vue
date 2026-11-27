<script setup>
import { shopBrand } from "../../data/shop";
</script>

<template>
  <header class="shop-header">
    <div class="shop-header__inner">
      <router-link to="/shop" class="shop-brand" :aria-label="`${shopBrand.name} 商城首页`">
        <span class="shop-brand__mark">{{ shopBrand.mark }}</span>
        <span>{{ shopBrand.name }}<small>{{ shopBrand.tagline }}</small></span>
      </router-link>
      <nav class="shop-nav" aria-label="商城导航">
        <router-link to="/shop" class="shop-nav__link" exact-active-class="shop-nav__link--active">额度商城</router-link>
        <router-link to="/shop/orders" class="shop-nav__link" active-class="shop-nav__link--active">订单查询</router-link>
        <router-link to="/relay" class="shop-nav__link" active-class="shop-nav__link--active">API 中转</router-link>
        <router-link to="/shop/help" class="shop-nav__link" exact-active-class="shop-nav__link--active">帮助中心</router-link>
      </nav>
    </div>
  </header>
</template>
