<script setup>
defineProps({ categories:{type:Array,default:()=>[]}, activeCategory:{type:String,default:""} });
const emit = defineEmits(["change"]);
</script>
<template>
  <div class="topic-filters" role="group" aria-label="按文章分类筛选">
    <button type="button" class="topic-filters__item" :class="{ 'topic-filters__item--active': !activeCategory }" :aria-pressed="!activeCategory" @click="emit('change','')">全部文章</button>
    <button v-for="item in categories" :key="item.name" type="button" class="topic-filters__item" :class="{ 'topic-filters__item--active': activeCategory === item.name }" :aria-pressed="activeCategory === item.name" @click="emit('change',item.name)">
      {{ item.name }} <span>{{ item.count }}</span>
    </button>
  </div>
</template>
<style scoped>
.topic-filters{display:flex;flex-wrap:wrap;gap:8px}
.topic-filters__item{cursor:pointer;border:1px solid #e6e5ef;border-radius:7px;background:#f5f4fa;color:#615c78;padding:7px 10px;font:700 12px/1.4 "DM Sans","Noto Sans SC",sans-serif;transition:background .15s,border-color .15s}
.topic-filters__item span{margin-left:4px;color:#8d8c9d;font-size:10px}
.topic-filters__item:hover{border-color:#9f8edb;background:#f0eafb}
.topic-filters__item--active{background:#483e9e;color:#fff;border-color:#483e9e}
.topic-filters__item--active span{color:#d7d0ff}
.topic-filters__item:focus-visible{outline:3px solid #a088df;outline-offset:2px}
@media(prefers-reduced-motion:reduce){.topic-filters__item{transition:none}}
</style>