<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { RouterLink, useRoute, useRouter } from 'vue-router'
import KnowledgeMap from '@/components/lesson/KnowledgeMap.vue'
import CodeSandbox from '@/components/lesson/CodeSandbox.vue'
import { lessonsApi } from '@/api/endpoints'
import type { LessonNode, LessonSnippet, LessonView } from '@/api/types'
import { useToast } from '@/composables/useToast'
import { previewUrl } from '@/utils/download'

const route = useRoute()
const router = useRouter()
const toast = useToast()
const id = computed(() => String(route.params.id))

const view = ref<LessonView | null>(null)
const error = ref<string | null>(null)
const videoSrc = ref<string | null>(null)
const video = ref<HTMLVideoElement | null>(null)
const t = ref(0)
const grow = ref(true)
const tab = ref<'map' | 'transcript' | 'screen'>('map')
const focused = ref<LessonNode | null>(null)
const frameTpl = ref<string | null>(null)
let ticketTimer: number | undefined

async function refreshTicket() {
  try {
    frameTpl.value = (await lessonsApi.ticket(id.value)).frameUrl
  } catch {
    frameTpl.value = null
  }
}

onMounted(async () => {
  try {
    view.value = await lessonsApi.get(id.value)
    videoSrc.value = await previewUrl(view.value.videoFileId)
    await refreshTicket()
    ticketTimer = window.setInterval(refreshTicket, 9 * 60 * 1000) // tickets last 10 minutes
  } catch (e) {
    error.value = (e as Error).message
  }
})
onBeforeUnmount(() => window.clearInterval(ticketTimer))

const lesson = computed(() => view.value?.lesson)
function onTime() {
  if (video.value) t.value = video.value.currentTime
}
function seek(sec: number, play = true) {
  if (!video.value) return
  video.value.currentTime = Math.max(0, sec)
  t.value = video.value.currentTime
  if (play) void video.value.play().catch(() => {})
}

// ---- code: auto-open the snippet being explained, unless the user picked one themselves
const current = computed(() => lesson.value?.snippets.find((s) => t.value >= s.start && t.value <= s.end) ?? null)
const opened = ref<LessonSnippet | null>(null)
const pinned = ref(false)
watch(current, (s) => {
  if (s && (!pinned.value || !opened.value)) opened.value = s
})
function openSnippet(s: LessonSnippet) {
  opened.value = s
  pinned.value = true
}
const frameUrl = computed(() => (opened.value?.evidenceFrame && frameTpl.value ? frameTpl.value.replace('{frame}', opened.value.evidenceFrame) : null))

// ---- transcript follows playback
const activeSentence = computed(() => view.value?.transcript.findIndex((s) => t.value >= s.begin && t.value < s.end) ?? -1)
const list = ref<HTMLOListElement | null>(null)
watch(activeSentence, async (i) => {
  if (tab.value !== 'transcript' || i < 0) return
  await nextTick()
  list.value?.children[i]?.scrollIntoView({ block: 'nearest', behavior: 'smooth' })
})
const chapter = computed(() => {
  const ns = lesson.value?.nodes ?? []
  return ns.filter((n) => !n.parentId).find((n) => t.value >= n.start && t.value < n.end) ?? null
})

function clock(sec: number) {
  const s = Math.floor(sec)
  const h = Math.floor(s / 3600)
  const mm = String(Math.floor((s % 3600) / 60)).padStart(2, '0')
  const ss = String(s % 60).padStart(2, '0')
  return h ? `${h}:${mm}:${ss}` : `${mm}:${ss}`
}

