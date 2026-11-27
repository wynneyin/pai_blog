<script setup>
import { computed, ref, watch } from "vue";
import { useRoute, useRouter } from "vue-router";
import {
  loginRelay,
  registerRelay,
  saveRelaySession
} from "../api/relay";

const EMAIL_RE = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;

const route = useRoute();
const router = useRouter();

const email = ref("");
const password = ref("");
const confirmPassword = ref("");
const error = ref("");
const submitting = ref(false);

const mode = computed(() => {
  const name = String(route.name || "").toLowerCase();
  if (
    name.includes("sign-up") ||
    name.includes("signup") ||
    name.includes("register")
  ) {
    return "signup";
  }
  return route.path.endsWith("sign-up") || route.path.endsWith("signup")
    ? "signup"
    : "signin";
});

const isSignUp = computed(() => mode.value === "signup");
const switchPath = computed(() => {
  const path = isSignUp.value ? "/relay/sign-in" : "/relay/sign-up";
  const redirect = route.query.redirect;
  return typeof redirect === "string" && redirect
    ? { path, query: { redirect } }
    : path;
});
const switchLead = computed(() => (isSignUp.value ? "已有账户？" : "没有账号？"));
const switchLabel = computed(() => (isSignUp.value ? "登录" : "注册"));

watch(
  () => route.path,
  () => {
    error.value = "";
    password.value = "";
    confirmPassword.value = "";
  }
);

function validate(emailValue) {
  if (!emailValue) return "请输入邮箱";
  if (emailValue.length > 120) return "邮箱长度不能超过 120 个字符";
  if (!EMAIL_RE.test(emailValue)) return "邮箱格式不正确";
  if (!password.value) return "请输入密码";
  if (isSignUp.value) {
    if (password.value.length < 8 || password.value.length > 72) {
      return "密码长度需为 8-72 位";
    }
    if (!confirmPassword.value) return "请再次输入密码";
    if (password.value !== confirmPassword.value) {
      return "两次输入的密码不一致";
    }
  }
  return "";
}

function redirectTarget() {
  const raw = route.query.redirect;
  const value = typeof raw === "string" ? raw : "";
  if (value.startsWith("/") && !value.startsWith("//")) return value;
  return "/relay/console";
}

async function submit() {
  error.value = "";
  const emailValue = email.value.trim();
  const problem = validate(emailValue);
  if (problem) {
    error.value = problem;
    return;
  }

  submitting.value = true;
  try {
    const data = isSignUp.value
      ? await registerRelay(emailValue, password.value)
      : await loginRelay(emailValue, password.value);
    if (!data?.token) {
      throw new Error("登录响应缺少令牌，请稍后重试");
    }
    saveRelaySession(data.token, data.user || null);
    password.value = "";
    confirmPassword.value = "";
    await router.replace(redirectTarget());
  } catch (e) {
    error.value = e?.message || "操作失败，请稍后重试";
  } finally {
    submitting.value = false;
  }
}
</script>

<template>
  <div class="auth-page">
    <router-link to="/relay" class="auth-logo" aria-label="返回 Wynne Relay 首页">
      <span class="auth-logo__mark">W</span>
      <span class="auth-logo__name">Wynne Relay</span>
    </router-link>

    <main class="auth-panel">
      <header class="auth-head">
        <h1>{{ isSignUp ? "创建一个账户" : "登录" }}</h1>
        <p class="auth-switch">
          {{ switchLead }}
          <router-link :to="switchPath">{{ switchLabel }}</router-link>
        </p>
      </header>

      <p v-if="error" class="auth-error" role="alert">{{ error }}</p>

      <form class="auth-form" novalidate @submit.prevent="submit">
        <label class="auth-field">
          <span>邮箱</span>
          <input
            v-model="email"
            type="email"
            name="email"
            autocomplete="email"
            placeholder="name@example.com"
            maxlength="120"
            :disabled="submitting"
          />
        </label>

        <label class="auth-field">
          <span>密码</span>
          <input
            v-model="password"
            type="password"
            name="password"
            :autocomplete="isSignUp ? 'new-password' : 'current-password'"
            :placeholder="isSignUp ? '8-72 位字符' : '请输入密码'"
            maxlength="72"
            :disabled="submitting"
          />
        </label>

        <label v-if="isSignUp" class="auth-field">
          <span>确认密码</span>
          <input
            v-model="confirmPassword"
            type="password"
            name="confirm-password"
            autocomplete="new-password"
            placeholder="再次输入密码"
            maxlength="72"
            :disabled="submitting"
          />
        </label>

        <button class="auth-submit" type="submit" :disabled="submitting">
          <span v-if="submitting">{{ isSignUp ? "创建中…" : "登录中…" }}</span>
          <span v-else>{{ isSignUp ? "创建账户" : "登录" }}</span>
        </button>
      </form>

      <p class="auth-foot">
        继续即表示你同意本站的服务条款与隐私政策。密码长度需为 8-72 位。
      </p>
    </main>
  </div>
