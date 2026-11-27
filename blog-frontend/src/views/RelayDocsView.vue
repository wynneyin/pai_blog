<script setup>
import { computed, onMounted, ref } from "vue";
import { fetchRelayCatalog } from "../api/relay";

const STEPS = [
  {
    title: "注册并充值",
    desc: "使用邮箱注册账户，选择合适的充值档位，余额立即到账。"
  },
  {
    title: "创建 API 令牌",
    desc: "进入控制台的 API 密钥页面创建令牌，完整 Key 只展示一次，请及时保存。"
  },
  {
    title: "修改 base_url",
    desc: "把现有 OpenAI SDK 的 base_url 换成下方地址，填入令牌即可调用。"
  }
];

const catalog = ref(null);
const loading = ref(true);
const error = ref("");
const activeSample = ref(0);
const copied = ref(false);

const brand = computed(
  () => catalog.value?.brand || { name: "Wynne Relay", baseUrl: "" }
);

const baseUrl = computed(() => {
  const raw = String(brand.value?.baseUrl || "").trim().replace(/\/+$/, "");
  if (raw) return raw;
  if (typeof window !== "undefined" && window.location?.origin) {
    return `${window.location.origin}/v1`;
  }
  return "/v1";
});

const samples = computed(() =>
  Array.isArray(catalog.value?.codeSamples) ? catalog.value.codeSamples : []
);

const activeSampleData = computed(
  () => samples.value[activeSample.value] || samples.value[0] || null
);

const resolvedCode = computed(() => {
  const code = activeSampleData.value?.code || "";
  return code.replaceAll("{baseUrl}", baseUrl.value);
});

const faqs = computed(() =>
  Array.isArray(catalog.value?.faqs) ? catalog.value.faqs : []
);

async function load(force = false) {
  loading.value = true;
  error.value = "";
  try {
    catalog.value = await fetchRelayCatalog({ force });
    if (activeSample.value >= samples.value.length) activeSample.value = 0;
  } catch (e) {
    error.value = e?.message || "文档目录加载失败";
  } finally {
    loading.value = false;
  }
}

async function copyBaseUrl() {
  try {
    await navigator.clipboard.writeText(baseUrl.value);
    copied.value = true;
    setTimeout(() => (copied.value = false), 1600);
  } catch {
    copied.value = false;
  }
}

onMounted(() => load());
</script>

<template>
  <main class="relay-docs">
    <header class="relay-pagehead" style="padding-bottom: 30px">
      <h1>接入文档</h1>
      <p class="relay-pagehead__sub">
        兼容 OpenAI 接口协议，三步完成接入，无需改动现有代码结构。
      </p>
    </header>

    <section class="docs-steps" aria-label="接入步骤">
      <article v-for="(step, index) in STEPS" :key="step.title" class="docs-step">
        <span class="docs-step__num">{{ index + 1 }}</span>
        <h3>{{ step.title }}</h3>
        <p>{{ step.desc }}</p>
      </article>
    </section>

    <div v-if="error" class="r-error" style="margin-top: 24px; display: flex; align-items: center; gap: 14px; flex-wrap: wrap">
      <span style="flex: 1 1 220px">{{ error }}</span>
      <button class="r-btn r-btn--ghost" type="button" @click="load(true)">重新加载</button>
    </div>

    <section class="docs-block">
      <h2 class="docs-block__title">Base URL</h2>
      <p class="docs-block__sub">
        将客户端或 SDK 的 base_url 指向以下地址；如果已有代码里写的是完整路径，请只保留到
        <code>/v1</code> 为止。
      </p>
      <div class="baseurl">
        <code>{{ baseUrl }}</code>
        <button class="r-btn r-btn--soft" type="button" @click="copyBaseUrl">
          {{ copied ? "已复制" : "复制地址" }}
        </button>
      </div>
      <p class="docs-block__sub" style="margin: 14px 0 0; font-size: 12.5px">
        在 OpenAI SDK 中把 api_key 填成控制台创建的令牌，即可直接调用。
      </p>
    </section>

    <section class="docs-block">
      <h2 class="docs-block__title">代码示例</h2>
      <p class="docs-block__sub">选择你熟悉的语言，替换令牌后即可运行。</p>

      <div v-if="loading" class="r-skeleton" style="height: 220px; border-radius: 14px"></div>

      <template v-else-if="samples.length">
        <div class="docs-codecard">
          <div class="relay-codecard__tabs" role="tablist" aria-label="代码示例语言">
            <button
              v-for="(sample, index) in samples"
              :key="sample.label || index"
              class="relay-codecard__tab"
              :class="{ 'relay-codecard__tab--active': activeSample === index }"
              type="button"
              role="tab"
              :aria-selected="activeSample === index"
              @click="activeSample = index"
            >
              {{ sample.label || `示例 ${index + 1}` }}
            </button>
          </div>
          <pre><code>{{ resolvedCode }}</code></pre>
        </div>
      </template>

      <p v-else class="r-empty">暂时没有可用的代码示例。</p>
    </section>

    <section v-if="faqs.length" class="docs-block">
      <h2 class="docs-block__title">常见问题</h2>
      <div class="relay-faq">
        <details v-for="item in faqs" :key="item.q">
          <summary>{{ item.q }}</summary>
          <p>{{ item.a }}</p>
        </details>
      </div>
    </section>

    <section class="docs-cta">
      <div>
        <h2>还没有令牌？</h2>
        <p>登录控制台创建 API 密钥，即可开始调用全部模型。</p>
      </div>
      <router-link class="r-btn" to="/relay/console">打开控制台</router-link>
    </section>
  </main>
</template>
