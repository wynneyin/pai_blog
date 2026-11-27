<script setup>
import { computed, onMounted, ref } from "vue";
import { fetchRelayCatalog } from "../api/relay";

const FALLBACK_APPS = [
  "Cherry Studio",
  "CC Switch",
  "ChatBox",
  "Dify"
];

const FALLBACK_FEATURES = [
  { title: "极速", desc: "优化的网络架构，确保毫秒级响应时间" },
  { title: "安全可靠", desc: "企业级安全，提供全面的权限管理" },
  { title: "全球覆盖", desc: "多区域部署，实现稳定的全球访问" },
  { title: "开发者友好", desc: "兼容常见 AI 应用工作流的 API 路由" },
  { title: "高性能", desc: "支持高并发和自动负载均衡" },
  { title: "透明计费", desc: "按量付费，实时监控使用情况" },
  { title: "团队协作", desc: "多用户管理，灵活分配权限" },
  { title: "开源项目", desc: "社区驱动，可自托管、易于扩展" }
];

const FALLBACK_STEPS = [
  { title: "配置", desc: "注册账户，充值并创建 API 密钥" },
  { title: "连接", desc: "通过 OpenAI、Claude、Gemini 以及其他兼容 API 路由接入" },
  { title: "监控", desc: "通过实时分析跟踪用量、成本和性能" }
];

const FEATURE_ICONS = [
  "M13 2 4.5 13.5H11l-1 8.5L19.5 10H13V2Z",
  "M12 3 5 6v5c0 4.5 3 8 7 10 4-2 7-5.5 7-10V6l-7-3Z",
  "M12 3a9 9 0 1 0 0 18 9 9 0 0 0 0-18Zm0 0c3 2.5 4.5 5.5 4.5 9S15 18.5 12 21m0-18c-3 2.5-4.5 5.5-4.5 9S9 18.5 12 21M3.5 12h17",
  "m8 8-4 4 4 4m8-8 4 4-4 4m-4-9-4 10",
  "M4 18a8 8 0 1 1 16 0M12 14l4-4",
  "M6 3h12v18l-3-2-3 2-3-2-3 2V3Zm3 5h6M9 12h6",
  "M9 11a3 3 0 1 0 0-6 3 3 0 0 0 0 6Zm-6 9a6 6 0 0 1 12 0m1-14a3 3 0 0 1 0 6m1.5 8a5.5 5.5 0 0 0-2.5-4.6",
  "M12 3 4 7v10l8 4 8-4V7l-8-4Zm0 0v18M4 7l8 4 8-4"
];

const STEP_ICONS = [
  "M12 15a3 3 0 1 0 0-6 3 3 0 0 0 0 6Zm8-3c0-.4 0-.8-.1-1.2l2-1.6-2-3.4-2.3.9a8 8 0 0 0-2-1.2L15.2 3h-4l-.4 2.5a8 8 0 0 0-2 1.2l-2.3-.9-2 3.4 2 1.6a8 8 0 0 0 0 2.4l-2 1.6 2 3.4 2.3-.9a8 8 0 0 0 2 1.2l.4 2.5h4l.4-2.5a8 8 0 0 0 2-1.2l2.3.9 2-3.4-2-1.6c.1-.4.1-.8.1-1.2Z",
  "M9 3v6m6-6v6M6 9h12v2a6 6 0 0 1-12 0V9Zm6 8v4",
  "M4 20h16M7 16V9m5 7V5m5 11v-5"
];

const BENTO_CHIPS = ["OpenAI", "Claude", "Gemini", "DeepSeek", "Qwen", "Llama"];

const RESPONSE_SAMPLE = `{
  "id": "chatcmpl-9x2k",
  "object": "chat.completion",
  "created": 1764139200,
  "choices": [{ "message": { "content": "你好！" } }],
  "usage": { "prompt_tokens": 8, "completion_tokens": 23,
             "total_tokens": 31 }
}`;

const catalog = ref(null);
const loading = ref(true);
const error = ref("");
const activeSample = ref(0);

const brand = computed(
  () =>
    catalog.value?.brand || {
      name: "Wynne Relay",
      baseUrl: "",
      consoleUrl: "/relay/console"
    }
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

function escapeHtml(value) {
  return String(value)
    .replaceAll("&", "&amp;")
    .replaceAll("<", "&lt;")
    .replaceAll(">", "&gt;");
}

const CODE_TOKEN_RE =
  /("(?:[^"\\]|\\.)*")(\s*:)?|(\b(?:true|false|null)\b)|(-?\b\d+(?:\.\d+)?\b)|(\/\/[^\n]*)/g;

