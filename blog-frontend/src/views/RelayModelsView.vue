<script setup>
import { computed, onMounted, ref } from "vue";
import { fetchRelayCatalog, loadRelaySession } from "../api/relay";

const catalog = ref(null);
const loading = ref(true);
const error = ref("");
const query = ref("");
const vendor = ref("all");
const tag = ref("all");

const models = computed(() =>
  Array.isArray(catalog.value?.models) ? catalog.value.models : []
);

const vendors = computed(() => {
  const map = new Map();
  for (const model of models.value) {
    const key = model.vendor || "其他";
    map.set(key, (map.get(key) || 0) + 1);
  }
  return Array.from(map, ([name, count]) => ({ name, count }));
});

const tags = computed(() => {
  const map = new Map();
  for (const model of models.value) {
    for (const item of modelTags(model)) {
      map.set(item, (map.get(item) || 0) + 1);
    }
  }
  return Array.from(map, ([name, count]) => ({ name, count }));
});

const filtered = computed(() => {
  const keyword = query.value.trim().toLowerCase();
  return models.value.filter((model) => {
    if (vendor.value !== "all" && (model.vendor || "其他") !== vendor.value) {
      return false;
    }
    if (tag.value !== "all" && !modelTags(model).includes(tag.value)) {
      return false;
    }
    if (!keyword) return true;
    const haystack = [model.name, model.vendor, model.context, ...modelTags(model)]
      .filter(Boolean)
      .join(" ")
      .toLowerCase();
    return haystack.includes(keyword);
  });
});

function modelTags(model) {
  if (Array.isArray(model?.tags)) return model.tags.filter(Boolean).map(String);
  if (typeof model?.tag === "string" && model.tag) return [model.tag];
  return [];
}

function formatPrice(value) {
  const number = Number(value);
  if (!Number.isFinite(number)) return "—";
  return String(Number(number.toFixed(2)));
}

function initial(name) {
  const text = String(name || "?").trim();
  return text ? text.slice(0, 1).toUpperCase() : "?";
}

function vendorStyle(name) {
  const text = String(name || "");
  let hash = 0;
  for (let i = 0; i < text.length; i += 1) {
    hash = (hash * 31 + text.charCodeAt(i)) >>> 0;
  }
  const hue = hash % 360;
  return {
    background: `hsl(${hue} 76% 94%)`,
    color: `hsl(${hue} 58% 36%)`
  };
}

function tokenLink() {
  const path = "/relay/console/keys";
  if (loadRelaySession()?.token) return path;
  return { path: "/relay/sign-in", query: { redirect: path } };
}

function resetFilters() {
  query.value = "";
  vendor.value = "all";
  tag.value = "all";
}

async function load(force = false) {
  loading.value = true;
  error.value = "";
  try {
    catalog.value = await fetchRelayCatalog({ force });
  } catch (e) {
    error.value = e?.message || "模型目录加载失败";
  } finally {
    loading.value = false;
  }
}

onMounted(() => load());
</script>

