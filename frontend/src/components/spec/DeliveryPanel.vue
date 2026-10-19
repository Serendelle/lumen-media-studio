<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { specsApi } from '@/api/endpoints'
import type { SpecProject, TaskView } from '@/api/types'
import TaskProgress from '@/components/common/TaskProgress.vue'
import { useLibraryStore } from '@/stores/library'
import { isTerminal, useTasksStore } from '@/stores/tasks'
import { useToast } from '@/composables/useToast'
import { downloadFile } from '@/utils/download'
import { bytes, relativeTime } from '@/utils/format'
import { ERROR_HINT, stageLabel } from '@/utils/labels'

const props = defineProps<{ project: SpecProject; complete: number; people: number }>()
const emit = defineEmits<{ saved: [p: SpecProject] }>()
const lib = useLibraryStore()
const tasks = useTasksStore()
const toast = useToast()

const ofFormat = (fmt: string) => lib.files.filter((f) => f.mediaType === 'DOCUMENT' && (f.info as any)?.format === fmt)
const docx = ref(props.project.templates.docx?.fileId ?? '')
const xlsx = ref(props.project.templates.xlsx?.fileId ?? '')
const headerRow = ref<number | ''>(props.project.templates.xlsx?.headerRow ?? '')
const savingTpl = ref(false)
const tplError = ref<string | null>(null)
const docxInput = ref<HTMLInputElement | null>(null)
const xlsxInput = ref<HTMLInputElement | null>(null)

async function upload(e: Event, target: 'docx' | 'xlsx') {
  const f = (e.target as HTMLInputElement).files?.[0]
  if (!f) return
  const fv = await lib.upload(f)
  if (fv) (target === 'docx' ? docx : xlsx).value = fv.fileId
  ;(e.target as HTMLInputElement).value = ''
}

async function saveTemplates() {
  savingTpl.value = true
  tplError.value = null
  try {
    const p = await specsApi.setTemplates(props.project.id, docx.value || null, xlsx.value || null, headerRow.value === '' ? null : Number(headerRow.value))
    emit('saved', p)
    toast.ok('模板已保存')
  } catch (e) {
    tplError.value = (e as Error).message
  } finally {
    savingTpl.value = false
  }
}

// ---- deliveries ----
const onlyComplete = ref(false)
const includeOriginals = ref(true)
const history = ref<TaskView[]>([])
const starting = ref(false)
const runningId = ref<string | null>(null)

async function loadHistory() {
  try {
    history.value = await specsApi.deliveries(props.project.id)
  } catch {
    /* shown as empty */
  }
}
onMounted(loadHistory)

const running = computed(() => (runningId.value ? tasks.byId[runningId.value] : undefined))
watch(() => running.value?.status, (s) => {
  if (s && ['SUCCEEDED', 'FAILED', 'CANCELLED'].includes(s)) void loadHistory()
})

async function generate() {
  starting.value = true
  try {
    const r = await specsApi.deliver(props.project.id, onlyComplete.value, includeOriginals.value)
    runningId.value = r.taskId
    await tasks.track(r.taskId)
    toast.ok('开始生成交付包')
  } catch (e) {
    toast.error((e as Error).message)
  } finally {
    starting.value = false
  }
}
const tpl = computed(() => props.project.templates)
const OVERALL: Record<string, string> = { SUCCEEDED: '全部合格', PARTIAL_SUCCESS: '部分成功（有异常项）' }
</script>

