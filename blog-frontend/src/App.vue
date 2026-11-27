<script setup>
import { computed } from "vue";
import { useRoute } from "vue-router";
import SiteHeader from "./components/SiteHeader.vue";
import RelayHeader from "./components/relay/RelayHeader.vue";
import RelayFooter from "./components/relay/RelayFooter.vue";

const route = useRoute();

const isRelay = computed(
  () => route.path === "/relay" || route.path.startsWith("/relay/")
);

const showRelayFooter = computed(
  () => isRelay.value && !route.meta.hideFooter
);
</script>

<template>
  <div
    class="app-shell"
    :class="{ 'relay-shell': isRelay }"
  >
    <RelayHeader v-if="isRelay && !route.meta.hideHeader" />
    <SiteHeader v-else-if="!isRelay" />
    <router-view />
    <RelayFooter v-if="showRelayFooter" />
  </div>
</template>
