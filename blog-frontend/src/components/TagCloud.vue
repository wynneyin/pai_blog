<script setup>
defineProps({
  tags: {
    type: Array,
    default: () => []
  },
  activeTag: {
    type: String,
    default: ""
  }
});

const emit = defineEmits(["change"]);
</script>

<template>
  <aside class="tags">
    <h3>热门标签</h3>
    <div class="tags__list">
      <button
        class="tags__item"
        :class="{ 'tags__item--active': !activeTag }"
        @click="emit('change', '')"
      >
        全部
      </button>
      <button
        v-for="tag in tags"
        :key="tag.name"
        class="tags__item"
        :class="{ 'tags__item--active': activeTag === tag.name }"
        @click="emit('change', tag.name)"
      >
        #{{ tag.name }} ({{ tag.count }})
      </button>
    </div>
  </aside>
</template>