<template>
  <main>
    <header class="relay-pagehead">
      <h1>模型广场</h1>
      <p class="relay-pagehead__sub">
        本站当前已启用 {{ models.length }} 个模型
      </p>
      <p class="relay-pagehead__note">
        探索精选 AI 模型，清晰比较价格与能力，为不同场景选择合适的模型。
      </p>
    </header>

    <section v-if="loading" class="relay-models" aria-busy="true">
      <div class="models-side">
        <div class="r-skeleton" style="height: 18px; width: 50%"></div>
        <div class="r-skeleton" style="height: 14px; width: 90%; margin-top: 12px"></div>
        <div class="r-skeleton" style="height: 34px; width: 100%; margin-top: 20px"></div>
        <div class="r-skeleton" style="height: 34px; width: 100%; margin-top: 10px"></div>
      </div>
      <div class="models-main">
        <div class="models-toolbar">
          <div class="r-skeleton" style="height: 42px; width: 320px; border-radius: 999px"></div>
        </div>
        <div class="model-grid">
          <div v-for="n in 6" :key="n" class="r-skeleton" style="height: 248px"></div>
        </div>
      </div>
    </section>

    <section v-else-if="error" class="r-state">
      <p class="r-error">{{ error }}</p>
      <p>模型目录暂时无法加载，请稍后重试。</p>
      <button class="r-btn" type="button" @click="load(true)">重新加载</button>
    </section>

    <section v-else class="relay-models">
      <aside class="models-side" aria-label="模型筛选">
        <div class="models-side__head">
          <h2 class="models-side__title">筛选</h2>
          <p class="models-side__hint">按供应商、标签和名称缩小范围。</p>
        </div>

        <div class="models-group">
          <h3>所有厂商</h3>
          <div class="models-chips">
            <button
              class="chip"
              :class="{ 'chip--active': vendor === 'all' }"
              type="button"
              :aria-pressed="vendor === 'all'"
              @click="vendor = 'all'"
            >
              全部 <small>{{ models.length }}</small>
            </button>
            <button
              v-for="item in vendors"
              :key="item.name"
              class="chip"
              :class="{ 'chip--active': vendor === item.name }"
              type="button"
              :aria-pressed="vendor === item.name"
              @click="vendor = item.name"
            >
              {{ item.name }} <small>{{ item.count }}</small>
            </button>
          </div>
        </div>

        <div class="models-group">
          <h3>模型标签</h3>
          <div class="models-chips">
            <button
              class="chip"
              :class="{ 'chip--active': tag === 'all' }"
              type="button"
              :aria-pressed="tag === 'all'"
              @click="tag = 'all'"
            >
              全部 <small>{{ models.length }}</small>
            </button>
            <button
              v-for="item in tags"
              :key="item.name"
              class="chip"
              :class="{ 'chip--active': tag === item.name }"
              type="button"
              :aria-pressed="tag === item.name"
              @click="tag = item.name"
            >
              {{ item.name }} <small>{{ item.count }}</small>
            </button>
          </div>
        </div>
      </aside>

      <div class="models-main">
        <div class="models-toolbar">
          <div class="models-search">
            <svg viewBox="0 0 24 24" fill="none" aria-hidden="true">
              <circle cx="11" cy="11" r="6.5" stroke="currentColor" stroke-width="1.7" />
              <path d="m16 16 4 4" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" />
            </svg>
            <input
              v-model="query"
              class="r-input"
              type="search"
              placeholder="搜索模型名称、厂商或标签"
              aria-label="搜索模型"
            />
          </div>
          <span class="models-count">共 {{ filtered.length }} 个模型</span>
        </div>

        <div class="model-grid">
          <article
            v-for="model in filtered"
            :key="model.name"
            class="model-card"
          >
            <div class="model-card__head">
              <span class="model-vendor-mark" :style="vendorStyle(model.vendor)" aria-hidden="true">
                {{ initial(model.vendor) }}
              </span>
              <div class="model-card__title">
                <h3 class="model-card__name" :title="model.name">{{ model.name }}</h3>
                <p class="model-card__vendor">{{ model.vendor || "其他" }}</p>
              </div>
            </div>

            <p class="model-card__context">上下文 {{ model.context || "—" }}</p>

            <div class="model-card__prices">
              <div class="model-card__price">
                <span>输入</span>
                <strong>&yen;{{ formatPrice(model.input) }} <em>/ 1M</em></strong>
              </div>
              <div class="model-card__price">
                <span>输出</span>
                <strong>&yen;{{ formatPrice(model.output) }} <em>/ 1M</em></strong>
              </div>
            </div>

            <div class="model-card__tags">
              <span v-for="item in modelTags(model)" :key="item" class="r-tag">{{ item }}</span>
              <span class="r-tag">OpenAI 兼容</span>
            </div>

            <router-link class="r-btn r-btn--soft" :to="tokenLink()">
              创建令牌
            </router-link>
          </article>

          <div v-if="!filtered.length" class="models-empty">
            <strong>没有找到匹配的模型</strong>
            <span>换一个关键词，或清除当前筛选条件。</span>
            <button class="r-btn r-btn--ghost" type="button" @click="resetFilters">
              清除筛选
            </button>
          </div>
        </div>
      </div>
    </section>
  </main>
</template>