</template>

<style scoped>
.auth-page {
  --primary: var(--r-primary, #3ea4ec);
  --primary-dark: var(--r-primary-dark, #2f92dd);
  --text: var(--r-text, #0b1220);
  --muted: var(--r-muted, #5b6b84);
  --line: var(--r-line, #e6ecf5);
  --soft: var(--r-soft, #f7f9fc);
  --danger: var(--r-danger, #f04438);
  --radius: var(--r-radius, 14px);
  position: relative;
  min-height: 100vh;
  padding: 0 16px;
  background: #ffffff;
  color: var(--text);
  font-family: "Inter", "DM Sans", "Noto Sans SC", "PingFang SC", system-ui,
    sans-serif;
  -webkit-font-smoothing: antialiased;
  overflow-x: hidden;
}

.auth-page::before {
  display: none;
}

.auth-page a {
  color: inherit;
}

.auth-logo {
  position: absolute;
  top: 30px;
  left: 32px;
  z-index: 1;
  display: inline-flex;
  align-items: center;
  gap: 10px;
  font-weight: 700;
  letter-spacing: -0.02em;
  color: var(--text);
}

.auth-logo__mark {
  width: 32px;
  height: 32px;
  display: grid;
  place-items: center;
  border-radius: 8px;
  background: var(--primary);
  color: #ffffff;
  font-size: 17px;
  line-height: 1;
}

.auth-logo__name {
  font-size: 16px;
}

.auth-panel {
  position: relative;
  width: min(420px, 100%);
  margin: 0 auto;
  padding: 104px 0 72px;
}

.auth-head h1 {
  margin: 0;
  font-size: 26px;
  font-weight: 700;
  letter-spacing: -0.01em;
  line-height: 1.25;
}

.auth-switch {
  margin: 10px 0 0;
  font-size: 14px;
  color: var(--muted);
}

.auth-switch a {
  color: var(--primary);
  font-weight: 500;
  text-decoration: underline;
  text-underline-offset: 3px;
}

.auth-switch a:hover {
  color: var(--primary-dark);
}

.auth-error {
  margin: 20px 0 0;
  padding: 11px 14px;
  border: 1px solid #ffd4d0;
  border-radius: 10px;
  background: #fff4f2;
  color: #c8362c;
  font-size: 13px;
  line-height: 1.55;
  word-break: break-word;
}

.auth-form {
  margin-top: 24px;
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.auth-field span {
  display: block;
  margin-bottom: 8px;
  font-size: 14.5px;
  font-weight: 600;
  color: var(--text);
}

.auth-field input {
  width: 100%;
  height: 44px;
  padding: 0 18px;
  border: 1px solid #e7e7e7;
  border-radius: 999px;
  background: #ffffff;
  color: var(--text);
  font-size: 14px;
  font-family: inherit;
  outline: none;
  transition: border-color 0.18s, box-shadow 0.18s;
}

.auth-field input::placeholder {
  color: #9aa8bf;
}

.auth-field input:focus {
  border-color: var(--primary);
  box-shadow: 0 0 0 3px rgba(62, 164, 236, 0.16);
}

.auth-field input:disabled {
  background: var(--soft);
  cursor: not-allowed;
}

.auth-submit {
  width: 100%;
  height: 40px;
  margin-top: 4px;
  border: none;
  border-radius: 999px;
  background: var(--primary);
  color: #ffffff;
  font-size: 14.5px;
  font-weight: 600;
  font-family: inherit;
  cursor: pointer;
  transition: background 0.18s, opacity 0.18s;
}

.auth-submit:hover:not(:disabled) {
  background: var(--primary-dark);
}

.auth-submit:disabled {
  opacity: 0.62;
  cursor: not-allowed;
}

.auth-foot {
  margin: 22px 0 0;
  font-size: 12px;
  line-height: 1.7;
  color: var(--muted);
  text-align: center;
}

@media (max-width: 640px) {
  .auth-logo {
    top: 20px;
    left: 20px;
  }

  .auth-panel {
    padding-top: 96px;
  }

  .auth-head h1 {
    font-size: 26px;
  }
}
</style>
