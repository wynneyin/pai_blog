<script setup>
import { computed, onMounted, ref } from "vue";
import { useRoute, useRouter } from "vue-router";
import dayjs from "dayjs";
import {
  createRelayKey,
  fetchRelayKeys,
  revokeRelayKey
} from "../../api/relay";

defineProps({
  user: { type: Object, default: null },
  refreshUser: { type: Function, default: null }
});

const route = useRoute();
const router = useRouter();

const keys = ref([]);
const loading = ref(true);
const error = ref("");
const showForm = ref(false);
const keyName = ref("");
const creating = ref(false);
const newKey = ref(null);
const copying = ref(false);
const copied = ref(false);
const revoking = ref("");

const activeCount = computed(
  () => keys.value.filter((item) => !item.revokedAt).length
);
const atLimit = computed(() => activeCount.value >= 10);

function formatTime(iso) {
  return iso ? dayjs(iso).format("YYYY-MM-DD HH:mm") : "—";
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

async function loadKeys() {
  loading.value = true;
  error.value = "";
  try {
    const data = await fetchRelayKeys();
    keys.value = Array.isArray(data?.list) ? data.list : [];
  } catch (e) {
    error.value = handleError(e, "令牌加载失败");
  } finally {
    loading.value = false;
  }
}

async function submitCreate() {
  if (creating.value || atLimit.value) return;
  creating.value = true;
  error.value = "";
  try {
    const data = await createRelayKey(keyName.value.trim());
    newKey.value = data;
    if (data?.record) {
      keys.value = [data.record, ...keys.value];
    } else {
      await loadKeys();
    }
    keyName.value = "";
    showForm.value = false;
  } catch (e) {
    error.value = handleError(e, "创建令牌失败");
  } finally {
    creating.value = false;
  }
}

function toggleForm() {
  showForm.value = !showForm.value;
  if (!showForm.value) keyName.value = "";
}

async function revoke(record) {
  if (!record?.id) return;
  if (
    !window.confirm(
      `确定吊销令牌「${record.name || "默认令牌"}」吗？吊销后无法恢复。`
    )
  ) {
    return;
  }
  revoking.value = record.id;
  error.value = "";
  try {
    await revokeRelayKey(record.id);
    if (newKey.value?.record?.id === record.id) newKey.value = null;
    await loadKeys();
  } catch (e) {
    error.value = handleError(e, "吊销令牌失败");
  } finally {
    revoking.value = "";
  }
}

async function copyKey() {
  const text = newKey.value?.key;
  if (!text) return;
  copying.value = true;
  try {
    await navigator.clipboard.writeText(text);
    copied.value = true;
    setTimeout(() => {
      copied.value = false;
    }, 1800);
  } catch {
    try {
      const area = document.createElement("textarea");
      area.value = text;
      area.style.position = "fixed";
      area.style.opacity = "0";
      document.body.appendChild(area);
      area.select();
      const ok = document.execCommand("copy");
      area.value = "";
      document.body.removeChild(area);
      if (!ok) throw new Error("copy failed");
      copied.value = true;
      setTimeout(() => {
        copied.value = false;
      }, 1800);
    } catch {
      copied.value = false;
    }
  } finally {
    copying.value = false;
  }
}

function dismissKey() {
  newKey.value = null;
  copied.value = false;
}

onMounted(loadKeys);
</script>

<template>
  <div class="keys">
    <header class="page-head">
      <div>
        <h1>API 密钥</h1>
        <p>用于调用 OpenAI 兼容接口，完整 Key 只在创建时展示一次。</p>
      </div>
      <button
        class="btn btn--primary"
        type="button"
        :disabled="atLimit || creating"
        @click="toggleForm"
      >
        {{ showForm ? "收起表单" : "创建令牌" }}
      </button>
    </header>

    <p v-if="error" class="alert" role="alert">{{ error }}</p>

    <section v-if="showForm" class="card create-card">
      <header class="card-head">
        <div>
          <h2>创建令牌</h2>
          <p>名称可选，留空时使用「默认令牌」。</p>
        </div>
      </header>
      <form class="create-form" @submit.prevent="submitCreate">
        <input
          v-model="keyName"
          type="text"
          maxlength="40"
          aria-label="令牌名称"
          placeholder="令牌名称（可选）"
          :disabled="creating"
        />
        <button
          class="btn btn--primary"
          type="submit"
          :disabled="creating || atLimit"
        >
          {{ creating ? "创建中…" : "确认创建" }}
        </button>
        <button class="btn" type="button" :disabled="creating" @click="toggleForm">
          取消
        </button>
      </form>
      <p v-if="atLimit" class="limit-hint">
        已达 10 个活跃令牌上限，请先吊销不再使用的令牌。
      </p>
    </section>

    <section v-if="newKey" class="key-once" role="status">
      <div class="key-once__head">
        <h2>完整 Key（只显示一次）</h2>
        <button class="close-btn" type="button" aria-label="关闭提示" @click="dismissKey">
          ×
        </button>
      </div>
      <div class="key-once__row">
        <code>{{ newKey.key }}</code>
        <button
          class="btn btn--primary btn--sm"
          type="button"
          :disabled="copying"
          @click="copyKey"
        >
          {{ copied ? "已复制" : "复制" }}
        </button>
      </div>
      <p class="key-once__hint">
        请立即保存到安全位置。关闭后无法再次查看完整 Key，只能吊销后重新创建。
      </p>
    </section>

    <section class="card">
      <header class="card-head">
        <div>
          <h2>令牌列表</h2>
          <p>活跃 {{ activeCount }} / 10 个</p>
        </div>
      </header>

      <div v-if="loading" class="placeholder">正在加载令牌…</div>
      <div v-else-if="!keys.length" class="placeholder">
        还没有令牌，点击右上角「创建令牌」开始使用。
      </div>
      <div v-else class="table-wrap">
        <table>
          <thead>
            <tr>
              <th scope="col">名称</th>
              <th scope="col">前缀</th>
              <th scope="col">创建时间</th>
              <th scope="col">最近使用</th>
              <th scope="col">状态</th>
              <th scope="col">操作</th>
            </tr>
          </thead>
          <tbody>
            <tr
              v-for="record in keys"
              :key="record.id"
              :class="{ 'row--revoked': !!record.revokedAt }"
            >
              <td class="name">{{ record.name || "默认令牌" }}</td>
              <td><code class="mono">{{ record.prefix }}…</code></td>
              <td>{{ formatTime(record.createdAt) }}</td>
              <td>{{ formatTime(record.lastUsedAt) }}</td>
              <td>
                <span
                  class="badge"
                  :class="record.revokedAt ? 'badge--revoked' : 'badge--active'"
                >
                  {{ record.revokedAt ? "已吊销" : "活跃" }}
                </span>
              </td>
              <td>
                <button
                  v-if="!record.revokedAt"
                  class="btn btn--danger btn--sm"
                  type="button"
                  :disabled="revoking === record.id"
                  @click="revoke(record)"
                >
                  {{ revoking === record.id ? "吊销中…" : "吊销" }}
                </button>
                <span v-else class="muted">—</span>
              </td>
            </tr>
          </tbody>
        </table>
      </div>

      <p class="limit-hint">每个账户最多保留 10 个活跃令牌，已吊销的令牌不计入上限。</p>
    </section>
  </div>
</template>

<style scoped>
.keys {
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

.keys a {
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

.card {
  padding: 20px;
  border: 1px solid #ebedf0;
  border-radius: 12px;
  background: #ffffff;
  box-shadow: none;
}

.create-card {
  margin-bottom: 16px;
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
  letter-spacing: 0;
}

.card-head p {
  margin: 4px 0 0;
  font-size: 12px;
  color: var(--muted);
}

.create-form {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
}

.create-form input {
  flex: 1 1 240px;
  min-width: 0;
  height: 40px;
  padding: 0 14px;
  border: 1px solid var(--line);
  border-radius: 10px;
  background: #ffffff;
  color: var(--text);
  font-size: 14px;
  font-family: inherit;
  outline: none;
  transition: border-color 0.16s, box-shadow 0.16s;
}

.create-form input:focus {
  border-color: var(--primary);
  box-shadow: 0 0 0 3px rgba(22, 119, 255, 0.12);
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

.btn--danger {
  border-color: #ffd4d0;
  color: var(--danger);
}

.btn--danger:hover:not(:disabled) {
  border-color: var(--danger);
  background: #fff4f2;
  color: var(--danger);
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

.limit-hint {
  margin: 14px 0 0;
  font-size: 12.5px;
  color: var(--muted);
}

.key-once {
  margin-bottom: 18px;
  padding: 18px 20px;
  border: 1px solid #bcd7ff;
  border-radius: 16px;
  background: #f0f7ff;
}

.key-once__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.key-once__head h2 {
  margin: 0;
  font-size: 15px;
  font-weight: 700;
  color: #0b4da2;
}

.close-btn {
  border: none;
  background: transparent;
  color: #5b7aa5;
  font-size: 20px;
  line-height: 1;
  cursor: pointer;
  padding: 2px 6px;
}

.close-btn:hover {
  color: #0b4da2;
}

.key-once__row {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-top: 12px;
  padding: 10px 10px 10px 14px;
  border: 1px solid #bcd7ff;
  border-radius: 10px;
  background: #ffffff;
}

.key-once__row code {
  flex: 1;
  min-width: 0;
  overflow-wrap: anywhere;
  font-family: "SFMono-Regular", ui-monospace, Menlo, Consolas, monospace;
  font-size: 13px;
  color: #0b1220;
}

.key-once__hint {
  margin: 12px 0 0;
  font-size: 12.5px;
  line-height: 1.6;
  color: #4a6a96;
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

td.name {
  font-weight: 600;
}

.row--revoked td {
  color: #9aa8bf;
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

.badge--active {
  background: #eafaf2;
  color: #0a8f53;
}

.badge--revoked {
  background: #f1f3f7;
  color: #8a97ad;
}

.muted {
  color: #9aa8bf;
}

@media (max-width: 640px) {
  .page-head {
    flex-direction: column;
    align-items: flex-start;
  }

  .key-once__row {
    flex-direction: column;
    align-items: stretch;
  }

  .create-form .btn {
    flex: 1 1 auto;
  }
}
</style>
