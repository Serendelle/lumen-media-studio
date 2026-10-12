<script setup lang="ts">
import { computed } from 'vue'
import { AGENTS, useAiStore } from '@/stores/ai'

/**
 * Four agents as points of light on one line: the active one glows, finished ones stay lit,
 * the line fills up to the furthest agent that has worked. Driven only by real agent events.
 */
const ai = useAiStore()
const reached = computed(() => {
  let idx = -1
  AGENTS.forEach((a, i) => {
    if (ai.agents[a.id].status !== 'idle') idx = i
  })
  return idx
})
const label: Record<string, string> = {
  idle: '待命', working: '工作中', waiting: '等你回复', done: '已完成', partial: '部分完成', failed: '出错', cancelled: '已取消',
}
</script>

<template>
  <ol class="constellation" aria-label="Agent 协作进度">
    <span class="rail" aria-hidden="true"><span class="fill" :style="{ transform: `scaleX(${Math.max(0, reached) / (AGENTS.length - 1)})` }" /></span>
    <li v-for="a in AGENTS" :key="a.id" class="agent" :class="`s-${ai.agents[a.id].status}`"
        :aria-current="ai.agents[a.id].status === 'working' ? 'step' : undefined">
      <span class="star" aria-hidden="true"><span class="core" /></span>
      <span class="no mono">AGENT {{ a.no }}</span>
      <span class="name">{{ a.title }}</span>
      <span class="status">{{ label[ai.agents[a.id].status] }}</span>
      <span v-if="ai.agents[a.id].note && ai.agents[a.id].status === 'working'" class="note">{{ ai.agents[a.id].note }}</span>
    </li>
  </ol>
</template>

<style scoped>
.constellation {
  position: relative;
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 8px;
  margin: 0;
  padding: 18px 8px 4px;
  list-style: none;
}
.rail {
  position: absolute;
  top: 30px;
  left: 12.5%;
  right: 12.5%;
  height: 1px;
  background: var(--line-strong);
}
.fill {
  position: absolute;
  inset: 0;
  transform-origin: left;
  background: linear-gradient(90deg, var(--mint-ink), var(--fog-ink));
  transition: transform 0.8s var(--ease-out);
}
.agent {
  position: relative;
  display: grid;
  justify-items: center;
  gap: 4px;
  text-align: center;
}
.star {
  position: relative;
  width: 24px;
  height: 24px;
  display: grid;
  place-items: center;
}
.core {
  width: 10px;
  height: 10px;
  border-radius: 50%;
  background: var(--paper);
  border: 1.5px solid var(--ink-3);
  transition: background 0.4s, border-color 0.4s, transform 0.4s var(--ease-out);
}
.star::before {
  content: '';
  position: absolute;
  inset: -6px;
  border-radius: 50%;
  background: radial-gradient(circle, rgba(217, 237, 228, 0.95), transparent 70%);
  opacity: 0;
  transform: scale(0.6);
  transition: opacity 0.4s, transform 0.4s;
}
.s-working .star::before {
  opacity: 1;
  animation: glow 1.8s var(--ease-film) infinite alternate;
}
.s-working .core {
  background: var(--mint-ink);
  border-color: var(--mint-ink);
  transform: scale(1.15);
}
.s-done .core,
.s-partial .core {
  background: var(--mint-ink);
  border-color: var(--mint-ink);
}
.s-waiting .core {
  background: var(--apricot);
  border-color: var(--apricot-ink);
}
.s-failed .core {
  background: var(--danger-soft);
  border-color: var(--danger);
}
@keyframes glow {
  to {
    transform: scale(1.35);
    opacity: 0.55;
  }
}
.no {
  margin-top: 4px;
  font-size: 10px;
  letter-spacing: 0.14em;
  color: var(--text-3);
}
.name {
  font-size: var(--fs-sm);
}
.status {
  font-size: var(--fs-xs);
  color: var(--text-3);
}
.s-working .status {
  color: var(--mint-ink);
}
.s-waiting .status {
  color: var(--apricot-ink);
}
.s-failed .status {
  color: var(--danger);
}
.note {
  max-width: 16em;
  font-size: 11px;
  line-height: 1.4;
  color: var(--text-2);
}
@media (max-width: 640px) {
  .note {
    display: none;
  }
  .name {
    font-size: var(--fs-xs);
  }
}
@media (prefers-reduced-motion: reduce) {
  .s-working .star::before {
    animation: none;
  }
}
</style>
