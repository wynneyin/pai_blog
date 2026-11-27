<script setup>
import { computed, onMounted, ref } from "vue";
import { useRoute, useRouter } from "vue-router";
import {
  clearRelaySession,
  fetchRelayMe,
  loadRelaySession,
  logoutRelay,
  saveRelaySession
} from "../../api/relay";

const route = useRoute();
const router = useRouter();

const session = ref(loadRelaySession());
const user = ref(session.value?.user || null);
const booting = ref(true);
const redirecting = ref(false);
const fatalError = ref("");
const alertMessage = ref("");
const loggingOut = ref(false);

const menu = [
  {
    label: "概览",
    to: "/relay/console",
    icon: "M3 3h7v7H3zM14 3h7v7h-7zM14 14h7v7h-7zM3 14h7v7H3z"
  },
  {
    label: "API 密钥",
    to: "/relay/console/keys",
    icon: "M21 2l-2 2m-7.61 7.61a5.5 5.5 0 1 1-7.778 7.778 5.5 5.5 0 0 1 7.777-7.777zm0 0L15.5 7.5m0 0l3 3L22 7l-3-3m-3.5 3.5L19 4"
  },
  {
    label: "钱包充值",
    to: "/relay/console/topup",
    icon: "M21 12V7H5a2 2 0 0 1 0-4h14v4M3 5v14a2 2 0 0 0 2 2h16v-5M18 12a2 2 0 0 0 0 4h4v-4Z"
  },
  {
    label: "使用日志",
    to: "/relay/console/logs",
    icon: "M8 6h13M8 12h13M8 18h13M3 6h.01M3 12h.01M3 18h.01"
  }
];

function isActive(to) {
  if (to === "/relay/console") {
    return route.path === "/relay/console" || route.path === "/relay/console/";
  }
  return route.path.startsWith(to);
}

function signInRedirect() {
  redirecting.value = true;
  booting.value = true;
  router.replace({
    path: "/relay/sign-in",
    query: { redirect: route.fullPath }
  });
}

function handleUnauthorized() {
  clearRelaySession();
  user.value = null;
  signInRedirect();
}

function storeSession(nextUser) {
  const token = session.value?.token || loadRelaySession()?.token || "";
  if (token) {
    session.value = saveRelaySession(token, nextUser);
  }
  user.value = nextUser;
}

async function refreshUser() {
  try {
    const data = await fetchRelayMe();
    if (data?.user) storeSession(data.user);
    return data?.user || null;
  } catch (e) {
    if (e?.status === 401) {
      handleUnauthorized();
    }
    throw e;
  }
}

async function boot() {
  const stored = loadRelaySession();
  session.value = stored;
  if (!stored?.token) {
    signInRedirect();
    return;
  }

  user.value = stored.user || null;
  try {
    const data = await fetchRelayMe();
    if (data?.user) {
      storeSession(data.user);
    } else if (!user.value) {
      fatalError.value = "账户信息加载失败，请稍后重试";
    }
  } catch (e) {
    if (e?.status === 401) {
      handleUnauthorized();
      return;
    }
    if (!user.value) {
      fatalError.value = e?.message || "账户信息加载失败，请稍后重试";
    } else {
      alertMessage.value = e?.message || "账户信息刷新失败";
    }
  } finally {
    if (!redirecting.value) booting.value = false;
  }
}

async function retry() {
  booting.value = true;
  fatalError.value = "";
  await boot();
}

async function doLogout() {
  loggingOut.value = true;
  try {
    await logoutRelay();
  } catch {
    /* 会话已失效时退出仍然成功 */
  }
  clearRelaySession();
  user.value = null;
  session.value = null;
  loggingOut.value = false;
  signInRedirect();
}

onMounted(boot);
</script>

