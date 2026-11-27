<script setup>
import { computed, onBeforeUnmount, onMounted, ref, watch } from "vue";
import { useRoute } from "vue-router";
import { fetchRelayCatalog, loadRelaySession } from "../../api/relay";

const route = useRoute();
const menuOpen = ref(false);
const session = ref(null);
const scrolled = ref(false);
const catalog = ref(null);
const noticesOpen = ref(false);

const hasToken = computed(() => Boolean(session.value?.token));
const accountEmail = computed(() => String(session.value?.user?.email || ""));
const accountInitial = computed(() =>
  accountEmail.value ? accountEmail.value[0].toUpperCase() : "U"
);
const notices = computed(() =>
  Array.isArray(catalog.value?.notices) ? catalog.value.notices : []
);
const modelCount = computed(() =>
  Array.isArray(catalog.value?.models) ? catalog.value.models.length : 0
);

function refreshSession() {
  session.value = loadRelaySession();
}

function onScroll() {
  scrolled.value = window.scrollY > 12;
}

function onDocumentClick(event) {
  if (noticesOpen.value && !event.target.closest(".relay-notices")) {
    noticesOpen.value = false;
  }
}

function onKeydown(event) {
  if (event.key === "Escape") noticesOpen.value = false;
}

onMounted(() => {
  refreshSession();
  onScroll();
  window.addEventListener("scroll", onScroll, { passive: true });
  document.addEventListener("click", onDocumentClick);
  document.addEventListener("keydown", onKeydown);
  fetchRelayCatalog()
    .then((data) => {
      catalog.value = data;
    })
    .catch(() => {
      catalog.value = null;
    });
});

onBeforeUnmount(() => {
  window.removeEventListener("scroll", onScroll);
  document.removeEventListener("click", onDocumentClick);
  document.removeEventListener("keydown", onKeydown);
});

watch(
  () => route.fullPath,
  () => {
    menuOpen.value = false;
    noticesOpen.value = false;
    refreshSession();
  }
);
</script>

<template>
  <header
    class="relay-header"
    :class="{ 'relay-header--scrolled': scrolled }"
  >
    <div class="relay-header__inner">
      <router-link to="/relay" class="relay-brand" aria-label="Wynne Relay 首页">
        <span class="relay-brand__mark" aria-hidden="true">
          <svg viewBox="0 0 24 24" fill="none">
            <path
              d="M12 2.8 20 7.4v9.2L12 21.2 4 16.6V7.4l8-4.6Z"
              stroke="#fff"
              stroke-width="1.5"
              stroke-linejoin="round"
            />
            <circle cx="12" cy="12" r="2.1" fill="#fff" />
            <path
              d="M12 6.4v3.4M12 14.2v3.4M7.9 9.7l3 1.6M13.1 12.7l3 1.6M16.1 9.7l-3 1.6M10.9 12.7l-3 1.6"
              stroke="#fff"
              stroke-width="1.15"
              stroke-linecap="round"
            />
          </svg>
        </span>
        <span>Wynne Relay</span>
      </router-link>

      <nav class="relay-nav" aria-label="中转站导航">
        <router-link
          to="/relay"
          class="relay-nav__link"
          exact-active-class="relay-nav__link--active"
          >主页</router-link
        >
        <router-link
          to="/relay/console"
          class="relay-nav__link"
          active-class="relay-nav__link--active"
          >控制台</router-link
        >
        <router-link
          to="/relay/models"
          class="relay-nav__link"
          active-class="relay-nav__link--active"
          >模型广场</router-link
        >
        <router-link
          to="/relay/docs"
          class="relay-nav__link"
          active-class="relay-nav__link--active"
          >文档</router-link
        >
        <a
          class="relay-nav__link"
          href="https://github.com/wynneyin"
          target="_blank"
          rel="noopener noreferrer"
        >
          商务合作
          <svg
            viewBox="0 0 24 24"
            fill="none"
            stroke="currentColor"
            stroke-width="2"
            stroke-linecap="round"
            stroke-linejoin="round"
            aria-hidden="true"
          >
            <path d="M7 17 17 7M9 7h8v8" />
          </svg>
        </a>
      </nav>

      <div class="relay-header__actions">
        <div
          v-if="notices.length"
          class="relay-notices"
          :class="{ 'relay-notices--open': noticesOpen }"
        >
          <button
            class="relay-icon"
            type="button"
            aria-label="站点公告"
            title="站点公告"
            :aria-expanded="noticesOpen"
            @click="noticesOpen = !noticesOpen"
          >
            <svg
              viewBox="0 0 24 24"
              fill="none"
              stroke="currentColor"
              stroke-width="1.7"
              stroke-linecap="round"
              stroke-linejoin="round"
              aria-hidden="true"
            >
              <path d="M18 8a6 6 0 0 0-12 0c0 7-3 9-3 9h18s-3-2-3-9" />
              <path d="M13.7 21a2 2 0 0 1-3.4 0" />
            </svg>
          </button>
          <div
            v-if="noticesOpen"
            class="relay-notices__panel"
            role="region"
            aria-label="站点公告"
          >
            <p class="relay-notices__title">站点公告</p>
            <ul>
              <li v-for="notice in notices" :key="notice">{{ notice }}</li>
            </ul>
          </div>
        </div>
        <router-link
          v-if="modelCount"
          to="/relay/models"
          class="relay-count"
          :title="`${modelCount} 个模型可用`"
        >
          <svg
            viewBox="0 0 24 24"
            fill="none"
            stroke="currentColor"
            stroke-width="1.7"
            stroke-linecap="round"
            stroke-linejoin="round"
            aria-hidden="true"
          >
            <rect x="3" y="4" width="18" height="7" rx="2" />
            <rect x="3" y="13" width="18" height="7" rx="2" />
            <path d="M7 7.5h.01M7 16.5h.01" />
          </svg>
          <span>{{ modelCount }}</span>
        </router-link>
        <router-link
          v-if="hasToken"
          to="/relay/console"
          class="relay-account"
          :title="accountEmail || '控制台'"
          aria-label="进入控制台"
        >
          {{ accountInitial }}
        </router-link>
        <router-link v-else to="/relay/sign-in" class="relay-pill">
          登录
        </router-link>
        <button
          class="relay-burger"
          :class="{ 'relay-burger--open': menuOpen }"
          type="button"
          aria-controls="relay-mobile-menu"
          :aria-expanded="menuOpen"
          aria-label="切换导航菜单"
          @click="menuOpen = !menuOpen"
        >
          <span></span>
          <span></span>
          <span></span>
        </button>
      </div>
    </div>

    <div v-if="menuOpen" id="relay-mobile-menu" class="relay-menu">
      <nav class="relay-menu__nav" aria-label="中转站移动导航">
        <router-link
          to="/relay"
          class="relay-menu__link"
          exact-active-class="relay-menu__link--active"
          >主页</router-link
        >
        <router-link
          to="/relay/console"
          class="relay-menu__link"
          active-class="relay-menu__link--active"
          >控制台</router-link
        >
        <router-link
          to="/relay/models"
          class="relay-menu__link"
          active-class="relay-menu__link--active"
          >模型广场</router-link
        >
        <router-link
          to="/relay/docs"
          class="relay-menu__link"
          active-class="relay-menu__link--active"
          >文档</router-link
        >
        <a
          class="relay-menu__link"
          href="https://github.com/wynneyin"
          target="_blank"
          rel="noopener noreferrer"
          >商务合作</a
        >
      </nav>
    </div>
  </header>
</template>
