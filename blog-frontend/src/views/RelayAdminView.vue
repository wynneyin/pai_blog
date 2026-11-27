<script setup>
import { onMounted, ref } from "vue";
import dayjs from "dayjs";
import {
  fetchAdminRelayTopups,
  fetchAdminRelayUsers,
  updateAdminRelayTopupStatus
} from "../api/relay";

const TOKEN_KEY = "pai_relay_admin_token";

const TOPUP_STATUS = {
  pending: "待支付",
  paid: "已支付",
  cancelled: "已取消"
};

const token = ref(sessionStorage.getItem(TOKEN_KEY) || "");
const tab = ref("topups");
const topupStatus = ref("");
const users = ref([]);
const usersTotal = ref(0);
const topups = ref([]);
const topupsTotal = ref(0);
const loading = ref(false);
const error = ref("");
const loaded = ref(false);
const updating = ref("");

const statusFilters = [
  { value: "", label: "全部" },
  { value: "pending", label: "待支付" },
  { value: "paid", label: "已支付" },
  { value: "cancelled", label: "已取消" }
];

function cents(value) {
  const number = Number(value);
  return Number.isFinite(number) ? (number / 100).toFixed(2) : "0.00";
}

function formatTime(iso) {
  return iso ? dayjs(iso).format("YYYY-MM-DD HH:mm") : "—";
}

function statusText(status) {
  return TOPUP_STATUS[status] || status || "—";
}

function errorMessage(e, fallback) {
  if (e?.status === 401) return "管理员令牌不正确，请重新输入";
  return e?.message || fallback;
}

async function load() {
  const key = token.value.trim();
  if (!key) {
    error.value = "请输入管理员令牌（服务端 BLOG_ADMIN_TOKEN）";
    loaded.value = false;
    return;
  }
  loading.value = true;
  error.value = "";
  try {
    if (tab.value === "users") {
      const data = await fetchAdminRelayUsers(key, { limit: 100 });
      users.value = Array.isArray(data?.list) ? data.list : [];
      usersTotal.value = Number(data?.total) || users.value.length;
    } else {
      const data = await fetchAdminRelayTopups(key, {
        status: topupStatus.value,
        limit: 100
      });
      topups.value = Array.isArray(data?.list) ? data.list : [];
      topupsTotal.value = Number(data?.total) || topups.value.length;
    }
    sessionStorage.setItem(TOKEN_KEY, key);
    loaded.value = true;
  } catch (e) {
    error.value = errorMessage(e, "加载失败");
    loaded.value = false;
  } finally {
    loading.value = false;
  }
}

function switchTab(next) {
  if (tab.value === next) return;
  tab.value = next;
  error.value = "";
  if (token.value.trim()) load();
}

function setTopupFilter(value) {
  if (topupStatus.value === value) return;
  topupStatus.value = value;
  if (token.value.trim()) load();
}

async function setTopupStatus(order, status) {
  if (!order?.orderNo) return;
  updating.value = order.orderNo;
  error.value = "";
  try {
    await updateAdminRelayTopupStatus(token.value.trim(), order.orderNo, status);
    await load();
  } catch (e) {
    error.value = errorMessage(e, "状态更新失败");
  } finally {
    updating.value = "";
  }
}

onMounted(() => {
  if (token.value) load();
});
</script>

