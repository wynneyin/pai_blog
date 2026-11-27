<script setup>
import { computed, onMounted, ref, watch } from "vue";
import dayjs from "dayjs";
import { fetchCatalog, fetchOrder, payOrder } from "../api/shop";
import { loadRelaySession } from "../api/relay";
import { loadRecentOrders, rememberOrder, STATUS_TEXT } from "../utils/shopOrders";
import { paymentLink, paymentNote, resolvePaymentQr } from "../utils/paymentQr";

const orderNo = ref("");
const lookupToken = ref("");
const relayLoggedIn = ref(false);
const result = ref(null);
const searched = ref(false);
const loading = ref(false);
const error = ref("");
const paying = ref(false);
const recent = ref(loadRecentOrders());
const selfPay = ref(true);
const supportUrl = ref("");
const supportLabel = ref("管理员");
const payment = ref({});
const method = ref("alipay");
const lastRefreshed = ref("");
const qr = ref({ image: "", link: "", generated: false });
let qrRequest = 0;

const methodLabel = computed(() => (method.value === "alipay" ? "支付宝" : "微信"));

const configuredQr = computed(() => {
  const key = method.value === "alipay" ? "alipayQr" : "wechatQr";
  const value = payment.value ? payment.value[key] : "";
  return typeof value === "string" ? value.trim() : "";
});

const methodLink = computed(() => paymentLink(payment.value, method.value));
const paymentTip = computed(() => paymentNote(payment.value));

watch(
  [configuredQr, methodLink],
  async ([image, link]) => {
    const request = ++qrRequest;
    const resolved = await resolvePaymentQr({ image, link });
    if (request === qrRequest) qr.value = resolved;
  },
  { immediate: true }
);

onMounted(async () => {
  relayLoggedIn.value = Boolean(loadRelaySession()?.token);
  try {
    const catalog = await fetchCatalog();
    selfPay.value = catalog.allowSelfPay !== false;
    payment.value = catalog.payment || {};
    supportUrl.value =
      typeof payment.value.contactUrl === "string" ? payment.value.contactUrl : "";
    supportLabel.value =
      typeof payment.value.contact === "string" && payment.value.contact
        ? payment.value.contact
        : "管理员";
  } catch {
    /* 目录加载失败时保持默认的本地确认流程 */
  }
});

async function search() {
  const key = orderNo.value.trim();
  if (!key) {
    error.value = "请输入订单号";
    return;
  }
  loading.value = true;
  error.value = "";
  try {
    const found = await fetchOrder(key, { lookupToken: lookupToken.value.trim() });
    result.value = found;
    if (found.paymentMethod === "alipay" || found.paymentMethod === "wechat") {
      method.value = found.paymentMethod;
    }
    rememberOrder({ ...found, lookupToken: lookupToken.value.trim() || found.lookupToken });
  } catch (e) {
    result.value = null;
    error.value = e.message || "订单查询失败";
  } finally {
    searched.value = true;
    loading.value = false;
    lastRefreshed.value = dayjs().format("HH:mm:ss");
    recent.value = loadRecentOrders();
  }
}

function pick(item) {
  orderNo.value = item.orderNo;
  lookupToken.value = item.lookupToken || "";
  search();
}

async function confirmPaid() {
  if (!result.value || result.value.status !== "pending") return;
  paying.value = true;
  error.value = "";
  try {
    const paid = await payOrder(
      result.value.orderNo,
      method.value || result.value.paymentMethod || "",
      { lookupToken: lookupToken.value.trim() }
    );
    result.value = paid;
    rememberOrder(paid);
    recent.value = loadRecentOrders();
  } catch (e) {
    error.value = e.message || "支付确认失败";
  } finally {
    paying.value = false;
  }
}

function formatTime(iso) {
  return iso ? dayjs(iso).format("YYYY-MM-DD HH:mm") : "—";
}

function formatCents(value) {
  const cents = Number(value);
  return Number.isFinite(cents) ? (cents / 100).toFixed(2) : "—";
}
</script>

