<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { RouterLink, useRouter } from 'vue-router'
import MediaPicker from '@/components/studio/MediaPicker.vue'
import { lessonsApi } from '@/api/endpoints'
import type { LessonCapabilities, LessonSummary } from '@/api/types'
import { useTaskSubmit } from '@/composables/useTaskSubmit'
import { isTerminal, useTasksStore } from '@/stores/tasks'
import { relativeTime } from '@/utils/format'
import { ERROR_HINT } from '@/utils/labels'

const router = useRouter()
const tasks = useTasksStore()
const { busy, error, run } = useTaskSubmit()

const caps = ref<LessonCapabilities | null>(null)
const lessons = ref<LessonSummary[]>([])
const selected = ref<string[]>([])
const taskId = ref<string | null>(null)

async function load() {
  lessons.value = (await lessonsApi.list()).items
}
onMounted(async () => {
  try {
    caps.value = await lessonsApi.capabilities()
  } catch {
    /* shown as unknown */
  }
  await load().catch(() => {})
})

const ready = computed(() => !!caps.value?.asr.configured && !!caps.value?.llm)
async function analyze() {
  if (!selected.value[0]) return
  const id = await run(() => lessonsApi.analyze(selected.value[0]), '开始分析课程')
  if (id) taskId.value = id
}
const task = computed(() => (taskId.value ? tasks.byId[taskId.value] : undefined))
watch(() => task.value?.status, (s) => {
  if (s === 'SUCCEEDED') void load()
})

/** The pipeline shown to the user, driven by the task's real stage names. */
const STEPS = [
  { k: '提取音轨', l: '听 · 提取音轨' },
  { k: '语音识别', l: '听 · 语音识别（阿里云百炼）' },
  { k: '抽取关键帧', l: '看 · 抽取关键帧' },
  { k: '画面文字识别', l: '看 · 画面文字识别（本地 OCR）' },
  { k: '整理知识大纲', l: '理解 · 整理知识大纲（DeepSeek）' },
  { k: '保存课程', l: '校验 · 核对时间与代码出处' },
]
const stepIndex = computed(() => {
  const st = task.value?.stage ?? ''
  if (task.value?.status === 'SUCCEEDED') return STEPS.length
  const i = STEPS.findIndex((s) => st.startsWith(s.k))
  return i < 0 ? 0 : i
})
function clock(sec: number) {
  const s = Math.floor(sec)
  return `${Math.floor(s / 60)}:${String(s % 60).padStart(2, '0')}`
}
</script>

<template>
  <main id="main" class="ll">
    <header class="head">
      <h1>学习播放器</h1>
      <p>上传一段教学视频：系统听讲解、看画面，整理出随播放生长的知识图谱；讲到代码时，代码会出现在旁边的沙盒里，改一改就能运行。</p>
    </header>

    <section v-if="caps" class="caps" aria-label="运行环境">
      <span class="chip" :class="caps.asr.configured ? 'chip--mint' : 'chip--apricot'">语音识别 · {{ caps.asr.provider }} {{ caps.asr.configured ? caps.asr.model : '未配置' }}</span>
      <span class="chip" :class="caps.ocr ? 'chip--mint' : 'chip--apricot'">画面识别 · RapidOCR {{ caps.ocr ? '本地就绪' : '未安装' }}</span>
      <span class="chip" :class="caps.llm ? 'chip--mint' : 'chip--apricot'">知识整理 · DeepSeek {{ caps.llm ? '就绪' : '未配置' }}</span>
    </section>
    <p v-if="caps && !caps.asr.configured" class="warn" role="note">
      还没有配置阿里云百炼的 API Key：在后端的 <code>.env</code> 中填写 <code>DASHSCOPE_API_KEY</code> 后重启即可。Key 只保存在服务器上，不会出现在页面里。
    </p>

    <section class="card pad">
      <h2>分析一节课</h2>
      <MediaPicker v-model="selected" :kinds="['VIDEO']" label="教学视频" accept="video/*" />
      <p class="muted">时长 10 秒到 {{ caps?.maxDurationMin ?? 120 }} 分钟。音频会上传到阿里云百炼做语音识别（临时存储，48 小时后自动删除）；画面识别在本机完成；讲稿文字会发送给 DeepSeek 整理。</p>
      <p v-if="error" class="err" role="alert">{{ error }}</p>
      <button type="button" class="btn go" :disabled="!selected[0] || busy || !ready || (task && !isTerminal(task))" @click="analyze">开始分析</button>

      <ol v-if="task" class="pipe" aria-live="polite">
        <li v-for="(s, i) in STEPS" :key="s.k"
            :class="{ done: i < stepIndex, now: i === stepIndex && !isTerminal(task), bad: task.status === 'FAILED' && i === stepIndex }">
          <span class="dot" aria-hidden="true" />{{ s.l }}
          <span v-if="i === stepIndex && !isTerminal(task) && task.stage" class="mono st">{{ task.stage }}</span>
        </li>
      </ol>
      <div v-if="task?.status === 'FAILED'" class="why">
        <p><strong>分析失败：</strong>{{ task.message }}</p>
        <p v-if="task.errorCode && ERROR_HINT[task.errorCode]">{{ ERROR_HINT[task.errorCode] }}</p>
      </div>
      <p v-if="task?.status === 'SUCCEEDED'" class="ok">
        已生成「{{ task.result?.title }}」：{{ task.result?.nodes }} 个知识点、{{ task.result?.snippets }} 段代码。
        <button type="button" class="btn btn--sm" @click="router.push(`/workspace/learn/${task.result?.lessonId}`)">打开播放器</button>
      </p>
    </section>

    <section class="list">
      <RouterLink v-for="l in lessons" :key="l.id" :to="`/workspace/learn/${l.id}`" class="card item">
        <strong>{{ l.title }}</strong>
        <span class="meta">{{ clock(l.duration) }} · {{ l.nodes }} 个知识点 · {{ l.snippets }} 段代码</span>
        <span class="when">{{ relativeTime(l.createdAt) }}</span>
      </RouterLink>
      <p v-if="!lessons.length" class="muted">还没有分析过的课程。</p>
    </section>
  </main>