// ---- notes export (Markdown, generated in the browser from the validated lesson)
const SOURCE_LABEL = { SCREEN: '画面识别', SCREEN_COMPLETED: '画面识别 + AI 补全', MODEL: 'AI 根据讲解整理' }
function exportNotes() {
  const l = lesson.value
  if (!l) return
  const kids = new Map<string | null, LessonNode[]>()
  for (const n of l.nodes) {
    const k = n.parentId ?? null
    if (!kids.has(k)) kids.set(k, [])
    kids.get(k)!.push(n)
  }
  const lines: string[] = [`# ${l.title}`, '', l.summary, '', '## 知识大纲', '']
  const walk = (parent: string | null, depth: number) => {
    for (const n of (kids.get(parent) ?? []).sort((a, b) => a.start - b.start)) {
      lines.push(`${'  '.repeat(depth)}- **${n.title}**（${clock(n.start)}–${clock(n.end)}）${n.summary ? '：' + n.summary : ''}`)
      walk(n.id, depth + 1)
    }
  }
  walk(null, 0)
  if (l.snippets.length) {
    lines.push('', '## 代码')
    for (const s of l.snippets) {
      lines.push('', `### ${s.title}（${clock(s.start)}，${SOURCE_LABEL[s.source]}）`, '', '```' + (s.language === 'text' ? '' : s.language), s.code, '```')
      if (s.explanation) lines.push('', s.explanation)
    }
  }
  lines.push('', `> 由 Lumen 学习播放器生成 · 语音识别 ${view.value!.meta.asrModel} · 整理 ${view.value!.meta.llmModel}`)
  const blob = new Blob([lines.join('\n')], { type: 'text/markdown;charset=utf-8' })
  const a = document.createElement('a')
  a.href = URL.createObjectURL(blob)
  a.download = `${l.title}-笔记.md`
  a.click()
  URL.revokeObjectURL(a.href)
}

const dlg = ref<HTMLDialogElement | null>(null)
async function remove() {
  try {
    await lessonsApi.remove(id.value)
    toast.ok('课程已删除，原视频仍保留在「我的文件」中')
    void router.replace('/workspace/learn')
  } catch (e) {
    toast.error((e as Error).message)
  }
}
</script>

