<script setup>
import { computed, onMounted, ref } from "vue";
import { useRoute, useRouter } from "vue-router";
import dayjs from "dayjs";
import {
  createRelayTopup,
  fetchRelayCatalog,
  fetchRelayTopups,
  payRelayTopup
} from "../../api/relay";

const props = defineProps({
  user: { type: Object, default: null },
  refreshUser: { type: Function, default: null }
});

const route = useRoute();
const router = useRouter();

const STATUS_TEXT = {
  pending: "待支付",
  paid: "已支付",
  cancelled: "已取消"
};

const catalog = ref(null);
const catalogLoading = ref(true);
const catalogError = ref("");

const topups = ref([]);
const topupsLoading = ref(true);
const topupError = ref("");

const actionError = ref("");
const successMessage = ref("");

const creatingPlan = ref("");
const payOrder = ref(null);
const payMethod = ref("alipay");
const paying = ref(false);
const payError = ref("");

const deepLinkHandled = ref(false);

const plans = computed(() =>
  Array.isArray(catalog.value?.plans) ? catalog.value.plans : []
);

const balanceYuan = computed(() => cents(props.user?.balanceCents));

function cents(value) {
  const number = Number(value);
  return Number.isFinite(number) ? (number / 100).toFixed(2) : "0.00";
}

function formatTime(iso) {
  return iso ? dayjs(iso).format("YYYY-MM-DD HH:mm") : "—";
}

