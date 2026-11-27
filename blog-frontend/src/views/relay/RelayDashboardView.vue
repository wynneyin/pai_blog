<script setup>
import { computed, onMounted, ref } from "vue";
import { useRoute, useRouter } from "vue-router";
import dayjs from "dayjs";
import {
  fetchRelayCatalog,
  fetchRelayKeys,
  fetchRelayTopups,
  fetchRelayUsage
} from "../../api/relay";

const props = defineProps({
  user: { type: Object, default: null },
  refreshUser: { type: Function, default: null }
});

const route = useRoute();
const router = useRouter();

const catalog = ref(null);
const topups = ref([]);
const keys = ref([]);
const usage = ref([]);
const usageTotal = ref(0);
const loading = ref(true);
const errors = ref([]);
const copied = ref(false);

const paidTotalCents = computed(() =>
  topups.value
    .filter((item) => item.status === "paid")
    .reduce((sum, item) => sum + (Number(item.amountCents) || 0), 0)
);

const activeKeyCount = computed(
  () => keys.value.filter((item) => !item.revokedAt).length
);

const callCount = computed(() => Number(usageTotal.value) || 0);

const recentUsage = computed(() => usage.value.slice(0, 5));

const baseUrl = computed(() => {
  const raw = String(catalog.value?.brand?.baseUrl || "")
    .trim()
    .replace(/\/+$/, "");
  if (raw) return raw;
  if (typeof window !== "undefined" && window.location?.origin) {
    return `${window.location.origin}/v1`;
  }
  return "/v1";
});

const days = computed(() => {
  const today = dayjs().startOf("day");
  return Array.from({ length: 7 }, (_, index) => {
    const day = today.subtract(6 - index, "day");
    const total = usage.value.reduce((sum, item) => {
      if (!item.createdAt) return sum;
      const created = dayjs(item.createdAt);
      if (!created.isValid() || !created.isSame(day, "day")) return sum;
      return sum + (Number(item.costCents) || 0);
    }, 0);
    return {
      key: day.format("YYYY-MM-DD"),
      label: day.format("M/D"),
      total
    };
  });
});

const weekTotalCents = computed(() =>
  days.value.reduce((sum, day) => sum + day.total, 0)
);

const chartHasData = computed(() => days.value.some((day) => day.total > 0));

const chart = computed(() => {
  const W = 700;
  const H = 240;
  const padL = 52;
  const padR = 14;
  const padT = 18;
  const padB = 34;
  const innerW = W - padL - padR;
  const innerH = H - padT - padB;
  const maxCents = Math.max(...days.value.map((day) => day.total), 1);
  const slot = innerW / 7;

  const bars = days.value.map((day, index) => {
    const barHeight =
      day.total > 0 ? Math.max(4, (day.total / maxCents) * innerH) : 0;
    return {
      key: day.key,
      label: day.label,
      value: (day.total / 100).toFixed(2),
      total: day.total,
      x: padL + slot * index + slot * 0.24,
      width: slot * 0.52,
      y: padT + innerH - barHeight,
      height: barHeight,
      centerX: padL + slot * index + slot / 2,
      baseline: padT + innerH
    };
  });

  const grid = [0, 0.5, 1].map((ratio) => ({
    y: padT + innerH * ratio,
    label: ((maxCents * (1 - ratio)) / 100).toFixed(2)
  }));

  return { W, H, padL, padR, padT, baseline: padT + innerH, bars, grid };
});

function cents(value) {
  const number = Number(value);
  return Number.isFinite(number) ? (number / 100).toFixed(2) : "0.00";
}

function formatTime(iso) {
  return iso ? dayjs(iso).format("YYYY-MM-DD HH:mm") : "—";
}

function formatNumber(value) {
  const number = Number(value) || 0;
  return number.toLocaleString("zh-CN");
}

function handleError(e, fallback) {
  if (e?.status === 401) {
    router.replace({
      path: "/relay/sign-in",
      query: { redirect: route.fullPath }
    });
    return e.message || "登录已失效，请重新登录";
  }
  return e?.message || fallback;
}

async function copyBaseUrl() {
  const text = baseUrl.value;
  if (!text) return;
  try {
    await navigator.clipboard.writeText(text);
    copied.value = true;
    setTimeout(() => {
      copied.value = false;
    }, 1600);
  } catch {
    try {
      const area = document.createElement("textarea");
      area.value = text;
      area.style.position = "fixed";
      area.style.opacity = "0";
      document.body.appendChild(area);
      area.select();
      document.execCommand("copy");
      document.body.removeChild(area);
      copied.value = true;
      setTimeout(() => {
        copied.value = false;
      }, 1600);
    } catch {
      copied.value = false;
    }
  }
}

