<script setup>
import { onMounted, reactive, ref, watch } from "vue";
import api from "../api/client";
import BlogHero from "../components/BlogHero.vue";
import CategoryTabs from "../components/CategoryTabs.vue";
import ArticleCard from "../components/ArticleCard.vue";
import PaginationNav from "../components/PaginationNav.vue";
import ProfileSidebar from "../components/ProfileSidebar.vue";
import BookmarkSidebar from "../components/BookmarkSidebar.vue";

const loading = ref(false);
const error = ref("");
const articles = ref([]);
const categories = ref([]);
const tags = ref([]);
const keywordInput = ref("");
let keywordTimer = null;

const pagination = reactive({
  page: 1,
  pageSize: 6,
  total: 0,
  totalPages: 0
});

const query = reactive({
  category: "",
  tag: "",
  keyword: ""
});

watch(keywordInput, (value) => {
  if (keywordTimer) {
    clearTimeout(keywordTimer);
  }
  keywordTimer = setTimeout(() => {
    query.keyword = value.trim();
    pagination.page = 1;
    fetchArticles();
  }, 350);
});

async function fetchMeta() {
  const [categoryRes, tagRes] = await Promise.all([
    api.get("/categories"),
    api.get("/tags")
  ]);
  categories.value = categoryRes.data || [];
  tags.value = tagRes.data || [];
}

async function fetchArticles() {
  loading.value = true;
  error.value = "";

  try {
    const result = await api.get("/articles", {
      params: {
        page: pagination.page,
        pageSize: pagination.pageSize,
        category: query.category || undefined,
        tag: query.tag || undefined,
        keyword: query.keyword || undefined
      }
    });
    articles.value = result.data?.list || [];
    Object.assign(pagination, result.data?.pagination || {});
  } catch (err) {
    error.value = err.message;
  } finally {
    loading.value = false;
  }
}

function onCategoryChange(category) {
  query.category = category;
  query.tag = "";
  pagination.page = 1;
  fetchArticles();
}

function onTagChange(tag) {
  query.tag = tag;
  query.category = "";
  pagination.page = 1;
  fetchArticles();
}

function onPageChange(page) {
  pagination.page = page;
  fetchArticles();
}

onMounted(async () => {
  await fetchMeta();
  await fetchArticles();
});
</script>

<template>
  <main class="container home-page">
    <BlogHero v-model:keyword="keywordInput" />
    <CategoryTabs
      :categories="categories"
      :active-category="query.category"
      @change="onCategoryChange"
    />

    <section class="content-grid home-content-grid">
      <ProfileSidebar
        :article-count="pagination.total"
        :category-count="categories.length"
        :tag-count="tags.length"
        :tags="tags"
        :active-tag="query.tag"
        @tag-change="onTagChange"
      />
      <div class="home-article-stream">
        <p v-if="error" class="status status--error">{{ error }}</p>
        <p v-else-if="loading" class="status">加载中...</p>
        <template v-else>
          <div class="card-grid">
            <ArticleCard
              v-for="article in articles"
              :key="article.id"
              :article="article"
            />
          </div>
          <p v-if="!articles.length" class="status">没有找到文章。</p>
          <PaginationNav
            :current="pagination.page"
            :total-pages="pagination.totalPages"
            @change="onPageChange"
          />
        </template>
      </div>
      <BookmarkSidebar />
    </section>
  </main>
</template>

<style scoped>
.home-page { width: min(1480px, 96vw); }
.home-content-grid { grid-template-columns: 250px minmax(0, 1fr) 280px; align-items: start; }
.home-article-stream { min-width: 0; }
@media (max-width: 1200px) {
  .home-content-grid { grid-template-columns: 220px minmax(0, 1fr) 245px; }
  .home-article-stream .card-grid { grid-template-columns: 1fr; }
}
@media (max-width: 960px) {
  .home-content-grid { grid-template-columns: minmax(0, 1fr); }
  .home-page { width: min(1120px, 92vw); }
}
</style>
