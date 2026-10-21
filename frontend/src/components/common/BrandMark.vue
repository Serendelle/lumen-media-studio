<script setup lang="ts">
defineProps<{ compact?: boolean }>()
</script>

<template>
  <span class="brand" :class="{ compact }">
    <img class="mark" src="/favicon.svg?v=lumen-glass" width="36" height="36" alt="" aria-hidden="true" />
    <span class="word">
      <span class="en display">Lumen</span>
      <span v-if="!compact" class="zh">流明智媒</span>
    </span>
  </span>
</template>

<style scoped>
.brand {
  display: inline-flex;
  align-items: center;
  gap: 10px;
  text-decoration: none;
}
.mark {
  width: 36px;
  height: 36px;
  object-fit: contain;
  flex: none;
}
.word {
  display: inline-flex;
  align-items: baseline;
  gap: 8px;
}
.en {
  font-size: 1.32rem;
  letter-spacing: -0.02em;
}
.zh {
  font-size: var(--fs-xs);
  letter-spacing: 0.2em;
  color: var(--text-2);
}
</style>
