<script setup>
import { computed, onMounted, ref } from "vue";
import { fetchCatalog } from "../api/shop";
import ProductCard from "../components/shop/ProductCard.vue";
import OrderDialog from "../components/shop/OrderDialog.vue";

const catalog = ref({ categories: [], products: [], notices: [] });
const loading = ref(true);
const error = ref("");
const activeCategory = ref("all");
const keyword = ref("");
const selected = ref(null);

const categories = computed(() => catalog.value.categories || []);
const products = computed(() => catalog.value.products || []);
const notices = computed(() => catalog.value.notices || []);

const counts = computed(() => {
  const map = { all: products.value.length };
  for (const category of categories.value) {
    map[category.id] = products.value.filter((item) => inCategory(item, category.id)).length;
  }
  return map;
});

function inCategory(item, categoryId) {
  const ids =
    Array.isArray(item.categoryIds) && item.categoryIds.length
      ? item.categoryIds
      : [item.categoryId];
  return ids.map(String).includes(String(categoryId));
}

const activeBlurb = computed(
  () =>
    categories.value.find((item) => item.id === activeCategory.value)?.blurb ||
    "全部在售商品，支持分类筛选与关键词搜索。"
);

const visibleProducts = computed(() => {
  const query = keyword.value.trim().toLowerCase();
  return products.value.filter((item) => {
    if (activeCategory.value !== "all" && !inCategory(item, activeCategory.value)) {
      return false;
    }
    if (!query) return true;
    return [item.name, item.subtitle, item.tag].join(" ").toLowerCase().includes(query);
  });
});

async function load(force = false) {
  loading.value = true;
  error.value = "";
  try {
    catalog.value = await fetchCatalog({ force });
  } catch (e) {
    error.value = e.message || "商品目录加载失败";
  } finally {
    loading.value = false;
  }
}

async function refreshQuietly() {
  try {
    catalog.value = await fetchCatalog({ force: true });
  } catch {
    /* 静默刷新失败时保留当前目录 */
  }
}

function onDialogClose() {
  selected.value = null;
  refreshQuietly();
}

onMounted(() => load());
</script>

<template>
  <main class="shop-main">
    <div v-if="notices.length" class="shop-notice">
      <strong>公告</strong>
      <p>{{ notices[0] }}</p>
      <router-link to="/shop/help#notice" class="shop-notice__more">全部 →</router-link>
    </div>

    <section class="shop-hero">
      <div>
        <p class="shop-kicker">Relay credits</p>
        <h1>中转额度 · 确认到账</h1>
        <p class="shop-hero__sub">
          购买 API 中转额度，管理员确认收款后自动充入中转站账户，全模型通用、按量扣费。
        </p>
      </div>
      <div class="shop-hero__stats">
        <div class="shop-stat">
          <strong>{{ categories.length }}</strong>
          <span>商品分类</span>
        </div>
        <div class="shop-stat">
          <strong>{{ products.length }}</strong>
          <span>在售额度包</span>
        </div>
        <div class="shop-stat">
          <strong>7×24</strong>
          <span>在线下单</span>
        </div>
      </div>
    </section>

    <div class="shop-layout">
      <aside class="shop-panel shop-rail">
        <div class="shop-panel__head">
          <p class="shop-kicker">Categories</p>
          <h2 class="shop-panel__title">商品分类</h2>
        </div>
        <ul class="shop-rail__list">
          <li>
            <button
              class="shop-rail__btn"
              :class="{ 'shop-rail__btn--active': activeCategory === 'all' }"
              type="button"
              @click="activeCategory = 'all'"
            >
              <span>全部商品</span>
              <span class="shop-rail__count">{{ counts.all }}</span>
            </button>
          </li>
          <li v-for="category in categories" :key="category.id">
            <button
              class="shop-rail__btn"
              :class="{ 'shop-rail__btn--active': activeCategory === category.id }"
              type="button"
              @click="activeCategory = category.id"
            >
              <span>{{ category.name }}</span>
              <span class="shop-rail__count">{{ counts[category.id] }}</span>
            </button>
          </li>
        </ul>
        <p v-if="!loading && !error" class="shop-rail__blurb">{{ activeBlurb }}</p>
      </aside>

      <section class="shop-panel">
        <div class="shop-panel__head">
          <p class="shop-kicker">Catalog</p>
          <h2 class="shop-panel__title">商品目录</h2>
        </div>
        <div class="shop-toolbar">
          <label class="shop-search">
            <span aria-hidden="true">⌕</span>
            <input v-model="keyword" type="search" placeholder="搜索商品名称或关键词" />
          </label>
          <span class="shop-count">共 {{ visibleProducts.length }} 件</span>
        </div>

        <p v-if="loading" class="shop-empty">正在加载商品目录…</p>
        <div v-else-if="error" class="shop-empty">
          <p class="shop-error" style="margin: 0 0 12px">{{ error }}</p>
          <button class="shop-btn" type="button" @click="load(true)">重新加载</button>
        </div>
        <div v-else-if="visibleProducts.length" class="shop-grid">
          <ProductCard
            v-for="product in visibleProducts"
            :key="product.id"
            :product="product"
            @buy="selected = $event"
          />
        </div>
        <p v-else class="shop-empty">没有找到匹配的商品，换个关键词试试。</p>
      </section>
    </div>

    <OrderDialog
      v-if="selected"
      :product="selected"
      :payment="catalog.payment || {}"
      :self-pay="catalog.allowSelfPay !== false"
      @close="onDialogClose"
    />
  </main>
</template>