<template>
  <div class="rc-page">
    <div v-if="booting" class="rc-gate">
      <span class="rc-spinner" aria-hidden="true"></span>
      <p>正在验证登录状态…</p>
    </div>

    <div v-else-if="fatalError || !user" class="rc-gate">
      <div class="rc-gate__card">
        <h1>无法进入控制台</h1>
        <p class="rc-alert" role="alert">{{ fatalError || "登录已失效，请重新登录" }}</p>
        <div class="rc-gate__actions">
          <button class="rc-btn rc-btn--primary" type="button" @click="retry">
            重试
          </button>
          <button class="rc-btn" type="button" @click="signInRedirect">
            返回登录
          </button>
        </div>
      </div>
    </div>

    <div v-else class="rc-wrap">
      <aside class="rc-sidebar" aria-label="控制台导航">
        <nav class="rc-menu">
          <span class="rc-group">常规</span>
          <router-link
            v-for="item in menu"
            :key="item.to"
            :to="item.to"
            class="rc-link"
            :class="{ 'rc-link--active': isActive(item.to) }"
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
              <path :d="item.icon" />
            </svg>
            <span>{{ item.label }}</span>
          </router-link>

          <span class="rc-divider" aria-hidden="true" />
          <span class="rc-group">账户</span>

          <router-link to="/relay" class="rc-link">
            <svg
              viewBox="0 0 24 24"
              fill="none"
              stroke="currentColor"
              stroke-width="1.7"
              stroke-linecap="round"
              stroke-linejoin="round"
              aria-hidden="true"
            >
              <path d="M19 12H5M12 19l-7-7 7-7" />
            </svg>
            <span>返回官网</span>
          </router-link>

          <button
            class="rc-link rc-link--danger"
            type="button"
            :disabled="loggingOut"
            @click="doLogout"
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
              <path d="M9 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h4M16 17l5-5-5-5M21 12H9" />
            </svg>
            <span>{{ loggingOut ? "退出中…" : "退出登录" }}</span>
          </button>
        </nav>
      </aside>

      <main class="rc-main">
        <p v-if="alertMessage" class="rc-alert rc-alert--inline" role="status">
          {{ alertMessage }}
        </p>
        <router-view :user="user" :refresh-user="refreshUser" />
      </main>
    </div>
  </div>
</template>

