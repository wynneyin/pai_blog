<script setup>
import { computed, onMounted, ref } from "vue";
import { useRoute, useRouter } from "vue-router";
import dayjs from "dayjs";
import { fetchRelayUsage } from "../../api/relay";

defineProps({
  user: { type: Object, default: null },
  refreshUser: { type: Function, default: null }
});

const route = useRoute();
const router = useRouter();

const usage = ref([]);
const total = ref(0);
const loading = ref(true);
const error = ref("");

const totalCostCents = computed(() =>
  usage.value.reduce((sum, item) => sum + (Number(item.costCents) || 0), 0)
);

function cents(value) {
  const number = Number(value);
  return Number.isFinite(number) ? (number / 100).toFixed(2) : "0.00";
}

function formatTime(iso) {
  return iso ? dayjs(iso).format("YYYY-MM-DD HH:mm:ss") : "—";
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

async function loadUsage() {
  loading.value = true;
  error.value = "";
  try {
    const data = await fetchRelayUsage(200);
    usage.value = Array.isArray(data?.list) ? data.list : [];
    total.value = Number(data?.total) || usage.value.length;
  } catch (e) {
    error.value = handleError(e, "用量记录加载失败");
  } finally {
    loading.value = false;
  }
}

onMounted(loadUsage);
</script>

<template>
  <div class="logs">
    <header class="page-head">
      <div>
        <h1>使用日志</h1>
        <p>查看最近的模型调用、Token 用量与费用。</p>
      </div>
    </header>

    <section class="summary" aria-label="用量汇总">
      <div class="summary__item">
        <span>总调用次数</span>
        <strong v-if="!loading">{{ formatNumber(total) }}</strong>
        <strong v-else class="skeleton">—</strong>
      </div>
      <div class="summary__item">
        <span>总费用（最近 200 条）</span>
        <strong v-if="!loading">¥{{ cents(totalCostCents) }}</strong>
        <strong v-else class="skeleton">—</strong>
      </div>
    </section>

    <section class="card">
      <header class="card-head">
        <div>
          <h2>调用明细</h2>
          <p>按时间倒序，最多展示最近 200 条记录</p>
        </div>
      </header>

      <p v-if="error" class="alert" role="alert">{{ error }}</p>
      <div v-else-if="loading" class="placeholder">正在加载用量记录…</div>
      <div v-else-if="!usage.length" class="placeholder">
        还没有调用记录，创建令牌并调用任意模型后即可在此查看。
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
            <tr v-for="item in usage" :key="item.id">
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
  </div>
</template>

<style scoped>
.logs {
  --primary: var(--r-primary, #1677ff);
  --text: var(--r-text, #0b1220);
  --muted: var(--r-muted, #5b6b84);
  --line: var(--r-line, #e6ecf5);
  --soft: var(--r-soft, #f7f9fc);
  --danger: var(--r-danger, #f04438);
  color: var(--text);
}

.logs a {
  color: inherit;
}

.page-head {
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

.summary {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 200px));
  gap: 16px;
  margin-bottom: 16px;
}

.summary__item {
  display: flex;
  flex-direction: column;
  gap: 4px;
  padding: 14px 18px;
  border: 1px solid #ebedf0;
  border-radius: 12px;
  background: #ffffff;
  box-shadow: none;
}

.summary__item span {
  font-size: 12.5px;
  color: var(--muted);
}

.summary__item strong {
  font-size: 22px;
  font-weight: 600;
  letter-spacing: -0.01em;
  font-variant-numeric: tabular-nums;
}

.skeleton {
  color: #c3cedf;
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
}

.card-head p {
  margin: 4px 0 0;
  font-size: 12px;
  color: var(--muted);
}

.alert {
  margin: 0;
  padding: 11px 14px;
  border: 1px solid #ffd4d0;
  border-radius: 10px;
  background: #fff4f2;
  color: #c8362c;
  font-size: 13px;
  line-height: 1.55;
}

.placeholder {
  padding: 48px 16px;
  border-radius: 0;
  background: transparent;
  color: #98a2b3;
  font-size: 13px;
  text-align: center;
}

.table-wrap {
  overflow-x: auto;
  margin: 16px -20px -20px;
}

table {
  width: 100%;
  border-collapse: collapse;
  font-size: 13px;
  min-width: 560px;
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

tbody tr:hover {
  background: #fafcff;
}

td.model {
  font-weight: 600;
}

td.cost {
  font-weight: 600;
  color: var(--primary);
}

@media (max-width: 560px) {
  .summary {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .summary__item strong {
    font-size: 19px;
  }
}
</style>
