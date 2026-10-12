<script setup lang="ts">
defineProps<{ compact?: boolean }>()
</script>

<template>
  <span class="brand" :class="{ compact }">
    <svg class="mark" viewBox="0 0 32 32" aria-hidden="true">
      <defs>
        <radialGradient id="lumen-g" cx="38%" cy="34%" r="72%">
          <stop offset="0" stop-color="#F3E3D7" />
          <stop offset=".55" stop-color="#D9EDE4" />
          <stop offset="1" stop-color="#DCE8F0" />
        </radialGradient>
      </defs>
      <rect width="32" height="32" rx="9" fill="#303A37" />
      <circle cx="16" cy="16" r="8.5" fill="url(#lumen-g)" />
      <path d="M16 7.5v17" stroke="#303A37" stroke-width="1.3" opacity=".35" />
    </svg>
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
  width: 30px;
  height: 30px;
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
