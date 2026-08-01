import { createRouter, createWebHistory } from "vue-router";
import HomeView from "../views/HomeView.vue";
import ArticleView from "../views/ArticleView.vue";

const router = createRouter({
  history: createWebHistory(),
  routes: [
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
  scrollBehavior() {
    return { top: 0 };
  }
});

export default router;