<template>
  <main class="shop-main">
    <header class="shop-page-head">
      <p class="shop-kicker">Orders</p>
      <h1>订单查询</h1>
      <p>输入下单后生成的订单号和查询凭证，查看支付状态与商品信息。</p>
    </header>

    <form class="shop-lookup" @submit.prevent="search">
      <input
        v-model="orderNo"
        class="shop-lookup__input"
        type="text"
        placeholder="例如 PB20261126143022ABCD"
        aria-label="订单号"
      />
      <input
        v-model="lookupToken"
        class="shop-lookup__input"
        type="text"
        placeholder="查询凭证（已登录中转站可留空）"
        aria-label="查询凭证"
        autocomplete="off"
      />
      <button class="shop-btn" type="submit" :disabled="loading">
        {{ loading ? "查询中…" : "查询订单" }}
      </button>
    </form>
    <p class="shop-hint">
      <template v-if="relayLoggedIn">
        已检测到中转站登录，可留空凭证查询本账号的订单；订单属于其他账号时仍需查询凭证。
      </template>
      <template v-else>
        查询凭证在下单成功时显示一次，只保存在本机；换设备查询请使用下单邮箱对应的中转站账号登录。
      </template>
    </p>

    <p v-if="error" class="shop-error">{{ error }}</p>
    <p v-else-if="searched && !result && !loading" class="shop-error">
      没有找到该订单，请核对订单号和查询凭证是否正确。
    </p>

    <section v-if="result" class="shop-panel shop-result" aria-live="polite">
      <div class="shop-result__head">
        <p class="shop-result__no">{{ result.orderNo }}</p>
        <span class="shop-status" :class="`shop-status--${result.status}`">
          {{ STATUS_TEXT[result.status] || result.status }}
        </span>
      </div>
      <p v-if="lastRefreshed" class="shop-result__meta">最近刷新 {{ lastRefreshed }}</p>
      <dl class="shop-dl">
        <div>
          <dt>商品</dt>
          <dd>{{ result.productName }}</dd>
        </div>
        <div>
          <dt>数量</dt>
          <dd>{{ result.quantity }} 件</dd>
        </div>
        <div>
          <dt>金额</dt>
          <dd>¥{{ result.total }}</dd>
        </div>
        <div>
          <dt>{{ result.relayPlanId ? "中转邮箱" : "联系方式" }}</dt>
          <dd>{{ result.contact }}</dd>
        </div>
        <div v-if="result.relayPlanId">
          <dt>到账额度</dt>
          <dd>
            ¥{{ formatCents(result.relayCreditCents) }} ·
            {{ result.relayCreditedAt ? "已到账" : "未到账" }}
          </dd>
        </div>
        <div>
          <dt>下单时间</dt>
          <dd>{{ formatTime(result.createdAt) }}</dd>
        </div>
        <div>
          <dt>备注</dt>
          <dd>{{ result.note || "—" }}</dd>
        </div>
      </dl>
      <div v-if="result.status === 'pending'" class="shop-paybox">
        <div class="shop-paybox__head">
          <strong>待付款</strong>
          <span>应付 ¥{{ Number(result.total).toFixed(2) }}</span>
        </div>
        <div class="shop-pay__methods">
          <button
            class="shop-pay__method"
            :class="{ 'shop-pay__method--active': method === 'alipay' }"
            type="button"
            @click="method = 'alipay'"
          >
            支付宝
          </button>
          <button
            class="shop-pay__method"
            :class="{ 'shop-pay__method--active': method === 'wechat' }"
            type="button"
            @click="method = 'wechat'"
          >
            微信支付
          </button>
        </div>
        <div v-if="qr.image" class="shop-qr">
          <img :src="qr.image" :alt="`${methodLabel}收款码`" />
          <p v-if="qr.generated" class="shop-pay__note">使用{{ methodLabel }}扫码付款</p>
        </div>
        <div v-else class="shop-qr shop-qr--manual">
          <span>当前为人工确认收款</span>
          <a
            v-if="supportUrl"
            class="shop-pay__open"
            :href="supportUrl"
            target="_blank"
            rel="noopener noreferrer"
          >
            联系{{ supportLabel }}获取收款方式 →
          </a>
          <span v-else>请联系管理员获取收款方式。</span>
        </div>
        <a
          v-if="qr.link"
          class="shop-pay__open"
          :href="qr.link"
          target="_blank"
          rel="noopener noreferrer"
        >
          在{{ methodLabel }}中打开付款 →
        </a>
        <p v-if="paymentTip" class="shop-hint">{{ paymentTip }}</p>
      </div>
      <div style="margin-top: 16px; display: flex; flex-wrap: wrap; gap: 10px">
        <button
          class="shop-btn shop-btn--ghost"
          type="button"
          :disabled="loading"
          @click="search"
        >
          {{ loading ? "刷新中…" : "刷新状态" }}
        </button>
        <button
          v-if="result.status === 'pending' && selfPay"
          class="shop-btn"
          type="button"
          :disabled="paying"
          @click="confirmPaid"
        >
          {{ paying ? "确认中…" : "我已完成转账" }}
        </button>
        <a
          v-else-if="result.status === 'pending' && supportUrl"
          class="shop-btn"
          :href="supportUrl"
          target="_blank"
          rel="noopener noreferrer"
        >
          联系{{ supportLabel }}确认
        </a>
      </div>
      <p v-if="result.status === 'pending' && !selfPay && !supportUrl" class="shop-hint" style="margin-top: 10px">
        完成转账后，请携带订单号联系管理员确认到账。
      </p>
      <p v-if="result.relayPlanId" class="shop-hint" style="margin-top: 10px">
        管理员确认收款后额度会自动充入中转站账户；如已付款但未到账，请携带订单号联系管理员。
      </p>
    </section>

    <section v-if="recent.length" class="shop-recent">
      <h2>本机最近订单</h2>
      <ul class="shop-recent__list">
        <li v-for="item in recent" :key="item.orderNo">
          <button class="shop-recent__btn" type="button" @click="pick(item)">
            <span class="shop-recent__no">{{ item.orderNo }}</span>
            <span class="shop-recent__side">
              <span class="shop-recent__meta">
                {{ item.productName }} · ¥{{ item.total }}
              </span>
              <span
                v-if="item.status"
                class="shop-status"
                :class="`shop-status--${item.status}`"
              >
                {{ STATUS_TEXT[item.status] || item.status }}
              </span>
            </span>
          </button>
        </li>
      </ul>
    </section>
  </main>
</template>
