<script setup>
import dayjs from "dayjs";
import { computed, onMounted, ref, watch } from "vue";
import { useRoute } from "vue-router";
import api from "../api/client";

const route = useRoute();
const loading = ref(false);
const error = ref("");
const article = ref(null);
const tocItems = ref([]);
const relatedArticles = ref([]);
const relatedTags = ref([]);
const hasToc = computed(() => tocItems.value.length > 0);
const hasRelated = computed(() => relatedArticles.value.length > 0);

function slugify(text) {
  return text
    .toLowerCase()
    .trim()
    .replace(/[^\w\u4e00-\u9fa5\s-]/g, "")
    .replace(/\s+/g, "-")
    .replace(/-+/g, "-");
}

function buildTocAndContent(html) {
  if (!html) {
    return { content: "", toc: [] };
  }

  const parser = new DOMParser();
  const doc = parser.parseFromString(html, "text/html");
  const headings = Array.from(doc.querySelectorAll("h2, h3, h4"));
  const idCount = new Map();
  const toc = [];

  headings.forEach((heading) => {
    const text = heading.textContent?.trim();
    if (!text) return;

    const baseId = slugify(text) || "section";
    const next = (idCount.get(baseId) || 0) + 1;
    idCount.set(baseId, next);
    const id = next === 1 ? baseId : `${baseId}-${next}`;

    heading.id = id;
    toc.push({
      id,
      text,
      level: Number(heading.tagName.slice(1)),
    });
  });

  return { content: doc.body.innerHTML, toc };
}

function countCommonTags(articleItem, tagSet) {
  if (!Array.isArray(articleItem.tags)) {
    return 0;
  }
  return articleItem.tags.reduce(
    (count, tag) => (tagSet.has(tag) ? count + 1 : count),
    0
  );
}

async function fetchRelatedArticles(currentArticle) {
  const tags = Array.isArray(currentArticle.tags)
    ? currentArticle.tags.filter(Boolean)
    : [];
  relatedTags.value = tags;

  if (!tags.length) {
    relatedArticles.value = [];
    return;
  }

  const tagSet = new Set(tags);
  const requests = tags.slice(0, 3).map((tag) =>
    api
      .get("/articles", {
        params: {
          tag,
          page: 1,
          pageSize: 8,
        },
      })
      .catch(() => null)
  );

  const responses = await Promise.all(requests);
  const articleMap = new Map();

  responses.forEach((res) => {
    const list = res?.data?.list || [];
    list.forEach((item) => {
      if (!item?.slug || item.slug === currentArticle.slug) return;
      if (!articleMap.has(item.slug)) {
        articleMap.set(item.slug, item);
      }
    });
  });

  relatedArticles.value = Array.from(articleMap.values())
    .sort((a, b) => countCommonTags(b, tagSet) - countCommonTags(a, tagSet))
    .slice(0, 6);
}

async function fetchArticle(slug) {
  loading.value = true;
  error.value = "";
  relatedArticles.value = [];
  relatedTags.value = [];
  try {
    const result = await api.get(`/articles/${slug}`);
    const parsed = buildTocAndContent(result.data.content);
    article.value = {
      ...result.data,
      content: parsed.content,
    };
    tocItems.value = parsed.toc;
    await fetchRelatedArticles(result.data);
  } catch (err) {
    error.value = err.message;
    tocItems.value = [];
    relatedArticles.value = [];
    relatedTags.value = [];
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

    <div
      v-else-if="article"
      :class="[
        'article-layout',
        hasToc && hasRelated && 'article-layout--toc-related',
        hasToc && !hasRelated && 'article-layout--toc',
        !hasToc && hasRelated && 'article-layout--related'
      ]"
    >
      <aside v-if="tocItems.length" class="article-toc">
        <h2>目录</h2>
        <nav>
          <a
            v-for="item in tocItems"
            :key="item.id"
            :href="`#${item.id}`"
            :class="`article-toc__item article-toc__item--h${item.level}`"
          >
            {{ item.text }}
          </a>
        </nav>
      </aside>

      <article class="article">
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

      <aside v-if="relatedArticles.length" class="article-related">
        <h2>相关推荐</h2>
        <div class="article-related__tags">
          <span v-for="tag in relatedTags" :key="tag">#{{ tag }}</span>
        </div>
        <nav class="article-related__list">
          <router-link
            v-for="item in relatedArticles"
            :key="item.slug"
            :to="`/article/${item.slug}`"
            class="article-related__item"
          >
            <p>{{ item.title }}</p>
            <small>{{ dayjs(item.createdAt).format("YYYY-MM-DD") }}</small>
          </router-link>
        </nav>
      </aside>
    </div>
  </main>
</template>
