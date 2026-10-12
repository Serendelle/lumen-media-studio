<script setup lang="ts">
import { RouterLink } from 'vue-router'
</script>

<template>
  <main id="main" class="nf">
    <p class="eyebrow">Scene 404 — Cut</p>
    <h1 class="display">这一帧不存在。</h1>
    <p>你访问的页面可能已移动或从未存在。</p>
    <RouterLink class="btn" to="/">回到首页</RouterLink>
  </main>
</template>

<style scoped>
.nf {
  min-height: 100svh;
  display: grid;
  place-content: center;
  justify-items: start;
  gap: var(--sp-4);
  padding: var(--gutter);
  max-width: 640px;
  margin: 0 auto;
}
h1 {
  font-size: var(--fs-2xl);
}
p:not(.eyebrow) {
  color: var(--text-2);
}
</style>
