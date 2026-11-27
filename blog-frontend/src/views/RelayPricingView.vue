<script setup>
import { computed, onMounted, ref } from "vue";
import { fetchRelayCatalog, loadRelaySession } from "../api/relay";

const catalog = ref(null);
const loading = ref(true);
const error = ref("");

const plans = computed(() =>
  Array.isArray(catalog.value?.plans) ? catalog.value.plans : []
);

const faqs = computed(() =>
  Array.isArray(catalog.value?.faqs) ? catalog.value.faqs : []
);

function planLink(plan) {
  const target = `/relay/console/topup?plan=${encodeURIComponent(plan.id)}`;
  if (loadRelaySession()?.token) return target;
  return { path: "/relay/sign-in", query: { redirect: target } };
}

async function load(force = false) {
  loading.value = true;
  error.value = "";
  try {
    catalog.value = await fetchRelayCatalog({ force });
  } catch (e) {
    error.value = e?.message || "充值档位加载失败";
  } finally {
    loading.value = false;
  }
}

onMounted(() => load());
</script>

<template>
  <main>
    <header class="relay-pagehead">
      <p class="relay-eyebrow">充值定价</p>
      <h1>按需充值，额度长期有效</h1>
      <p class="relay-pagehead__sub">
        充值额度全模型通用，按实际调用量扣费，用多少扣多少。
      </p>
    </header>

    <section v-if="loading" class="r-section r-section--pricing" aria-busy="true">
      <div class="relay-pricing">
        <div v-for="n in 4" :key="n" class="r-skeleton" style="height: 360px"></div>
      </div>
    </section>

    <section v-else-if="error" class="r-state">
      <p class="r-error">{{ error }}</p>
      <p>充值档位暂时无法加载，请稍后重试。</p>
      <button class="r-btn" type="button" @click="load(true)">重新加载</button>
    </section>

    <template v-else>
      <section class="r-section r-section--pricing">
        <div v-if="plans.length" class="relay-pricing">
          <article
            v-for="plan in plans"
            :key="plan.id"
            class="plan"
            :class="{ 'plan--hot': plan.hot }"
          >
            <span v-if="plan.hot" class="plan__hot">推荐</span>
            <h3>{{ plan.name }}</h3>
            <p class="plan__price">
              <small>&yen;</small>{{ plan.price }}
            </p>
            <p class="plan__credit">
              {{ plan.bonus || `到账 ${plan.credit} 元额度` }}
            </p>
            <p class="plan__note">
              实付 &yen;{{ plan.price }}，到账 &yen;{{ plan.credit }}
            </p>
            <ul v-if="Array.isArray(plan.perks)">
              <li v-for="perk in plan.perks" :key="perk">
                <svg
                  viewBox="0 0 24 24"
                  fill="none"
                  stroke="currentColor"
                  stroke-width="2"
                  stroke-linecap="round"
                  stroke-linejoin="round"
                  aria-hidden="true"
                >
                  <path d="m5 12 4 4 10-10" />
                </svg>
                <span>{{ perk }}</span>
              </li>
            </ul>
            <router-link class="r-btn r-btn--block" :to="planLink(plan)">
              立即充值
            </router-link>
          </article>
        </div>
        <p v-else class="r-state">暂时没有可用的充值档位。</p>

        <div v-if="faqs.length" class="relay-faq-block">
          <div class="r-section__head--center">
            <p class="relay-eyebrow">FAQ</p>
            <h2>常见问题</h2>
          </div>
          <div class="relay-faq">
            <details v-for="item in faqs" :key="item.q">
              <summary>{{ item.q }}</summary>
              <p>{{ item.a }}</p>
            </details>
          </div>
        </div>
      </section>
    </template>
  </main>
</template>
