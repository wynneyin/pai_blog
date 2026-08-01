<script setup>
defineProps({
  categories: {
    type: Array,
    default: () => []
  },
  activeCategory: {
    type: String,
    default: ""
  }
});

const emit = defineEmits(["change"]);
</script>

<template>
  <div class="tabs">
    <button
      class="tabs__item"
      :class="{ 'tabs__item--active': !activeCategory }"
      @click="emit('change', '')"
    >
      全部
    </button>
    <button
      v-for="item in categories"
      :key="item.name"
      class="tabs__item"
      :class="{ 'tabs__item--active': activeCategory === item.name }"
      @click="emit('change', item.name)"
    >
      {{ item.name }} ({{ item.count }})
    </button>
  </div>
</template>
