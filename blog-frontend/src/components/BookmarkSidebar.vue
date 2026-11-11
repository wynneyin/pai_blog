<script setup>
import { bookmarks } from "../data/bookmarks";
defineProps({ embedded:{type:Boolean,default:false} });
</script>
<template>
  <section class="bookmark-rail" :class="{ 'bookmark-rail--embedded': embedded }" aria-labelledby="bookmark-heading">
    <header class="bookmark-rail__header">
      <div><p class="bookmark-rail__kicker">HANDPICKED LINKS</p><h2 id="bookmark-heading">网站收藏<span>.</span></h2></div>
      <span class="bookmark-rail__count" :aria-label="'共 ' + bookmarks.length + ' 个收藏'">{{ bookmarks.length }}</span>
    </header>
    <p class="bookmark-rail__intro">常用工具、技术文档与阅读发现。</p>
    <nav class="bookmark-rail__list" aria-label="全部收藏网站">
      <a v-for="site in bookmarks" :key="site.url" class="bookmark-rail__item" :href="site.url" :title="site.description" target="_blank" rel="noopener noreferrer">
        <span class="bookmark-rail__icon" :style="{ '--bookmark-color':site.color }" aria-hidden="true">{{ site.mark }}</span>
        <span class="bookmark-rail__info"><strong>{{ site.name }}</strong><small>{{ site.category }}</small></span>
        <span class="bookmark-rail__arrow" aria-hidden="true">↗</span>
      </a>
    </nav>
    <router-link to="/bookmarks" class="bookmark-rail__all">分类查看全部收藏 <span aria-hidden="true">→</span></router-link>
  </section>
</template>
<style scoped>
.bookmark-rail{min-width:0;position:relative}
.bookmark-rail__header{display:flex;align-items:center;justify-content:space-between;gap:10px}
.bookmark-rail__kicker{margin:0 0 11px;color:#bb3b7d;font:800 11px/1.4 "Space Grotesk",sans-serif;letter-spacing:.13em}
.bookmark-rail__header h2{font-size:21px;line-height:1.3;font-weight:800;letter-spacing:-.04em;color:#26223f;margin:0}
.bookmark-rail__header h2 span{color:#bb447f}
.bookmark-rail__count{font:800 17px "Space Grotesk",sans-serif;color:#5b46ac;background:#f1edfa;border-radius:8px;padding:3px 9px}
.bookmark-rail__intro{font-size:12px;color:#808093;line-height:1.75;margin:10px 0 14px}
.bookmark-rail__list{display:flex;flex-direction:column;max-height:475px;overflow-y:auto;scrollbar-width:thin;scrollbar-color:#d6d1e7 transparent}
.bookmark-rail__item{display:flex;align-items:center;gap:10px;min-height:55px;padding:9px 2px;border-bottom:1px solid #eeeef4;transition:background-color .17s}
.bookmark-rail__item:hover{background:#fbfaff}
.bookmark-rail__icon{flex:0 0 35px;width:35px;height:35px;display:grid;place-items:center;border-radius:10px;color:#fff;background:var(--bookmark-color);font:800 10px "Space Grotesk",sans-serif}
.bookmark-rail__info{min-width:0;flex:1}
.bookmark-rail__info strong{display:block;color:#38344f;font:700 12px/1.5 "DM Sans","Noto Sans SC",sans-serif;overflow-wrap:anywhere}
.bookmark-rail__info small{display:block;color:#8e8b9d;font-size:10px;margin-top:2px}
.bookmark-rail__arrow{color:#8676af;align-self:center;transition:transform .18s}
.bookmark-rail__item:hover .bookmark-rail__arrow{transform:translate(2px,-2px)}
.bookmark-rail__all{display:inline-flex;gap:7px;margin-top:14px;color:#5944af;font:800 12px/1.5 "DM Sans","Noto Sans SC",sans-serif}
.bookmark-rail a:focus-visible{outline:3px solid #a18ddd;outline-offset:2px;border-radius:4px}
.bookmark-rail--embedded .bookmark-rail__list{max-height:350px}
@media(prefers-reduced-motion:reduce){.bookmark-rail__arrow,.bookmark-rail__item{transition:none}}
</style>