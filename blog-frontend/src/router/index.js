import { createRouter, createWebHistory } from "vue-router";
import HomeView from "../views/HomeView.vue";
import ArticleView from "../views/ArticleView.vue";

import BookmarksView from "../views/BookmarksView.vue";
import AboutView from "../views/AboutView.vue";

const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: "/bookmarks", name: "bookmarks", component: BookmarksView },
    { path: "/about", name: "about", component: AboutView },
    {
      path: "/",
      name: "home",
      component: HomeView
    },
    {
      path: "/article/:slug",
      name: "article",
      component: ArticleView
    }
  ],
  scrollBehavior(to, from, savedPosition) {
    if (savedPosition) return savedPosition;
    if (to.hash) return { el: to.hash, top: 24, behavior: "smooth" };
    return { top: 0 };
  }
});

export default router;
