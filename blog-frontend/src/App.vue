<script setup>
import { computed } from "vue";
import { useRoute } from "vue-router";
import SiteHeader from "./components/SiteHeader.vue";
import RelayHeader from "./components/relay/RelayHeader.vue";
import RelayFooter from "./components/relay/RelayFooter.vue";
import ShopHeader from "./components/shop/ShopHeader.vue";

const route = useRoute();

const isRelay = computed(
  () => route.path === "/relay" || route.path.startsWith("/relay/")
);
const isShop = computed(
  () => route.path === "/shop" || route.path.startsWith("/shop/")
);

const showRelayFooter = computed(
  () => isRelay.value && !route.meta.hideFooter
);
</script>

<template>
  <div
    class="app-shell"
    :class="{ 'relay-shell': isRelay, 'shop-shell': isShop }"
  >
    <ShopHeader v-if="isShop && !route.meta.hideHeader" />
    <RelayHeader v-else-if="isRelay && !route.meta.hideHeader" />
    <SiteHeader v-else-if="!isShop && !isRelay" />
    <router-view />
    <RelayFooter v-if="showRelayFooter" />
  </div>
</template>
