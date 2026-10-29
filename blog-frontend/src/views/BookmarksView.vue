<script setup>
import { computed, ref } from "vue";
import { bookmarks } from "../data/bookmarks";

const keyword = ref("");
const category = ref("全部");
const categories = ["全部", ...new Set(bookmarks.map(site => site.category))];
const filtered = computed(() => {
  const query = keyword.value.trim().toLocaleLowerCase();
  return bookmarks.filter(site =>
    (category.value === "全部" || site.category === category.value) &&
    [site.name, site.description, site.url, site.category].join(" ").toLocaleLowerCase().includes(query)
  );
});
const hostname = url => new URL(url).hostname.replace(/^www\./, "");
function reset() { keyword.value = ""; category.value = "全部"; }
</script>

<template>
  <main class="container bookmarks-page">
    <router-link to="/" class="article-page__back">← 返回首页</router-link>
    <header class="collection-intro">
      <div>
        <p class="collection-eyebrow">BOOKMARKS / 网络书签</p>
        <h1>值得反复打开的网站<span>。</span></h1>
        <p class="collection-description">把好用的工具、常读的文档和有趣的发现，收进同一个角落。</p>
      </div>
      <div class="collection-total"><strong>{{ bookmarks.length.toString().padStart(2, '0') }}</strong><span>个网站 · 持续收集</span></div>
    </header>

    <section class="collection-toolbar" aria-label="筛选收藏">
      <div class="collection-filters" role="group" aria-label="网站分类">
        <button v-for="item in categories" :key="item" type="button" :aria-pressed="category === item" :class="{ selected: category === item }" @click="category = item">{{ item }}</button>
      </div>
      <label class="collection-search"><span class="sr-only">搜索收藏网站</span><span aria-hidden="true">⌕</span><input v-model="keyword" type="search" placeholder="搜索名称、描述或网址…" /></label>
    </section>

    <p class="collection-caption" role="status" aria-live="polite">{{ category === '全部' ? '所有收藏' : category }} <span>/ {{ filtered.length }} 个网站</span></p>
    <div v-if="filtered.length" class="bookmark-grid">
      <a v-for="site in filtered" :key="site.url" :href="site.url" target="_blank" rel="noopener noreferrer" class="bookmark-card" :aria-label="`${site.name}（在新标签页打开）`">
        <div class="bookmark-card__top"><span class="bookmark-mark" :style="{ '--mark-color': site.color }">{{ site.mark }}</span><span class="bookmark-category">{{ site.category }}</span><span class="bookmark-arrow" aria-hidden="true">↗</span></div>
        <h2>{{ site.name }}</h2>
        <p>{{ site.description }}</p>
        <div class="bookmark-card__bottom"><span>{{ hostname(site.url) }}</span><span>访问网站 ↗</span></div>
      </a>
    </div>
    <div v-else class="collection-empty"><span aria-hidden="true">⌕</span><h2>还没有找到这个网站</h2><p>换个关键词，或看看其他分类。</p><button type="button" @click="reset">查看全部收藏</button></div>
    <footer class="collection-footer"><span>好网站，慢慢攒。</span><span>所有链接均在新标签页打开 ↗</span></footer>
  </main>
</template>