<template>
  <main id="main" class="lp">
    <p v-if="error" class="err" role="alert">{{ error }} <RouterLink to="/workspace/learn">返回</RouterLink></p>
    <template v-else-if="view && lesson">
      <header class="head">
        <RouterLink to="/workspace/learn" class="back">← 学习播放器</RouterLink>
        <h1>{{ lesson.title }}</h1>
        <span class="sp" />
        <button type="button" class="btn btn--sm btn--ghost" @click="exportNotes">导出笔记（Markdown）</button>
        <button type="button" class="btn btn--sm btn--ghost" @click="dlg?.showModal()">删除</button>
      </header>
      <p v-if="lesson.summary" class="summary">{{ lesson.summary }}</p>
      <details v-if="view.meta.warnings?.length" class="warns">
        <summary>{{ view.meta.warnings.length }} 条提示</summary>
        <ul><li v-for="w in view.meta.warnings" :key="w">{{ w }}</li></ul>
      </details>

      <div class="grid">
        <section class="left">
          <div class="player card">
            <video v-if="videoSrc" ref="video" :src="videoSrc" controls playsinline preload="metadata" @timeupdate="onTime" @seeked="onTime" />
            <p class="now">
              <span class="mono">{{ clock(t) }}</span>
              <span v-if="chapter">· {{ chapter.title }}</span>
            </p>
          </div>

          <div v-if="current && opened?.id !== current.id" class="banner" role="status">
            正在讲解代码「{{ current.title }}」
            <button type="button" class="btn btn--sm" @click="openSnippet(current)">在沙盒中打开</button>
          </div>

          <section class="card pad">
            <header class="sh">
              <h2>代码沙盒</h2>
              <div v-if="lesson.snippets.length" class="snips" role="tablist" aria-label="代码片段">
                <button v-for="s in lesson.snippets" :key="s.id" type="button" role="tab" :aria-selected="opened?.id === s.id"
                        :class="{ on: opened?.id === s.id, live: current?.id === s.id }" @click="openSnippet(s)">
                  {{ clock(s.start) }} {{ s.title }}
                </button>
              </div>
            </header>
            <CodeSandbox v-if="opened" :snippet="opened" :frame-url="frameUrl" @seek="(x) => seek(x)" />
            <p v-else-if="lesson.snippets.length" class="muted">播放到讲解代码的地方时，代码会自动出现在这里；也可以点上方的片段直接打开。</p>
            <p v-else class="muted">这节课没有识别到代码。</p>
          </section>
        </section>

        <section class="right card">
          <div class="tabs" role="tablist">
            <button type="button" role="tab" :aria-selected="tab === 'map'" :class="{ on: tab === 'map' }" @click="tab = 'map'">知识图谱</button>
            <button type="button" role="tab" :aria-selected="tab === 'transcript'" :class="{ on: tab === 'transcript' }" @click="tab = 'transcript'">讲稿</button>
            <button type="button" role="tab" :aria-selected="tab === 'screen'" :class="{ on: tab === 'screen' }" @click="tab = 'screen'">画面文字</button>
            <label v-if="tab === 'map'" class="grow"><input v-model="grow" type="checkbox" /> 随播放生长</label>
          </div>

          <template v-if="tab === 'map'">
            <div class="map"><KnowledgeMap :nodes="lesson.nodes" :time="t" :grow="grow" @seek="(x) => seek(x)" @focus="(n) => (focused = n)" /></div>
            <article v-if="focused" class="focus">
              <header>
                <strong>{{ focused.title }}</strong>
                <button type="button" class="btn btn--sm btn--ghost" @click="seek(focused.start)">▶ {{ clock(focused.start) }}</button>
              </header>
              <p>{{ focused.summary }}</p>
              <p v-if="focused.keywords.length" class="kw"><span v-for="k in focused.keywords" :key="k" class="chip">{{ k }}</span></p>
            </article>
          </template>

          <ol v-else-if="tab === 'transcript'" ref="list" class="tr">
            <li v-for="(s, i) in view.transcript" :key="i" :class="{ on: i === activeSentence }">
              <button type="button" @click="seek(s.begin)"><span class="mono">{{ clock(s.begin) }}</span>{{ s.text }}</button>
            </li>
          </ol>

          <div v-else class="scr">
            <p class="muted">视频画面上识别到的文字（{{ view.meta.ocrFrames }} 个不同画面，共抽取 {{ view.meta.frames }} 帧）。这是代码片段的核对依据。</p>
            <article v-for="(s, i) in view.screen" :key="i">
              <button type="button" class="mono time" @click="seek(s.t)">{{ clock(s.t) }}</button>
              <pre>{{ s.text }}</pre>
            </article>
            <p v-if="!view.screen.length" class="muted">没有识别到画面文字。</p>
          </div>
          <p class="meta">语音识别 {{ view.meta.asrModel }} · 画面识别 RapidOCR（本地）· 整理 {{ view.meta.llmModel }} · 提示词 v{{ view.meta.promptVersion }}</p>
        </section>
      </div>

      <dialog ref="dlg" class="dlg" aria-labelledby="ldt">
        <h2 id="ldt">删除这节课的分析结果？</h2>
        <p>知识图谱、讲稿和识别结果会被删除，<strong>无法恢复</strong>；原视频不会删除。</p>
        <div class="row">
          <button type="button" class="btn btn--ghost" @click="dlg?.close()">取消</button>
          <button type="button" class="btn btn--danger" @click="remove">删除</button>
        </div>
      </dialog>
    </template>
    <p v-else class="muted">加载中…</p>
  </main>
</template>

