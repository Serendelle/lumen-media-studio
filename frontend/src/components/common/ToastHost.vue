<script setup lang="ts">
import { useToast } from '@/composables/useToast'

const toast = useToast()
</script>

<template>
  <div class="toasts" role="status" aria-live="polite">
    <TransitionGroup name="toast">
      <div v-for="t in toast.items" :key="t.id" class="toast" :class="`toast--${t.tone}`">
        <span class="dot" aria-hidden="true" />
        <span class="text">{{ t.text }}</span>
        <button class="close" type="button" aria-label="关闭提示" @click="toast.dismiss(t.id)">×</button>
      </div>
    </TransitionGroup>
  </div>
</template>

<style scoped>
.toasts {
  position: fixed;
  right: max(16px, env(safe-area-inset-right));
  bottom: max(16px, env(safe-area-inset-bottom));
  z-index: 90;
  display: grid;
  gap: 10px;
  width: min(380px, calc(100vw - 32px));
}
.toast {
  display: flex;
  align-items: flex-start;
  gap: 10px;
  padding: 12px 14px;
  border-radius: var(--r-md);
  background: var(--ink);
  color: var(--cream);
  box-shadow: var(--shadow-3);
  font-size: var(--fs-sm);
}
.dot {
  flex: none;
  width: 8px;
  height: 8px;
  margin-top: 7px;
  border-radius: 50%;
  background: var(--fog);
}
.toast--ok .dot {
  background: var(--mint);
}
.toast--error .dot {
  background: #f0b3a8;
}
.text {
  flex: 1;
  line-height: 1.5;
}
.close {
  flex: none;
  border: 0;
  background: none;
  color: inherit;
  opacity: 0.6;
  font-size: 18px;
  line-height: 1;
}
.toast-enter-active,
.toast-leave-active {
  transition: opacity 0.3s var(--ease-out), transform 0.3s var(--ease-out);
}
.toast-enter-from,
.toast-leave-to {
  opacity: 0;
  transform: translateY(8px);
}
</style>
