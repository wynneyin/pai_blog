<script setup>
defineProps({
  keyword: {
    type: String,
    default: ""
  },
  total: {
    type: Number,
    default: 0
  }
});

const emit = defineEmits(["update:keyword"]);
</script>

<template>
  <header class="hero">
    <div class="hero__badge">Personal Notebook</div>
    <h1>生活、技术和创造记录</h1>
    <p>基于 Markdown 写作，内容更新后自动生效。</p>
    <div class="hero__search">
      <input
        :value="keyword"
        type="search"
        placeholder="搜索文章标题或摘要"
        @input="emit('update:keyword', $event.target.value)"
      />
      <span>{{ total }} 篇文章</span>
    </div>
  </header>
</template>