<style scoped>
.lp { display: grid; grid-template-columns: minmax(0, 1fr); gap: var(--sp-3); max-width: 1480px; margin: 0 auto; padding: var(--sp-5) var(--gutter) var(--sp-8); }
.head { display: flex; align-items: baseline; flex-wrap: wrap; gap: var(--sp-3); }
.back { font-size: var(--fs-sm); color: var(--text-3); text-decoration: none; }
.head h1 { font-size: var(--fs-xl); }
.sp { flex: 1; }
.summary { font-size: var(--fs-sm); color: var(--text-2); line-height: 1.7; max-width: 900px; }
.warns { font-size: var(--fs-xs); color: var(--apricot-ink); }
.warns ul { margin: 6px 0 0; padding-left: 18px; line-height: 1.7; }
.grid { display: grid; grid-template-columns: minmax(0, 1.35fr) minmax(0, 1fr); gap: var(--sp-4); align-items: start; }
.left { display: grid; grid-template-columns: minmax(0, 1fr); gap: var(--sp-3); }
.player { overflow: hidden; }
.player video { display: block; width: 100%; aspect-ratio: 16 / 9; background: #111; }
.now { margin: 0; padding: 8px 14px; font-size: var(--fs-sm); color: var(--text-2); }
.banner { display: flex; align-items: center; gap: 10px; padding: 10px 14px; border-radius: var(--r-sm); background: var(--fog); color: var(--fog-ink); font-size: var(--fs-sm); }
.banner .btn { margin-left: auto; }
.pad { display: grid; grid-template-columns: minmax(0, 1fr); gap: var(--sp-3); padding: var(--sp-4); }
.sh { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; }
.sh h2 { font-size: var(--fs-lg); }
.snips { display: flex; gap: 4px; flex-wrap: wrap; }
.snips button { min-height: 30px; padding: 0 10px; border: 1px solid var(--line); border-radius: var(--r-pill); background: var(--surface); font-size: 12px; color: var(--text-2); }
.snips button.on { border-color: var(--ink); background: var(--ink); color: var(--cream); }
.snips button.live:not(.on) { border-color: var(--mint-ink); color: var(--mint-ink); }
.right { position: sticky; top: 76px; display: grid; grid-template-rows: auto minmax(0, 1fr) auto auto; gap: 10px; height: calc(100vh - 100px); min-height: 520px; padding: var(--sp-4); }
.tabs { display: flex; align-items: center; gap: 4px; padding: 3px; border-radius: var(--r-pill); background: var(--surface-sunk); }
.tabs button { min-height: 32px; padding: 0 12px; border: 0; border-radius: var(--r-pill); background: transparent; font-size: var(--fs-sm); color: var(--text-2); }
.tabs button.on { background: var(--surface); color: var(--text); box-shadow: var(--shadow-1); }
.grow { margin-left: auto; padding-right: 8px; display: flex; align-items: center; gap: 6px; font-size: var(--fs-xs); color: var(--text-3); }
.map { min-height: 0; }
.focus { display: grid; gap: 6px; padding: 10px 12px; border-radius: var(--r-sm); background: var(--surface-sunk); font-size: var(--fs-sm); line-height: 1.6; }
.focus header { display: flex; align-items: center; gap: 8px; }
.focus header .btn { margin-left: auto; }
.kw { display: flex; gap: 4px; flex-wrap: wrap; margin: 0; }
.tr { margin: 0; padding: 0; list-style: none; overflow: auto; min-height: 0; }
.tr button { display: flex; gap: 10px; width: 100%; padding: 6px 8px; border: 0; border-radius: var(--r-sm); background: none; text-align: left; font-size: var(--fs-sm); line-height: 1.6; color: var(--text-2); }
.tr button .mono { flex: none; font-size: 11px; color: var(--text-3); padding-top: 3px; }
.tr li.on button { background: var(--mint); color: var(--text); }
.scr { overflow: auto; min-height: 0; display: grid; align-content: start; gap: 8px; }
.scr article { display: grid; gap: 4px; }
.scr pre { margin: 0; padding: 8px 10px; border-radius: var(--r-sm); background: var(--surface-sunk); font-family: var(--font-mono); font-size: 12px; white-space: pre-wrap; }
.time { justify-self: start; min-height: 26px; padding: 0 8px; border: 1px solid var(--line); border-radius: var(--r-pill); background: var(--surface); font-size: 11px; }
.meta { margin: 0; font-size: 11px; color: var(--text-3); }
.muted { font-size: var(--fs-sm); color: var(--text-3); }
.err { color: var(--danger); font-size: var(--fs-sm); }
.dlg { width: min(440px, calc(100vw - 32px)); padding: var(--sp-5); border: 0; border-radius: var(--r-lg, 16px); background: var(--surface); }
.dlg::backdrop { background: rgba(30, 36, 34, 0.45); }
.dlg p { margin: var(--sp-3) 0; font-size: var(--fs-sm); line-height: 1.6; color: var(--text-2); }
.row { display: flex; justify-content: flex-end; gap: var(--sp-2); }
@media (max-width: 1099px) {
  .grid { grid-template-columns: minmax(0, 1fr); }
  .right { position: static; height: 560px; }
}
</style>
