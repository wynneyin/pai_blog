<template>
  <section class="friend-links" aria-labelledby="friend-links-heading">
    <p class="friend-links__kicker">FRIENDS</p>
    <h2 id="friend-links-heading">友情链接<span>.</span></h2>
    <router-link
      to="/relay"
      target="_blank"
      rel="noopener"
      class="friend-links__item"
      aria-label="Wynne Relay（在新标签页打开）"
    >
      <span class="friend-links__icon" aria-hidden="true">WR</span>
      <span class="friend-links__info">
        <strong>Wynne Relay</strong>
        <small>OpenAI 兼容 API · 按量计费</small>
      </span>
      <span class="friend-links__arrow" aria-hidden="true">↗</span>
    </router-link>
  </section>
</template>

<style scoped>
.friend-links {
  padding: 0 0 26px;
  border-bottom: 1px solid #e9e9f0;
}
.friend-links__kicker {
  margin: 0 0 11px;
  color: #bb3b7d;
  font: 800 11px/1.4 "Space Grotesk", sans-serif;
  letter-spacing: 0.13em;
}
.friend-links h2 {
  margin: 0 0 14px;
  color: #28233f;
  font-size: 21px;
  font-weight: 800;
  letter-spacing: -0.025em;
}
.friend-links h2 span {
  color: #bb447f;
}
.friend-links__item {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 11px 12px;
  border: 1px solid #e7e3ef;
  border-radius: 12px;
  background: #fff;
  transition: border-color 0.18s, transform 0.18s, box-shadow 0.18s;
}
.friend-links__item:hover {
  border-color: #ac97de;
  transform: translateY(-2px);
  box-shadow: 0 10px 22px rgba(51, 39, 112, 0.08);
}
.friend-links__icon {
  flex: 0 0 35px;
  width: 35px;
  height: 35px;
  display: grid;
  place-items: center;
  border-radius: 10px;
  background: #3ea4ec;
  color: #fff;
  font: 800 10px "Space Grotesk", sans-serif;
}
.friend-links__info {
  min-width: 0;
  flex: 1;
}
.friend-links__info strong {
  display: block;
  color: #38344f;
  font: 700 12px/1.5 "DM Sans", "Noto Sans SC", sans-serif;
}
.friend-links__info small {
  display: block;
  margin-top: 2px;
  color: #8e8b9d;
  font-size: 10px;
}
.friend-links__arrow {
  color: #8676af;
  transition: transform 0.18s;
}
.friend-links__item:hover .friend-links__arrow {
  transform: translate(2px, -2px);
}
.friend-links__item:focus-visible {
  outline: 3px solid #a18ddd;
  outline-offset: 2px;
}
@media (prefers-reduced-motion: reduce) {
  .friend-links__item,
  .friend-links__arrow {
    transition: none;
  }
}
</style>
