<script setup lang="ts">
import { RouterView } from 'vue-router'
import { defineAsyncComponent } from 'vue'
import ToastHost from '@/components/common/ToastHost.vue'

// ?fps on any URL shows a live frame-rate meter for verifying animation smoothness
const showFps = new URLSearchParams(location.search).has('fps')
const FpsMeter = defineAsyncComponent(() => import('@/components/common/FpsMeter.vue'))
</script>

<template>
  <a class="skip-link" href="#main">跳到主要内容</a>
  <!--
    Enter-only CSS animation instead of <Transition mode="out-in">: the next page mounts immediately,
    so navigation never waits on a leave transition (which stalls when frames are throttled).
  -->
  <RouterView v-slot="{ Component, route }">
    <div :key="route.name ?? route.path" class="route">
      <component :is="Component" />
    </div>
  </RouterView>
  <ToastHost />
  <FpsMeter v-if="showFps" />
</template>

<style>
.skip-link {
  position: fixed;
  left: 16px;
  top: -60px;
  z-index: 100;
  padding: 8px 14px;
  border-radius: 999px;
  background: var(--ink);
  color: var(--cream);
  text-decoration: none;
  transition: top 0.2s;
}
.skip-link:focus {
  top: 16px;
}
/*
 * Opacity only, and "backwards" fill: a (filled) transform animation would make .route the containing block
 * for position:fixed descendants, breaking the fixed nav and GSAP's pinned scenes.
 */
.route {
  animation: route-in 0.45s var(--ease-out) backwards;
}
@keyframes route-in {
  from {
    opacity: 0;
  }
}
</style>
