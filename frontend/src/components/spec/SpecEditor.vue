<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { specsApi } from '@/api/endpoints'
import { ApiError } from '@/api/http'
import type { MaterialSpec, SlotKind, SpecProject, SpecSlot } from '@/api/types'
import { useToast } from '@/composables/useToast'

const props = defineProps<{ project: SpecProject }>()
const emit = defineEmits<{ saved: [p: SpecProject] }>()
const toast = useToast()

const KINDS: { v: SlotKind; l: string; exts: string[] }[] = [
  { v: 'VIDEO', l: '视频', exts: ['mp4', 'mov', 'mkv', 'webm', 'avi'] },
  { v: 'AUDIO', l: '音频', exts: ['mp3', 'wav', 'm4a', 'aac', 'flac'] },
  { v: 'IMAGE', l: '图片', exts: ['jpg', 'png', 'webp', 'gif', 'bmp', 'tiff', 'heic'] },
  { v: 'DOCUMENT', l: '文档', exts: ['pdf', 'docx', 'xlsx', 'html'] },
]
const BUILTIN = ['序号', '材料', '原文件名']

const notice = ref(props.project.noticeText ?? '')
const draft = ref<MaterialSpec>(clone(props.project.spec))
const fromAi = ref<string | null>(null)
const errors = ref<string[]>([])
const warnings = ref<string[]>(props.project.warnings ?? [])
const extracting = ref(false)
const saving = ref(false)
const dirty = ref(false)

watch(() => props.project.version, () => {
  if (!dirty.value) draft.value = clone(props.project.spec)
})
watch(draft, () => { dirty.value = true }, { deep: true })

function clone<T>(v: T): T {
  return JSON.parse(JSON.stringify(v))
}

const unit = computed(() => (draft.value.sizeUnit === 'BINARY' ? 1024 * 1024 : 1_000_000))
const unitLabel = computed(() => (draft.value.sizeUnit === 'BINARY' ? 'MiB' : 'MB'))
const tokens = computed(() => [...(props.project.roster?.fields ?? []), ...BUILTIN])

function mb(s: SpecSlot) {
  return s.rules.maxBytes ? Math.round((s.rules.maxBytes / unit.value) * 100) / 100 : undefined
}
function setMb(s: SpecSlot, v: string) {
  const n = Number(v)
  if (!v || !Number.isFinite(n) || n <= 0) delete s.rules.maxBytes
  else s.rules.maxBytes = Math.round(n * unit.value)
}
function setNum(s: SpecSlot, key: keyof SpecSlot['rules'], v: string) {
  const n = Number(v)
  if (!v || !Number.isFinite(n) || n <= 0) delete (s.rules as any)[key]
  else (s.rules as any)[key] = Math.round(n)
}
function toggleExt(s: SpecSlot, ext: string) {
  const list = s.rules.extensions ?? []
  s.rules.extensions = list.includes(ext) ? list.filter((e) => e !== ext) : [...list, ext]
  if (!s.rules.extensions.length) delete s.rules.extensions
}
function onKind(s: SpecSlot) {
  s.rules = {} // rules differ per kind
}

function addSlot() {
  const n = draft.value.slots.length + 1
  draft.value.slots.push({ key: `item${n}`, label: '', kind: 'VIDEO', required: true, rules: {} })
}
function removeSlot(i: number) {
  draft.value.slots.splice(i, 1)
}
function insertToken(t: string) {
  draft.value.naming.pattern = (draft.value.naming.pattern ?? '') + `{${t}}`
}

async function extract() {
  if (!notice.value.trim()) return
  extracting.value = true
  errors.value = []
  try {
    const d = await specsApi.extract(props.project.id, notice.value)
    draft.value = d.spec
    errors.value = d.errors
    warnings.value = d.warnings
    fromAi.value = `${d.model} · 提示词 v${d.promptVersion}${d.rounds > 1 ? ' · 自动修正 1 次' : ''}`
    toast.ok('已生成草稿，请逐条核对后保存')
  } catch (e) {
    toast.error((e as Error).message)
  } finally {
    extracting.value = false
  }
}

