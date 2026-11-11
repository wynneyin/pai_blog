<script setup>
import dayjs from "dayjs";
defineProps({ article: { type: Object, required: true } });
</script>
<template>
  <article class="journal-entry">
    <div class="journal-entry__meta">
      <span class="journal-entry__category">{{ article.category || "工程笔记" }}</span>
      <span aria-hidden="true">·</span>
      <time :datetime="article.createdAt">{{ dayjs(article.createdAt).format("YYYY 年 MM 月 DD 日") }}</time>
    </div>
    <h3 class="journal-entry__title"><router-link :to="'/article/' + article.slug">{{ article.title }}</router-link></h3>
    <p v-if="article.summary" class="journal-entry__excerpt">{{ article.summary }}</p>
    <div class="journal-entry__footer">
      <router-link class="journal-entry__read" :to="'/article/' + article.slug">阅读文章 <span aria-hidden="true">→</span></router-link>
      <div v-if="article.tags?.length" class="journal-entry__tags"><span v-for="tag in article.tags.slice(0, 3)" :key="tag">#{{ tag }}</span></div>
    </div>
  </article>
</template>
<style scoped>
.journal-entry{padding:29px 0 34px;border-bottom:1px solid #e8e9f1}
.journal-entry:first-child{padding-top:10px}
.journal-entry__meta{display:flex;flex-wrap:wrap;gap:10px;align-items:center;color:#85869a;font:600 11px/1.6 "DM Sans","Noto Sans SC",sans-serif}
.journal-entry__category{color:#b83f7e;font-weight:800;letter-spacing:.04em}
.journal-entry__title{margin:10px 0 12px;font-size:clamp(23px,2.1vw,31px);font-weight:800;letter-spacing:-.035em;line-height:1.35;color:#25223e}
.journal-entry__title a:hover{color:#6345bc}
.journal-entry__excerpt{display:-webkit-box;-webkit-line-clamp:3;-webkit-box-orient:vertical;overflow:hidden;color:#555b70;font-size:14.5px;line-height:1.95;max-width:68ch;margin:0 0 18px}
.journal-entry__footer{display:flex;align-items:center;justify-content:space-between;gap:12px;flex-wrap:wrap}
.journal-entry__read{font:800 12px/1.5 "DM Sans","Noto Sans SC",sans-serif;color:#5039a2}
.journal-entry__read span{display:inline-block;margin-left:5px;transition:transform .18s}
.journal-entry__read:hover span{transform:translateX(4px)}
.journal-entry__tags{display:flex;gap:6px;flex-wrap:wrap}
.journal-entry__tags span{color:#77758e;background:#f3f2fa;font-size:10px;border-radius:5px;padding:4px 8px}
.journal-entry a:focus-visible{outline:3px solid #9881da;outline-offset:3px}
@media(max-width:600px){.journal-entry{padding:23px 0 28px}.journal-entry__title{font-size:24px}}
@media(prefers-reduced-motion:reduce){.journal-entry__read span{transition:none}}
</style>