<style scoped>
.rc-page {
  --primary: var(--r-primary, #3ea4ec);
  --primary-dark: var(--r-primary-dark, #2f92dd);
  --text: var(--r-text, #0a0a0a);
  --muted: var(--r-muted, #7e7f80);
  --line: #ececec;
  --soft: #fafafa;
  --danger: var(--r-danger, #f04438);
  --success: var(--r-success, #12b76a);
  --radius: 12px;
  min-height: 100vh;
  padding: 64px 0 0;
  background: #ffffff;
  color: var(--text);
  font-family: "Inter", "DM Sans", "Noto Sans SC", "PingFang SC", system-ui,
    sans-serif;
  -webkit-font-smoothing: antialiased;
}

.rc-page a {
  color: inherit;
}

.rc-page button {
  font-family: inherit;
}

.rc-wrap {
  width: 100%;
  margin: 0 auto;
  padding: 0;
  display: grid;
  grid-template-columns: 200px minmax(0, 1fr);
  gap: 0;
  align-items: stretch;
}

.rc-sidebar {
  position: sticky;
  top: 64px;
  align-self: start;
  height: calc(100vh - 64px);
  overflow-y: auto;
  padding: 14px 8px;
  border: 0;
  border-right: 1px solid #ececec;
  border-radius: 0;
  background: #ffffff;
  box-shadow: none;
}

.rc-group {
  display: block;
  padding: 14px 10px 6px;
  color: #98a2b3;
  font-size: 11px;
  font-weight: 600;
  letter-spacing: 0.04em;
}

.rc-menu {
  display: flex;
  flex-direction: column;
  gap: 1px;
}

.rc-link {
  display: flex;
  align-items: center;
  gap: 9px;
  width: 100%;
  padding: 8px 10px;
  border: none;
  border-radius: 8px;
  background: transparent;
  color: #44506b;
  font-size: 13.5px;
  font-weight: 500;
  text-align: left;
  white-space: nowrap;
  cursor: pointer;
  transition: background 0.16s, color 0.16s;
}

.rc-link svg {
  flex: none;
  width: 16px;
  height: 16px;
}

.rc-link:hover {
  background: var(--soft);
  color: var(--text);
}

.rc-link--active {
  background: #eaf4ff;
  color: var(--primary);
  font-weight: 500;
}

.rc-link--danger:hover {
  background: #fff1f0;
  color: var(--danger);
}

.rc-link:disabled {
  opacity: 0.6;
  cursor: not-allowed;
}

.rc-divider {
  display: block;
  height: 1px;
  margin: 10px 4px;
  background: var(--line);
}

.rc-main {
  min-width: 0;
  padding: 18px 24px 56px;
}

.rc-alert {
  margin: 0 0 16px;
  padding: 11px 14px;
  border: 1px solid #ffd4d0;
  border-radius: 10px;
  background: #fff4f2;
  color: #c8362c;
  font-size: 13px;
  line-height: 1.55;
}

.rc-alert--inline {
  margin-bottom: 16px;
}

.rc-gate {
  min-height: 60vh;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 14px;
  padding: 48px 16px;
  color: var(--muted);
  font-size: 14px;
}

.rc-gate p {
  margin: 0;
}

.rc-spinner {
  width: 26px;
  height: 26px;
  border: 3px solid #dbe6f7;
  border-top-color: var(--primary);
  border-radius: 50%;
  animation: rc-spin 0.8s linear infinite;
}

@keyframes rc-spin {
  to {
    transform: rotate(360deg);
  }
}

.rc-gate__card {
  width: min(420px, 100%);
  padding: 28px;
  border: 1px solid var(--line);
  border-radius: 16px;
  background: #ffffff;
  text-align: center;
  box-shadow: 0 12px 40px rgba(23, 58, 110, 0.08);
}

.rc-gate__card h1 {
  margin: 0 0 14px;
  font-size: 20px;
  letter-spacing: -0.02em;
}

.rc-gate__actions {
  display: flex;
  justify-content: center;
  gap: 10px;
}

.rc-btn {
  height: 38px;
  padding: 0 18px;
  border: 1px solid var(--line);
  border-radius: 10px;
  background: #ffffff;
  color: var(--text);
  font-size: 14px;
  font-weight: 600;
  cursor: pointer;
}

.rc-btn--primary {
  border-color: var(--primary);
  background: var(--primary);
  color: #ffffff;
}

.rc-btn:hover {
  border-color: #c9d7ea;
}

.rc-btn--primary:hover {
  border-color: var(--primary-dark);
  background: var(--primary-dark);
}

@media (max-width: 900px) {
  .rc-page {
    padding-top: 74px;
  }

  .rc-wrap {
    grid-template-columns: minmax(0, 1fr);
    gap: 10px;
    padding: 0 12px;
  }

  .rc-sidebar {
    position: sticky;
    top: 64px;
    z-index: 20;
    height: auto;
    overflow: visible;
    padding: 8px;
    border: 1px solid #ececec;
    border-radius: 12px;
    background: #ffffff;
  }

  .rc-group {
    display: none;
  }

  .rc-menu {
    flex-direction: row;
    align-items: center;
    gap: 4px;
    overflow-x: auto;
    margin: 0 -8px;
    padding: 2px 8px 4px;
    scroll-snap-type: x mandatory;
    scrollbar-width: none;
    mask-image: linear-gradient(90deg, #000 calc(100% - 24px), transparent);
  }

  .rc-menu::-webkit-scrollbar {
    display: none;
  }

  .rc-divider {
    display: none;
  }

  .rc-link {
    flex: none;
    width: auto;
    padding: 7px 11px;
    border-radius: 999px;
    font-size: 13px;
    scroll-snap-align: start;
  }

  .rc-link svg {
    display: none;
  }

  .rc-link[href="/relay"] {
    display: none;
  }

  .rc-main {
    padding: 12px 0 48px;
  }
}
</style>