function highlight(code) {
  return escapeHtml(code).replace(
    CODE_TOKEN_RE,
    (match, str, colon, bool, num, comment) => {
      if (str) {
        return colon
          ? `<span class="tok-key">${str}</span>${colon}`
          : `<span class="tok-str">${str}</span>`;
      }
      if (bool) return `<span class="tok-bool">${bool}</span>`;
      if (num) return `<span class="tok-num">${num}</span>`;
      if (comment) return `<span class="tok-com">${comment}</span>`;
      return match;
    }
  );
}

const highlightedCode = computed(() =>
  highlight(resolvedCode.value || "暂无代码示例")
);
const highlightedResponse = computed(() => highlight(RESPONSE_SAMPLE));

const stats = computed(() =>
  Array.isArray(catalog.value?.stats) ? catalog.value.stats : []
);

const apps = computed(() => {
  const raw = catalog.value?.apps;
  if (!Array.isArray(raw) || !raw.length) return FALLBACK_APPS;
  return raw
    .map((item) => (typeof item === "string" ? item : item?.name || ""))
    .filter((item) => item && item !== "更多");
});

const heroApps = computed(() => apps.value.slice(0, 2));

const features = computed(() => {
  const raw = catalog.value?.features;
  if (!Array.isArray(raw) || !raw.length) return FALLBACK_FEATURES;
  return raw.map((item, index) => ({
    title:
      (typeof item === "string" ? item : item?.title || item?.name) ||
      FALLBACK_FEATURES[index % FALLBACK_FEATURES.length].title,
    desc:
      (typeof item === "object" && (item?.desc || item?.description)) ||
      FALLBACK_FEATURES[index % FALLBACK_FEATURES.length].desc
  }));
});

const steps = computed(() => {
  const raw = catalog.value?.steps;
  if (!Array.isArray(raw) || !raw.length) return FALLBACK_STEPS;
  return raw.map((item, index) => ({
    title:
      (typeof item === "string" ? item : item?.title || item?.name) ||
      FALLBACK_STEPS[index % FALLBACK_STEPS.length].title,
    desc:
      (typeof item === "object" && (item?.desc || item?.description)) ||
      FALLBACK_STEPS[index % FALLBACK_STEPS.length].desc
  }));
});

const mainFeatures = computed(() => features.value.slice(0, 4));
const minorFeatures = computed(() => features.value.slice(4));

async function load(force = false) {
  loading.value = true;
  error.value = "";
  try {
    catalog.value = await fetchRelayCatalog({ force });
    if (activeSample.value >= samples.value.length) activeSample.value = 0;
  } catch (e) {
    error.value = e?.message || "中转目录加载失败";
  } finally {
    loading.value = false;
  }
}

function pad(index) {
  return String(index + 1).padStart(2, "0");
}

onMounted(() => load());
</script>

