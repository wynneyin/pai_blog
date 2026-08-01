<script setup>
import dayjs from "dayjs";

defineProps({
  article: {
    type: Object,
    required: true
  }
});
</script>

<template>
  <article class="card">
    <div class="card__meta">
      <span class="card__category">{{ article.category }}</span>
      <time>{{ dayjs(article.createdAt).format("YYYY-MM-DD") }}</time>
    </div>
    <h2>
      <router-link :to="`/article/${article.slug}`">{{ article.title }}</router-link>
    </h2>
    <p>{{ article.summary }}</p>
    <div class="card__footer">
      <span class="card__author">{{ article.author }}</span>
      <div class="card__tags">
        <span v-for="tag in article.tags" :key="tag">#{{ tag }}</span>
      </div>
    </div>
  </article>
</template>
