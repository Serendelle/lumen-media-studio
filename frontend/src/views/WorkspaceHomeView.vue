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
  loading.value = true
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
watch(() => tasks.active.length, (n, prev) => { if (n < prev) void load() })
const active = computed(() => summary.value ? summary.value.tasksByStatus.QUEUED + summary.value.tasksByStatus.RUNNING : 0)
const displayName = computed(() => auth.user?.displayName || auth.user?.username || '你')
const today = new Intl.DateTimeFormat('zh-CN', { month: 'long', day: 'numeric', weekday: 'long' }).format(new Date())

const QUICK = [
  { to: { path: '/workspace/media', query: { mode: 'pro', tool: 'compress' } }, icon: 'compress', label: '视频压缩', description: '按目标体积压缩，保留清晰画质', meta: '视频' },
  { to: { path: '/workspace/media', query: { mode: 'pro', tool: 'audio' } }, icon: 'audio', label: '音频提取', description: '从视频中提取你需要的声音', meta: '音频' },
  { to: { path: '/workspace/media', query: { mode: 'pro', tool: 'image' } }, icon: 'image', label: '图片处理', description: '批量压缩、调整尺寸与转换格式', meta: '图片' },
  { to: { path: '/workspace/documents' }, icon: 'pdf', label: '文档工作台', description: '将 Word、Excel 和网页转换为 PDF', meta: '文档' },
  { to: { path: '/workspace/specs' }, icon: 'check', label: '材料规范官', description: '整理参赛材料，检查提交规范', meta: '材料' },
  { to: { path: '/workspace/learn' }, icon: 'spark', label: '学习播放器', description: '把教学视频整理成知识图谱', meta: '学习' },
]
</script>

<template>
  <main id="main" class="home workspace-page" tabindex="-1">
    <header class="hello">
      <div>
        <p class="date">{{ today }}</p>
        <h1>你好，{{ displayName }}<span class="greeting-dot">.</span></h1>
        <p class="intro">从一个新想法开始，把处理文件的琐事交给 Lumen。</p>
      </div>
      <RouterLink to="/workspace/ai" class="btn start">
        <Icon name="spark" :size="15" /> 智能模式 <Icon name="arrow" :size="14" />
      </RouterLink>
    </header>

    <p v-if="error" class="err" role="alert">
      概览加载失败：{{ error }}
      <button type="button" class="btn btn--sm btn--ghost" :disabled="loading" @click="load">{{ loading ? '加载中…' : '重试' }}</button>
    </p>

    <section v-if="summary" class="stats" aria-label="工作台统计">
      <div class="stat card">
        <span class="k">进行中 <Icon name="clock" :size="15" /></span>
        <span class="v">{{ active }}</span>
        <span class="s">排队与正在处理的任务</span>
      </div>
      <div class="stat card">
        <span class="k">已完成 <Icon name="check" :size="15" /></span>
        <span class="v">{{ summary.tasksByStatus.SUCCEEDED }}</span>
        <span class="s">成功处理的任务</span>
      </div>
      <div class="stat card">
        <span class="k">失败任务 <Icon name="retry" :size="15" /></span>
        <span class="v" :class="{ warn: summary.tasksByStatus.FAILED > 0 }">{{ summary.tasksByStatus.FAILED }}</span>
        <RouterLink to="/workspace/tasks" class="s stat-link">{{ summary.tasksByStatus.FAILED ? '前往任务中心查看' : '暂无失败任务' }}</RouterLink>
      </div>
      <div class="stat card">
        <span class="k">上传文件 <Icon name="folder" :size="15" /></span>
        <span class="v">{{ summary.inputs.count }}</span>
        <span class="s">共 {{ bytes(summary.inputs.bytes) }}</span>
      </div>
      <div class="stat card">
        <span class="k">处理结果 <Icon name="file" :size="15" /></span>
        <span class="v">{{ summary.outputs.count }}</span>
        <span class="s">共 {{ bytes(summary.outputs.bytes) }}</span>
      </div>
    </section>
    <div v-else-if="loading" class="stats" aria-busy="true" aria-label="正在加载统计">
      <div v-for="i in 5" :key="i" class="stat card skel" />
    </div>

    <section aria-labelledby="quick-title">
      <header class="section-head">
        <div><h2 id="quick-title">开始创作</h2><p>选一个工具，继续你的下一步。</p></div>
        <RouterLink to="/workspace/media" class="more">全部媒体工具 <Icon name="arrow" :size="14" /></RouterLink>
      </header>
      <div class="quick">
        <RouterLink v-for="q in QUICK" :key="q.label" :to="q.to" class="qa card">
          <span class="qa-top"><span class="qi"><Icon :name="q.icon" :size="18" /></span><span class="tool-category">{{ q.meta }}</span></span>
          <span class="qa-title">{{ q.label }}<Icon name="arrow" :size="15" class="qarrow" /></span>
          <span class="qa-description">{{ q.description }}</span>
        </RouterLink>
      </div>
    </section>

    <div v-if="summary" class="cols">
      <section class="card block" aria-labelledby="rt">
        <header class="block-head"><h2 id="rt">最近任务</h2><RouterLink to="/workspace/tasks" class="more">查看全部 <Icon name="arrow" :size="13" /></RouterLink></header>
        <ul v-if="summary.recentTasks.length" class="rows">
          <li v-for="t in summary.recentTasks.slice(0, 5)" :key="t.taskId">
            <span class="row-icon"><Icon name="list" :size="16" /></span>
            <span class="row-copy"><span class="name">{{ TASK_LABEL[t.type] ?? t.type }}</span><span class="sub">{{ t.inputs?.[0]?.filename || relativeTime(t.createdAt) }}</span></span>
            <span class="chip" :class="{ 'chip--mint': t.status === 'SUCCEEDED', 'chip--danger': t.status === 'FAILED', 'chip--fog': t.status === 'RUNNING' || t.status === 'QUEUED' }">{{ STATUS_LABEL[t.status] }}</span>
          </li>
        </ul>
        <div v-else class="empty"><span class="empty-icon"><Icon name="list" :size="23" /></span><p>还没有处理任务</p><span>从上方选择一个工具，开始你的第一次创作。</span><RouterLink to="/workspace/media" class="empty-link">开始处理 <Icon name="arrow" :size="13" /></RouterLink></div>
      </section>
      <section class="card block" aria-labelledby="rf">
        <header class="block-head"><h2 id="rf">最近文件</h2><RouterLink to="/workspace/files" class="more">文件库 <Icon name="arrow" :size="13" /></RouterLink></header>
        <ul v-if="summary.recentFiles.length" class="rows">
          <li v-for="f in summary.recentFiles.slice(0, 5)" :key="f.fileId">
            <span class="row-icon"><Icon name="file" :size="16" /></span>
            <span class="row-copy"><span class="name" :title="f.originalFilename">{{ f.originalFilename }}</span><span class="sub">{{ bytes(f.size) }}</span></span>
            <span class="when">{{ relativeTime(f.createdAt) }}</span>
          </li>
        </ul>
        <div v-else class="empty"><span class="empty-icon"><Icon name="folder" :size="23" /></span><p>你的文件，从这里开始</p><span>上传的文件和处理结果会集中保存在文件库。</span><RouterLink to="/workspace/files" class="empty-link">打开文件库 <Icon name="arrow" :size="13" /></RouterLink></div>
      </section>
    </div>
    <p v-if="summary?.nextExpiryAt" class="note"><Icon name="clock" :size="13" />临时文件默认保留 24 小时，最早一个将于 {{ new Date(summary.nextExpiryAt).toLocaleString('zh-CN') }} 自动清理。</p>
    <footer class="home-footer"><span>Lumen · 流明智媒</span><span>专注创作，其余交给工具。</span></footer>
  </main>
