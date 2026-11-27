<script setup>
import { onMounted, ref } from "vue";
import { fetchCatalog } from "../api/shop";
import { faqs, shopBrand, support } from "../data/shop";

const notices = ref([]);
const noticeError = ref("");

onMounted(async () => {
  try {
    const catalog = await fetchCatalog();
    notices.value = catalog.notices || [];
  } catch (e) {
    noticeError.value = e.message || "公告加载失败";
  }
});
</script>

<template>
  <main class="shop-main">
    <header class="shop-page-head">
      <p class="shop-kicker">Help center</p>
      <h1>帮助中心</h1>
      <p>公告、常见问题与联系方式，都集中在这一页。</p>
    </header>

    <div class="shop-help">
      <nav class="shop-panel shop-help__nav" aria-label="帮助中心目录">
        <a href="#notice">公告</a>
        <a href="#faq">常见问题</a>
        <a href="#contact">联系管理员</a>
      </nav>

      <div>
        <section id="notice" class="shop-panel shop-help__section">
          <p class="shop-kicker">Notice</p>
          <h2>公告</h2>
          <p v-if="noticeError" class="shop-error">{{ noticeError }}</p>
          <ul v-else-if="notices.length" class="shop-notice-list">
            <li v-for="notice in notices" :key="notice">{{ notice }}</li>
          </ul>
          <p v-else class="shop-hint">暂无公告。</p>
        </section>

        <section id="faq" class="shop-panel shop-help__section">
          <p class="shop-kicker">FAQ</p>
          <h2>常见问题</h2>
          <div class="shop-faq">
            <details v-for="item in faqs" :key="item.q">
              <summary>{{ item.q }}</summary>
              <p>{{ item.a }}</p>
            </details>
          </div>
        </section>

        <section id="contact" class="shop-panel shop-help__section">
          <p class="shop-kicker">Support</p>
          <h2>联系管理员</h2>
          <p class="shop-hero__sub">
            付款、到账、退款或大额采购问题，请携带订单号联系管理员。{{ shopBrand.name }}
            会在看到消息后按顺序处理。
          </p>
          <div class="shop-contact">
            联系入口：
            <a :href="support.url" target="_blank" rel="noopener noreferrer">
              {{ support.label }} ↗
            </a>
            <br />
            中转站注册与 API 使用问题，请先查看
            <router-link to="/relay/docs" style="color: var(--s-green); font-weight: 700">
              接入文档
            </router-link>
            。
          </div>
        </section>
      </div>
    </div>
  </main>
</template>
