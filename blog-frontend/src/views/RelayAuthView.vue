<script setup>
import { computed, ref, watch } from "vue";
import { useRoute, useRouter } from "vue-router";
import {
  forgotRelayPassword,
  loginRelay,
  registerRelay,
  resetRelayPassword,
  saveRelaySession
} from "../api/relay";

const EMAIL_RE = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;

const route = useRoute();
const router = useRouter();

const email = ref("");
const password = ref("");
const confirmPassword = ref("");
const error = ref("");
const success = ref("");
const submitting = ref(false);
const showPassword = ref(false);

const mode = computed(() => {
  const name = String(route.name || "").toLowerCase();
  const path = String(route.path || "");
  if (name.includes("forgot") || path.includes("forgot-password")) {
    return "forgot";
  }
  if (name.includes("reset") || path.includes("reset-password")) {
    return "reset";
  }
  if (
    name.includes("sign-up") ||
    name.includes("signup") ||
    name.includes("register")
  ) {
    return "signup";
  }
  return path.endsWith("sign-up") || path.endsWith("signup")
    ? "signup"
    : "signin";
});

const isSignUp = computed(() => mode.value === "signup");
const isForgot = computed(() => mode.value === "forgot");
const isReset = computed(() => mode.value === "reset");

const resetToken = computed(() => {
  const raw = String(route.hash || "").replace(/^#/, "");
  if (!raw) return "";
  return (new URLSearchParams(raw).get("token") || "").trim();
});

const heading = computed(
  () =>
    ({
      signin: "登录",
      signup: "创建一个账户",
      forgot: "找回密码",
      reset: "设置新密码"
    })[mode.value]
);

const lead = computed(
  () =>
    ({
      signin: "欢迎回来，继续管理你的中转账户。",
      signup: "注册后即可创建 API 令牌、查看用量和充值。",
      forgot: "输入注册邮箱，我们会发送一封重置密码的邮件。",
      reset: "设置新密码后，旧密码、所有登录会话和 API Key 都会失效。"
    })[mode.value]
);

const switchPath = computed(() => {
  if (isForgot.value || isReset.value) return "/relay/sign-in";
  const path = isSignUp.value ? "/relay/sign-in" : "/relay/sign-up";
  const redirect = route.query.redirect;
  return typeof redirect === "string" && redirect
    ? { path, query: { redirect } }
    : path;
});
const switchLead = computed(() => {
  if (isForgot.value || isReset.value) return "想起密码了？";
  return isSignUp.value ? "已有账户？" : "没有账号？";
});
const switchLabel = computed(() => {
  if (isForgot.value || isReset.value) return "返回登录";
  return isSignUp.value ? "登录" : "注册";
});

const submitLabel = computed(() => {
  if (submitting.value) {
    return {
      signin: "登录中…",
      signup: "创建中…",
      forgot: "发送中…",
      reset: "提交中…"
    }[mode.value];
  }
  return {
    signin: "登录",
    signup: "创建账户",
    forgot: "发送重置邮件",
    reset: "保存新密码"
  }[mode.value];
});

watch(
  () => [route.path, route.hash],
  () => {
    error.value = "";
    success.value = "";
    password.value = "";
    confirmPassword.value = "";
    showPassword.value = false;
  }
);

function emailProblem(value) {
  if (!value) return "请输入邮箱";
  if (value.length > 120) return "邮箱长度不能超过 120 个字符";
  if (!EMAIL_RE.test(value)) return "邮箱格式不正确";
  return "";
}

function passwordProblem(value, label) {
  if (!value) return `请输入${label}`;
  if (value.length < 8 || value.length > 72) return "密码长度需为 8-72 位";
  return "";
}

function validate() {
  if (isForgot.value) return emailProblem(email.value.trim());
  if (isReset.value) {
    if (!resetToken.value) return "重置链接不完整，请重新申请找回密码";
    const problem = passwordProblem(password.value, "新密码");
    if (problem) return problem;
    if (!confirmPassword.value) return "请再次输入新密码";
    if (password.value !== confirmPassword.value) return "两次输入的密码不一致";
    return "";
  }

  const emailIssue = emailProblem(email.value.trim());
  if (emailIssue) return emailIssue;
  if (!password.value) return "请输入密码";
  if (isSignUp.value) {
    const problem = passwordProblem(password.value, "密码");
    if (problem) return problem;
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
  if (submitting.value) return;
  error.value = "";
  success.value = "";
  const problem = validate();
  if (problem) {
    error.value = problem;
    return;
  }

  submitting.value = true;
  try {
    if (isForgot.value) {
      await forgotRelayPassword(email.value.trim());
      success.value = "forgot";
      return;
    }
    if (isReset.value) {
      await resetRelayPassword(resetToken.value, password.value);
      success.value = "reset";
      password.value = "";
      confirmPassword.value = "";
      // Drop the consumed token from the address bar and history entry.
      if (typeof window !== "undefined" && window.history?.replaceState) {
        window.history.replaceState(
          window.history.state,
          "",
          route.fullPath.split("#")[0]
        );
      }
      return;
    }

    const data = isSignUp.value
      ? await registerRelay(email.value.trim(), password.value)
      : await loginRelay(email.value.trim(), password.value);
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
        <h1>{{ heading }}</h1>
        <p class="auth-lead">{{ lead }}</p>
        <p class="auth-switch">
          {{ switchLead }}
          <router-link :to="switchPath">{{ switchLabel }}</router-link>
        </p>
      </header>

      <section v-if="success" class="auth-success" role="status">
        <span class="auth-success__icon" aria-hidden="true">✓</span>
        <div>
          <strong>{{ success === "reset" ? "密码已重置" : "请查收邮件" }}</strong>
          <p v-if="success === 'reset'">
            新密码已经生效，所有旧登录会话和 API Key 已失效。请使用新密码重新登录，并为客户端创建新的 API Key。
          </p>
          <p v-else>
            如果该邮箱已注册，我们已发送重置链接。请检查收件箱和垃圾邮件，链接 30
            分钟内有效且只能使用一次。
          </p>
          <router-link class="auth-success__link" to="/relay/sign-in">
            返回登录 →
          </router-link>
        </div>
      </section>

      <template v-else>
        <p v-if="error" class="auth-error" role="alert">{{ error }}</p>

        <div v-if="isReset && !resetToken" class="auth-error" role="alert">
          重置链接不完整或已失效，请重新申请找回密码。
          <router-link class="auth-inline-link" to="/relay/forgot-password">
            发送新的重置邮件
          </router-link>
        </div>

        <form v-else class="auth-form" novalidate @submit.prevent="submit">
          <label v-if="!isReset" class="auth-field">
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

          <label v-if="!isForgot" class="auth-field">
            <span>{{ isReset ? "新密码" : "密码" }}</span>
            <span class="auth-field__control">
              <input
                v-model="password"
                :type="showPassword ? 'text' : 'password'"
                name="password"
                :autocomplete="isSignUp || isReset ? 'new-password' : 'current-password'"
                :placeholder="
                  isSignUp || isReset ? '8-72 位字符' : '请输入密码'
                "
                maxlength="72"
                :disabled="submitting"
              />
              <button
                class="auth-eye"
                type="button"
                :aria-label="showPassword ? '隐藏密码' : '显示密码'"
                @click="showPassword = !showPassword"
              >
                {{ showPassword ? "隐藏" : "显示" }}
              </button>
            </span>
          </label>

          <label v-if="isSignUp || isReset" class="auth-field">
            <span>确认密码</span>
            <input
              v-model="confirmPassword"
              :type="showPassword ? 'text' : 'password'"
              name="confirm-password"
              autocomplete="new-password"
              :placeholder="isReset ? '再次输入新密码' : '再次输入密码'"
              maxlength="72"
              :disabled="submitting"
            />
          </label>

          <p v-if="mode === 'signin'" class="auth-aside">
            <router-link to="/relay/forgot-password">忘记密码？</router-link>
          </p>

          <button class="auth-submit" type="submit" :disabled="submitting">
            {{ submitLabel }}
          </button>
        </form>
      </template>

      <p class="auth-foot">
        <template v-if="isReset">
          重置链接 30 分钟内有效，只能使用一次。遇到问题请联系管理员重新发送。
        </template>
        <template v-else-if="isForgot">
          出于安全考虑，我们不会提示该邮箱是否已注册。密码长度需为 8-72 位。
        </template>
        <template v-else>
          继续即表示你同意本站的服务条款与隐私政策。密码长度需为 8-72 位。
        </template>
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

.auth-lead {
  margin: 10px 0 0;
  color: var(--muted);
  font-size: 14px;
  line-height: 1.65;
}

.auth-switch {
  margin: 14px 0 0;
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

.auth-inline-link {
  margin-left: 6px;
  color: #c8362c;
  font-weight: 700;
  text-decoration: underline;
  text-underline-offset: 3px;
}

.auth-success {
  display: flex;
  gap: 14px;
  margin-top: 26px;
  padding: 18px 18px 18px 16px;
  border: 1px solid #cdeadd;
  border-radius: 14px;
  background: #f3fbf7;
  color: #145c40;
}

.auth-success__icon {
  flex: 0 0 28px;
  width: 28px;
  height: 28px;
  display: grid;
  place-items: center;
  border-radius: 50%;
  background: #1a7f5a;
  color: #ffffff;
  font-size: 15px;
  font-weight: 700;
}

.auth-success strong {
  display: block;
  font-size: 15px;
  line-height: 1.5;
}

.auth-success p {
  margin: 6px 0 0;
  color: #2f6b55;
  font-size: 13px;
  line-height: 1.7;
}

.auth-success__link {
  display: inline-block;
  margin-top: 12px;
  color: #1a7f5a;
  font-size: 13.5px;
  font-weight: 700;
  text-decoration: none;
}

.auth-success__link:hover {
  text-decoration: underline;
  text-underline-offset: 3px;
}

.auth-form {
  margin-top: 24px;
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.auth-field > span:first-child {
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

.auth-field__control {
  position: relative;
  display: block;
}

.auth-field__control input {
  padding-right: 64px;
}

.auth-eye {
  position: absolute;
  top: 50%;
  right: 8px;
  transform: translateY(-50%);
  padding: 6px 10px;
  border: none;
  border-radius: 999px;
  background: transparent;
  color: var(--muted);
  font-size: 12.5px;
  font-family: inherit;
  cursor: pointer;
}

.auth-eye:hover {
  color: var(--primary-dark);
  background: var(--soft);
}

.auth-aside {
  margin: -6px 0 0;
  text-align: right;
  font-size: 13px;
}

.auth-aside a {
  color: var(--muted);
  text-decoration: none;
}

.auth-aside a:hover {
  color: var(--primary);
  text-decoration: underline;
  text-underline-offset: 3px;
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