<template>
  <main class="relay-admin">
    <div class="ra-wrap">
      <header class="ra-head">
        <div class="ra-head__text">
          <span class="ra-eyebrow">运维控制台</span>
          <h1 class="ra-title">中转后台</h1>
          <p class="ra-desc">
            管理已注册用户与充值订单，查看余额流水，并处理待支付的订单。
          </p>
        </div>
        <span class="ra-secure">
          <svg viewBox="0 0 24 24" aria-hidden="true">
            <rect x="4.5" y="10.5" width="15" height="10" rx="2.5" />
            <path d="M8 10.5V8a4 4 0 0 1 8 0v2.5" />
            <circle cx="12" cy="15.5" r="1.4" />
          </svg>
          仅限管理员
        </span>
      </header>

      <form class="ra-token" @submit.prevent="load">
        <div class="ra-token__head">
          <span class="ra-token__icon" aria-hidden="true">
            <svg viewBox="0 0 24 24">
              <circle cx="8.5" cy="12" r="3.5" />
              <path d="M12 12h8.5M17 12v3M20.5 12v2.5" />
            </svg>
          </span>
          <div>
            <h2 class="ra-token__title">管理员令牌</h2>
            <p class="ra-token__desc">
              输入服务端配置的管理员令牌以加载数据，令牌仅保存在当前标签页。
            </p>
          </div>
        </div>
        <div class="ra-token__row">
          <input
            v-model="token"
            class="ra-input"
            type="password"
            placeholder="请输入管理员令牌"
            autocomplete="off"
            aria-label="管理员令牌"
            :disabled="loading"
          />
          <button class="ra-btn ra-btn--primary" type="submit" :disabled="loading">
            {{ loading ? "加载中…" : "加载数据" }}
          </button>
        </div>
      </form>

      <div v-if="error" class="ra-alert" role="alert">
        <span class="ra-alert__dot" aria-hidden="true">!</span>
        <span>{{ error }}</span>
      </div>

      <section class="ra-panel" :aria-busy="loading">
        <div v-if="loading" class="ra-progress" aria-hidden="true"><span></span></div>

        <div class="ra-panel__head">
          <div class="ra-tabs" role="tablist" aria-label="后台数据类型">
            <button
              class="ra-tab"
              :class="{ 'ra-tab--active': tab === 'users' }"
              type="button"
              role="tab"
              :aria-selected="tab === 'users'"
              @click="switchTab('users')"
            >
              用户
            </button>
            <button
              class="ra-tab"
              :class="{ 'ra-tab--active': tab === 'topups' }"
              type="button"
              role="tab"
              :aria-selected="tab === 'topups'"
              @click="switchTab('topups')"
            >
              充值订单
            </button>
          </div>
          <span v-if="loaded" class="ra-count">
            <template v-if="tab === 'users'">共 {{ usersTotal }} 位用户</template>
            <template v-else>共 {{ topupsTotal }} 笔订单</template>
          </span>
        </div>

        <div v-if="tab === 'topups'" class="ra-filters" role="group" aria-label="订单状态筛选">
          <button
            v-for="item in statusFilters"
            :key="item.value"
            class="ra-chip"
            :class="{ 'ra-chip--active': topupStatus === item.value }"
            type="button"
            @click="setTopupFilter(item.value)"
          >
            {{ item.label }}
          </button>
        </div>

        <template v-if="tab === 'users'">
          <div v-if="users.length" class="ra-table-wrap">
            <table class="ra-table ra-table--users">
              <thead>
                <tr>
                  <th scope="col">邮箱</th>
                  <th scope="col">余额</th>
                  <th scope="col">令牌数</th>
                  <th scope="col">累计实付</th>
                  <th scope="col">注册时间</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="item in users" :key="item.id || item.email">
                  <td class="ra-table__email">{{ item.email || "—" }}</td>
                  <td class="ra-money">¥{{ cents(item.balanceCents) }}</td>
                  <td>{{ item.keyCount ?? 0 }}</td>
                  <td class="ra-money ra-money--paid">¥{{ cents(item.paidTopupCents) }}</td>
                  <td class="ra-time">{{ formatTime(item.createdAt) }}</td>
                </tr>
              </tbody>
            </table>
          </div>
          <p v-else-if="loading" class="ra-state">
            <span class="ra-spinner" aria-hidden="true"></span>
            正在加载用户…
          </p>
          <p v-else-if="loaded" class="ra-state">暂无用户。</p>
          <p v-else class="ra-state">输入管理员令牌后加载数据。</p>
        </template>

        <template v-else>
          <div v-if="topups.length" class="ra-table-wrap">
            <table class="ra-table ra-table--topups">
              <thead>
                <tr>
                  <th scope="col">订单号</th>
                  <th scope="col">邮箱</th>
                  <th scope="col">档位</th>
                  <th scope="col">实付 / 到账</th>
                  <th scope="col">状态</th>
                  <th scope="col">创建时间</th>
                  <th scope="col">操作</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="order in topups" :key="order.orderNo">
                  <td class="ra-table__order">{{ order.orderNo }}</td>
                  <td class="ra-table__email">{{ order.email || "—" }}</td>
                  <td>{{ order.planName || "—" }}</td>
                  <td>
                    <span class="ra-money">¥{{ cents(order.amountCents) }}</span>
                    <span class="ra-sub">到账 ¥{{ cents(order.creditCents) }}</span>
                  </td>
                  <td>
                    <span class="ra-status" :class="`ra-status--${order.status}`">
                      {{ statusText(order.status) }}
                    </span>
                  </td>
                  <td class="ra-time">{{ formatTime(order.createdAt) }}</td>
                  <td>
                    <div v-if="order.status === 'pending'" class="ra-actions">
                      <button
                        class="ra-btn ra-btn--primary ra-btn--sm"
                        type="button"
                        :disabled="updating === order.orderNo"
                        @click="setTopupStatus(order, 'paid')"
                      >
                        {{ updating === order.orderNo ? "处理中…" : "标记已支付" }}
                      </button>
                      <button
                        class="ra-btn ra-btn--ghost ra-btn--sm"
                        type="button"
                        :disabled="updating === order.orderNo"
                        @click="setTopupStatus(order, 'cancelled')"
                      >
                        取消订单
                      </button>
                    </div>
                    <span v-else class="ra-muted">—</span>
                  </td>
                </tr>
              </tbody>
            </table>
          </div>
          <p v-else-if="loading" class="ra-state">
            <span class="ra-spinner" aria-hidden="true"></span>
            正在加载订单…
          </p>
          <p v-else-if="loaded" class="ra-state">当前筛选下没有充值订单。</p>
          <p v-else class="ra-state">输入管理员令牌后加载数据。</p>
        </template>
      </section>
    </div>
  </main>
