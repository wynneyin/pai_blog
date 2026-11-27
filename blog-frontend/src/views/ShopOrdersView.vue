<script setup>
import { onMounted, ref } from "vue";
import dayjs from "dayjs";
import { fetchCatalog, fetchOrder, payOrder } from "../api/shop";
import { loadRecentOrders, STATUS_TEXT } from "../utils/shopOrders";

const orderNo = ref("");
const result = ref(null);
const searched = ref(false);
const loading = ref(false);
const error = ref("");
const paying = ref(false);
const recent = ref(loadRecentOrders());
const selfPay = ref(true);
const supportUrl = ref("");
const supportLabel = ref("管理员");

onMounted(async () => {
  try {
    const catalog = await fetchCatalog();
    selfPay.value = catalog.allowSelfPay !== false;
    const payment = catalog.payment || {};
    supportUrl.value = typeof payment.contactUrl === "string" ? payment.contactUrl : "";
    supportLabel.value =
      typeof payment.contact === "string" && payment.contact ? payment.contact : "管理员";
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
    result.value = await fetchOrder(key);
  } catch (e) {
    result.value = null;
    error.value = e.message || "订单查询失败";
  } finally {
    searched.value = true;
    loading.value = false;
    recent.value = loadRecentOrders();
  }
}

function pick(no) {
  orderNo.value = no;
  search();
}

async function confirmPaid() {
  if (!result.value || result.value.status !== "pending") return;
  paying.value = true;
  error.value = "";
  try {
    result.value = await payOrder(result.value.orderNo, "alipay");
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
      <p>输入下单后生成的订单号，查看支付状态与商品信息。</p>
    </header>

    <form class="shop-lookup" @submit.prevent="search">
      <input
        v-model="orderNo"
        class="shop-lookup__input"
        type="text"
        placeholder="例如 PB20261126143022ABCD"
        aria-label="订单号"
      />
      <button class="shop-btn" type="submit" :disabled="loading">
        {{ loading ? "查询中…" : "查询订单" }}
      </button>
    </form>

    <p v-if="error" class="shop-error">{{ error }}</p>
    <p v-else-if="searched && !result && !loading" class="shop-error">
      没有找到该订单，请核对订单号是否完整。
    </p>

    <section v-if="result" class="shop-panel shop-result" aria-live="polite">
      <div class="shop-result__head">
        <p class="shop-result__no">{{ result.orderNo }}</p>
        <span class="shop-status" :class="`shop-status--${result.status}`">
          {{ STATUS_TEXT[result.status] || result.status }}
        </span>
      </div>
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
      <button
        v-if="result.status === 'pending' && selfPay"
        class="shop-btn"
        style="margin-top: 16px"
        type="button"
        :disabled="paying"
        @click="confirmPaid"
      >
        {{ paying ? "确认中…" : "我已完成转账" }}
      </button>
      <a
        v-else-if="result.status === 'pending' && supportUrl"
        class="shop-btn"
        style="margin-top: 16px"
        :href="supportUrl"
        target="_blank"
        rel="noopener noreferrer"
      >
        联系{{ supportLabel }}确认
      </a>
      <p v-else-if="result.status === 'pending'" class="shop-hint" style="margin-top: 10px">
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
          <button class="shop-recent__btn" type="button" @click="pick(item.orderNo)">
            <span class="shop-recent__no">{{ item.orderNo }}</span>
            <span class="shop-recent__meta">
              {{ item.productName }} · ¥{{ item.total }}
            </span>
          </button>
        </li>
      </ul>
    </section>
  </main>
</template>
