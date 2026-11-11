<script setup>
import { onMounted, ref } from "vue";
import api from "../api/client";
import ProfileSidebar from "../components/ProfileSidebar.vue";
const articleCount = ref(0);
const categoryCount = ref(0);
const tagCount = ref(0);
const tags = ref([]);
onMounted(async () => {
  const results = await Promise.allSettled([
    api.get("/articles",{params:{page:1,pageSize:1}}),
    api.get("/categories"),
    api.get("/tags")
  ]);
  if(results[0].status==="fulfilled") articleCount.value=results[0].value.data?.pagination?.total || 0;
  if(results[1].status==="fulfilled") categoryCount.value=(results[1].value.data || []).length;
  if(results[2].status==="fulfilled"){
    tags.value=results[2].value.data || [];
    tagCount.value=tags.value.length;
  }
});
</script>
<template>
  <main class="container about-page">
    <router-link class="about-page__back" to="/">← 返回首页</router-link>
    <header class="about-page__hero">
      <p>ABOUT / WYNNE</p>
      <h1>你好，我是 Wynne<span>.</span></h1>
      <p class="about-page__subtitle">研究与实践并行。探索高性能 GPU 计算、大模型推理系统和可信计算。</p>
    </header>
    <div class="about-page__layout">
      <ProfileSidebar :article-count="articleCount" :category-count="categoryCount" :tag-count="tagCount" :tags="tags" />
      <div class="about-page__content">
        <h2>我在研究什么？</h2>
        <p>主要关注 GPU 并行计算、AI Infra、LLM 推理引擎以及多 GPU 加速。这里既是分享研究成果的地方，也是记录工程实践、技术权衡与实验发现的长期笔记本。</p>
        <h2>研究与工程</h2>
        <p>从 GPU Kernel 和性能分析，到推理系统中的 KV Cache、CUDA Graph，再到多 GPU 零知识证明加速，我希望把复杂的系统设计讲得清楚、扎实，并留下可复用的技术材料。</p>
        <div class="about-page__actions">
          <a href="https://github.com/wynneyin" target="_blank" rel="noopener noreferrer">GitHub ↗</a>
          <router-link to="/bookmarks">我收藏的技术工具 →</router-link>
        </div>
      </div>
    </div>
  </main>
</template>
<style scoped>
.about-page{width:min(1160px,92vw)}
.about-page__back{color:#5a44a6;font-size:13px;font-weight:700}
.about-page__hero{margin:35px 0 45px;padding:40px;border-radius:26px;background:#d6eefb}
.about-page__hero>p:first-child{font:800 12px "Space Grotesk",sans-serif;letter-spacing:.16em;color:#af3d7b}
.about-page__hero h1{font-size:clamp(36px,5vw,60px);margin:14px 0;letter-spacing:-.05em;color:#25223e}
.about-page__hero h1 span{color:#b94283}
.about-page__subtitle{margin:0;color:#53637f;line-height:1.8}
.about-page__layout{display:grid;grid-template-columns:300px minmax(0,1fr);gap:40px;align-items:start}
.about-page__content{padding:14px 0;max-width:68ch;color:#4d5068;font-size:15px;line-height:2}
.about-page__content h2{color:#28223f;font-size:24px;margin:0 0 12px}
.about-page__content h2:not(:first-child){margin-top:30px}
.about-page__actions{display:flex;gap:15px;flex-wrap:wrap;margin-top:35px}
.about-page__actions a{background:#f1edfa;color:#563fa3;border-radius:10px;padding:10px 15px;font-size:13px;font-weight:800}
@media(max-width:800px){.about-page__layout{grid-template-columns:1fr}.about-page__hero{padding:26px}.about-page__content{padding:0}}
</style>