async function loadCatalog() {
  try {
    catalog.value = await fetchRelayCatalog();
  } catch {
    /* 使用 location.origin 兜底 Base URL */
  }
}

async function loadTopups() {
  const data = await fetchRelayTopups();
  topups.value = Array.isArray(data?.list) ? data.list : [];
}

async function loadKeys() {
  const data = await fetchRelayKeys();
  keys.value = Array.isArray(data?.list) ? data.list : [];
}

async function loadUsage() {
  const data = await fetchRelayUsage(200);
  usage.value = Array.isArray(data?.list) ? data.list : [];
  usageTotal.value = Number(data?.total) || usage.value.length;
}

async function loadAll() {
  loading.value = true;
  errors.value = [];
  const tasks = [
    { run: loadTopups(), fallback: "充值记录加载失败" },
    { run: loadKeys(), fallback: "令牌加载失败" },
    { run: loadUsage(), fallback: "用量记录加载失败" }
  ];
  const results = await Promise.allSettled(tasks.map((task) => task.run));
  const messages = [];
  results.forEach((result, index) => {
    if (result.status === "rejected") {
      messages.push(handleError(result.reason, tasks[index].fallback));
    }
  });
  errors.value = [...new Set(messages.filter(Boolean))];
  loading.value = false;
}

onMounted(() => {
  loadCatalog();
  loadAll();
});
</script>

<template>
  <div class="dash">
    <header class="page-head">
      <div>
        <h1>概览</h1>
        <p>查看账户余额、充值、令牌与最近调用情况。</p>
      </div>
    </header>

    <p v-for="message in errors" :key="message" class="alert" role="alert">
      {{ message }}
    </p>

    <section class="stats" aria-label="账户统计">
      <article class="stat-card">
        <span class="stat-card__label">余额</span>
        <span v-if="loading" class="skeleton skeleton--value"></span>
        <strong v-else class="stat-card__value">¥{{ cents(user?.balanceCents) }}</strong>
        <span class="stat-card__hint">当前可用额度</span>
      </article>

      <article class="stat-card">
        <span class="stat-card__label">累计充值</span>
        <span v-if="loading" class="skeleton skeleton--value"></span>
        <strong v-else class="stat-card__value">¥{{ cents(paidTotalCents) }}</strong>
        <span class="stat-card__hint">已支付订单实付合计</span>
      </article>

      <article class="stat-card">
        <span class="stat-card__label">活跃令牌</span>
        <span v-if="loading" class="skeleton skeleton--value"></span>
        <strong v-else class="stat-card__value">{{ formatNumber(activeKeyCount) }}</strong>
        <span class="stat-card__hint">共 {{ formatNumber(keys.length) }} 个令牌</span>
      </article>

      <article class="stat-card">
        <span class="stat-card__label">调用次数</span>
        <span v-if="loading" class="skeleton skeleton--value"></span>
        <strong v-else class="stat-card__value">{{ formatNumber(callCount) }}</strong>
        <span class="stat-card__hint">全部调用记录</span>
      </article>
    </section>

    <section class="card chart-card">
      <header class="card-head">
        <div>
          <h2>最近 7 天消耗</h2>
          <p>按调用时间汇总每日费用</p>
        </div>
        <span class="chart-total">合计 ¥{{ cents(weekTotalCents) }}</span>
      </header>

      <div v-if="loading" class="placeholder">正在加载用量数据…</div>
      <div v-else-if="!chartHasData" class="placeholder">
        最近 7 天暂无调用记录，创建令牌后即可开始调用。
      </div>
      <svg
        v-else
        class="chart-svg"
        :viewBox="`0 0 ${chart.W} ${chart.H}`"
        role="img"
        aria-label="最近 7 天消耗柱状图"
      >
        <g class="chart-grid">
          <line
            v-for="line in chart.grid"
            :key="line.y"
            :x1="chart.padL"
            :x2="chart.W - chart.padR"
            :y1="line.y"
            :y2="line.y"
          />
          <text
            v-for="line in chart.grid"
            :key="`label-${line.y}`"
            :x="chart.padL - 10"
            :y="line.y + 4"
            text-anchor="end"
          >
            {{ line.label }}
          </text>
        </g>

        <g v-for="bar in chart.bars" :key="bar.key">
          <rect
            class="chart-track"
            :x="bar.x"
            :y="chart.padT"
            :width="bar.width"
            :height="chart.baseline - chart.padT"
            rx="5"
          />
          <rect
            v-if="bar.height > 0"
            class="chart-bar"
            :x="bar.x"
            :y="bar.y"
            :width="bar.width"
            :height="bar.height"
            rx="5"
          >
            <title>{{ bar.label }} ¥{{ bar.value }}</title>
          </rect>
          <text
            v-if="bar.height > 0"
            class="chart-value"
            :x="bar.centerX"
            :y="bar.y - 7"
            text-anchor="middle"
          >
            {{ bar.value }}
          </text>
          <text
            class="chart-label"
            :x="bar.centerX"
            :y="chart.baseline + 21"
            text-anchor="middle"
          >
            {{ bar.label }}
          </text>
        </g>
      </svg>
    </section>

    <div class="bottom">
      <section class="card usage-card">
        <header class="card-head">
          <div>
            <h2>最近调用</h2>
            <p>最新 5 条用量记录</p>
          </div>
          <router-link class="head-link" to="/relay/console/logs">查看全部</router-link>
        </header>

        <div v-if="loading" class="placeholder">正在加载用量记录…</div>
        <div v-else-if="!recentUsage.length" class="placeholder">
          还没有调用记录。
        </div>
        <div v-else class="table-wrap">
          <table>
            <thead>
              <tr>
                <th scope="col">时间</th>
                <th scope="col">模型</th>
                <th scope="col">输入 tokens</th>
                <th scope="col">输出 tokens</th>
                <th scope="col">费用</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="item in recentUsage" :key="item.id">
                <td>{{ formatTime(item.createdAt) }}</td>
                <td class="model">{{ item.model }}</td>
                <td>{{ formatNumber(item.promptTokens) }}</td>
                <td>{{ formatNumber(item.completionTokens) }}</td>
                <td class="cost">¥{{ cents(item.costCents) }}</td>
              </tr>
            </tbody>
          </table>
        </div>
      </section>

      <section class="card quick-card">
        <header class="card-head">
          <div>
            <h2>快速接入</h2>
            <p>兼容 OpenAI SDK 与常见客户端</p>
          </div>
        </header>

        <span class="quick-label">Base URL</span>
        <div class="quick-base">
          <code>{{ baseUrl }}</code>
          <button class="copy-btn" type="button" @click="copyBaseUrl">
            {{ copied ? "已复制" : "复制" }}
          </button>
        </div>

        <p class="quick-note">
          在客户端中填入 Base URL 与 API Key，即可调用全部在售模型。
        </p>

        <div class="quick-actions">
          <router-link class="btn btn--primary" to="/relay/console/keys">
            创建令牌
          </router-link>
          <router-link class="btn" to="/relay/docs">查看文档</router-link>
        </div>
      </section>
    </div>
  </div>
