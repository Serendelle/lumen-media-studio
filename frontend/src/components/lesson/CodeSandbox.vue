<script setup lang="ts">
import { computed, defineAsyncComponent, ref, watch } from 'vue'
import type { LessonSnippet } from '@/api/types'
import { LIMIT_MS, runJs, runPython, type RunEvent } from '@/sandbox/runners'

// Monaco is large: load it only when a sandbox is actually shown
const CodeEditor = defineAsyncComponent(() => import('./CodeEditor.vue'))

const props = defineProps<{ snippet: LessonSnippet; frameUrl?: string | null }>()
const emit = defineEmits<{ seek: [t: number] }>()

const code = ref(props.snippet.code)
const dirty = computed(() => code.value !== props.snippet.code)
const out = ref<{ kind: 'out' | 'err' | 'sys'; text: string }[]>([])
const running = ref(false)
const booting = ref(false)

watch(() => props.snippet.id, () => {
  code.value = props.snippet.code
  out.value = []
})

const SOURCE: Record<LessonSnippet['source'], { label: string; tone: string; note: string }> = {
  SCREEN: { label: '画面识别', tone: 'ok', note: '代码来自视频画面的文字识别（已修正明显错字、还原缩进）' },
  SCREEN_COMPLETED: { label: '画面识别 + AI 补全', tone: 'warn', note: '画面上只识别到一部分，其余由 AI 根据讲解补全，请留意差异' },
  MODEL: { label: 'AI 根据讲解整理', tone: 'bad', note: '对应时间的画面上几乎没有识别到这段代码，是 AI 根据讲解写的，请谨慎参考' },
}
const src = computed(() => SOURCE[props.snippet.source])
const runnable = computed(() => props.snippet.language === 'python' || props.snippet.language === 'javascript')

async function run() {
  if (!runnable.value || running.value) return
  out.value = []
  running.value = true
  const onEvent = (e: RunEvent) => out.value.push({ kind: e.type, text: e.text })
  try {
    if (props.snippet.language === 'python') {
      booting.value = true
      out.value.push({ kind: 'sys', text: '正在加载 Python 运行环境（首次约几秒）…' })
    }
    const r = props.snippet.language === 'python'
      ? await runPython(code.value, (e) => {
        if (booting.value) {
          booting.value = false
          out.value = out.value.filter((o) => o.kind !== 'sys')
        }
        onEvent(e)
      })
      : await runJs(code.value, onEvent)
    booting.value = false
    out.value = out.value.filter((o) => !(o.kind === 'sys' && o.text.startsWith('正在加载')))
    out.value.push(r.ok
      ? { kind: 'sys', text: `运行结束 · ${r.ms} ms` }
      : { kind: 'err', text: r.error ?? '运行出错' })
  } catch (e) {
    out.value.push({ kind: 'err', text: (e as Error).message })
  } finally {
    running.value = false
    booting.value = false
  }
}
function reset() {
  code.value = props.snippet.code
  out.value = []
}
function clock(t: number) {
  const s = Math.floor(t)
  return `${String(Math.floor(s / 60)).padStart(2, '0')}:${String(s % 60).padStart(2, '0')}`
}
</script>

<template>
  <section class="sb" aria-label="代码沙盒">
    <header class="hd">
      <strong class="title">{{ snippet.title }}</strong>
      <button type="button" class="time mono" :aria-label="`跳到 ${clock(snippet.start)}`" @click="emit('seek', snippet.start)">{{ clock(snippet.start) }}</button>
      <span class="chip" :class="`t-${src.tone}`" :title="src.note">{{ src.label }} · 画面依据 {{ Math.round(snippet.ocrSupport * 100) }}%</span>
      <span class="lang mono">{{ snippet.language }}</span>
      <span class="sp" />
      <button v-if="dirty" type="button" class="btn btn--sm btn--ghost" @click="reset">还原为视频中的代码</button>
      <button type="button" class="btn btn--sm" :disabled="!runnable || running" :title="runnable ? 'Ctrl + Enter' : '只有 Python 和 JavaScript 可以在浏览器中运行'" @click="run">
        {{ running ? (booting ? '加载中…' : '运行中…') : '▶ 运行' }}
      </button>
    </header>
    <div class="body">
      <div class="editor"><CodeEditor v-model="code" :language="snippet.language" :label="`代码：${snippet.title}`" @run="run" /></div>
      <aside class="side">
        <p class="note">{{ src.note }}。</p>
        <p v-if="snippet.explanation" class="exp">{{ snippet.explanation }}</p>
        <figure v-if="frameUrl" class="ev">
          <img :src="frameUrl" :alt="`视频 ${clock(snippet.evidenceTime ?? snippet.start)} 的画面`" loading="lazy" />
          <figcaption>识别依据：{{ clock(snippet.evidenceTime ?? snippet.start) }} 的画面</figcaption>
        </figure>
        <p class="limits">在浏览器的隔离线程中运行：没有网络、不能访问页面，超过 {{ (snippet.language === 'python' ? LIMIT_MS.python : LIMIT_MS.javascript) / 1000 }} 秒自动停止。</p>
      </aside>
    </div>
    <pre class="out" aria-live="polite" :aria-busy="running"><template v-if="!out.length"><span class="ph">{{ runnable ? '点击「运行」或按 Ctrl + Enter，输出会显示在这里' : '这段代码不是 Python / JavaScript，可以复制到本地环境运行' }}</span></template><span v-for="(o, i) in out" :key="i" :class="o.kind">{{ o.text }}
</span></pre>
  </section>
</template>

<style scoped>
.sb { display: grid; gap: 8px; min-width: 0; }
.hd { display: flex; align-items: center; flex-wrap: wrap; gap: 8px; }
.title { font-size: var(--fs-sm); }
.time { min-height: 28px; padding: 0 8px; border: 1px solid var(--line); border-radius: var(--r-pill); background: var(--surface); font-size: 12px; }
.lang { font-size: 11px; color: var(--text-3); }
.sp { flex: 1; }
.t-ok { background: var(--mint); color: var(--mint-ink); }
.t-warn { background: var(--apricot); color: var(--apricot-ink); }
.t-bad { background: var(--danger-soft); color: var(--danger); }
.body { display: grid; grid-template-columns: minmax(0, 1fr) 220px; gap: 10px; min-height: 260px; }
.editor { min-width: 0; height: 280px; border-radius: var(--r-sm); overflow: hidden; background: #1e1e1e; }
.side { display: grid; align-content: start; gap: 8px; font-size: var(--fs-xs); line-height: 1.6; color: var(--text-2); }
.note { color: var(--text-3); }
.ev { margin: 0; display: grid; gap: 4px; }
.ev img { width: 100%; border-radius: var(--r-sm); background: #111; }
.ev figcaption { font-size: 11px; color: var(--text-3); }
.limits { font-size: 11px; color: var(--text-3); }
.out { margin: 0; min-height: 70px; max-height: 200px; overflow: auto; padding: 10px 12px; border-radius: var(--r-sm); background: #151917; color: #d6dfda; font-family: var(--font-mono); font-size: 12px; line-height: 1.6; white-space: pre-wrap; }
.out .err { color: #f19a8e; }
.out .sys, .out .ph { color: #8a9a93; }
@media (max-width: 900px) {
  .body { grid-template-columns: 1fr; }
}
</style>
