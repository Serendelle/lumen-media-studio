<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import TaskCard from './TaskCard.vue'
import ResultPreview from './ResultPreview.vue'
import type { TaskView } from '@/api/types'
import { useTasksStore } from '@/stores/tasks'

/** Right-hand task panel: bordered, collapsible groups of compact rows. */
const tasks = useTasksStore()
const previewing = ref<TaskView | null>(null)

const KEY = 'lumen.tray-collapsed'
const collapsed = reactive<Record<string, boolean>>({})
try { Object.assign(collapsed, JSON.parse(localStorage.getItem(KEY) ?? '{}')) } catch { /* defaults: all open */ }
watch(collapsed, (v) => { try { localStorage.setItem(KEY, JSON.stringify(v)) } catch { /* session only */ } })

const groups = computed(() => [
  { k: 'active', l: '进行中', items: tasks.active, empty: '没有正在处理的任务' },
  { k: 'done', l: '已完成', items: tasks.list.filter((t) => t.status === 'SUCCEEDED'), empty: '完成的任务会出现在这里' },
  { k: 'failed', l: '失败与取消', items: tasks.list.filter((t) => t.status === 'FAILED' || t.status === 'CANCELLED'), empty: '' },
].filter((g) => g.k !== 'failed' || g.items.length))
</script>

<template>
  <aside class="tray" aria-label="任务">
    <section v-for="g in groups" :key="g.k" class="grp" :class="`g-${g.k}`">
      <button type="button" class="gh" :aria-expanded="!collapsed[g.k]" :aria-controls="`tray-${g.k}`" @click="collapsed[g.k] = !collapsed[g.k]">
        <span class="gl">{{ g.l }}</span>
        <svg class="chev" :class="{ shut: collapsed[g.k] }" width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="m6 9 6 6 6-6" /></svg>
        <span class="cnt mono" :class="{ live: g.k === 'active' && g.items.length }">{{ g.items.length }}</span>
      </button>
      <ul v-show="!collapsed[g.k]" :id="`tray-${g.k}`" class="rows" aria-live="polite">
        <TaskCard v-for="t in g.items" :key="t.taskId" :task="t" @preview="previewing = $event" />
        <li v-if="!g.items.length" class="none">{{ g.empty }}</li>
      </ul>
    </section>
    <ResultPreview :task="previewing" @close="previewing = null" />
  </aside>
</template>

<style scoped>
.tray {
  display: grid;
  align-content: start;
  gap: 10px;
}
.grp {
  border: 1px solid var(--line);
  border-radius: 10px;
  background: rgb(255 255 255 / 0.015);
}
.gh {
  display: flex;
  align-items: center;
  gap: 6px;
  width: 100%;
  padding: 10px 12px 6px;
  border: 0;
  background: none;
  color: var(--text-2);
  text-align: left;
  cursor: pointer;
}
.gh:hover {
  color: var(--text);
}
.gl {
  font-size: 12px;
  font-weight: 500;
}
.chev {
  color: var(--text-3);
  transition: transform 160ms;
}
.chev.shut {
  transform: rotate(-90deg);
}
.cnt {
  margin-left: auto;
  font-size: 11px;
  color: var(--text-3);
}
.cnt.live {
  color: var(--fog-ink);
}
.rows {
  display: grid;
  gap: 1px;
  margin: 0;
  padding: 0 4px 6px;
  list-style: none;
}
.none {
  padding: 4px 8px 6px;
  font-size: 12px;
  color: var(--text-3);
}
</style>