</template>

<style scoped>
.ll { display: grid; grid-template-columns: minmax(0, 1fr); gap: var(--sp-4); max-width: 1000px; margin: 0 auto; padding: var(--sp-6) var(--gutter) var(--sp-8); }
.head h1 { font-size: var(--fs-2xl); }
.head p { margin-top: 6px; color: var(--text-2); font-size: var(--fs-sm); line-height: 1.7; }
.caps { display: flex; flex-wrap: wrap; gap: 6px; }
.warn { padding: 10px 12px; border-radius: var(--r-sm); background: var(--apricot); color: var(--apricot-ink); font-size: var(--fs-sm); line-height: 1.6; }
code { font-family: var(--font-mono); font-size: 12px; }
.pad { display: grid; grid-template-columns: minmax(0, 1fr); gap: var(--sp-3); padding: var(--sp-5); }
h2 { font-size: var(--fs-lg); }
.muted { font-size: var(--fs-xs); color: var(--text-3); line-height: 1.6; }
.go { justify-self: start; }
.pipe { display: grid; gap: 6px; margin: 0; padding: 0; list-style: none; font-size: var(--fs-sm); color: var(--text-3); }
.pipe li { display: flex; align-items: center; gap: 10px; }
.dot { width: 10px; height: 10px; border-radius: 50%; border: 2px solid var(--line-strong); }
.pipe .done { color: var(--text-2); }
.pipe .done .dot { background: var(--mint-ink); border-color: var(--mint-ink); }
.pipe .now { color: var(--text); font-weight: 500; }
.pipe .now .dot { border-color: var(--fog-ink); animation: pulse 1.2s ease-in-out infinite; }
.pipe .bad .dot { background: var(--danger); border-color: var(--danger); animation: none; }
.st { font-size: 11px; color: var(--text-3); font-weight: 400; }
@keyframes pulse { 50% { background: var(--fog); } }
.why { padding: 10px 12px; border-radius: var(--r-sm); background: var(--danger-soft); font-size: var(--fs-sm); line-height: 1.6; }
.why p:first-child { color: var(--danger); }
.ok { display: flex; align-items: center; flex-wrap: wrap; gap: 10px; font-size: var(--fs-sm); color: var(--ok); }
.err { color: var(--danger); font-size: var(--fs-sm); }
.list { display: grid; gap: var(--sp-2); }
.item { display: grid; grid-template-columns: 1fr auto; gap: 4px 12px; padding: var(--sp-4) var(--sp-5); color: var(--text); text-decoration: none; }
.meta { font-size: var(--fs-xs); color: var(--text-3); }
.when { grid-row: 1 / span 2; grid-column: 2; align-self: center; font-size: var(--fs-xs); color: var(--text-3); }
@media (prefers-reduced-motion: reduce) { .pipe .now .dot { animation: none; } }
</style>