<template>
  <main>
    <section v-if="loading" class="relay-loading" aria-busy="true" aria-live="polite">
      <div class="relay-loading__left">
        <div class="r-skeleton" style="width: 132px; height: 30px"></div>
        <div class="r-skeleton" style="width: 92%; height: 64px"></div>
        <div class="r-skeleton" style="width: 72%; height: 64px"></div>
        <div class="r-skeleton" style="width: 88%; height: 20px"></div>
        <div class="r-skeleton" style="width: 64%; height: 20px"></div>
        <div class="r-skeleton" style="width: 260px; height: 44px; margin-top: 10px"></div>
      </div>
      <div class="r-skeleton" style="height: 420px"></div>
    </section>

    <section v-else-if="error" class="r-state">
      <p class="r-error">{{ error }}</p>
      <p>中转目录暂时无法加载，请稍后重试。</p>
      <button class="r-btn" type="button" @click="load(true)">重新加载</button>
    </section>

    <template v-else>
      <section class="r-hero-wrap">
        <div class="r-hero">
          <div class="r-hero__body">
            <span class="relay-badge">
              {{ catalog?.hero?.badge || "人工智能应用基座" }}
            </span>
            <h1>
              {{ catalog?.hero?.title || "统一 API 网关，服务于" }}<br />
              <span class="relay-grad">{{ catalog?.hero?.accent || "海量 AI 模型" }}</span>
            </h1>
            <p class="r-hero__lead">
              {{
                catalog?.hero?.subtitle ||
                catalog?.hero?.description ||
                "通过统一、标准的接口协议接入海量模型。承载 AI 应用，高效管理数字资产，连接未来。"
              }}
            </p>
            <div class="r-hero__actions">
              <router-link class="r-btn" to="/relay/sign-up">
                开始使用
                <svg
                  viewBox="0 0 24 24"
                  fill="none"
                  stroke="currentColor"
                  stroke-width="2"
                  stroke-linecap="round"
                  stroke-linejoin="round"
                  aria-hidden="true"
                >
                  <path d="m9 18 6-6-6-6" />
                </svg>
              </router-link>
              <router-link class="r-btn r-btn--ghost" to="/relay/pricing">
                查看定价
              </router-link>
              <router-link class="r-btn r-btn--ghost" to="/relay/docs">
                <svg
                  viewBox="0 0 24 24"
                  fill="none"
                  stroke="currentColor"
                  stroke-width="1.7"
                  stroke-linecap="round"
                  stroke-linejoin="round"
                  aria-hidden="true"
                >
                  <path d="M4 19.5A2.5 2.5 0 0 1 6.5 17H20V4H6.5A2.5 2.5 0 0 0 4 6.5v13Z" />
                  <path d="M4 19.5A2.5 2.5 0 0 0 6.5 22H20v-5" />
                </svg>
                文档
              </router-link>
            </div>
            <div class="relay-apps">
              <p class="relay-apps__label">常用应用支持</p>
              <p class="relay-apps__hint">支持一键配置并完美适配 NewAPI 多协议配置</p>
              <div class="relay-apps__list">
                <span
                  v-for="(app, index) in heroApps"
                  :key="app"
                  class="relay-apps__item"
                >
                  <svg
                    v-if="index === 0"
                    viewBox="0 0 24 24"
                    aria-hidden="true"
                  >
                    <g fill="#ef5b6b">
                      <circle cx="12" cy="5.8" r="3.1" />
                      <circle cx="17.4" cy="9.7" r="3.1" />
                      <circle cx="15.4" cy="16" r="3.1" />
                      <circle cx="8.6" cy="16" r="3.1" />
                      <circle cx="6.6" cy="9.7" r="3.1" />
                    </g>
                    <circle cx="12" cy="11.9" r="2.2" fill="#f9c74f" />
                  </svg>
                  <svg
                    v-else
                    viewBox="0 0 24 24"
                    aria-hidden="true"
                  >
                    <rect x="2" y="2" width="20" height="20" rx="6" fill="#f5b722" />
                    <path
                      d="M7 9.5h7m0 0-2.2-2.2M14 9.5l-2.2 2.2M17 14.5H10m0 0 2.2-2.2M10 14.5l2.2 2.2"
                      fill="none"
                      stroke="#fff"
                      stroke-width="1.6"
                      stroke-linecap="round"
                      stroke-linejoin="round"
                    />
                  </svg>
                  {{ app }}
                </span>
                <router-link to="/relay/docs" class="relay-apps__item">
                  <svg viewBox="0 0 24 24" aria-hidden="true">
                    <circle cx="5" cy="12" r="1.6" fill="#a3a3a3" />
                    <circle cx="12" cy="12" r="1.6" fill="#a3a3a3" />
                    <circle cx="19" cy="12" r="1.6" fill="#a3a3a3" />
                  </svg>
                  更多
                </router-link>
              </div>
            </div>
          </div>

          <div class="relay-codecard">
            <div class="relay-codecard__bar">
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
              <span class="relay-codecard__live">200 OK</span>
            </div>

            <div class="relay-codecard__endpoint">
              <span class="relay-codecard__method">POST</span>
              <span>/v1/chat/completions</span>
            </div>

            <div class="relay-codecard__body">
              <p class="relay-codecard__label">REQUEST</p>
              <pre><code v-html="highlightedCode"></code></pre>
              <p class="relay-codecard__label">RESPONSE</p>
              <pre><code v-html="highlightedResponse"></code></pre>
            </div>

            <div class="relay-codecard__footer">
              <span>168 MS &middot; 31 TOKENS &middot; COST &yen;0.0009</span>
              <span>STREAM &middot; SSE</span>
            </div>
          </div>
        </div>
      </section>

      <section v-if="stats.length" class="r-stats" aria-label="站点统计">
        <div v-for="stat in stats" :key="stat.label" class="r-stat">
          <strong>{{ stat.value }}</strong>
          <span>{{ stat.label }}</span>
        </div>
      </section>

      <section class="r-section">
        <p class="relay-eyebrow">核心功能</p>
        <h2>为开发者打造，<br />为规模而设计</h2>

        <div class="relay-bento">
          <article
            v-for="(feature, index) in mainFeatures"
            :key="`${feature.title}-${index}`"
            class="bento-item"
            :class="{
              'bento-item--lead': index === 0 && mainFeatures.length === 4,
              'bento-item--tail': index === 1 && mainFeatures.length === 4,
              'bento-item--pre': index === 2 && mainFeatures.length === 4,
              'bento-item--wide': index === 3 && mainFeatures.length === 4
            }"
          >
            <span class="bento-item__num">{{ pad(index) }}</span>
            <h3 class="bento-item__title">{{ feature.title }}</h3>
            <p class="bento-item__desc">{{ feature.desc }}</p>

            <div v-if="index === 0" class="bento-item__chips">
              <span v-for="chip in BENTO_CHIPS" :key="chip">{{ chip }}</span>
            </div>
            <span v-else-if="index === 1" class="bento-item__art" aria-hidden="true">
              <svg
                viewBox="0 0 24 24"
                fill="none"
                stroke="currentColor"
                stroke-width="1.8"
                stroke-linecap="round"
                stroke-linejoin="round"
              >
                <path d="m5 12 4 4 10-10" />
              </svg>
            </span>
            <ul v-else-if="index === 2" class="bento-list">
              <li><span>1</span>负载均衡</li>
              <li><span>2</span>速率限制</li>
              <li><span>3</span>成本跟踪</li>
            </ul>
            <div v-else-if="index === 3" class="bento-item__chips">
              <span>API / SDK</span>
              <span>CLI / Docs</span>
              <span>兼容多协议</span>
            </div>
          </article>
        </div>

        <div v-if="minorFeatures.length" class="relay-minor">
          <article
            v-for="(feature, index) in minorFeatures"
            :key="`${feature.title}-${index}`"
            class="minor-item"
          >
            <span class="minor-item__icon" aria-hidden="true">
              <svg
                viewBox="0 0 24 24"
                fill="none"
                stroke="currentColor"
                stroke-width="1.5"
                stroke-linecap="round"
                stroke-linejoin="round"
              >
                <path :d="FEATURE_ICONS[(index + 4) % FEATURE_ICONS.length]" />
              </svg>
            </span>
            <h3>{{ feature.title }}</h3>
            <p>{{ feature.desc }}</p>
          </article>
        </div>
      </section>

      <section class="r-section r-section--tight">
        <div class="r-section__head--center">
          <p class="relay-eyebrow">工作流程</p>
          <h2>三步快速上手</h2>
        </div>
        <div class="relay-steps">
          <div v-for="(step, index) in steps" :key="`${step.title}-${index}`" class="step">
            <span class="step__num" aria-hidden="true">
              <svg
                viewBox="0 0 24 24"
                fill="none"
                stroke="currentColor"
                stroke-width="1.5"
                stroke-linecap="round"
                stroke-linejoin="round"
              >
                <path :d="STEP_ICONS[index % STEP_ICONS.length]" />
              </svg>
              <span class="step__badge">{{ index + 1 }}</span>
            </span>
            <h3>{{ step.title }}</h3>
            <p>{{ step.desc }}</p>
          </div>
        </div>
      </section>

      <section class="r-section r-section--tight r-section--cta">
        <div class="r-cta">
          <h2>
            准备好简化<br />
            <span class="relay-grad">你的 AI 集成了吗？</span>
          </h2>
          <p>部署你自己的网关，并通过已配置的上游服务开始转发请求。</p>
          <div class="r-cta__actions">
            <router-link class="r-btn" to="/relay/sign-up">
              开始使用
              <svg
                viewBox="0 0 24 24"
                fill="none"
                stroke="currentColor"
                stroke-width="2"
                stroke-linecap="round"
                stroke-linejoin="round"
                aria-hidden="true"
              >
                <path d="m9 18 6-6-6-6" />
              </svg>
            </router-link>
            <router-link class="r-btn r-btn--ghost" to="/relay/pricing">
              查看定价
            </router-link>
          </div>
        </div>
      </section>
    </template>
  </main>
</template>
