import axios from "axios";

// 中转站接口。Node 后端返回 {success, data, message}，Java 后端返回
// {code, message, data}，这里统一归一化成 data，并把业务错误转成 RelayError。
const SESSION_KEY = "pai_relay_session";

export class RelayError extends Error {
  constructor(message, status = 0, body = null) {
    super(message);
    this.name = "RelayError";
    this.status = status;
    this.body = body;
  }
}

const http = axios.create({
  baseURL: import.meta.env.VITE_API_BASE_URL || "/api",
  timeout: 15000
});

let memorySession = null;

function bodyMessage(body) {
  if (!body || typeof body !== "object") return "";
  if (typeof body.message === "string" && body.message) return body.message;
  if (body.error && typeof body.error === "object") {
    if (typeof body.error.message === "string" && body.error.message) {
      return body.error.message;
    }
  }
  return "";
}

function unwrap(body) {
  if (!body || typeof body !== "object") return body;
  if (body.success === false) {
    throw new RelayError(body.message || "请求失败", 200, body);
  }
  if (typeof body.code === "number" && body.code !== 200) {
    throw new RelayError(body.message || "请求失败", body.code, body);
  }
  return Object.prototype.hasOwnProperty.call(body, "data") ? body.data : body;
}

export function loadRelaySession() {
  try {
    const raw = localStorage.getItem(SESSION_KEY);
    if (!raw) {
      return memorySession;
    }
    let parsed;
    try {
      parsed = JSON.parse(raw);
    } catch {
      // 兼容直接把令牌字符串写进 localStorage 的旧数据。
      parsed = raw;
    }
    if (typeof parsed === "string") {
      parsed = { token: parsed, user: null };
    }
    if (!parsed || typeof parsed.token !== "string" || !parsed.token) {
      memorySession = null;
      return null;
    }
    memorySession = parsed;
    return parsed;
  } catch {
    // localStorage 不可用（如隐私模式）时回退到当前内存会话。
    return memorySession;
  }
}

export function saveRelaySession(token, user) {
  const session = { token, user: user || null };
  memorySession = session;
  try {
    localStorage.setItem(SESSION_KEY, JSON.stringify(session));
  } catch {
    /* 存储不可用时仅保留内存会话 */
  }
  return session;
}

export function clearRelaySession() {
  memorySession = null;
  try {
    localStorage.removeItem(SESSION_KEY);
  } catch {
    /* 忽略存储异常 */
  }
}

function sessionToken() {
  return loadRelaySession()?.token || "";
}

http.interceptors.request.use((config) => {
  const token = sessionToken();
  if (token) {
    config.headers = config.headers || {};
    config.headers.Authorization = `Bearer ${token}`;
  }
  return config;
});

http.interceptors.response.use(
  (response) => response.data,
  (error) => {
    const response = error?.response;
    const body = response?.data;
    const status = response?.status || 0;
    const url = String(error?.config?.url || "");

    // 用户态接口返回 401 时清除本地会话；后台接口和登录/注册失败不清理。
    if (
      status === 401 &&
      !url.startsWith("/admin") &&
      !url.startsWith("/relay/auth/")
    ) {
      clearRelaySession();
    }

    let message = bodyMessage(body);
    if (!message) {
      if (error?.code === "ECONNABORTED") {
        message = "请求超时，请稍后再试";
      } else if (error?.request && !response) {
        message = "网络异常，请检查网络连接";
      } else {
        message = error?.message || "请求失败";
      }
    }
    return Promise.reject(new RelayError(message, status, body));
  }
);

async function get(path, config) {
  return unwrap(await http.get(path, config));
}

async function post(path, payload, config) {
  return unwrap(await http.post(path, payload, config));
}

async function remove(path, config) {
  return unwrap(await http.delete(path, config));
}

let catalogCache = null;

export async function fetchRelayCatalog({ force = false } = {}) {
  if (!force && catalogCache) return catalogCache;
  const data = await get("/relay/catalog");
  catalogCache = data;
  return data;
}

export async function registerRelay(email, password) {
  return post("/relay/auth/register", { email, password });
}

export async function loginRelay(email, password) {
  return post("/relay/auth/login", { email, password });
}

export async function forgotRelayPassword(email) {
  return post("/relay/auth/forgot-password", { email });
}

export async function resetRelayPassword(token, password) {
  return post("/relay/auth/reset-password", { token, password });
}

export async function logoutRelay() {
  return post("/relay/auth/logout", {});
}

export async function fetchRelayMe() {
  return get("/relay/me");
}

export async function fetchRelayKeys() {
  return get("/relay/keys");
}

export async function createRelayKey(name) {
  return post("/relay/keys", { name });
}

export async function revokeRelayKey(id) {
  return remove(`/relay/keys/${encodeURIComponent(id)}`);
}

export async function fetchRelayTopups() {
  return get("/relay/topups");
}

export async function createRelayTopup(planId) {
  return post("/relay/topups", { planId });
}

export async function payRelayTopup(orderNo, method) {
  return post(`/relay/topups/${encodeURIComponent(orderNo)}/pay`, { method });
}

export async function fetchRelayUsage(limit = 50) {
  const value = Number(limit);
  const safeLimit = Number.isFinite(value) && value > 0 ? Math.min(Math.floor(value), 200) : 50;
  return get(`/relay/usage?limit=${safeLimit}`);
}

export async function fetchAdminRelayUsers(token, { limit = 100 } = {}) {
  return get(`/admin/relay/users?limit=${encodeURIComponent(limit)}`, {
    headers: { "X-Admin-Token": token }
  });
}

export async function fetchAdminRelayTopups(token, { status = "", limit = 100 } = {}) {
  const params = new URLSearchParams();
  if (status) params.set("status", status);
  params.set("limit", String(limit));
  return get(`/admin/relay/topups?${params.toString()}`, {
    headers: { "X-Admin-Token": token }
  });
}

export async function updateAdminRelayTopupStatus(token, orderNo, status) {
  return post(
    `/admin/relay/topups/${encodeURIComponent(orderNo)}/status`,
    { status },
    { headers: { "X-Admin-Token": token } }
  );
}
