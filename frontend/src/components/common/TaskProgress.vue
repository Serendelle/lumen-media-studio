<script setup lang="ts">
import { computed } from 'vue'
import type { TaskView } from '@/api/types'
import { STATUS_LABEL, stageLabel } from '@/utils/labels'

const props = defineProps<{ task: TaskView }>()

const tone = computed(() => {
  switch (props.task.status) {
    case 'SUCCEEDED':
      return 'ok'
    case 'FAILED':
      return 'bad'
    case 'CANCELLED':
      return 'muted'
    case 'QUEUED':
      return 'wait'
    default:
      return 'run'
  }
})
// queued tasks have no real progress yet: show an indeterminate bar rather than a fake number
const indeterminate = computed(() => props.task.status === 'QUEUED' || (props.task.status === 'RUNNING' && props.task.progress === 0))
const width = computed(() => (props.task.status === 'SUCCEEDED' ? 100 : props.task.progress))
</script>

<template>
  <div class="tp" :class="`tp--${tone}`">
    <div
      class="bar"
      role="progressbar"
      :aria-valuenow="indeterminate ? undefined : width"
      aria-valuemin="0"
      aria-valuemax="100"
      :aria-label="STATUS_LABEL[task.status]"
    >
      <span v-if="indeterminate" class="indet" />
      <span v-else class="fill" :style="{ transform: `scaleX(${width / 100})` }" />
    </div>
    <div class="meta">
      <span class="status">{{ STATUS_LABEL[task.status] }}<template v-if="task.status === 'RUNNING' && task.stage"> · {{ stageLabel(task.stage) }}</template></span>
      <span v-if="!indeterminate && task.status === 'RUNNING'" class="pct mono">{{ task.progress }}%</span>
    </div>
  </div>
</template>

<style scoped>
.tp {
  display: grid;
  gap: 6px;
}
.bar {
  position: relative;
  height: 4px;
  border-radius: 999px;
  overflow: hidden;
  background: var(--surface-sunk);
}
.fill {
  position: absolute;
  inset: 0;
  transform-origin: left;
  background: linear-gradient(90deg, var(--fog-ink), var(--mint-ink));
  transition: transform 0.5s var(--ease-out);
}
.indet {
  position: absolute;
  inset: 0;
  width: 35%;
  background: linear-gradient(90deg, transparent, var(--fog-ink), transparent);
  animation: indet 1.4s var(--ease-film) infinite;
}
@keyframes indet {
  from {
    transform: translateX(-100%);
  }
  to {
    transform: translateX(300%);
  }
}
.tp--ok .fill {
  background: var(--mint-ink);
}
.tp--bad .fill {
  background: var(--danger);
}
.tp--muted .fill {
  background: var(--ink-3);
}
.meta {
  display: flex;
  justify-content: space-between;
  font-size: var(--fs-xs);
  color: var(--text-2);
}
.tp--bad .status {
  color: var(--danger);
}
.tp--ok .status {
  color: var(--ok);
}
</style>
