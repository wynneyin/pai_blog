<script setup>
import { computed, onBeforeUnmount, onMounted, ref } from "vue";
import { createOrder, payOrder } from "../../api/shop";
import { rememberOrder } from "../../utils/shopOrders";

const props = defineProps({
  product: { type: Object, required: true },
  payment: { type: Object, default: () => ({}) },
  selfPay: { type: Boolean, default: true }
});

const emit = defineEmits(["close"]);

const step = ref("form");
const quantity = ref(1);
const contact = ref("");
const note = ref("");
const error = ref("");
const order = ref(null);
const method = ref("alipay");
const copied = ref(false);
const submitting = ref(false);
const paying = ref(false);
const payError = ref("");

const total = computed(() => Number((props.product.price * quantity.value).toFixed(2)));

const isRelayProduct = computed(() => Boolean(props.product.relayPlanId));

const contactLabel = computed(() =>
  isRelayProduct.value ? "中转站注册邮箱" : "联系方式"
);

const contactPlaceholder = computed(() =>
  isRelayProduct.value ? "与中转站账号一致的邮箱" : "手机号或邮箱"
);

const qrSrc = computed(() => {
  const key = method.value === "alipay" ? "alipayQr" : "wechatQr";
  const value = props.payment ? props.payment[key] : "";
  return typeof value === "string" ? value.trim() : "";
});

const supportUrl = computed(() =>
  props.payment && typeof props.payment.contactUrl === "string"
    ? props.payment.contactUrl
    : ""
);

const supportLabel = computed(() =>
  props.payment && typeof props.payment.contact === "string" && props.payment.contact
    ? props.payment.contact
    : "管理员"
);

const maxQuantity = computed(() => {
  const value = props.product.availableStock;
  const limit = Number.isFinite(value) ? value : props.product.stock;
  return Math.max(0, Math.min(99, Number(limit) || 0));
});

function changeQty(delta) {
  quantity.value = Math.min(maxQuantity.value || 1, Math.max(1, quantity.value + delta));
}

async function submit() {
  if (isRelayProduct.value) {
    if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(contact.value.trim())) {
      error.value = "请填写中转站注册邮箱";
      return;
    }
  } else if (contact.value.trim().length < 5) {
    error.value = "请填写有效的联系方式";
    return;
  }
  if (maxQuantity.value < 1) {
    error.value = "该商品已售罄";
    return;
  }
  if (quantity.value > maxQuantity.value) {
    error.value = `最多可购买 ${maxQuantity.value} 件`;
    return;
  }
  error.value = "";
  submitting.value = true;
  try {
    order.value = await createOrder({
      productId: props.product.id,
      quantity: quantity.value,
      contact: contact.value.trim(),
      note: note.value.trim()
    });
    rememberOrder(order.value);
    step.value = "pay";
  } catch (e) {
    error.value = e.message || "下单失败，请稍后重试";
  } finally {
    submitting.value = false;
  }
}

async function copyOrderNo() {
  if (!order.value) return;
  try {
    await navigator.clipboard.writeText(order.value.orderNo);
    copied.value = true;
    setTimeout(() => (copied.value = false), 1600);
  } catch {
    copied.value = false;
  }
}

async function confirmPaid() {
  if (!order.value || order.value.status !== "pending") return;
  payError.value = "";
  paying.value = true;
  try {
    order.value = await payOrder(order.value.orderNo, method.value);
  } catch (e) {
    payError.value = e.message || "支付确认失败，请稍后重试";
  } finally {
    paying.value = false;
  }
}

function close() {
  emit("close");
}

function onKeydown(event) {
  if (event.key === "Escape") close();
}

onMounted(() => window.addEventListener("keydown", onKeydown));
onBeforeUnmount(() => window.removeEventListener("keydown", onKeydown));
</script>

