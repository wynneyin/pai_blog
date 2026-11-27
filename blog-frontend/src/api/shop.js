import api from "./client";

// Java 后端返回 {code, message, data}，Node 后端返回 {success, data, message}，
// 这里统一成 data，并把业务错误转成 Error。
function unwrap(body) {
  if (!body || typeof body !== "object") return body;
  if (body.success === false) {
    throw new Error(body.message || "请求失败");
  }
  if (typeof body.code === "number" && body.code !== 200) {
    throw new Error(body.message || "请求失败");
  }
  return Object.prototype.hasOwnProperty.call(body, "data") ? body.data : body;
}

let catalogCache = null;

export async function fetchCatalog({ force = false } = {}) {
  if (!force && catalogCache) return catalogCache;
  const data = unwrap(await api.get("/shop/catalog"));
  catalogCache = data;
  return data;
}

export async function createOrder(payload) {
  return unwrap(await api.post("/shop/orders", payload));
}

export async function fetchOrder(orderNo) {
  return unwrap(await api.get(`/shop/orders/${encodeURIComponent(orderNo)}`));
}

export async function payOrder(orderNo, method) {
  return unwrap(
    await api.post(`/shop/orders/${encodeURIComponent(orderNo)}/pay`, { method })
  );
}

export async function fetchAdminOrders(token, { status = "", limit = 100 } = {}) {
  const params = new URLSearchParams();
  if (status) params.set("status", status);
  params.set("limit", String(limit));
  return unwrap(
    await api.get(`/admin/shop/orders?${params.toString()}`, {
      headers: { "X-Admin-Token": token }
    })
  );
}

export async function updateAdminOrderStatus(token, orderNo, status) {
  return unwrap(
    await api.post(
      `/admin/shop/orders/${encodeURIComponent(orderNo)}/status`,
      { status },
      { headers: { "X-Admin-Token": token } }
    )
  );
}
