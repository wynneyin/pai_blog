<script setup>
import TagCloud from "./TagCloud.vue";

defineProps({
  articleCount: {
    type: Number,
    default: 0
  },
  categoryCount: {
    type: Number,
    default: 0
  },
  tagCount: {
    type: Number,
    default: 0
  },
  tags: {
    type: Array,
    default: () => []
  },
  activeTag: {
    type: String,
    default: ""
  }
});

const emit = defineEmits(["tag-change"]);
const avatarSrc = "/avatar.jpg";

function onAvatarError(event) {
  event.target.src = "/avatar.png";
}

const publications = [
  {
    title: "TetrisZK: Memory-Aware 2D Parallelism for Multi-GPU ZKP",
    venue: "ICCD 2026 · 第一作者",
    year: "2025.08 - 2025.11",
    link: ""
  },
  {
    title: "Pipelonk: End-to-End ZKP Generation on GPUs",
    venue: "PPoPP 2026 · 第三作者",
    year: "2025.01 - 2025.07",
    link: ""
  }
];

const internships = [
  {
    org: "Momenta（魔门塔自动驾驶）",
    role: "平台工程师 · GPU 训练平台",
    period: "2026.05 - 2026.08"
  },
  {
    org: "比特大陆算能科技",
    role: "算法工程师 · Openclaw 一体机开发",
    period: "2026.03 - 2026.05"
  }
];
</script>

<template>
  <aside class="profile">
    <img
      class="profile__avatar"
      :src="avatarSrc"
      alt="博主头像"
      @error="onAvatarError"
    />
    <h3>Wynne</h3>
    <p class="profile__role">并行计算/AI 应用/推理引擎开发者 / 内容创作者</p>
    <p class="profile__bio">
      记录技术实践、产品思考和生活片段。希望每一篇文章都能解决一个真实问题。
    </p>

    <ul class="profile__stats">
      <li>
        <strong>{{ articleCount }}</strong>
        <span>文章</span>
      </li>
      <li>
        <strong>{{ categoryCount }}</strong>
        <span>分类</span>
      </li>
      <li>
        <strong>{{ tagCount }}</strong>
        <span>标签</span>
      </li>
    </ul>

    <div class="profile__links">
      <a href="mailto:hello@example.com">Email</a>
      <a href="https://github.com" target="_blank" rel="noreferrer">GitHub</a>
    </div>

    <details class="profile__section" open>
      <summary>论文成果</summary>
      <ol class="profile__pub-list">
        <li v-for="(pub, index) in publications" :key="index" class="profile__pub-item">
          <a v-if="pub.link" :href="pub.link" target="_blank" rel="noreferrer" class="profile__pub-title">
            {{ pub.title }}
          </a>
          <span v-else class="profile__pub-title">{{ pub.title }}</span>
          <span class="profile__pub-meta">{{ pub.venue }} · {{ pub.year }}</span>
        </li>
      </ol>
    </details>

    <details class="profile__section" open>
      <summary>实习履历</summary>
      <ol class="profile__intern-list">
        <li v-for="(job, index) in internships" :key="index" class="profile__intern-item">
          <div class="profile__intern-head">
            <span class="profile__intern-org">{{ job.org }}</span>
            <span class="profile__intern-period">{{ job.period }}</span>
          </div>
          <p class="profile__intern-role">{{ job.role }}</p>
        </li>
      </ol>
    </details>

    <details class="profile__section">
      <summary>标签</summary>
      <TagCloud
        class="profile__tags"
        :tags="tags"
        :active-tag="activeTag"
        @change="emit('tag-change', $event)"
      />
    </details>
  </aside>
</template>
