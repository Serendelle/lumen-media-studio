<script setup lang="ts">
import { computed, ref } from 'vue'
import { specsApi } from '@/api/endpoints'
import { ApiError } from '@/api/http'
import type { SpecProject } from '@/api/types'
import { useLibraryStore } from '@/stores/library'
import { useToast } from '@/composables/useToast'

const props = defineProps<{ project: SpecProject }>()
const emit = defineEmits<{ saved: [p: SpecProject] }>()
const lib = useLibraryStore()
const toast = useToast()

const xlsxFiles = computed(() => lib.files.filter((f) => f.mediaType === 'DOCUMENT' && (f.info as any)?.format === 'xlsx'))
const fileId = ref(props.project.roster?.fileId ?? '')
const headerRow = ref<number | ''>('')
const nameField = ref(props.project.roster?.nameField ?? '')
const busy = ref(false)
const error = ref<string | null>(null)
const fieldHint = ref<string | null>(null)
const showAll = ref(false)
const input = ref<HTMLInputElement | null>(null)

async function pick(e: Event) {
  const f = (e.target as HTMLInputElement).files?.[0]
  if (!f) return
  const fv = await lib.upload(f)
  if (fv) fileId.value = fv.fileId
  ;(e.target as HTMLInputElement).value = ''
}

async function importRoster() {
  if (!fileId.value) return
  busy.value = true
  error.value = null
  fieldHint.value = null
  try {
    const p = await specsApi.importRoster(props.project.id, fileId.value, headerRow.value === '' ? undefined : Number(headerRow.value),
      nameField.value || undefined)
    nameField.value = p.roster?.nameField ?? ''
    emit('saved', p)
    toast.ok(`已导入 ${p.roster?.people.length ?? 0} 人，材料已按名单重新匹配`)
  } catch (e) {
    error.value = (e as Error).message
    if (e instanceof ApiError) fieldHint.value = e.fields.nameField ?? null
  } finally {
    busy.value = false
  }
}

/** The organiser sees their own list, but phone / ID numbers are masked on screen by default. */
function mask(v: string) {
  if (/^1\d{10}$/.test(v)) return `${v.slice(0, 3)}****${v.slice(7)}`
  if (/^\d{17}[\dXx]$/.test(v)) return `${v.slice(0, 4)}**********${v.slice(14)}`
  return v
}
const people = computed(() => {
  const all = props.project.roster?.people ?? []
  return showAll.value ? all : all.slice(0, 20)
})
</script>

<template>
  <section class="card pad">
    <h2>参赛名单</h2>
    <p class="muted">上传 .xlsx 名单：第一张工作表、一行表头、每人一行。名单只保存在本服务器，不会发送给 AI。</p>
    <div class="grid">
      <div class="field">
        <label for="rf">名单文件</label>
        <select id="rf" v-model="fileId" class="select">
          <option value="">请选择…</option>
          <option v-for="f in xlsxFiles" :key="f.fileId" :value="f.fileId">{{ f.originalFilename }}</option>
        </select>
        <button type="button" class="btn btn--sm btn--ghost up" @click="input?.click()">上传 .xlsx</button>
        <input ref="input" type="file" accept=".xlsx" hidden @change="pick" />
      </div>
      <div class="field">
        <label for="hr">表头所在行</label>
        <input id="hr" v-model.number="headerRow" class="input mono" type="number" min="1" max="1000" placeholder="自动识别" />
      </div>
      <div class="field">
        <label for="nf">姓名列</label>
        <select v-if="project.roster" id="nf" v-model="nameField" class="select">
          <option v-for="f in project.roster.fields" :key="f" :value="f">{{ f }}</option>
        </select>
        <input v-else id="nf" v-model="nameField" class="input" placeholder="自动识别" />
        <span v-if="fieldHint" class="hint">{{ fieldHint }}</span>
      </div>
    </div>
    <p v-if="error" class="err" role="alert">{{ error }}</p>
    <button type="button" class="btn go" :disabled="!fileId || busy" @click="importRoster">{{ busy ? '导入中…' : project.roster ? '重新导入' : '导入名单' }}</button>

    <template v-if="project.roster">
      <p class="sum">
        已导入 <strong>{{ project.roster.people.length }}</strong> 人 · 「{{ project.roster.filename }}」第 {{ project.roster.headerRow }} 行为表头
        · 姓名列「{{ project.roster.nameField }}」<template v-if="project.roster.idField"> · 编号列「{{ project.roster.idField }}」（用于精确匹配）</template>
      </p>
      <p v-if="project.namingUnknown?.length" class="err">
        命名规则用到的 {{ project.namingUnknown.map((t) => '{' + t + '}').join('、') }} 在名单中没有对应的列，请到「① 规范」修改命名规则。
      </p>
      <ul v-if="project.roster.warnings.length" class="warns"><li v-for="w in project.roster.warnings" :key="w">{{ w }}</li></ul>
      <div class="tbl-wrap">
        <table class="tbl">
          <thead><tr><th>行</th><th v-for="f in project.roster.fields" :key="f">{{ f }}</th></tr></thead>
          <tbody>
            <tr v-for="p in people" :key="p.key">
              <td class="mono">{{ p.row }}</td>
              <td v-for="f in project.roster.fields" :key="f">{{ mask(p.values[f] ?? '') }}</td>
            </tr>
          </tbody>
        </table>
      </div>
      <button v-if="project.roster.people.length > 20" type="button" class="btn btn--sm btn--ghost" @click="showAll = !showAll">
        {{ showAll ? '收起' : `显示全部 ${project.roster.people.length} 人` }}
      </button>
    </template>
  </section>
</template>

<style scoped>
.pad { display: grid; grid-template-columns: minmax(0, 1fr); gap: var(--sp-3); padding: var(--sp-5); }
h2 { font-size: var(--fs-lg); }
.muted, .hint { font-size: var(--fs-xs); color: var(--text-3); line-height: 1.6; }
.grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(180px, 1fr)); gap: var(--sp-3); align-items: start; }
.up { justify-self: start; margin-top: 4px; }
.go { justify-self: start; }
.sum { font-size: var(--fs-sm); color: var(--text-2); }
.warns { margin: 0; padding: 10px 12px 10px 28px; border-radius: var(--r-sm); background: var(--apricot); color: var(--apricot-ink); font-size: var(--fs-xs); line-height: 1.6; }
.tbl-wrap { max-width: 100%; overflow-x: auto; }
.tbl { width: 100%; border-collapse: collapse; font-size: var(--fs-xs); }
.tbl th, .tbl td { padding: 6px 8px; border-top: 1px solid var(--line); text-align: left; white-space: nowrap; }
.tbl th { color: var(--text-3); font-weight: 400; border-top: 0; }
.err { color: var(--danger); font-size: var(--fs-sm); }
</style>
