<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { RouterLink } from 'vue-router'
import Icon from '@/components/common/Icon.vue'
import { workspaceApi } from '@/api/endpoints'
import type { WorkspaceSummary } from '@/api/types'
import { useAuthStore } from '@/stores/auth'
import { useTasksStore } from '@/stores/tasks'
import { bytes, relativeTime } from '@/utils/format'
import { STATUS_LABEL, TASK_LABEL } from '@/utils/labels'

const auth = useAuthStore()
const tasks = useTasksStore()
const summary = ref<WorkspaceSummary | null>(null)
const error = ref<string | null>(null)
const loading = ref(true)

async function load() {
  try {
    summary.value = await workspaceApi.summary()
    error.value = null
  } catch (e) {
    error.value = (e as Error).message
  } finally {
    loading.value = false
  }
}
onMounted(load)
// counts change when a running task finishes
watch(() => tasks.active.length, (n, prev) => { if (n < prev) void load() })

const active = computed(() => (summary.value ? summary.value.tasksByStatus.QUEUED + summary.value.tasksByStatus.RUNNING : 0))

const QUICK = [
  { to: { path: '/workspace/media', query: { mode: 'pro', tool: 'compress' } }, icon: 'compress', label: '按目标体积压缩视频' },
  { to: { path: '/workspace/media', query: { mode: 'pro', tool: 'audio' } }, icon: 'audio', label: '从视频提取音轨' },
  { to: { path: '/workspace/media', query: { mode: 'pro', tool: 'image' } }, icon: 'image', label: '批量处理图片' },
  { to: { path: '/workspace/documents' }, icon: 'pdf', label: 'Word / Excel / 网页转 PDF' },
  { to: { path: '/workspace/specs' }, icon: 'check', label: '整理一批参赛材料' },
  { to: { path: '/workspace/learn' }, icon: 'spark', label: '把教学视频变成知识图谱' },
  { to: { path: '/workspace/media', query: { mode: 'ai' } }, icon: 'spark', label: '用一句话描述需求' },
]
</script>

<template>
  <main id="main" class="home">
    <header class="hello">
      <h1>你好，{{ auth.user?.displayName ?? auth.user?.username }}</h1>
      <p>这里汇总了你的任务与文件。所有数字都来自服务端的真实记录。</p>
    </header>

    <section class="quick" aria-label="快捷入口">
      <RouterLink v-for="q in QUICK" :key="q.label" :to="q.to" class="qa card">
        <span class="qi"><Icon :name="q.icon" :size="18" /></span>
        <span>{{ q.label }}</span>
        <Icon name="arrow" :size="16" class="qarrow" />
      </RouterLink>
    </section>

    <p v-if="error" class="err" role="alert">
      概览加载失败：{{ error }}
      <button type="button" class="btn btn--sm btn--ghost" @click="load">重试</button>
    </p>

    <section v-if="summary" class="stats" aria-label="统计">
      <div class="stat card">
        <span class="k">进行中</span>
        <span class="v mono">{{ active }}</span>
      </div>
      <div class="stat card">
        <span class="k">已完成</span>
        <span class="v mono">{{ summary.tasksByStatus.SUCCEEDED }}</span>
      </div>
      <div class="stat card">
        <span class="k">失败</span>
        <span class="v mono" :class="{ warn: summary.tasksByStatus.FAILED > 0 }">{{ summary.tasksByStatus.FAILED }}</span>
      </div>
      <div class="stat card">
        <span class="k">上传文件</span>
        <span class="v mono">{{ summary.inputs.count }}</span>
        <span class="s">{{ bytes(summary.inputs.bytes) }}</span>
      </div>
      <div class="stat card">
        <span class="k">处理结果</span>
        <span class="v mono">{{ summary.outputs.count }}</span>
        <span class="s">{{ bytes(summary.outputs.bytes) }}</span>
      </div>
    </section>
    <div v-else-if="loading" class="stats" aria-busy="true">
      <div v-for="i in 5" :key="i" class="stat card skel" />
    </div>

    <p v-if="summary?.nextExpiryAt" class="note">
      <Icon name="clock" :size="14" />
      临时文件默认保留 24 小时，最早一个将于 {{ new Date(summary.nextExpiryAt).toLocaleString('zh-CN') }} 自动清理。
    </p>

    <div v-if="summary" class="cols">
      <section class="card block" aria-labelledby="rt">
        <header>
          <h2 id="rt">最近任务</h2>
          <RouterLink to="/workspace/tasks" class="more">任务中心 <Icon name="arrow" :size="14" /></RouterLink>
        </header>
        <ul v-if="summary.recentTasks.length" class="rows">
          <li v-for="t in summary.recentTasks" :key="t.taskId">
            <span class="name">{{ TASK_LABEL[t.type] ?? t.type }}</span>
            <span class="sub">{{ t.inputs?.[0]?.filename ?? '' }}</span>
            <span class="chip" :class="{ 'chip--mint': t.status === 'SUCCEEDED', 'chip--danger': t.status === 'FAILED', 'chip--fog': t.status === 'RUNNING' || t.status === 'QUEUED' }">
              {{ STATUS_LABEL[t.status] }}
            </span>
            <span class="when">{{ relativeTime(t.createdAt) }}</span>
          </li>
        </ul>
        <p v-else class="empty">还没有任务。从上方快捷入口开始吧。</p>
      </section>

      <section class="card block" aria-labelledby="rf">
        <header>
          <h2 id="rf">最近上传</h2>
          <RouterLink to="/workspace/files" class="more">我的文件 <Icon name="arrow" :size="14" /></RouterLink>
        </header>
        <ul v-if="summary.recentFiles.length" class="rows">
          <li v-for="f in summary.recentFiles" :key="f.fileId">
            <span class="name">{{ f.originalFilename }}</span>
            <span class="sub">{{ bytes(f.size) }}</span>
            <span class="when">{{ relativeTime(f.createdAt) }}</span>
          </li>
        </ul>
        <p v-else class="empty">还没有上传文件。</p>
      </section>
    </div>
  </main>