<template>
  <div class="dl">
    <section class="card pad">
      <h2>交付模板（可选）</h2>
      <p class="muted">个人表（.docx）里写 <code v-pre>{{姓名}}</code> 或 <code v-pre>${学校}</code> 这样的占位符，每人生成一份；汇总表（.xlsx）只需要一行表头，列名与名单一致，系统从表头下一行开始逐行填写，已有内容的单元格不会被覆盖。不设模板时会自动生成一份汇总表。</p>
      <div class="grid">
        <div class="field">
          <label for="dt">个人表模板（.docx）</label>
          <select id="dt" v-model="docx" class="select">
            <option value="">不使用</option>
            <option v-for="f in ofFormat('docx')" :key="f.fileId" :value="f.fileId">{{ f.originalFilename }}</option>
          </select>
          <button type="button" class="btn btn--sm btn--ghost up" @click="docxInput?.click()">上传 .docx</button>
          <input ref="docxInput" type="file" accept=".docx" hidden @change="upload($event, 'docx')" />
        </div>
        <div class="field">
          <label for="xt">汇总表模板（.xlsx）</label>
          <select id="xt" v-model="xlsx" class="select">
            <option value="">自动生成</option>
            <option v-for="f in ofFormat('xlsx')" :key="f.fileId" :value="f.fileId">{{ f.originalFilename }}</option>
          </select>
          <button type="button" class="btn btn--sm btn--ghost up" @click="xlsxInput?.click()">上传 .xlsx</button>
          <input ref="xlsxInput" type="file" accept=".xlsx" hidden @change="upload($event, 'xlsx')" />
        </div>
        <div class="field">
          <label for="xh">汇总表表头行</label>
          <input id="xh" v-model.number="headerRow" class="input mono" type="number" min="1" max="1000" placeholder="自动识别" :disabled="!xlsx" />
        </div>
      </div>
      <p v-if="tplError" class="err">{{ tplError }}</p>
      <button type="button" class="btn btn--sm go" :disabled="savingTpl" @click="saveTemplates">保存模板设置</button>

      <div v-if="tpl.docx || tpl.xlsx" class="ready">
        <p v-if="tpl.docx">
          个人表「{{ tpl.docx.filename }}」：
          <template v-if="tpl.docx.error"><span class="bad">{{ tpl.docx.error }}</span></template>
          <template v-else>占位符 {{ tpl.docx.placeholders?.length ?? 0 }} 个
            <span v-if="tpl.docx.unresolved?.length" class="bad">，其中 {{ tpl.docx.unresolved.join('、') }} 没有对应数据（会保留原样并记入异常清单）</span>
            <span v-else class="good">，全部可以填写</span>
          </template>
        </p>
        <p v-if="tpl.xlsx">
          汇总表「{{ tpl.xlsx.filename }}」：
          <template v-if="tpl.xlsx.error"><span class="bad">{{ tpl.xlsx.error }}</span></template>
          <template v-else>第 {{ tpl.xlsx.detectedHeaderRow }} 行为表头，将填写 {{ tpl.xlsx.columns?.join('、') }}</template>
        </p>
      </div>
      <p class="muted">可用字段：<span v-for="k in tpl.knownFields" :key="k" class="tok">{{ k }}</span></p>
    </section>

    <section class="card pad">
      <h2>生成交付包</h2>
      <p class="sum">{{ complete }} / {{ people }} 人必交材料齐全。缺失与不合格的会写进 exceptions.csv，不会被悄悄丢掉。</p>
      <label class="check"><input v-model="onlyComplete" type="checkbox" /> 只打包材料齐全的人</label>
      <label class="check"><input v-model="includeOriginals" type="checkbox" /> 被修正过的文件同时附上原始素材</label>
      <button type="button" class="btn go" :disabled="starting || !project.roster || !project.spec.slots.length || (running && !isTerminal(running))" @click="generate">
        生成交付包
      </button>
      <div v-if="running && !isTerminal(running)" class="run">
        <TaskProgress :task="running" />
        <span class="muted">{{ stageLabel(running.stage) }}</span>
      </div>
    </section>

    <section class="card pad">
      <h2>历史交付</h2>
      <p v-if="!history.length" class="muted">还没有生成过交付包。</p>
      <article v-for="t in history" :key="t.taskId" class="h">
        <header>
          <strong>{{ t.status === 'SUCCEEDED' ? (OVERALL[t.result?.overall] ?? '已生成') : t.status === 'FAILED' ? '生成失败' : t.status === 'CANCELLED' ? '已取消' : '生成中' }}</strong>
          <span class="muted">{{ relativeTime(t.createdAt) }}</span>
          <button v-if="t.status === 'SUCCEEDED' && t.output" type="button" class="btn btn--sm" @click="downloadFile(t.output!.fileId, t.output!.filename)">
            下载 ZIP · {{ bytes(t.output.sizeBytes) }}
          </button>
        </header>
        <p v-if="t.status === 'SUCCEEDED'" class="muted">
          名单 {{ t.result?.people }} 人，打包 {{ t.result?.included }} 人，齐全 {{ t.result?.complete }} 人，{{ t.result?.files }} 个文件，异常 {{ t.result?.exceptionCount }} 条
        </p>
        <ul v-if="t.result?.exceptions?.length" class="ex">
          <li v-for="(e, i) in t.result.exceptions.slice(0, 8)" :key="i">{{ e.serial ? e.serial + ' ' : '' }}{{ e.name }} {{ e.material }}：{{ e.reason }}</li>
          <li v-if="t.result.exceptionCount > 8" class="muted">…其余见 ZIP 内 90_校验报告/exceptions.csv</li>
        </ul>
        <p v-if="t.status === 'FAILED'" class="bad">{{ t.message }}<template v-if="t.errorCode && ERROR_HINT[t.errorCode]"> · {{ ERROR_HINT[t.errorCode] }}</template></p>
      </article>
    </section>
  </div>
</template>

<style scoped>
.dl { display: grid; grid-template-columns: minmax(0, 1fr); gap: var(--sp-4); }
.pad { display: grid; grid-template-columns: minmax(0, 1fr); gap: var(--sp-3); padding: var(--sp-5); }
h2 { font-size: var(--fs-lg); }
.muted { font-size: var(--fs-xs); color: var(--text-3); line-height: 1.6; }
.grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(200px, 1fr)); gap: var(--sp-3); align-items: start; }
.up, .go { justify-self: start; }
.up { margin-top: 4px; }
.ready { display: grid; gap: 4px; font-size: var(--fs-sm); color: var(--text-2); }
.good { color: var(--ok); }
.bad { color: var(--danger); font-size: var(--fs-sm); }
.err { color: var(--danger); font-size: var(--fs-sm); }
.tok { display: inline-block; margin: 2px; padding: 0 8px; border: 1px solid var(--line); border-radius: var(--r-pill); font-size: 11px; }
.sum { font-size: var(--fs-sm); color: var(--text-2); }
.check { display: flex; align-items: center; gap: 8px; font-size: var(--fs-sm); min-height: 32px; }
.run { display: grid; gap: 4px; }
.h { display: grid; gap: 6px; padding: 10px 0; border-top: 1px solid var(--line); }
.h header { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; }
.h header .btn { margin-left: auto; }
.ex { margin: 0; padding-left: 18px; font-size: var(--fs-xs); line-height: 1.7; color: var(--apricot-ink); }
code { font-family: var(--font-mono); font-size: 11px; }
</style>
