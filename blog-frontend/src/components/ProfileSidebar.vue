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
  event.target.src = "/avatar.svg";
}
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

    <TagCloud
      class="profile__tags"
      :tags="tags"
      :active-tag="activeTag"
      @change="emit('tag-change', $event)"
    />
  </aside>
</template>