</template>

<style scoped>
.home {
  display: grid;
  gap: var(--sp-5);
  max-width: 1200px;
  margin: 0 auto;
  padding: var(--sp-6) var(--gutter) var(--sp-8);
}
.hello h1 {
  font-size: var(--fs-2xl);
}
.hello p {
  margin-top: 6px;
  color: var(--text-2);
}
.quick {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(220px, 1fr));
  gap: var(--sp-3);
}
.qa {
  display: flex;
  align-items: center;
  gap: 12px;
  min-height: 56px;
  padding: 12px 16px;
  color: var(--text);
  text-decoration: none;
  font-size: var(--fs-sm);
  transition: transform var(--t-fast), box-shadow var(--t-fast);
}
.qa:hover {
  transform: translateY(-1px);
  box-shadow: var(--shadow-2, var(--shadow-1));
}
.qa:focus-visible {
  outline: 2px solid var(--focus);
  outline-offset: 2px;
}
.qi {
  display: grid;
  place-items: center;
  width: 34px;
  height: 34px;
  border-radius: 10px;
  background: var(--mint);
  color: var(--mint-ink);
}
.qarrow {
  margin-left: auto;
  color: var(--text-3);
}
.stats {
  display: grid;
  grid-template-columns: repeat(5, minmax(0, 1fr));
  gap: var(--sp-3);
}
.stat {
  display: grid;
  gap: 2px;
  min-height: 88px;
  padding: 14px 16px;
}
.stat .k {
  font-size: var(--fs-xs);
  color: var(--text-3);
}
.stat .v {
  font-size: 1.75rem;
  line-height: 1.2;
}
.stat .v.warn {
  color: var(--danger);
}
.stat .s {
  font-size: var(--fs-xs);
  color: var(--text-2);
}
.skel {
  background: var(--surface-sunk);
  animation: pulse 1.4s ease-in-out infinite;
}
@keyframes pulse {
  50% { opacity: 0.55; }
}
.note {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: var(--fs-xs);
  color: var(--text-3);
}
.cols {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: var(--sp-4);
}
.block {
  padding: var(--sp-5);
}
.block header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: var(--sp-3);
}
.block h2 {
  font-size: var(--fs-lg);
}
.more {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  font-size: var(--fs-sm);
  color: var(--accent);
  text-decoration: none;
}
.rows {
  display: grid;
  list-style: none;
  margin: 0;
  padding: 0;
}
.rows li {
  display: grid;
  grid-template-columns: auto minmax(0, 1fr) auto auto;
  align-items: center;
  gap: 10px;
  min-height: 44px;
  border-top: 1px solid var(--line);
  font-size: var(--fs-sm);
}
.rows li:first-child {
  border-top: 0;
}
.rows .sub {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  color: var(--text-3);
}
.rows .when {
  font-size: var(--fs-xs);
  color: var(--text-3);
}
.empty {
  padding: var(--sp-4) 0;
  font-size: var(--fs-sm);
  color: var(--text-3);
}
.err {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 10px 14px;
  border-radius: var(--r-sm);
  background: var(--danger-soft);
  color: var(--danger);
  font-size: var(--fs-sm);
}
@media (max-width: 1100px) {
  .stats {
    grid-template-columns: repeat(3, minmax(0, 1fr));
  }
}
@media (max-width: 760px) {
  .stats {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
  .cols {
    grid-template-columns: 1fr;
  }
}
@media (prefers-reduced-motion: reduce) {
  .qa, .skel {
    transition: none;
    animation: none;
  }
}
</style>
