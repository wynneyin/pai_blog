<script setup>
const props = defineProps({
  current: {
    type: Number,
    default: 1
  },
  totalPages: {
    type: Number,
    default: 0
  }
});

const emit = defineEmits(["change"]);

function goto(page) {
  if (page < 1 || page > props.totalPages || page === props.current) {
    return;
  }
  emit("change", page);
}
</script>

<template>
  <nav v-if="totalPages > 1" class="pager">
    <button @click="goto(current - 1)">上一页</button>
    <span>第 {{ current }} / {{ totalPages }} 页</span>
    <button @click="goto(current + 1)">下一页</button>
  </nav>
</template>