<template>
  <div class="shop-overlay" role="presentation" @click.self="close">
    <div class="shop-dialog" role="dialog" aria-modal="true" aria-labelledby="shop-order-title">
      <template v-if="step === 'form'">
        <header class="shop-dialog__head">
          <div>
            <p class="shop-kicker">Checkout</p>
            <h2 id="shop-order-title">确认订单</h2>
          </div>
          <button class="shop-dialog__close" type="button" aria-label="关闭" @click="close">×</button>
        </header>
        <div class="shop-dialog__body">
          <div class="shop-summary">
            <div>
              <p class="shop-summary__name">{{ product.name }}</p>
              <p class="shop-summary__unit">单价 ¥{{ product.price }}</p>
            </div>
            <div class="shop-qty" aria-label="购买数量">
              <button type="button" aria-label="减少数量" @click="changeQty(-1)">−</button>
              <span>{{ quantity }}</span>
              <button type="button" aria-label="增加数量" @click="changeQty(1)">+</button>
            </div>
          </div>
          <label class="shop-field">
            <span>{{ contactLabel }} <em>*</em></span>
            <input
              v-model="contact"
              :type="isRelayProduct ? 'email' : 'text'"
              :placeholder="contactPlaceholder"
              :autocomplete="isRelayProduct ? 'email' : 'tel'"
            />
          </label>
          <p v-if="isRelayProduct" class="shop-field__hint">
            额度会充入这个邮箱对应的中转站账户。
            <router-link to="/relay/sign-up" target="_blank">还没有账号？先注册 →</router-link>
          </p>
          <label class="shop-field">
            <span>备注（可选）</span>
            <textarea
              v-model="note"
              :placeholder="isRelayProduct ? '如需对公、发票或其他说明可填写' : '补充说明'"
            ></textarea>
          </label>
          <p v-if="error" class="shop-error">{{ error }}</p>
          <div class="shop-total">
            <span>应付金额</span>
            <strong>{{ total }}</strong>
          </div>
        </div>
        <div class="shop-dialog__foot">
          <button class="shop-btn shop-btn--ghost" type="button" @click="close">取消</button>
          <button class="shop-btn" type="button" :disabled="submitting" @click="submit">
            {{ submitting ? "提交中…" : "确认下单" }}
          </button>
        </div>
      </template>

      <template v-else>
        <header class="shop-dialog__head">
          <div>
            <p class="shop-kicker">Payment</p>
            <h2 id="shop-order-title">确认付款</h2>
          </div>
          <button class="shop-dialog__close" type="button" aria-label="关闭" @click="close">×</button>
        </header>
        <div class="shop-dialog__body shop-pay">
          <p class="shop-pay__amount">{{ order.total }}</p>
          <p class="shop-pay__tips">
            <template v-if="isRelayProduct">
              <template v-if="selfPay">
                付款后点击「我已完成转账」通知管理员；管理员核对到账后，额度会自动充入
                <strong>{{ order.contact }}</strong> 的中转站账户。
              </template>
              <template v-else>
                请先使用下方方式完成转账，再携带订单号联系管理员核对；确认到账后额度会自动充入
                <strong>{{ order.contact }}</strong> 的中转站账户。
              </template>
            </template>
            <template v-else-if="selfPay">
              请使用下方收款方式完成支付，支付后点击「我已完成转账」。<br />
              付款备注可填写订单号后 6 位，便于核对。
            </template>
            <template v-else>
              请使用下方收款方式完成支付，并携带订单号联系管理员确认到账。
            </template>
          </p>
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
          <div v-if="qrSrc" class="shop-qr">
            <img :src="qrSrc" :alt="method === 'alipay' ? '支付宝收款码' : '微信收款码'" />
          </div>
          <div v-else class="shop-qr shop-qr--manual">
            <span>当前为人工确认收款</span>
            <span v-if="supportUrl">
              请通过
              <a :href="supportUrl" target="_blank" rel="noopener noreferrer">{{ supportLabel }}</a>
              联系管理员获取收款方式。
            </span>
            <span v-else>请联系管理员获取收款方式。</span>
          </div>
          <div class="shop-pay__no">
            <span>订单号</span>
            <code>{{ order.orderNo }}</code>
            <button class="shop-copy" type="button" @click="copyOrderNo">
              {{ copied ? "已复制" : "复制" }}
            </button>
          </div>
          <p v-if="payError" class="shop-error">{{ payError }}</p>
          <p v-if="order.status === 'paid'" class="shop-paid">
            <template v-if="order.relayCreditedAt">
              已确认收款，额度已充入 {{ order.contact }} 的中转站账户。
            </template>
            <template v-else>已确认收款，管理员会尽快处理到账。</template>
          </p>
        </div>
        <div class="shop-dialog__foot">
          <router-link class="shop-btn shop-btn--ghost" to="/shop/orders" @click="close">
            订单查询
          </router-link>
          <button
            v-if="selfPay"
            class="shop-btn"
            type="button"
            :disabled="order.status !== 'pending' || paying"
            @click="confirmPaid"
          >
            {{ order.status !== "pending" ? "已提交" : paying ? "确认中…" : "我已完成转账" }}
          </button>
          <a
            v-else-if="order.status === 'pending' && supportUrl"
            class="shop-btn"
            :href="supportUrl"
            target="_blank"
            rel="noopener noreferrer"
          >
            联系{{ supportLabel }}确认
          </a>
          <span v-else-if="order.status === 'pending'" class="shop-pay__wait">
            请携带订单号联系管理员确认到账
          </span>
          <button v-else class="shop-btn" type="button" disabled>已提交</button>
        </div>
      </template>
    </div>
  </div>
</template>

<style scoped>
.shop-field__hint {
  margin: -4px 0 0;
  color: #7b8494;
  font-size: 12px;
  line-height: 1.6;
}
.shop-field__hint a {
  color: #1a7f5a;
  font-weight: 700;
}
.shop-qr img {
  width: 100%;
  height: 100%;
  object-fit: contain;
}
.shop-qr--manual {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 4px;
  padding: 18px;
  text-align: center;
  color: #6b7280;
  font-size: 13px;
  line-height: 1.7;
}
.shop-qr--manual a {
  color: #1a7f5a;
  font-weight: 700;
}
.shop-pay__wait {
  align-self: center;
  color: #6b7280;
  font-size: 13px;
}
</style>
