<script setup>
import { onMounted, onUnmounted, reactive, ref, watch } from "vue";
import api from "../api/client";
import BlogHero from "../components/BlogHero.vue";
import CategoryTabs from "../components/CategoryTabs.vue";
import ArticleCard from "../components/ArticleCard.vue";
import PaginationNav from "../components/PaginationNav.vue";
import TagCloud from "../components/TagCloud.vue";
import BookmarkSidebar from "../components/BookmarkSidebar.vue";

const loading = ref(false);
const error = ref("");
const articles = ref([]);
const categories = ref([]);
const tags = ref([]);
const keywordInput = ref("");
let keywordTimer = null;
let latestRequest = 0;

const pagination = reactive({ page:1, pageSize:6, total:0, totalPages:0 });
const query = reactive({ category:"", tag:"", keyword:"" });

watch(keywordInput, value => {
  if (keywordTimer) clearTimeout(keywordTimer);
  keywordTimer = setTimeout(() => {
    query.keyword = value.trim();
    pagination.page = 1;
    fetchArticles();
  }, 350);
});

async function fetchMeta() {
  try {
    const [categoryRes, tagRes] = await Promise.all([api.get("/categories"),api.get("/tags")]);
    categories.value = categoryRes.data || [];
    tags.value = tagRes.data || [];
  } catch {
    categories.value = [];
    tags.value = [];
  }
}

async function fetchArticles() {
  const request = ++latestRequest;
  loading.value = true;
  error.value = "";
  try {
    const result = await api.get("/articles", { params:{
      page:pagination.page, pageSize:pagination.pageSize,
      category:query.category || undefined,
      tag:query.tag || undefined,
      keyword:query.keyword || undefined
    }});
    if (request !== latestRequest) return;
    articles.value = result.data?.list || [];
    Object.assign(pagination,result.data?.pagination || {});
  } catch (err) {
    if (request !== latestRequest) return;
    error.value = err.message || "文章暂时无法加载";
  } finally {
    if (request === latestRequest) loading.value = false;
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
function onPageChange(page) { pagination.page = page; fetchArticles(); }

onMounted(() => { fetchMeta(); fetchArticles(); });
onUnmounted(() => { if(keywordTimer) clearTimeout(keywordTimer); });
</script>

<template>
  <div class="home-page">
    <BlogHero v-model:keyword="keywordInput" />
    <main class="container home-page__main">
      <div class="home-layout">
        <section id="articles" class="home-journal" aria-labelledby="articles-title">
          <div class="home-journal__heading">
            <p class="home-kicker">ARTICLES & TUTORIALS</p>
            <h2 id="articles-title">文章与技术笔记<span>.</span></h2>
            <p>深入理解系统原理，也记录真实的工程实践。</p>
          </div>
          <p v-if="error" role="alert" class="status status--error">{{ error }}</p>
          <p v-else-if="loading" role="status" class="status">正在载入文章…</p>
          <template v-else>
            <div class="home-journal__list">
              <ArticleCard v-for="article in articles" :key="article.id || article.slug" :article="article" />
            </div>
            <p v-if="!articles.length" class="status">还没有找到匹配的文章，试试其他关键词或分类。</p>
            <PaginationNav :current="pagination.page" :total-pages="pagination.totalPages" @change="onPageChange" />
          </template>
        </section>

        <aside class="home-rail" aria-label="文章导航与网站收藏">
          <section class="home-rail__section" aria-labelledby="categories-heading">
            <p class="home-kicker">EXPLORE</p>
            <h2 id="categories-heading">按主题浏览<span>.</span></h2>
            <CategoryTabs :categories="categories" :active-category="query.category" @change="onCategoryChange" />
          </section>
          <BookmarkSidebar />
          <div v-if="tags.length" class="home-rail__tags">
            <TagCloud :tags="tags" :active-tag="query.tag" @change="onTagChange" />
          </div>
        </aside>
      </div>
      <footer class="home-outro">
        <span>Learning in public. Building things that matter.</span>
        <router-link to="/about">更多关于我 <span aria-hidden="true">↗</span></router-link>
      </footer>
    </main>
  </div>
</template>

<style scoped>
.home-page__main { width:min(1190px,92vw); padding-top:42px; padding-bottom:85px; }
.home-layout { display:grid; grid-template-columns:minmax(0,1fr) 290px; align-items:start; gap:clamp(40px,5vw,78px); }
.home-journal { min-width:0; scroll-margin-top:35px; }
.home-kicker { margin:0 0 12px; color:#ba3b78; font:800 11px/1.5 "Space Grotesk",sans-serif; letter-spacing:.14em; }
.home-journal__heading { padding:0 0 20px; border-bottom:1px solid #e8e9f0; }
.home-journal__heading h2 { margin:0 0 10px; color:#25213e; font-size:clamp(30px,3.1vw,42px); font-weight:800; letter-spacing:-.05em; line-height:1.35; }
.home-journal__heading h2 span,.home-rail__section h2 span { color:#bb447f; }
.home-journal__heading > p:last-child { font-size:14px; color:#79788a; line-height:1.7; margin:0; }
.home-journal__list { display:flex; flex-direction:column; }
.home-rail { display:flex; flex-direction:column; gap:30px; min-width:0; }
.home-rail__section { padding:0 0 26px; border-bottom:1px solid #e9e9f0; }
.home-rail__section h2 { margin:0 0 17px; color:#28233f; font-size:21px; font-weight:800; letter-spacing:-.025em; }
.home-rail__tags :deep(.tags) { padding:16px 0; background:transparent; border:0; box-shadow:none; border-top:1px solid #e8e9f0; border-radius:0; }
.home-rail__tags :deep(.tags h3) { color:#272442; font-size:17px; }
.home-outro { margin-top:55px; padding-top:25px; border-top:1px solid #e9e9f0; display:flex; justify-content:space-between; gap:20px; flex-wrap:wrap; color:#79788a; font:500 12px/1.6 "DM Sans","Noto Sans SC",sans-serif; }
.home-outro a { color:#543eae; font-weight:800; }
@media(max-width:860px) { .home-layout { grid-template-columns:1fr; gap:45px; } .home-page__main { padding-top:20px; } .home-rail { display:grid; grid-template-columns:minmax(0,1fr); } }
</style>