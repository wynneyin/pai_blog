import { createRouter, createWebHistory } from "vue-router";
import HomeView from "../views/HomeView.vue";
import ArticleView from "../views/ArticleView.vue";

import BookmarksView from "../views/BookmarksView.vue";
import AboutView from "../views/AboutView.vue";
import RelayView from "../views/RelayView.vue";
import RelayAdminView from "../views/RelayAdminView.vue";

const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: "/bookmarks", name: "bookmarks", component: BookmarksView },
    { path: "/about", name: "about", component: AboutView },
    {
      path: "/relay",
      name: "relay",
      component: RelayView,
      meta: { title: "中转站" }
    },
    {
      path: "/relay/models",
      name: "relay-models",
      component: () => import("../views/RelayModelsView.vue"),
      meta: { title: "模型广场" }
    },
    {
      path: "/relay/docs",
      name: "relay-docs",
      component: () => import("../views/RelayDocsView.vue"),
      meta: { title: "接入文档" }
    },
    {
      path: "/relay/pricing",
      name: "relay-pricing",
      component: () => import("../views/RelayPricingView.vue"),
      meta: { title: "充值定价" }
    },
    {
      path: "/relay/sign-in",
      name: "relay-sign-in",
      component: () => import("../views/RelayAuthView.vue"),
      meta: { title: "登录", hideHeader: true, hideFooter: true }
    },
    {
      path: "/relay/sign-up",
      name: "relay-sign-up",
      component: () => import("../views/RelayAuthView.vue"),
      meta: { title: "注册", hideHeader: true, hideFooter: true }
    },
    {
      path: "/relay/console",
      component: () => import("../views/relay/RelayConsoleLayout.vue"),
      meta: { title: "控制台", hideFooter: true },
      children: [
        {
          path: "",
          name: "relay-console",
          component: () => import("../views/relay/RelayDashboardView.vue"),
          meta: { title: "控制台" }
        },
        {
          path: "keys",
          name: "relay-console-keys",
          component: () => import("../views/relay/RelayKeysView.vue"),
          meta: { title: "API 密钥" }
        },
        {
          path: "topup",
          name: "relay-console-topup",
          component: () => import("../views/relay/RelayTopupView.vue"),
          meta: { title: "钱包充值" }
        },
        {
          path: "logs",
          name: "relay-console-logs",
          component: () => import("../views/relay/RelayLogsView.vue"),
          meta: { title: "使用日志" }
        }
      ]
    },
    {
      path: "/relay/admin",
      name: "relay-admin",
      component: RelayAdminView,
      meta: { title: "中转后台" }
    },
    {
      path: "/",
      name: "home",
      component: HomeView
    },
    {
      path: "/article/:slug",
      name: "article",
      component: ArticleView
    },
    {
      path: "/:pathMatch(.*)*",
      name: "not-found",
      redirect: "/"
    }
  ],
  scrollBehavior(to, from, savedPosition) {
    if (savedPosition) return savedPosition;
    if (to.hash) return { el: to.hash, top: 24, behavior: "smooth" };
    return { top: 0 };
  }
});

router.afterEach((to) => {
  document.title = to.meta.title
    ? `${to.meta.title} · Wynne`
    : "Wynne · GPU Computing & AI Infra Notes";
});

export default router;
