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