function statusText(status) {
  return STATUS_TEXT[status] || status || "—";
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

async function loadCatalog() {
  catalogLoading.value = true;
  catalogError.value = "";
  try {
    catalog.value = await fetchRelayCatalog();
  } catch (e) {
    catalogError.value = e?.message || "充值档位加载失败";
  } finally {
    catalogLoading.value = false;
  }
}

async function loadTopups() {
  topupsLoading.value = true;
  topupError.value = "";
  try {
    const data = await fetchRelayTopups();
    topups.value = Array.isArray(data?.list) ? data.list : [];
  } catch (e) {
    topupError.value = handleError(e, "充值记录加载失败");
  } finally {
    topupsLoading.value = false;
  }
}

async function refreshAccount() {
  if (typeof props.refreshUser !== "function") return;
  try {
    await props.refreshUser();
  } catch {
    /* 401 时 layout 会负责跳转登录 */
  }
}

function openPay(order) {
  payOrder.value = order;
  payMethod.value = order?.method === "wechat" ? "wechat" : "alipay";
  payError.value = "";
  successMessage.value = "";
}

async function startTopup(plan) {
  if (!plan || creatingPlan.value) return;
  creatingPlan.value = plan.id;
  actionError.value = "";
  payError.value = "";
  successMessage.value = "";
  try {
    const order = await createRelayTopup(plan.id);
    openPay(order);
    await loadTopups();
  } catch (e) {
    actionError.value = handleError(e, "创建充值订单失败");
  } finally {
    creatingPlan.value = "";
  }
}

function continuePay(order) {
  openPay(order);
}

async function confirmPay() {
  if (!payOrder.value || paying.value) return;
  paying.value = true;
  payError.value = "";
  successMessage.value = "";
  const orderNo = payOrder.value.orderNo;
  try {
    const order = await payRelayTopup(orderNo, payMethod.value);
    payOrder.value = order;
    if (order?.status === "paid") {
      successMessage.value = `支付成功，实际到账 ¥${cents(order.creditCents)}。`;
      await Promise.all([refreshAccount(), loadTopups()]);
    } else {
      await loadTopups();
    }
  } catch (e) {
    if (e?.status === 409) {
      payError.value = e.message || "订单已取消";
      payOrder.value = { ...payOrder.value, status: "cancelled" };
      await loadTopups();
    } else {
      payError.value = handleError(e, "支付确认失败");
    }
  } finally {
    paying.value = false;
  }
}

function clearPlanQuery() {
  if (!("plan" in route.query)) return;
  const query = { ...route.query };
  delete query.plan;
  router.replace({ path: route.path, query });
}

async function applyDeepLink() {
  if (deepLinkHandled.value) return;
  const raw = route.query.plan;
  const planId = typeof raw === "string" ? raw.trim() : "";
  if (!planId || !plans.value.length) return;
  deepLinkHandled.value = true;
  const plan = plans.value.find((item) => String(item.id) === planId);
  clearPlanQuery();
  if (!plan) {
    actionError.value = "充值档位不存在，请重新选择。";
    return;
  }
  await startTopup(plan);
}

onMounted(async () => {
  await Promise.all([loadCatalog(), loadTopups()]);
  await applyDeepLink();
});
</script>

<template>
  <div class="topup">
    <header class="page-head">
      <div>
        <h1>钱包充值</h1>
        <p>选择充值档位创建订单，完成转账后联系管理员确认到账。</p>
      </div>
      <div class="balance">
        <span>当前余额</span>
        <strong>¥{{ balanceYuan }}</strong>
      </div>
    </header>

    <p v-if="successMessage" class="success" role="status">{{ successMessage }}</p>
    <p v-if="actionError" class="alert" role="alert">{{ actionError }}</p>

    <section class="plans-section">
      <header class="section-head">
        <h2>充值档位</h2>
        <span>实付金额与到账额度以订单为准</span>
      </header>

      <div v-if="catalogLoading && !plans.length" class="placeholder">
        正在加载充值档位…
      </div>
      <div v-else-if="catalogError" class="placeholder placeholder--error">
        <p>{{ catalogError }}</p>
        <button class="btn" type="button" @click="loadCatalog">重新加载</button>
      </div>
      <div v-else-if="!plans.length" class="placeholder">暂无可用的充值档位。</div>
      <div v-else class="plan-grid">
        <article
          v-for="plan in plans"
          :key="plan.id"
          class="plan"
          :class="{ 'plan--hot': plan.hot }"
        >
          <span v-if="plan.hot" class="plan__badge">热门</span>
          <h3 class="plan__name">{{ plan.name }}</h3>
          <p class="plan__price">
            <span>¥</span>{{ plan.price }}
          </p>
          <p class="plan__credit">{{ plan.bonus || `到账 ¥${plan.credit}` }}</p>
          <ul v-if="Array.isArray(plan.perks) && plan.perks.length" class="plan__perks">
            <li v-for="perk in plan.perks" :key="perk">{{ perk }}</li>
          </ul>
          <button
            class="btn btn--primary btn--block"
            type="button"
            :disabled="!!creatingPlan"
            @click="startTopup(plan)"
          >
            {{ creatingPlan === plan.id ? "创建中…" : "立即充值" }}
          </button>
        </article>
      </div>
    </section>

    <section v-if="payOrder" class="card pay-card">
      <header class="card-head">
        <div>
          <h2>支付订单</h2>
          <p>{{ payOrder.planName || "充值订单" }}</p>
        </div>
        <span class="badge" :class="`badge--${payOrder.status}`">
          {{ statusText(payOrder.status) }}
        </span>
      </header>

      <dl class="pay-facts">
        <div>
          <dt>订单号</dt>
          <dd class="mono">{{ payOrder.orderNo }}</dd>
        </div>
        <div>
          <dt>实付金额</dt>
          <dd>¥{{ cents(payOrder.amountCents) }}</dd>
        </div>
        <div>
          <dt>到账额度</dt>
          <dd class="credit">¥{{ cents(payOrder.creditCents) }}</dd>
        </div>
        <div>
          <dt>创建时间</dt>
          <dd>{{ formatTime(payOrder.createdAt) }}</dd>
        </div>
      </dl>

      <template v-if="payOrder.status === 'pending'">
        <p class="pay-tip">选择支付方式，完成转账后点击「我已完成支付」，再由管理员核对到账。</p>
        <div class="methods" role="group" aria-label="支付方式">
          <button
            class="method"
            :class="{ 'method--active': payMethod === 'alipay' }"
            type="button"
            :aria-pressed="payMethod === 'alipay'"
            @click="payMethod = 'alipay'"
          >
            支付宝
          </button>
          <button
            class="method"
            :class="{ 'method--active': payMethod === 'wechat' }"
            type="button"
            :aria-pressed="payMethod === 'wechat'"
            @click="payMethod = 'wechat'"
          >
            微信支付
          </button>
        </div>
        <button
          class="btn btn--primary"
          type="button"
          :disabled="paying"
          @click="confirmPay"
        >
          {{ paying ? "确认中…" : "我已完成支付" }}
        </button>
      </template>

      <p v-else-if="payOrder.status === 'paid'" class="pay-done">
        支付成功，余额已更新。
      </p>
      <p v-else class="pay-done pay-done--muted">
        该订单已取消，不会扣款也不会增加余额。
      </p>

      <p v-if="payError" class="alert alert--tight" role="alert">{{ payError }}</p>
    </section>

    <section class="card history-card">
      <header class="card-head">
        <div>
          <h2>充值记录</h2>
          <p>最近 100 条订单</p>
        </div>
      </header>

      <p v-if="topupError" class="alert alert--tight" role="alert">{{ topupError }}</p>
      <div v-else-if="topupsLoading && !topups.length" class="placeholder">
        正在加载充值记录…
      </div>
      <div v-else-if="!topups.length" class="placeholder">还没有充值记录。</div>
      <div v-else class="table-wrap">
        <table>
          <thead>
            <tr>
              <th scope="col">订单号</th>
              <th scope="col">档位</th>
              <th scope="col">实付</th>
              <th scope="col">到账</th>
              <th scope="col">状态</th>
              <th scope="col">时间</th>
              <th scope="col">操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="order in topups" :key="order.orderNo">
              <td class="mono">{{ order.orderNo }}</td>
              <td>{{ order.planName }}</td>
              <td>¥{{ cents(order.amountCents) }}</td>
              <td class="credit">¥{{ cents(order.creditCents) }}</td>
              <td>
                <span class="badge" :class="`badge--${order.status}`">
                  {{ statusText(order.status) }}
                </span>
              </td>
              <td>{{ formatTime(order.createdAt) }}</td>
              <td>
                <button
                  v-if="order.status === 'pending'"
                  class="btn btn--sm"
                  type="button"
                  @click="continuePay(order)"
                >
                  继续支付
                </button>
                <span v-else class="muted">—</span>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </section>
  </div>
</template>

<style scoped>
.topup {
  --primary: var(--r-primary, #1677ff);
  --primary-dark: var(--r-primary-dark, #0958d9);
  --text: var(--r-text, #0b1220);
  --muted: var(--r-muted, #5b6b84);
  --line: var(--r-line, #e6ecf5);
  --soft: var(--r-soft, #f7f9fc);
  --danger: var(--r-danger, #f04438);
  --success: var(--r-success, #12b76a);
  color: var(--text);
}

.topup a {
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

.balance {
  flex: none;
  display: flex;
  flex-direction: column;
  align-items: flex-end;
  gap: 2px;
  padding: 8px 16px;
  border: 1px solid #ebedf0;
  border-radius: 12px;
  background: #ffffff;
}

.balance span {
  font-size: 12px;
  color: var(--muted);
}

.balance strong {
  font-size: 18px;
  font-weight: 800;
  letter-spacing: -0.02em;
}

.success {
  margin: 0 0 16px;
  padding: 11px 14px;
  border: 1px solid #b7ebd2;
  border-radius: 10px;
  background: #ecfdf3;
  color: #0a8f53;
  font-size: 13px;
  line-height: 1.55;
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

.alert--tight {
  margin: 12px 0 0;
}

.plans-section {
  margin-bottom: 18px;
}

.section-head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 12px;
}

.section-head h2 {
  margin: 0;
  font-size: 16px;
  font-weight: 700;
}

.section-head span {
  font-size: 12.5px;
  color: var(--muted);
}

.plan-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 16px;
}

.plan {
  position: relative;
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding: 20px;
  border: 1px solid var(--line);
  border-radius: 16px;
  background: #ffffff;
  box-shadow: 0 6px 22px rgba(23, 58, 110, 0.04);
  transition: transform 0.16s, box-shadow 0.16s, border-color 0.16s;
}

.plan:hover {
  transform: translateY(-2px);
  box-shadow: 0 14px 34px rgba(23, 58, 110, 0.08);
}

.plan--hot {
  border-color: #9dc5ff;
  box-shadow: 0 10px 30px rgba(22, 119, 255, 0.12);
}

.plan__badge {
  position: absolute;
  top: 14px;
  right: 14px;
  padding: 3px 9px;
  border-radius: 999px;
  background: var(--primary);
  color: #ffffff;
  font-size: 11px;
  font-weight: 600;
}

.plan__name {
  margin: 0;
  font-size: 14px;
  font-weight: 600;
  color: var(--muted);
}

.plan__price {
  margin: 0;
  font-size: 32px;
  font-weight: 800;
  letter-spacing: -0.04em;
  line-height: 1.1;
}

.plan__price span {
  margin-right: 2px;
  font-size: 18px;
  font-weight: 700;
  vertical-align: 4px;
}

.plan__credit {
  margin: 0;
  font-size: 13.5px;
  font-weight: 600;
  color: var(--primary);
}

.plan__bonus {
  margin: 0;
  font-size: 12.5px;
  color: var(--muted);
}

.plan__perks {
  flex: 1;
  margin: 6px 0 10px;
  padding: 0;
  list-style: none;
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.plan__perks li {
  position: relative;
  padding-left: 16px;
  font-size: 12.5px;
  color: var(--muted);
}

.plan__perks li::before {
  content: "";
  position: absolute;
  left: 2px;
  top: 6px;
  width: 5px;
  height: 5px;
  border-radius: 50%;
  background: #9dc5ff;
}

.card {
  padding: 20px;
  border: 1px solid #ebedf0;
  border-radius: 12px;
  background: #ffffff;
  box-shadow: none;
}

.pay-card {
  margin-bottom: 16px;
  max-width: 720px;
}

.history-card {
  margin-bottom: 8px;
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

.pay-facts {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 14px 20px;
  margin: 0 0 18px;
  padding: 16px;
  border-radius: 12px;
  background: var(--soft);
}

.pay-facts dt {
  font-size: 12px;
  color: var(--muted);
}

.pay-facts dd {
  margin: 4px 0 0;
  font-size: 14px;
  font-weight: 600;
  overflow-wrap: anywhere;
}

.credit {
  color: var(--primary);
  font-weight: 600;
}

.pay-tip {
  margin: 0 0 12px;
  font-size: 13px;
  color: var(--muted);
}

.methods {
  display: flex;
  gap: 10px;
  margin-bottom: 14px;
}

.method {
  flex: 1;
  height: 40px;
  border: 1px solid var(--line);
  border-radius: 10px;
  background: #ffffff;
  color: var(--muted);
  font-size: 13.5px;
  font-weight: 600;
  font-family: inherit;
  cursor: pointer;
  transition: border-color 0.16s, color 0.16s, background 0.16s;
}

.method:hover {
  border-color: #9dc5ff;
  color: var(--primary);
}

.method--active {
  border-color: var(--primary);
  background: #eaf2ff;
  color: var(--primary);
}

.pay-done {
  margin: 0;
  padding: 14px;
  border-radius: 10px;
  background: #ecfdf3;
  color: #0a8f53;
  font-size: 13.5px;
  font-weight: 600;
  text-align: center;
}

.pay-done--muted {
  background: var(--soft);
  color: var(--muted);
  font-weight: 500;
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
  font-family: inherit;
  cursor: pointer;
  transition: border-color 0.16s, background 0.16s, color 0.16s;
}

.btn:hover:not(:disabled) {
  border-color: var(--primary);
  color: var(--primary);
}

.btn--primary {
  border-color: var(--primary);
  background: var(--primary);
  color: #ffffff;
}

.btn--primary:hover:not(:disabled) {
  border-color: var(--primary-dark);
  background: var(--primary-dark);
  color: #ffffff;
}

.btn--block {
  width: 100%;
  margin-top: auto;
}

.btn--sm {
  height: 32px;
  padding: 0 12px;
  font-size: 12.5px;
}

.btn:disabled {
  opacity: 0.55;
  cursor: not-allowed;
}

.placeholder {
  padding: 48px 16px;
  border-radius: 0;
  background: transparent;
  color: #98a2b3;
  font-size: 13px;
  text-align: center;
}

.placeholder p {
  margin: 0 0 12px;
}

.placeholder--error {
  color: #c8362c;
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

.mono {
  font-family: "SFMono-Regular", ui-monospace, Menlo, Consolas, monospace;
  font-size: 12.5px;
}

.badge {
  display: inline-flex;
  align-items: center;
  height: 24px;
  padding: 0 10px;
  border-radius: 999px;
  font-size: 12px;
  font-weight: 600;
}

.badge--pending {
  background: #fff7e6;
  color: #b25e09;
}

.badge--paid {
  background: #eafaf2;
  color: #0a8f53;
}

.badge--cancelled {
  background: #f1f3f7;
  color: #8a97ad;
}

.muted {
  color: #9aa8bf;
}

@media (max-width: 1100px) {
  .plan-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}

@media (max-width: 640px) {
  .page-head {
    flex-direction: column;
    align-items: flex-start;
  }

  .balance {
    align-items: flex-start;
  }

  .plan-grid {
    grid-template-columns: minmax(0, 1fr);
  }

  .pay-facts {
    grid-template-columns: minmax(0, 1fr);
  }
}
</style>