</template>

<style scoped>
.home { display: grid; gap: 28px; outline: none; }
.hello { display: flex; align-items: center; justify-content: space-between; gap: 20px; }
.date { color: var(--text-3); font-size: 11px; margin-bottom: 8px; }
.hello h1 { font-size: clamp(23px, 2.2vw, 29px); line-height: 1.3; font-weight: 600; }
.greeting-dot { color: var(--text-3); }
.intro { margin-top: 8px; color: var(--text-3); font-size: 12px; }
.start { min-height: 36px; padding: 0 14px; flex: none; font-size: 12px; gap: 8px; }
.stats { display: grid; grid-template-columns: repeat(5, minmax(0, 1fr)); gap: 12px; }
.stat { display: flex; flex-direction: column; min-width: 0; min-height: 116px; padding: 16px; border-radius: 9px; }
.k { display: flex; align-items: center; justify-content: space-between; gap: 8px; font-size: 11px; color: var(--text-2); }
.k .icon { color: var(--text-3); }
.v { font-size: 28px; font-weight: 500; line-height: 1.3; margin-top: 10px; letter-spacing: -0.04em; font-variant-numeric: tabular-nums; }
.v.warn { color: var(--danger); }
.s { font-size: 10px; color: var(--text-3); margin-top: 5px; }
.stat-link { text-decoration: none; }
.stat-link:hover { text-decoration: underline; }
.section-head { display: flex; align-items: center; justify-content: space-between; gap: 16px; margin-bottom: 14px; }
.section-head h2, .block h2 { font-size: 14px; font-weight: 600; letter-spacing: -0.01em; }
.section-head p { margin-top: 5px; font-size: 11px; color: var(--text-3); }
.quick { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 12px; }
.qa { display: flex; flex-direction: column; min-width: 0; padding: 17px 18px; border-radius: 9px; text-decoration: none; transition: border-color 160ms, box-shadow 160ms; }
.qa:hover { border-color: var(--line-strong); box-shadow: 0 3px 12px rgb(0 0 0 / 0.035); }
.qa-top { display: flex; align-items: center; justify-content: space-between; margin-bottom: 15px; }
.qi { display: grid; place-items: center; width: 32px; height: 32px; border: 1px solid var(--line); border-radius: 7px; color: var(--text-2); background: var(--surface-sunk); }
.tool-category { color: var(--text-3); font-size: 10px; }
.qa-title { display: flex; align-items: center; justify-content: space-between; gap: 8px; font-size: 13px; font-weight: 500; }
.qarrow { color: var(--text-3); transition: transform 160ms; }
.qa:hover .qarrow { transform: translateX(3px); color: var(--text); }
.qa-description { font-size: 11px; color: var(--text-3); margin-top: 5px; line-height: 1.6; }
.more { display: inline-flex; align-items: center; flex: none; gap: 5px; font-size: 11px; color: var(--text-3); text-decoration: none; }
.more:hover { color: var(--text); }
.cols { display: grid; grid-template-columns: 1.15fr 1fr; gap: 16px; }
.block { min-width: 0; overflow: hidden; border-radius: 9px; }
.block-head { display: flex; align-items: center; justify-content: space-between; gap: 12px; padding: 16px 18px; border-bottom: 1px solid var(--line); }
.rows { margin: 0; padding: 0 18px; list-style: none; }
.rows li { display: flex; align-items: center; gap: 10px; min-height: 61px; border-bottom: 1px solid var(--line); font-size: 12px; }
.rows li:last-child { border-bottom: 0; }
.row-icon { display: grid; place-items: center; width: 30px; height: 32px; border: 1px solid var(--line); border-radius: 6px; color: var(--text-3); flex: none; }
.row-copy { display: flex; flex-direction: column; gap: 2px; min-width: 0; flex: 1; }
.rows .name { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; font-size: 12px; }
.rows .sub { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; color: var(--text-3); font-size: 10px; }
.when { color: var(--text-3); font-size: 10px; flex: none; }
.chip { font-size: 10px; padding: 2px 7px; flex: none; }
.empty { display: flex; flex-direction: column; align-items: center; text-align: center; padding: 30px 16px; min-height: 212px; }
.empty-icon { display: grid; place-items: center; height: 44px; width: 44px; border: 1px solid var(--line); border-radius: 11px; color: var(--text-3); background: #fafafa; margin-bottom: 13px; }
.empty p { font-size: 12px; font-weight: 500; }
.empty > span:not(.empty-icon) { margin-top: 5px; font-size: 11px; color: var(--text-3); }
.empty-link { display: flex; align-items: center; gap: 5px; margin-top: 14px; font-size: 11px; text-decoration: none; }
.empty-link:hover { text-decoration: underline; }
.note { display: flex; align-items: flex-start; gap: 7px; color: var(--text-3); font-size: 10px; }
.note .icon { margin-top: 2px; }
.home-footer { display: flex; align-items: center; justify-content: space-between; gap: 12px; border-top: 1px solid var(--line); padding-top: 16px; color: var(--text-3); font-size: 10px; }
.err { display: flex; flex-wrap: wrap; align-items: center; gap: 10px; padding: 10px 14px; border-radius: 6px; background: var(--danger-soft); color: var(--danger); font-size: 12px; }
.skel { background: var(--surface-sunk); animation: pulse 1.4s ease-in-out infinite; }
@keyframes pulse { 50% { opacity: 0.55; } }
@media (min-width: 768px) and (max-width: 1100px) { .stats { grid-template-columns: repeat(3, minmax(0, 1fr)); } .quick { grid-template-columns: repeat(2, minmax(0, 1fr)); } .cols { grid-template-columns: 1fr; } }
@media (max-width: 767px) { .home { padding-top: 24px; gap: 24px; } .stats { grid-template-columns: repeat(3, minmax(0, 1fr)); } .quick { grid-template-columns: repeat(2, minmax(0, 1fr)); } .cols { grid-template-columns: 1fr; } }
@media (max-width: 480px) { .hello { align-items: flex-start; flex-direction: column; gap: 14px; } .stats { grid-template-columns: repeat(2, minmax(0, 1fr)); } .stats .stat:last-child { grid-column: 1 / -1; } .quick { grid-template-columns: 1fr; } .qa { padding: 15px 17px; } .qa-top { margin-bottom: 12px; } .section-head { align-items: flex-start; } .stat { min-height: 108px; padding: 14px; } .home-footer span:last-child { display: none; } }
@media (prefers-reduced-motion: reduce) { .qa, .qarrow { transition: none; } .skel { animation: none; } }
</style>
