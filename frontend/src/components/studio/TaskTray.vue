<script setup lang="ts">
import { computed, ref } from 'vue'
import TaskCard from './TaskCard.vue'
import ResultPreview from './ResultPreview.vue'
import type { TaskView } from '@/api/types'
import { useTasksStore } from '@/stores/tasks'

const tasks = useTasksStore()
const filter = ref<'all' | 'active' | 'done'>('all')
const previewing = ref<TaskView | null>(null)

const shown = computed(() => {
  if (filter.value === 'active') return tasks.active
  if (filter.value === 'done') return tasks.list.filter((t) => t.status === 'SUCCEEDED')
  return tasks.list
})
</script>

<template>
  <aside class="tray" aria-label="任务列表">
    <header class="head">
      <h2>任务</h2>
      <span v-if="tasks.active.length" class="chip chip--fog">{{ tasks.active.length }} 个进行中</span>
    </header>
    <div class="filters" role="tablist" aria-label="任务筛选">
      <button v-for="f in [{ k: 'all', l: '全部' }, { k: 'active', l: '进行中' }, { k: 'done', l: '已完成' }] as const" :key="f.k"
              type="button" role="tab" :aria-selected="filter === f.k" :class="{ on: filter === f.k }" @click="filter = f.k">
        {{ f.l }}
      </button>
    </div>
    <TransitionGroup tag="div" name="list" class="list" aria-live="polite">
      <TaskCard v-for="t in shown" :key="t.taskId" :task="t" @preview="previewing = $event" />
    </TransitionGroup>
    <p v-if="!shown.length" class="empty">
      {{ filter === 'all' ? '提交的任务会出现在这里，进度实时更新；刷新页面也不会丢失。' : '暂无' }}
    </p>
    <ResultPreview :task="previewing" @close="previewing = null" />
  </aside>
</template>

<style scoped>
.tray {
  display: grid;
  align-content: start;
  gap: var(--sp-3);
}
.head {
  display: flex;
  align-items: center;
  gap: 8px;
}
.head h2 {
  font-size: var(--fs-lg);
}
.filters {
  display: flex;
  gap: 4px;
  padding: 3px;
  border-radius: var(--r-pill);
  background: var(--surface-sunk);
  width: max-content;
}
.filters button {
  min-height: 30px;
  padding: 0 12px;
  border: 0;
  border-radius: var(--r-pill);
  background: transparent;
  font-size: var(--fs-xs);
  color: var(--text-2);
}
.filters button.on {
  background: var(--surface);
  color: var(--text);
  box-shadow: var(--shadow-1);
}
.list {
  display: grid;
  gap: 10px;
}
.empty {
  font-size: var(--fs-sm);
  color: var(--text-3);
}
.list-enter-active,
.list-leave-active {
  transition: opacity 0.35s var(--ease-out), transform 0.35s var(--ease-out);
}
.list-enter-from {
  opacity: 0;
  transform: translateY(-8px);
}
.list-leave-to {
  opacity: 0;
}
</style>