</template>

<style scoped>
.relay-admin {
  min-height: 100vh;
  padding: 64px 0 0;
  color: var(--r-text, #0b1220);
  background:
    radial-gradient(1100px 320px at 12% -90px, rgba(22, 119, 255, 0.06), transparent 70%),
    radial-gradient(900px 260px at 92% -120px, rgba(124, 92, 255, 0.05), transparent 70%),
    var(--r-soft, #f7f9fc);
  font-family: "Inter", "Noto Sans SC", system-ui, -apple-system, "Segoe UI", sans-serif;
}

.relay-admin,
.relay-admin * {
  box-sizing: border-box;
}

.ra-wrap {
  width: 100%;
  max-width: min(1400px, 100%);
  margin: 0 auto;
  padding: 36px 32px 72px;
}

.ra-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 24px;
}

.ra-eyebrow {
  display: inline-block;
  margin-bottom: 8px;
  color: var(--r-primary, #1677ff);
  font-size: 12px;
  font-weight: 600;
  letter-spacing: 0.12em;
}

.ra-title {
  margin: 0 0 8px;
  color: var(--r-text, #0b1220);
  font-size: clamp(24px, 3vw, 30px);
  font-weight: 800;
  letter-spacing: -0.03em;
  line-height: 1.2;
}

.ra-desc {
  margin: 0;
  max-width: 640px;
  color: var(--r-muted, #5b6b84);
  font-size: 14px;
  line-height: 1.7;
}

.ra-secure {
  display: inline-flex;
  flex: 0 0 auto;
  align-items: center;
  gap: 6px;
  margin-top: 4px;
  padding: 6px 12px;
  border: 1px solid var(--r-line, #e6ecf5);
  border-radius: 999px;
  background: var(--r-bg, #ffffff);
  color: var(--r-muted, #5b6b84);
  font-size: 12px;
  font-weight: 600;
  white-space: nowrap;
}

.ra-secure svg {
  width: 14px;
  height: 14px;
  fill: none;
  stroke: currentColor;
  stroke-width: 1.8;
  stroke-linecap: round;
  stroke-linejoin: round;
}

.ra-token {
  padding: 20px 22px;
  border: 1px solid var(--r-line, #e6ecf5);
  border-radius: var(--r-radius, 14px);
  background: var(--r-bg, #ffffff);
  box-shadow: 0 1px 2px rgba(16, 24, 40, 0.04);
}

.ra-token__head {
  display: flex;
  align-items: flex-start;
  gap: 12px;
  margin-bottom: 16px;
}

.ra-token__icon {
  display: inline-flex;
  flex: 0 0 auto;
  align-items: center;
  justify-content: center;
  width: 38px;
  height: 38px;
  border-radius: 10px;
  background: rgba(22, 119, 255, 0.09);
  color: var(--r-primary, #1677ff);
}

.ra-token__icon svg {
  width: 19px;
  height: 19px;
  fill: none;
  stroke: currentColor;
  stroke-width: 1.8;
  stroke-linecap: round;
  stroke-linejoin: round;
}

.ra-token__title {
  margin: 0 0 4px;
  font-size: 15px;
  font-weight: 700;
  letter-spacing: -0.01em;
}

.ra-token__desc {
  margin: 0;
  color: var(--r-muted, #5b6b84);
  font-size: 13px;
  line-height: 1.6;
}

.ra-token__row {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
}

.ra-input {
  flex: 1 1 240px;
  min-width: 0;
  height: 42px;
  padding: 0 14px;
  border: 1px solid var(--r-line, #e6ecf5);
  border-radius: 10px;
  background: var(--r-bg, #ffffff);
  color: var(--r-text, #0b1220);
  font: inherit;
  font-size: 14px;
  outline: none;
  transition: border-color 0.18s ease, box-shadow 0.18s ease;
}

.ra-input::placeholder {
  color: #9aa8bd;
}

.ra-input:focus {
  border-color: var(--r-primary, #1677ff);
  box-shadow: 0 0 0 3px rgba(22, 119, 255, 0.14);
}

.ra-input:disabled {
  background: var(--r-soft, #f7f9fc);
  cursor: not-allowed;
}

.ra-btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  height: 42px;
  padding: 0 20px;
  border: 1px solid transparent;
  border-radius: 10px;
  font: inherit;
  font-size: 14px;
  font-weight: 600;
  white-space: nowrap;
  cursor: pointer;
  transition: background-color 0.18s ease, border-color 0.18s ease, color 0.18s ease,
    box-shadow 0.18s ease;
}

.ra-btn:focus-visible {
  outline: none;
  box-shadow: 0 0 0 3px rgba(22, 119, 255, 0.24);
}

.ra-btn:disabled {
  opacity: 0.6;
  cursor: not-allowed;
}

.ra-btn--primary {
  background: var(--r-primary, #1677ff);
  color: #ffffff;
  box-shadow: 0 4px 12px rgba(22, 119, 255, 0.18);
}

.ra-btn--primary:hover:not(:disabled) {
  background: var(--r-primary-dark, #0958d9);
}

.ra-btn--sm {
  height: 32px;
  padding: 0 12px;
  border-radius: 8px;
  font-size: 12.5px;
  box-shadow: none;
}

.ra-btn--ghost {
  border-color: var(--r-line, #e6ecf5);
  background: var(--r-bg, #ffffff);
  color: var(--r-muted, #5b6b84);
}

.ra-btn--ghost:hover:not(:disabled) {
  border-color: #f5c7c1;
  background: #fff8f7;
  color: var(--r-danger, #f04438);
}

.ra-alert {
  display: flex;
  align-items: flex-start;
  gap: 10px;
  margin-top: 16px;
  padding: 12px 16px;
  border: 1px solid #f6cfca;
  border-radius: 12px;
  background: #fff5f4;
  color: #b42318;
  font-size: 13px;
  line-height: 1.6;
}

.ra-alert__dot {
  display: inline-flex;
  flex: 0 0 auto;
  align-items: center;
  justify-content: center;
  width: 18px;
  height: 18px;
  margin-top: 1px;
  border-radius: 50%;
  background: #f04438;
  color: #ffffff;
  font-size: 12px;
  font-weight: 700;
  line-height: 1;
}

.ra-panel {
  position: relative;
  margin-top: 20px;
  overflow: hidden;
  border: 1px solid var(--r-line, #e6ecf5);
  border-radius: var(--r-radius, 14px);
  background: var(--r-bg, #ffffff);
  box-shadow: 0 1px 2px rgba(16, 24, 40, 0.04);
}

.ra-progress {
  position: absolute;
  top: 0;
  left: 0;
  right: 0;
  height: 2px;
  overflow: hidden;
  background: rgba(22, 119, 255, 0.12);
}

.ra-progress span {
  display: block;
  width: 40%;
  height: 100%;
  border-radius: 2px;
  background: var(--r-primary, #1677ff);
  animation: ra-progress 1.1s ease-in-out infinite;
}

@keyframes ra-progress {
  0% {
    transform: translateX(-110%);
  }
  100% {
    transform: translateX(360%);
  }
}

.ra-panel__head {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 16px 20px;
}

.ra-tabs {
  display: inline-flex;
  gap: 4px;
  padding: 4px;
  border: 1px solid var(--r-line, #e6ecf5);
  border-radius: 12px;
  background: var(--r-soft, #f7f9fc);
}

.ra-tab {
  padding: 8px 16px;
  border: 0;
  border-radius: 9px;
  background: transparent;
  color: var(--r-muted, #5b6b84);
  font: inherit;
  font-size: 13px;
  font-weight: 600;
  cursor: pointer;
  transition: background-color 0.18s ease, color 0.18s ease, box-shadow 0.18s ease;
}

.ra-tab:hover {
  color: var(--r-text, #0b1220);
}

.ra-tab:focus-visible {
  outline: none;
  box-shadow: 0 0 0 2px rgba(22, 119, 255, 0.3);
}

.ra-tab--active {
  background: var(--r-bg, #ffffff);
  color: var(--r-primary, #1677ff);
  box-shadow: 0 1px 3px rgba(16, 24, 40, 0.1);
}

.ra-count {
  color: var(--r-muted, #5b6b84);
  font-size: 13px;
  font-weight: 500;
}

.ra-filters {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  padding: 0 20px 16px;
}

.ra-chip {
  padding: 6px 14px;
  border: 1px solid var(--r-line, #e6ecf5);
  border-radius: 999px;
  background: var(--r-bg, #ffffff);
  color: var(--r-muted, #5b6b84);
  font: inherit;
  font-size: 12.5px;
  font-weight: 500;
  cursor: pointer;
  transition: border-color 0.18s ease, background-color 0.18s ease, color 0.18s ease;
}

.ra-chip:hover {
  border-color: #bcd7ff;
  color: var(--r-primary, #1677ff);
}

.ra-chip:focus-visible {
  outline: none;
  box-shadow: 0 0 0 3px rgba(22, 119, 255, 0.18);
}

.ra-chip--active {
  border-color: #bcd7ff;
  background: #eaf2ff;
  color: var(--r-primary, #1677ff);
  font-weight: 600;
}

.ra-table-wrap {
  width: 100%;
  overflow-x: auto;
  -webkit-overflow-scrolling: touch;
  border-top: 1px solid var(--r-line, #e6ecf5);
}

.ra-table {
  width: 100%;
  border-collapse: collapse;
  font-size: 14px;
}

.ra-table--users {
  min-width: 720px;
}

.ra-table--topups {
  min-width: 940px;
}

.ra-table th {
  padding: 11px 20px;
  border-bottom: 1px solid var(--r-line, #e6ecf5);
  background: var(--r-soft, #f7f9fc);
  color: var(--r-muted, #5b6b84);
  font-size: 12px;
  font-weight: 600;
  letter-spacing: 0.02em;
  text-align: left;
  white-space: nowrap;
}

.ra-table td {
  padding: 14px 20px;
  border-bottom: 1px solid #eef2f8;
  color: var(--r-text, #0b1220);
  vertical-align: middle;
  white-space: nowrap;
}

.ra-table tbody tr:last-child td {
  border-bottom: 0;
}

.ra-table tbody tr {
  transition: background-color 0.14s ease;
}

.ra-table tbody tr:hover {
  background: #f8faff;
}

.ra-table__email {
  font-weight: 500;
}

.ra-table__order {
  color: var(--r-muted, #5b6b84);
  font-family: "SFMono-Regular", "JetBrains Mono", ui-monospace, Menlo, Consolas, monospace;
  font-size: 12.5px;
}

.ra-money {
  font-variant-numeric: tabular-nums;
  font-weight: 600;
}

.ra-money--paid {
  color: var(--r-success, #12b76a);
}

.ra-sub {
  display: block;
  margin-top: 3px;
  color: var(--r-muted, #5b6b84);
  font-size: 12px;
  font-variant-numeric: tabular-nums;
}

.ra-time {
  color: var(--r-muted, #5b6b84);
  font-size: 13px;
  font-variant-numeric: tabular-nums;
}

.ra-muted {
  color: #9aa8bd;
}

.ra-status {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 3px 10px;
  border-radius: 999px;
  font-size: 12px;
  font-weight: 600;
  line-height: 1.5;
}

.ra-status::before {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: currentColor;
  content: "";
}

.ra-status--pending {
  background: #fff6e6;
  color: #b54708;
}

.ra-status--paid {
  background: #e8f8ef;
  color: #067647;
}

.ra-status--cancelled {
  background: #f1f3f7;
  color: #667085;
}

.ra-actions {
  display: flex;
  align-items: center;
  gap: 8px;
}

.ra-state {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 10px;
  margin: 0;
  padding: 56px 20px;
  border-top: 1px solid var(--r-line, #e6ecf5);
  color: var(--r-muted, #5b6b84);
  font-size: 13.5px;
  text-align: center;
}

.ra-spinner {
  width: 16px;
  height: 16px;
  border: 2px solid rgba(22, 119, 255, 0.2);
  border-top-color: var(--r-primary, #1677ff);
  border-radius: 50%;
  animation: ra-spin 0.8s linear infinite;
}

@keyframes ra-spin {
  to {
    transform: rotate(360deg);
  }
}

@media (max-width: 820px) {
  .ra-wrap {
    padding: 28px 20px 56px;
  }
}

@media (max-width: 640px) {
  .ra-head {
    flex-direction: column;
    gap: 12px;
  }

  .ra-secure {
    margin-top: 0;
  }

  .ra-wrap {
    padding: 22px 14px 48px;
  }

  .ra-token {
    padding: 16px;
  }

  .ra-token__row {
    flex-direction: column;
  }

  .ra-token__row .ra-btn {
    width: 100%;
  }

  .ra-panel__head {
    padding: 14px 16px;
  }

  .ra-filters {
    padding: 0 16px 14px;
  }

  .ra-table th,
  .ra-table td {
    padding: 12px 16px;
  }

  .ra-tab {
    padding: 8px 13px;
  }

  .ra-actions {
    flex-wrap: nowrap;
  }
}

@media (max-width: 400px) {
  .ra-wrap {
    padding: 18px 12px 44px;
  }

  .ra-tabs {
    width: 100%;
  }

  .ra-tab {
    flex: 1 1 0;
  }
}

@media (prefers-reduced-motion: reduce) {
  .ra-spinner,
  .ra-progress span {
    animation-duration: 1.6s;
  }
}
</style>
