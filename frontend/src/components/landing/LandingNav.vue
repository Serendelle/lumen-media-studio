<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref } from 'vue'
import { RouterLink } from 'vue-router'
import BrandMark from '@/components/common/BrandMark.vue'
import { useAuthStore } from '@/stores/auth'

const auth = useAuthStore()
const scrolled = ref(false)
const onScroll = () => (scrolled.value = window.scrollY > 24)
onMounted(() => {
  onScroll()
  window.addEventListener('scroll', onScroll, { passive: true })
})
onBeforeUnmount(() => window.removeEventListener('scroll', onScroll))
</script>

<template>
  <header class="nav" :class="{ scrolled }">
    <div class="inner">
      <RouterLink to="/" class="home" aria-label="Lumen 首页"><BrandMark /></RouterLink>
      <nav class="links" aria-label="主导航">
        <a href="#capabilities">能力</a>
        <a href="#workspace">两种模式</a>
      </nav>
      <div class="cta">
        <template v-if="auth.isAuthenticated">
          <RouterLink class="btn btn--sm" to="/workspace">进入工作台</RouterLink>
        </template>
        <template v-else>
          <RouterLink class="btn btn--sm btn--ghost login" to="/login">登录</RouterLink>
          <RouterLink class="btn btn--sm" to="/register">免费开始</RouterLink>
        </template>
      </div>
    </div>
  </header>
</template>

<style scoped>
.nav {
  position: fixed;
  inset: 0 0 auto;
  z-index: 40;
  transition: background var(--t-med) var(--ease-out), box-shadow var(--t-med) var(--ease-out);
}
.nav.scrolled {
  background: rgba(247, 247, 242, 0.86);
  backdrop-filter: saturate(1.2) blur(12px);
  box-shadow: 0 1px 0 var(--line);
}
.inner {
  display: flex;
  align-items: center;
  gap: var(--sp-5);
  max-width: var(--maxw);
  margin: 0 auto;
  padding: 14px var(--gutter);
}
.home {
  text-decoration: none;
}
.links {
  display: flex;
  gap: var(--sp-5);
  margin-left: auto;
  font-size: var(--fs-sm);
}
.links a {
  color: var(--text-2);
  text-decoration: none;
  transition: color var(--t-fast);
}
.links a:hover {
  color: var(--text);
}
.cta {
  display: flex;
  gap: var(--sp-2);
}
@media (max-width: 640px) {
  .links {
    display: none;
  }
  .cta {
    margin-left: auto;
  }
  .login {
    display: none;
  }
}
</style>
