<script setup>
import { onMounted, ref } from "vue";
import dayjs from "dayjs";
import { fetchAdminOrders, updateAdminOrderStatus } from "../api/shop";
import { STATUS_TEXT } from "../utils/shopOrders";

const TOKEN_KEY = "pai_shop_admin_token";
const token = ref(sessionStorage.getItem(TOKEN_KEY) || "");
const statusFilter = ref("");
const orders = ref([]);
const total = ref(0);
const loading = ref(false);
const error = ref("");
const loaded = ref(false);
const updating = ref("");

const filters = [
  { value: "", label: "全部" },
  { value: "pending", label: "待支付" },
  { value: "paid", label: "已支付 / 已到账" },
  { value: "delivered", label: "已完成" },
  { value: "cancelled", label: "已取消" }
];

async function load() {
  const key = token.value.trim();
  if (!key) {
    error.value = "请输入管理员令牌（服务端 BLOG_ADMIN_TOKEN）";
    return;
  }
  loading.value = true;
  error.value = "";
  try {
    const data = await fetchAdminOrders(key, { status: statusFilter.value });
    orders.value = data.list || [];
    total.value = data.total || 0;
    sessionStorage.setItem(TOKEN_KEY, key);
    loaded.value = true;
  } catch (e) {
    error.value = e.message || "订单加载失败";
  } finally {
    loading.value = false;
  }
}

async function setStatus(order, status) {
  updating.value = order.orderNo;
  error.value = "";
  try {
    await updateAdminOrderStatus(token.value.trim(), order.orderNo, status);
    await load();
  } catch (e) {
    error.value = e.message || "状态更新失败";
  } finally {
    updating.value = "";
  }
}

function formatTime(iso) {
  return iso ? dayjs(iso).format("YYYY-MM-DD HH:mm") : "—";
}

function formatCents(value) {
  const cents = Number(value);
  return Number.isFinite(cents) ? (cents / 100).toFixed(2) : "—";
}

onMounted(() => {
  if (token.value) load();
});
</script>

<template>
  <main class="shop-main">
    <header class="shop-page-head">
      <p class="shop-kicker">Admin</p>
      <h1>订单后台</h1>
      <p>
        输入管理员令牌后查看订单、确认收款与发货。中转额度订单确认收款后会自动给买家账户充值；
        已到账订单不能取消，令牌保存在当前标签页会话中。
      </p>
    </header>

    <form class="shop-lookup" @submit.prevent="load">
      <input
        v-model="token"
        class="shop-lookup__input"
        type="password"
        placeholder="管理员令牌"
        autocomplete="off"
      />
      <button class="shop-btn" type="submit" :disabled="loading">
        {{ loading ? "加载中…" : "加载订单" }}
      </button>
    </form>

    <div class="shop-filters">
      <button
        v-for="item in filters"
        :key="item.value"
        class="shop-filter"
        :class="{ 'shop-filter--active': statusFilter === item.value }"
        type="button"
        @click="statusFilter = item.value; loaded && load()"
      >
        {{ item.label }}
      </button>
      <span v-if="loaded" class="shop-count" style="align-self: center">
        共 {{ total }} 单
      </span>
    </div>

    <p v-if="error" class="shop-error">{{ error }}</p>
    <p v-else-if="loaded && !orders.length" class="shop-hint">当前筛选下没有订单。</p>

    <ul v-if="orders.length" class="shop-admin__list">
      <li v-for="order in orders" :key="order.orderNo" class="shop-admin__order">
        <div class="shop-admin__top">
          <span class="shop-admin__no">{{ order.orderNo }}</span>
          <span class="shop-status" :class="`shop-status--${order.status}`">
            {{ STATUS_TEXT[order.status] || order.status }}
          </span>
        </div>
        <p class="shop-admin__meta">
          {{ order.productName }} × {{ order.quantity }} · ¥{{ order.total }}<br />
          <template v-if="order.relayPlanId">
            中转邮箱：{{ order.contact }} · 到账额度：¥{{ formatCents(order.relayCreditCents) }}<br />
            到账状态：{{ order.relayCreditedAt ? `已到账（${order.relayTopupOrderNo}）` : "未到账" }}
            · 下单时间：{{ formatTime(order.createdAt) }}<br />
          </template>
          <template v-else>
            联系方式：{{ order.contact }} · 下单时间：{{ formatTime(order.createdAt) }}<br />
          </template>
          备注：{{ order.note || "—" }}
          <template v-if="order.paymentMethod">
            · 支付方式：{{ order.paymentMethod === "wechat" ? "微信" : "支付宝" }}
          </template>
        </p>
        <div class="shop-admin__actions">
          <button
            v-if="order.status === 'pending'"
            class="shop-btn shop-btn--sm"
            type="button"
            :disabled="updating === order.orderNo"
            @click="setStatus(order, 'paid')"
          >
            {{ order.relayPlanId ? "确认收款并到账" : "标记已支付" }}
          </button>
          <button
            v-if="order.status === 'pending'"
            class="shop-btn shop-btn--ghost shop-btn--sm"
            type="button"
            :disabled="updating === order.orderNo"
            @click="setStatus(order, 'cancelled')"
          >
            取消订单
          </button>
          <button
            v-if="order.status === 'paid'"
            class="shop-btn shop-btn--sm"
            type="button"
            :disabled="updating === order.orderNo"
            @click="setStatus(order, 'delivered')"
          >
            {{ order.relayPlanId ? "标记完成" : "标记已发货" }}
          </button>
          <button
            v-if="order.status === 'delivered'"
            class="shop-btn shop-btn--ghost shop-btn--sm"
            type="button"
            :disabled="updating === order.orderNo"
            @click="setStatus(order, 'paid')"
          >
            撤回发货
          </button>
        </div>
      </li>
    </ul>
  </main>
</template>
