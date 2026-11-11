<script setup>
import { bookmarks } from "../data/bookmarks";

defineProps({
  embedded: {
    type: Boolean,
    default: false
  }
});
</script>

<template>
  <section
    class="bookmark-sidebar"
    :class="{ 'bookmark-sidebar--embedded': embedded }"
    aria-label="网站收藏"
  >
    <header class="bookmark-sidebar__header">
      <div>
        <p class="bookmark-sidebar__eyebrow">CURATED LINKS</p>
        <h2>网站收藏</h2>
      </div>
      <span class="bookmark-sidebar__count" :aria-label="`共 ${bookmarks.length} 个收藏`">{{ bookmarks.length }}</span>
    </header>
    <p class="bookmark-sidebar__intro">常用工具、技术文档与阅读发现</p>

    <nav class="bookmark-sidebar__list" aria-label="收藏网站列表">
      <a
        v-for="site in bookmarks"
        :key="site.url"
        :href="site.url"
        :title="site.description"
        :aria-label="`${site.name}（${site.category}，在新标签页打开）`"
        target="_blank"
        rel="noopener noreferrer"
        class="bookmark-sidebar__item"
      >
        <span class="bookmark-sidebar__mark" :style="{ '--bookmark-color': site.color }" aria-hidden="true">
          {{ site.mark }}
        </span>
        <span class="bookmark-sidebar__meta">
          <strong>{{ site.name }}</strong>
          <small>{{ site.category }}</small>
        </span>
        <span class="bookmark-sidebar__arrow" aria-hidden="true">↗</span>
      </a>
    </nav>
  </section>
</template>

<style scoped>
.bookmark-sidebar {
  position: sticky;
  top: 24px;
  max-height: calc(100vh - 48px);
  overflow-y: auto;
  min-width: 0;
  padding: 18px;
  border: 1px solid var(--line);
  border-radius: 18px;
  background: rgba(255, 253, 250, 0.92);
  box-shadow: var(--shadow);
  scrollbar-width: thin;
}
.bookmark-sidebar__header { display: flex; justify-content: space-between; align-items: center; gap: 12px; }
.bookmark-sidebar__eyebrow { margin: 0 0 6px; color: var(--brand-2); font: 10px/1.4 "Space Grotesk", sans-serif; letter-spacing: 0.13em; }
.bookmark-sidebar__header h2 { margin: 0; font-size: 19px; }
.bookmark-sidebar__count { color: var(--brand-2); font: 600 18px/1 "Space Grotesk", sans-serif; }
.bookmark-sidebar__intro { color: var(--muted); font-size: 12px; line-height: 1.7; margin: 10px 0 16px; }
.bookmark-sidebar__list { display: flex; flex-direction: column; gap: 8px; }
.bookmark-sidebar__item {
  display: flex;
  align-items: center;
  gap: 9px;
  padding: 10px;
  border: 1px solid var(--line);
  border-radius: 12px;
  background: #fff;
  transition: border-color .2s, background-color .2s;
}
.bookmark-sidebar__item:hover { border-color: var(--brand-2); background: rgba(15, 118, 110, .04); }
.bookmark-sidebar__item:focus-visible { outline: 2px solid var(--brand-2); outline-offset: 2px; }
.bookmark-sidebar__mark {
  flex: 0 0 35px;
  display: grid;
  place-items: center;
  min-height: 35px;
  border-radius: 9px;
  background: var(--bookmark-color);
  color: white;
  font: 600 10px/1 "Space Grotesk", sans-serif;
}
.bookmark-sidebar__meta { flex: 1; min-width: 0; }
.bookmark-sidebar__meta strong {
  display: block;
  font-size: 12.5px;
  line-height: 1.5;
  overflow-wrap: anywhere;
}
.bookmark-sidebar__meta small { display: block; margin-top: 2px; color: var(--muted); font-size: 11px; }
.bookmark-sidebar__arrow { align-self: flex-start; color: var(--brand-2); font-size: 13px; }
.bookmark-sidebar--embedded {
  position: static;
  max-height: none;
  overflow: visible;
  padding: 0;
  border: 0;
  border-radius: 0;
  background: transparent;
  box-shadow: none;
}
@media (max-width: 960px) {
  .bookmark-sidebar { position: static; max-height: none; overflow: visible; }
}
</style>