async function save() {
  saving.value = true
  errors.value = []
  try {
    const p = await specsApi.saveSpec(props.project.id, draft.value, props.project.version, notice.value || undefined)
    warnings.value = p.warnings ?? []
    dirty.value = false
    fromAi.value = null
    emit('saved', p)
    toast.ok('规范已保存，已有材料按新规范重新检查')
  } catch (e) {
    if (e instanceof ApiError) {
      errors.value = (e.details?.errors as string[]) ?? [e.message]
      warnings.value = (e.details?.warnings as string[]) ?? []
      if (e.code === 'VERSION_CONFLICT') toast.error('规范已在别处被修改，请刷新页面')
    } else toast.error('保存失败')
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <div class="ed">
    <section class="card pad">
      <h2>通知原文</h2>
      <p class="muted">粘贴比赛或活动通知。AI 只读取这段文字，不会接触名单或任何文件；每条规则都必须引用原文中的句子，引用不到的会被标出来。</p>
      <textarea v-model="notice" class="input area" rows="8" maxlength="20000" placeholder="例如：讲解视频为 MP4 格式，大小不超过 100MB……" />
      <div class="row">
        <button type="button" class="btn" :disabled="extracting || !notice.trim() || !project.ai.configured" @click="extract">
          {{ extracting ? '正在提取…' : '从通知提取规范' }}
        </button>
        <span v-if="!project.ai.configured" class="muted">未配置 AI：可以直接在下方手动填写。</span>
      </div>
    </section>

    <section class="card pad">
      <header class="hd">
        <h2>材料规范</h2>
        <span v-if="fromAi" class="chip chip--fog">AI 草稿 · {{ fromAi }} · 未保存</span>
        <span v-else-if="dirty" class="chip chip--apricot">有未保存的修改</span>
        <label class="unit">
          体积单位
          <select v-model="draft.sizeUnit" class="select select--sm">
            <option value="DECIMAL">MB（1 MB = 1,000,000 字节）</option>
            <option value="BINARY">MiB（1 MiB = 1,048,576 字节）</option>
          </select>
        </label>
      </header>

      <article v-for="(s, i) in draft.slots" :key="i" class="slot">
        <div class="grid">
          <div class="field"><label :for="`l${i}`">名称</label><input :id="`l${i}`" v-model="s.label" class="input" maxlength="40" placeholder="讲解视频" /></div>
          <div class="field"><label :for="`k${i}`">标识</label><input :id="`k${i}`" v-model="s.key" class="input mono" maxlength="32" placeholder="video" /></div>
          <div class="field">
            <label :for="`t${i}`">类型</label>
            <select :id="`t${i}`" v-model="s.kind" class="select" @change="onKind(s)">
              <option v-for="k in KINDS" :key="k.v" :value="k.v">{{ k.l }}</option>
            </select>
          </div>
          <label class="check req"><input v-model="s.required" type="checkbox" /> 必交</label>
        </div>
        <div class="fmts" role="group" :aria-label="`${s.label || '材料'} 允许的格式`">
          <span class="lbl">格式</span>
          <button v-for="e in KINDS.find((k) => k.v === s.kind)!.exts" :key="e" type="button" class="fmt"
                  :class="{ on: s.rules.extensions?.includes(e) }" :aria-pressed="!!s.rules.extensions?.includes(e)" @click="toggleExt(s, e)">
            {{ e.toUpperCase() }}
          </button>
          <span class="muted">{{ s.rules.extensions?.length ? '' : '不限' }}</span>
        </div>
        <div class="grid rules">
          <div class="field"><label>体积上限（{{ unitLabel }}）</label><input class="input mono" type="number" min="0" step="0.1" :value="mb(s)" @change="setMb(s, ($event.target as HTMLInputElement).value)" /></div>
          <template v-if="s.kind === 'VIDEO'">
            <div class="field"><label>最大高度（px）</label><input class="input mono" type="number" min="0" :value="s.rules.maxHeight" @change="setNum(s, 'maxHeight', ($event.target as HTMLInputElement).value)" /></div>
            <div class="field"><label>最大宽度（px）</label><input class="input mono" type="number" min="0" :value="s.rules.maxWidth" @change="setNum(s, 'maxWidth', ($event.target as HTMLInputElement).value)" /></div>
          </template>
          <template v-if="s.kind === 'VIDEO' || s.kind === 'AUDIO'">
            <div class="field"><label>最短时长（秒）</label><input class="input mono" type="number" min="0" :value="s.rules.minDurationSec" @change="setNum(s, 'minDurationSec', ($event.target as HTMLInputElement).value)" /></div>
            <div class="field"><label>最长时长（秒）</label><input class="input mono" type="number" min="0" :value="s.rules.maxDurationSec" @change="setNum(s, 'maxDurationSec', ($event.target as HTMLInputElement).value)" /></div>
          </template>
          <template v-if="s.kind === 'IMAGE'">
            <div class="field"><label>最小宽度（px）</label><input class="input mono" type="number" min="0" :value="s.rules.minWidth" @change="setNum(s, 'minWidth', ($event.target as HTMLInputElement).value)" /></div>
            <div class="field"><label>最小高度（px）</label><input class="input mono" type="number" min="0" :value="s.rules.minHeight" @change="setNum(s, 'minHeight', ($event.target as HTMLInputElement).value)" /></div>
          </template>
        </div>
        <p v-if="s.evidence" class="ev" :class="{ bad: s.evidenceVerified === false }">
          <span class="tag">{{ s.evidenceVerified === false ? '原文中找不到，请核对' : s.evidenceVerified ? '原文依据' : '依据' }}</span>
          “{{ s.evidence }}”
        </p>
        <button type="button" class="btn btn--sm btn--ghost rm" @click="removeSlot(i)">移除这项</button>
      </article>
      <button type="button" class="btn btn--sm btn--soft" @click="addSlot">+ 添加材料项</button>

      <div class="naming">
        <div class="field">
          <label for="np">文件命名规则</label>
          <input id="np" v-model="draft.naming.pattern" class="input mono" maxlength="120" placeholder="{学校}_{姓名}_{材料}" />
          <span class="hint">可用字段：
            <button v-for="t in tokens" :key="t" type="button" class="tok" @click="insertToken(t)">{{ '{' + t + '}' }}</button>
            <template v-if="!project.roster">（导入名单后可使用名单的列名）</template>
          </span>
          <span v-if="project.namingUnknown?.length" class="bad-hint">
            名单里没有 {{ project.namingUnknown.map((t) => '{' + t + '}').join('、') }} 这一列，交付时会填成空白：请改成上面列出的字段后重新保存。
          </span>
        </div>
        <p v-if="draft.naming.evidence" class="ev" :class="{ bad: draft.naming.evidenceVerified === false }">
          <span class="tag">{{ draft.naming.evidenceVerified === false ? '原文中找不到，请核对' : '原文依据' }}</span>“{{ draft.naming.evidence }}”
        </p>
      </div>

      <div v-if="draft.notes.length" class="notes">
        <h3>无法自动检查的要求</h3>
        <ul><li v-for="(n, i) in draft.notes" :key="i">{{ n }}</li></ul>
      </div>

      <ul v-if="errors.length" class="msgs bad" role="alert"><li v-for="e in errors" :key="e">{{ e }}</li></ul>
      <ul v-if="warnings.length" class="msgs warn"><li v-for="w in warnings" :key="w">{{ w }}</li></ul>
      <div class="row">
        <button type="button" class="btn" :disabled="saving || !draft.slots.length" @click="save">{{ saving ? '保存中…' : '确认并保存规范' }}</button>
        <span class="muted">保存后，已放入的材料会按新规范重新检查。</span>
      </div>
    </section>
  </div>
</template>

<style scoped>
.ed { display: grid; grid-template-columns: minmax(0, 1fr); gap: var(--sp-4); }
.pad { display: grid; grid-template-columns: minmax(0, 1fr); gap: var(--sp-3); padding: var(--sp-5); }
h2 { font-size: var(--fs-lg); }
h3 { font-size: var(--fs-sm); }
.muted, .hint { font-size: var(--fs-xs); color: var(--text-3); line-height: 1.6; }
.area { width: 100%; min-height: 140px; resize: vertical; font-size: var(--fs-sm); line-height: 1.6; }
.row { display: flex; align-items: center; gap: var(--sp-3); flex-wrap: wrap; }
.hd { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; }
.unit { margin-left: auto; display: flex; align-items: center; gap: 6px; font-size: var(--fs-xs); color: var(--text-3); }
.slot { position: relative; display: grid; gap: 10px; padding: var(--sp-4); border: 1px solid var(--line); border-radius: var(--r-md); background: var(--surface); }
.grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(150px, 1fr)); gap: var(--sp-3); align-items: end; }
.check { display: flex; align-items: center; gap: 6px; min-height: 40px; font-size: var(--fs-sm); }
.fmts { display: flex; flex-wrap: wrap; align-items: center; gap: 6px; }
.lbl { font-size: var(--fs-xs); color: var(--text-3); margin-right: 4px; }
.fmt { min-height: 32px; padding: 0 10px; border: 1px solid var(--line-strong); border-radius: var(--r-pill); background: var(--surface); font-size: var(--fs-xs); color: var(--text-2); }
.fmt.on { border-color: var(--mint-ink); background: var(--mint); color: var(--mint-ink); }
.ev { font-size: var(--fs-xs); line-height: 1.6; color: var(--text-2); padding: 8px 10px; border-radius: var(--r-sm); background: var(--accent-soft); }
.ev.bad { background: var(--apricot); color: var(--apricot-ink); }
.tag { margin-right: 6px; font-weight: 600; }
.rm { justify-self: end; }
.naming { display: grid; gap: 8px; }
.bad-hint { font-size: var(--fs-xs); color: var(--danger); line-height: 1.6; }
.tok { margin: 2px; padding: 0 8px; min-height: 26px; border: 1px solid var(--line); border-radius: var(--r-pill); background: var(--surface); font-family: var(--font-mono); font-size: 11px; color: var(--text-2); }
.notes ul, .msgs { margin: 0; padding-left: 20px; font-size: var(--fs-xs); line-height: 1.7; }
.msgs.bad { color: var(--danger); }
.msgs.warn { color: var(--apricot-ink); }
</style>