<style scoped>
.bookmarks-page { padding-top: 54px; }
.collection-intro { display: flex; justify-content: space-between; align-items: center; gap: 24px; padding-bottom: 38px; border-bottom: 1px solid var(--line); }
.collection-eyebrow { margin: 0 0 18px; font: 12px/1.5 sans-serif; letter-spacing: .16em; color: var(--brand-2); }
h1 { font-size: clamp(29px, 4vw, 44px); line-height: 1.35; margin: 0 0 16px; letter-spacing: -.04em; }
h1 span { color: var(--brand-2); }
.collection-description { color: var(--muted); margin: 0; font-size: 15px; line-height: 1.8; }
.collection-total { display: grid; text-align: right; flex-shrink: 0; gap: 8px; }
.collection-total strong { font: 52px/1 sans-serif; font-weight: 300; color: var(--brand-2); }
.collection-total span { color: var(--muted); font-size: 12px; }
.collection-toolbar { display: flex; align-items: center; justify-content: space-between; gap: 20px; padding: 28px 0 16px; flex-wrap: wrap; }
.collection-filters { display: flex; flex-wrap: wrap; gap: 8px; }
button, input { font: inherit; }
.collection-filters button { border: 1px solid transparent; border-radius: 999px; background: transparent; padding: 9px 15px; color: var(--muted); cursor: pointer; font-size: 13px; }
.collection-filters button:hover { background: rgba(255,255,255,.6); }
.collection-filters button.selected { color: white; background: var(--brand-2); }
.collection-search { display: flex; gap: 9px; align-items: center; border: 1px solid var(--line); border-radius: 12px; padding: 10px 13px; background: rgba(255,253,250,.8); color: var(--muted); }
.collection-search input { border: 0; background: transparent; min-width: 0; width: 210px; outline: 0; font-size: 13px; color: var(--ink); }
.collection-search:focus-within { outline: 2px solid var(--brand-2); outline-offset: 2px; }
.collection-caption { margin: 10px 0 18px; font-size: 13px; }
.collection-caption span { color: var(--muted); margin-left: 6px; }
.bookmark-grid { display: grid; grid-template-columns: repeat(3, minmax(0,1fr)); gap: 18px; }
.bookmark-card { display: flex; flex-direction: column; background: var(--card); border: 1px solid var(--line); border-radius: 18px; padding: 24px; transition: transform .2s, border-color .2s, box-shadow .2s; }
.bookmark-card:hover { transform: translateY(-4px); border-color: var(--brand-2); box-shadow: var(--shadow); }
.bookmark-card__top { display: flex; align-items: center; gap: 12px; }
.bookmark-mark { display: grid; place-items: center; width: 44px; height: 44px; border-radius: 12px; background: var(--mark-color); color: white; font: bold 18px sans-serif; }
.bookmark-category { font-size: 11px; color: var(--muted); }
.bookmark-arrow { margin-left: auto; color: var(--brand-2); font-size: 21px; }
.bookmark-card h2 { font-size: 21px; margin: 22px 0 10px; }
.bookmark-card p { color: var(--muted); font-size: 14px; line-height: 1.9; margin: 0 0 24px; flex: 1; }
.bookmark-card__bottom { display: flex; justify-content: space-between; gap: 8px; border-top: 1px solid var(--line); padding-top: 16px; font: 11px/1.5 sans-serif; color: var(--muted); overflow-wrap: anywhere; }
.bookmark-card__bottom span:last-child { color: var(--brand-2); white-space: nowrap; }
.collection-footer { display: flex; justify-content: space-between; flex-wrap: wrap; gap: 12px; margin-top: 34px; color: var(--muted); font-size: 12px; }
.collection-empty { text-align: center; padding: 48px 20px; border: 1px dashed var(--line); border-radius: 18px; background: rgba(255,253,250,.7); }
.collection-empty > span { font-size: 36px; color: var(--brand-2); }
.collection-empty h2 { font-size: 20px; }
.collection-empty p { color: var(--muted); }
.collection-empty button { border: 0; padding: 12px 18px; background: var(--brand-2); color: white; border-radius: 10px; cursor: pointer; }
a:focus-visible, button:focus-visible { outline: 2px solid var(--brand-2); outline-offset: 4px; }
.sr-only { position: absolute; width: 1px; height: 1px; padding: 0; overflow: hidden; clip: rect(0,0,0,0); white-space: nowrap; }
@media (max-width: 960px) { .bookmark-grid { grid-template-columns: repeat(2,minmax(0,1fr)); } }
@media (max-width: 600px) { .bookmarks-page { padding-top: 36px; } .collection-intro { align-items: flex-start; } .collection-total { display: none; } .bookmark-grid { grid-template-columns: 1fr; } .collection-search, .collection-search input { width: 100%; } .collection-filters button { padding: 8px 11px; } .collection-description { font-size: 14px; } }
@media (prefers-reduced-motion: reduce) { .bookmark-card { transition: none; } }
</style>