</template>

<style scoped>
.dash {
  --primary: var(--r-primary, #1677ff);
  --primary-dark: var(--r-primary-dark, #0958d9);
  --text: var(--r-text, #0b1220);
  --muted: var(--r-muted, #5b6b84);
  --line: var(--r-line, #e6ecf5);
  --soft: var(--r-soft, #f7f9fc);
  --danger: var(--r-danger, #f04438);
  color: var(--text);
}

.dash a {
  color: inherit;
}

.page-head {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 16px;
}

.page-head h1 {
  margin: 0;
  font-size: 20px;
  font-weight: 600;
  letter-spacing: -0.01em;
}

.page-head p {
  margin: 4px 0 0;
  font-size: 12.5px;
  color: var(--muted);
}

.alert {
  margin: 0 0 16px;
  padding: 11px 14px;
  border: 1px solid #ffd4d0;
  border-radius: 10px;
  background: #fff4f2;
  color: #c8362c;
  font-size: 13px;
  line-height: 1.55;
}

.stats {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 0;
  margin-bottom: 16px;
  border: 1px solid #ebedf0;
  border-radius: 12px;
  background: #ffffff;
  overflow: hidden;
}

.stat-card {
  display: flex;
  flex-direction: column;
  gap: 4px;
  padding: 14px 20px;
  border: 0;
  border-radius: 0;
  background: #ffffff;
  box-shadow: none;
}

.stat-card + .stat-card {
  border-left: 1px solid #eff0f2;
}

.stat-card__label {
  font-size: 12.5px;
  color: var(--muted);
}

.stat-card__value {
  font-size: 26px;
  font-weight: 600;
  letter-spacing: -0.01em;
  line-height: 1.1;
  font-variant-numeric: tabular-nums;
}

.stat-card__hint {
  font-size: 11.5px;
  color: #98a2b3;
}

.skeleton {
  display: inline-block;
  height: 30px;
  border-radius: 8px;
  background: linear-gradient(90deg, #eef2f8 25%, #f7f9fc 50%, #eef2f8 75%);
  background-size: 200% 100%;
  animation: shimmer 1.2s ease-in-out infinite;
}

.skeleton--value {
  width: 120px;
}

@keyframes shimmer {
  from {
    background-position: 200% 0;
  }
  to {
    background-position: -200% 0;
  }
}

.card {
  padding: 20px;
  border: 1px solid #ebedf0;
  border-radius: 12px;
  background: #ffffff;
  box-shadow: none;
}

.card-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 14px;
}

.card-head h2 {
  margin: 0;
  font-size: 15px;
  font-weight: 600;
  letter-spacing: 0;
}

.card-head p {
  margin: 4px 0 0;
  font-size: 12px;
  color: var(--muted);
}

.chart-total {
  flex: none;
  font-size: 13px;
  font-weight: 600;
  color: var(--primary);
}

.chart-card {
  margin-bottom: 18px;
}

.chart-svg {
  display: block;
  width: 100%;
  height: auto;
}

.chart-grid line {
  stroke: var(--line);
  stroke-width: 1;
}

.chart-grid text {
  fill: #9aa8bf;
  font-size: 11px;
}

.chart-track {
  fill: #f2f6fc;
}

.chart-bar {
  fill: var(--primary);
  transition: fill 0.16s;
}

.chart-bar:hover {
  fill: var(--primary-dark);
}

.chart-value {
  fill: #44506b;
  font-size: 11px;
  font-weight: 600;
}

.chart-label {
  fill: #8a97ad;
  font-size: 11px;
}

.placeholder {
  padding: 48px 16px;
  border-radius: 0;
  background: transparent;
  color: #98a2b3;
  font-size: 13px;
  text-align: center;
}

.bottom {
  display: grid;
  grid-template-columns: minmax(0, 1.65fr) minmax(0, 1fr);
  gap: 18px;
  align-items: start;
}

.head-link {
  flex: none;
  font-size: 13px;
  font-weight: 600;
  color: var(--primary);
}

.head-link:hover {
  color: var(--primary-dark);
  text-decoration: underline;
}

.table-wrap {
  overflow-x: auto;
  margin: 16px -20px -20px;
}

table {
  width: 100%;
  border-collapse: collapse;
  font-size: 13px;
}

th {
  padding: 10px 16px;
  background: #fafafa;
  border-bottom: 1px solid #eff0f2;
  color: var(--muted);
  font-size: 12px;
  font-weight: 500;
  text-align: left;
  white-space: nowrap;
}

th:first-child {
  border-radius: 0;
}

th:last-child {
  border-radius: 0;
}

td {
  padding: 13px 16px;
  border-bottom: 1px solid #f2f2f2;
  white-space: nowrap;
  font-variant-numeric: tabular-nums;
}

tbody tr:last-child td {
  border-bottom: none;
}

td.model {
  font-weight: 600;
}

td.cost {
  font-weight: 600;
  color: var(--primary);
}

.quick-card {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.quick-label {
  font-size: 12px;
  font-weight: 600;
  color: var(--muted);
}

.quick-base {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 8px 8px 12px;
  border: 1px solid var(--line);
  border-radius: 10px;
  background: var(--soft);
}

.quick-base code {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-family: "SFMono-Regular", ui-monospace, Menlo, Consolas, monospace;
  font-size: 12.5px;
  color: var(--text);
}

.copy-btn {
  flex: none;
  height: 30px;
  padding: 0 12px;
  border: 1px solid var(--line);
  border-radius: 8px;
  background: #ffffff;
  color: var(--text);
  font-size: 12.5px;
  font-weight: 600;
  cursor: pointer;
}

.copy-btn:hover {
  border-color: var(--primary);
  color: var(--primary);
}

.quick-note {
  margin: 0;
  font-size: 12.5px;
  line-height: 1.6;
  color: var(--muted);
}

.quick-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
  margin-top: 4px;
}

.btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  height: 38px;
  padding: 0 16px;
  border: 1px solid var(--line);
  border-radius: 10px;
  background: #ffffff;
  color: var(--text);
  font-size: 13.5px;
  font-weight: 600;
  transition: border-color 0.16s, background 0.16s, color 0.16s;
}

.btn:hover {
  border-color: var(--primary);
  color: var(--primary);
}

.btn--primary {
  border-color: var(--primary);
  background: var(--primary);
  color: #ffffff;
}

.btn--primary:hover {
  border-color: var(--primary-dark);
  background: var(--primary-dark);
  color: #ffffff;
}

@media (max-width: 1180px) {
  .stats {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .stat-card:nth-child(3) {
    border-left: 0;
  }

  .stat-card:nth-child(3),
  .stat-card:nth-child(4) {
    border-top: 1px solid #eff0f2;
  }
}

@media (max-width: 1000px) {
  .bottom {
    grid-template-columns: minmax(0, 1fr);
  }
}

@media (max-width: 560px) {
  .stat-card__value {
    font-size: 24px;
  }
}
</style>
