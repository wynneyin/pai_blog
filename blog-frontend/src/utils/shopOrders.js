// 订单数据以后端为准；这里只保存本机最近查询过的订单号，方便回看。
const RECENT_KEY = "pai_shop_recent_orders";
const MAX_RECENT = 12;

export const STATUS_TEXT = {
  pending: "待支付",
  paid: "已支付",
  delivered: "已完成",
  cancelled: "已取消"
};

export function loadRecentOrders() {
  try {
    const raw = localStorage.getItem(RECENT_KEY);
    const list = raw ? JSON.parse(raw) : [];
    return Array.isArray(list) ? list : [];
  } catch {
    return [];
  }
}

export function rememberOrder(order) {
  if (!order?.orderNo) return;
  const existingList = loadRecentOrders();
  const existing = existingList.find((item) => item.orderNo === order.orderNo);
  const entry = {
    orderNo: order.orderNo,
    productName: order.productName || "",
    total: order.total,
    status: order.status || existing?.status || "",
    createdAt: order.createdAt,
    // 查询凭证只在本机保存，用于再次打开订单查询页时免登录查看。
    // 用中转站登录查询时响应里没有凭证，此时保留本机已有的那份。
    lookupToken: order.lookupToken || existing?.lookupToken || ""
  };
  const list = existingList.filter((item) => item.orderNo !== entry.orderNo);
  list.unshift(entry);
  try {
    localStorage.setItem(RECENT_KEY, JSON.stringify(list.slice(0, MAX_RECENT)));
  } catch {
    /* localStorage 不可用时忽略 */
  }
}
