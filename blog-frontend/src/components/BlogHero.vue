<script setup>
defineProps({
  keyword: {
    type: String,
    default: ""
  }
});

const emit = defineEmits(["update:keyword"]);
</script>

<template>
  <header class="hero">
    <h1 class="hero__title">生活和技术</h1>
    <div class="hero__search">
      <input
        :value="keyword"
        type="search"
        placeholder="搜索文章标题或摘要"
        @input="emit('update:keyword', $event.target.value)"
      />
    </div>
  </header>
</template>
