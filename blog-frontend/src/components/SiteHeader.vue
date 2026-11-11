<script setup>
import { ref, watch } from "vue";
import { useRoute } from "vue-router";
const route = useRoute();
const menuOpen = ref(false);
watch(() => route.fullPath, () => { menuOpen.value = false; });
</script>
<template>
  <header class="site-header">
    <div class="site-header__inner">
      <router-link to="/" class="site-header__brand" aria-label="Wynne，返回首页">
        <span class="site-header__mark">W<span>.</span></span>
        <span>Wynne <small>/ engineering notes</small></span>
      </router-link>
      <button class="site-header__menu" type="button" aria-controls="site-navigation" :aria-expanded="menuOpen" aria-label="切换导航菜单" @click="menuOpen = !menuOpen"><span></span><span></span><span></span></button>
      <nav id="site-navigation" class="site-header__nav" :class="{ 'site-header__nav--open': menuOpen }" aria-label="主导航">
        <router-link to="/" class="site-header__link" exact-active-class="site-header__link--active">首页</router-link>
        <router-link :to="{ path: '/', hash: '#articles' }" class="site-header__link">文章</router-link>
        <router-link to="/bookmarks" class="site-header__link" active-class="site-header__link--active">收藏</router-link>
        <router-link to="/about" class="site-header__link" active-class="site-header__link--active">关于</router-link>
        <a class="site-header__github" href="https://github.com/wynneyin" target="_blank" rel="noopener noreferrer">GitHub <span aria-hidden="true">↗</span></a>
      </nav>
    </div>
  </header>
</template>
<style scoped>
.site-header { background:#a8daf2; color:#1a3452; position:relative; z-index:20; }
.site-header__inner { width:min(1216px,92vw); min-height:76px; margin:auto; display:flex; justify-content:space-between; align-items:center; gap:20px; }
.site-header__brand { display:flex; align-items:center; gap:10px; font:800 17px/1 "Space Grotesk",sans-serif; letter-spacing:-.04em; white-space:nowrap; }
.site-header__brand small { font:500 12px/1.4 "DM Sans",sans-serif; letter-spacing:0; color:#4d6c84; }
.site-header__mark { width:37px; height:37px; display:grid; place-items:center; background:#24387e; color:white; border-radius:11px; font-size:19px; box-shadow:0 4px 0 #78a8d3; }
.site-header__mark span { color:#ffa2c1; }
.site-header__nav { display:flex; align-items:center; gap:29px; font:700 13px/1.5 "DM Sans","Noto Sans SC",sans-serif; }
.site-header__link { padding:8px 0; color:#315470; transition:color .18s; }
.site-header__link:hover,.site-header__link--active { color:#44339c; }
.site-header__link--active { box-shadow:inset 0 -2px #7250b8; }
.site-header__github { color:white; background:#293f9b; padding:10px 16px; border-radius:10px; box-shadow:0 3px 0 rgba(32,54,124,.18); }
.site-header__github:hover { background:#21327d; }
.site-header__menu { display:none; }
@media(max-width:700px) {
 .site-header__inner { min-height:68px; }
 .site-header__brand small { display:none; }
 .site-header__menu { display:flex; flex-direction:column; gap:4px; padding:10px; background:rgba(255,255,255,.55); border:1px solid #85b6d4; border-radius:9px; cursor:pointer; }
 .site-header__menu span { width:20px; height:2px; background:#263b80; }
 .site-header__nav { display:none; position:absolute; top:62px; right:4vw; width:min(280px,92vw); padding:14px; flex-direction:column; align-items:stretch; gap:4px; background:white; border:1px solid #dde5ef; border-radius:14px; box-shadow:0 16px 48px rgba(35,57,82,.2); }
 .site-header__nav--open { display:flex; }
 .site-header__link { padding:10px 12px; }
 .site-header__link--active { box-shadow:none; background:#f0effb; border-radius:7px; }
 .site-header__github { text-align:center; margin-top:7px; }
}
@media(prefers-reduced-motion:reduce){.site-header__link{transition:none}}
</style>