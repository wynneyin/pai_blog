<script setup>
import dayjs from "dayjs";
import { onMounted, ref, watch } from "vue";
import { useRoute } from "vue-router";
import api from "../api/client";

const route = useRoute();
const loading = ref(false);
const error = ref("");
const article = ref(null);

async function fetchArticle(slug) {
  loading.value = true;
  error.value = "";
  try {
    const result = await api.get(`/articles/${slug}`);
    article.value = result.data;
  } catch (err) {
    error.value = err.message;
  } finally {
    loading.value = false;
  }
}

watch(
  () => route.params.slug,
  (slug) => {
    if (slug) {
      fetchArticle(String(slug));
    }
  }
);

onMounted(() => {
  fetchArticle(String(route.params.slug));
});
</script>

<template>
  <main class="container article-page">
    <router-link to="/" class="article-page__back">← 返回首页</router-link>
    <p v-if="error" class="status status--error">{{ error }}</p>
    <p v-else-if="loading" class="status">加载中...</p>

    <article v-else-if="article" class="article">
      <header class="article__header">
        <span>{{ article.category }}</span>
        <h1>{{ article.title }}</h1>
        <div class="article__meta">
          <span>{{ article.author }}</span>
          <span>{{ dayjs(article.createdAt).format("YYYY-MM-DD HH:mm") }}</span>
          <span>{{ article.views }} 阅读</span>
        </div>
      </header>
      <section class="markdown-content" v-html="article.content"></section>
    </article>
  </main>
</template>
