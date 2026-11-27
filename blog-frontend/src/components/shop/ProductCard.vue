<script setup>
import { computed, ref } from "vue";

const props = defineProps({
  product: { type: Object, required: true }
});

defineEmits(["buy"]);

const imageBroken = ref(false);

const available = computed(() => {
  const value = props.product.availableStock;
  return Number.isFinite(value) ? value : props.product.stock;
});

const soldOut = computed(() => available.value <= 0);

const stockLabel = computed(() => {
  if (soldOut.value) return "已售罄";
  return props.product.stockText || `可售 ${available.value} 件`;
});
</script>

<template>
  <article class="shop-card">
    <div v-if="product.cover && !imageBroken" class="shop-card__media">
      <img
        :src="product.cover"
        :alt="product.name"
        loading="lazy"
        @error="imageBroken = true"
      />
    </div>
    <span v-if="product.tag" class="shop-card__badge">{{ product.tag }}</span>
    <h3 class="shop-card__name" :title="product.name">{{ product.name }}</h3>
    <p v-if="product.subtitle" class="shop-card__sub">{{ product.subtitle }}</p>
    <div class="shop-card__price">
      <strong>{{ product.price }}</strong>
      <span v-if="product.origPrice" class="shop-card__orig">¥{{ product.origPrice }}</span>
    </div>
    <p class="shop-card__stock">
      {{ stockLabel }}<template v-if="product.sold"> · 已售 {{ product.sold }}</template>
    </p>
    <div class="shop-card__actions">
      <button
        class="shop-btn shop-btn--block"
        type="button"
        :disabled="soldOut"
        @click="$emit('buy', product)"
      >
        {{ soldOut ? "已售罄" : "立即购买" }}
      </button>
    </div>
  </article>
</template>
