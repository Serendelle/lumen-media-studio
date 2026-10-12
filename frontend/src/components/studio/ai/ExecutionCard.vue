<script setup lang="ts">
import type { Plan } from '@/api/ai'
import { useAiStore } from '@/stores/ai'
import { stageLabel, STATUS_LABEL } from '@/utils/labels'
import type { TaskStatus } from '@/api/types'

defineProps<{ plan: Plan }>()
const ai = useAiStore()
const label = (s?: string) => (s === 'SKIPPED' ? '已跳过' : s ? (STATUS_LABEL[s as TaskStatus] ?? s) : '等待中')
</script>

<template>
  <article class="exec card" aria-label="执行进度">
    <header class="eyebrow">Agent 03 · 处理执行官</header>
    <ol class="list">
      <li v-for="s in plan.steps" :key="s.id" class="item" :class="`s-${(ai.steps[s.id]?.status ?? 'WAITING').toLowerCase()}`">
        <span class="n mono">{{ String(s.id).padStart(2, '0') }}</span>
        <div class="body">
          <div class="row">
            <span class="title">{{ s.title }}</span>
            <span class="st">
              {{ label(ai.steps[s.id]?.status) }}
              <template v-if="ai.steps[s.id]?.status === 'RUNNING' && ai.steps[s.id]?.stage"> · {{ stageLabel(ai.steps[s.id]?.stage) }}</template>
              <span v-if="ai.steps[s.id]?.status === 'RUNNING'" class="mono"> {{ ai.steps[s.id]?.progress }}%</span>
            </span>
          </div>
          <div class="bar" role="progressbar" :aria-valuenow="ai.steps[s.id]?.progress ?? 0" aria-valuemin="0" aria-valuemax="100" :aria-label="s.title">
            <span :style="{ transform: `scaleX(${(ai.steps[s.id]?.status === 'SUCCEEDED' ? 100 : (ai.steps[s.id]?.progress ?? 0)) / 100})` }" />
          </div>
          <p v-if="ai.steps[s.id]?.message && ai.steps[s.id]?.status !== 'SUCCEEDED'" class="msg">{{ ai.steps[s.id]?.message }}</p>
        </div>
      </li>
    </ol>
  </article>
</template>

<style scoped>
.exec {
  display: grid;
  gap: 12px;
  padding: var(--sp-5);
  border-radius: var(--r-lg);
}
.list {
  margin: 0;
  padding: 0;
  list-style: none;
  display: grid;
  gap: 12px;
}
.item {
  display: grid;
  grid-template-columns: 30px 1fr;
  gap: 10px;
}
.n {
  font-size: var(--fs-sm);
  color: var(--text-3);
}
.body {
  display: grid;
  gap: 6px;
}
.row {
  display: flex;
  justify-content: space-between;
  gap: 8px;
  font-size: var(--fs-sm);
}
.st {
  font-size: var(--fs-xs);
  color: var(--text-2);
  white-space: nowrap;
}
.bar {
  height: 4px;
  border-radius: 999px;
  overflow: hidden;
  background: var(--surface-sunk);
}
.bar span {
  display: block;
  height: 100%;
  transform-origin: left;
  background: linear-gradient(90deg, var(--fog-ink), var(--mint-ink));
  transition: transform 0.5s var(--ease-out);
}
.s-succeeded .st {
  color: var(--ok);
}
.s-failed .st,
.s-failed .msg {
  color: var(--danger);
}
.s-failed .bar span {
  background: var(--danger);
}
.s-skipped .st {
  color: var(--text-3);
}
.msg {
  font-size: var(--fs-xs);
  color: var(--text-3);
}
</style>
