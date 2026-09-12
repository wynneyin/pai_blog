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

// TODO: 替换为真实的论文成果
const publications = [
  {
    title: "论文标题占位 A",
    venue: "期刊 / 会议名称，卷期号",
    year: "2026",
    link: ""
  },
  {
    title: "论文标题占位 B",
    venue: "期刊 / 会议名称，卷期号",
    year: "2025",
    link: ""
  }
];

// TODO: 替换为真实的实习履历
const internships = [
  {
    org: "公司 / 机构名称占位",
    role: "职位占位",
    period: "2026.01 - 2026.06",
    desc: "一句话描述职责或产出占位。"
  },
  {
    org: "公司 / 机构名称占位",
    role: "职位占位",
    period: "2025.06 - 2025.09",
    desc: "一句话描述职责或产出占位。"
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
    <p class="profile__role">后端开发者 / 内容创作者</p>
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
          <p class="profile__intern-desc">{{ job.desc }}</p>
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
