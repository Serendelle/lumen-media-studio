<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { filesApi, specsApi } from '@/api/endpoints'
import type { ItemStatus, SpecBoard, SpecItem, SpecProject } from '@/api/types'
import { useToast } from '@/composables/useToast'
import { filesFromDrop, filesFromInput, type PathedFile } from '@/utils/folderDrop'
import { bytes } from '@/utils/format'

const props = defineProps<{ project: SpecProject }>()
const emit = defineEmits<{ changed: [b: SpecBoard] }>()
const toast = useToast()

const board = ref<SpecBoard | null>(null)
const error = ref<string | null>(null)
const selected = ref<string | null>(null)
const filter = ref<'todo' | 'all'>('todo')

const STATUS: Record<ItemStatus | 'MISSING', { l: string; tone: string }> = {
  OK: { l: '合格', tone: 't-ok' },
  FIXED: { l: '已修正', tone: 't-ok' },
  FIXABLE: { l: '可修正', tone: 't-warn' },
  FIXING: { l: '修正中', tone: 't-run' },
  INVALID: { l: '不合格', tone: 't-bad' },
  FIX_FAILED: { l: '修正失败', tone: 't-bad' },
  UNMATCHED: { l: '未匹配', tone: 't-muted' },
  MISSING: { l: '缺', tone: 't-miss' },
}

async function load() {
  try {
    board.value = await specsApi.board(props.project.id)
    error.value = null
    emit('changed', board.value)
  } catch (e) {
    error.value = (e as Error).message
  }
}
onMounted(load)
watch(() => props.project.version + '|' + (props.project.roster?.fileId ?? ''), load)

// poll only while fixes are running; the server folds finished fix tasks in on read
let timer: number | undefined
const fixing = computed(() => board.value?.items.some((i) => i.status === 'FIXING') ?? false)
watch(fixing, (on) => {
  window.clearInterval(timer)
  if (on) timer = window.setInterval(load, 2500)
}, { immediate: true })
onBeforeUnmount(() => window.clearInterval(timer))

// ---- intake ----
const dragging = ref(false)
const queue = ref<{ name: string; state: 'wait' | 'up' | 'ok' | 'err'; msg?: string }[]>([])
const uploading = computed(() => queue.value.some((q) => q.state === 'wait' || q.state === 'up'))
const dirInput = ref<HTMLInputElement | null>(null)
const fileInput = ref<HTMLInputElement | null>(null)

async function onDrop(e: DragEvent) {
  dragging.value = false
  if (e.dataTransfer) await intake(await filesFromDrop(e.dataTransfer))
}
async function onPick(e: Event) {
  await intake(filesFromInput((e.target as HTMLInputElement).files))
  ;(e.target as HTMLInputElement).value = ''
}

async function intake(files: PathedFile[]) {
  if (!files.length) return
  if (files.length > 500) {
    toast.error('一次最多放入 500 个文件')
    return
  }
  queue.value = files.map((f) => ({ name: f.relativePath, state: 'wait' }))
  const done: { fileId: string; relativePath: string }[] = []
  let next = 0
  async function worker() {
    while (next < files.length) {
      const i = next++
      queue.value[i].state = 'up'
      try {
        const fv = await filesApi.upload(files[i].file)
        done.push({ fileId: fv.fileId, relativePath: files[i].relativePath })
        queue.value[i].state = 'ok'
      } catch (e) {
        queue.value[i].state = 'err'
        queue.value[i].msg = (e as Error).message
      }
    }
  }
  await Promise.all([worker(), worker(), worker()])
  for (let i = 0; i < done.length; i += 200) {
    board.value = await specsApi.attach(props.project.id, done.slice(i, i + 200))
  }
  emit('changed', board.value!)
  const failed = queue.value.filter((q) => q.state === 'err').length
  toast.ok(`已放入 ${done.length} 个文件${failed ? `，${failed} 个被拒收（见下方原因）` : ''}`)
  if (!failed) queue.value = []
}

// ---- items ----
const slots = computed(() => props.project.spec.slots)
const people = computed(() => props.project.roster?.people ?? [])
const TODO = new Set(['UNMATCHED', 'FIXABLE', 'INVALID', 'FIX_FAILED'])
const shown = computed(() => (board.value?.items ?? []).filter((i) => filter.value === 'all' || TODO.has(i.status)))
const fixable = computed(() => board.value?.items.filter((i) => i.status === 'FIXABLE').length ?? 0)
const current = computed(() => board.value?.items.find((i) => i.id === selected.value) ?? null)

async function assign(it: SpecItem, personKey: string | null, slotKey: string | null) {
  try {
    await specsApi.assign(props.project.id, it.id, personKey, slotKey)
    await load()
  } catch (e) {
    toast.error((e as Error).message)
  }
}
async function remove(it: SpecItem) {
  try {
    await specsApi.removeItem(props.project.id, it.id)
    if (selected.value === it.id) selected.value = null
    await load()
  } catch (e) {
    toast.error((e as Error).message)
  }
}
const fixingBusy = ref(false)
async function fixAll() {
  fixingBusy.value = true
  try {
    board.value = await specsApi.fix(props.project.id)
    toast.ok(`已提交 ${board.value.started ?? 0} 个修正任务`)
  } catch (e) {
    toast.error((e as Error).message)
  } finally {
    fixingBusy.value = false
  }
}
function openCell(itemId?: string) {
  if (itemId) {
    selected.value = itemId
    filter.value = 'all'
  }
}
</script>

<template>
  <div class="ib">
    <section class="card pad">
      <h2>放入材料</h2>
      <div class="drop" :class="{ on: dragging }" role="button" tabindex="0" aria-label="拖入文件夹或文件"
           @dragover.prevent="dragging = true" @dragleave="dragging = false" @drop.prevent="onDrop"
           @keydown.enter="dirInput?.click()">
        <strong>把收上来的文件夹整个拖进来</strong>
        <span>系统按文件夹名、文件名中的姓名或{{ project.roster?.idField ?? '编号' }}匹配到人，按类型和名称匹配到材料项；匹配不确定的不会乱猜，留给你指定。</span>
        <span class="btns">
          <button type="button" class="btn btn--sm" @click.stop="dirInput?.click()">选择文件夹</button>
          <button type="button" class="btn btn--sm btn--ghost" @click.stop="fileInput?.click()">选择文件</button>
        </span>
        <input ref="dirInput" type="file" webkitdirectory multiple hidden @change="onPick" />
        <input ref="fileInput" type="file" multiple hidden @change="onPick" />
      </div>
      <p v-if="!project.roster" class="warn">还没有导入名单：文件可以先放进来，导入名单后会自动匹配到人。</p>
      <ul v-if="queue.length" class="queue" aria-live="polite">
        <li v-for="(q, i) in queue" :key="i" :class="`q-${q.state}`">
          <span class="nm">{{ q.name }}</span>
          <span class="st">{{ q.state === 'wait' ? '等待' : q.state === 'up' ? '上传中' : q.state === 'ok' ? '已放入' : '被拒收：' + q.msg }}</span>
        </li>
      </ul>
      <p v-if="uploading" class="muted">上传中，请不要关闭页面…</p>
    </section>

    <p v-if="error" class="err" role="alert">{{ error }}</p>

    <section v-if="board" class="card pad">
      <header class="hd">
        <h2>收齐情况</h2>
        <span class="sum">{{ board.summary.complete }} / {{ board.summary.people }} 人必交材料齐全 · 共 {{ board.summary.files }} 个文件</span>
        <button v-if="fixable" type="button" class="btn btn--sm" :disabled="fixingBusy" @click="fixAll">一键修正 {{ fixable }} 个文件</button>
        <span v-if="fixing" class="chip chip--fog">修正进行中，自动刷新</span>
      </header>
      <div v-if="board.people.length" class="grid-wrap">
        <table class="grid">
          <thead>
            <tr><th scope="col">姓名</th><th v-for="s in slots" :key="s.key" scope="col">{{ s.label }}{{ s.required ? '' : '（选交）' }}</th><th scope="col">状态</th></tr>
          </thead>
          <tbody>
            <tr v-for="p in board.people" :key="p.key">
              <th scope="row">{{ p.name }}</th>
              <td v-for="s in slots" :key="s.key">
                <button type="button" class="cell" :class="STATUS[p.cells[s.key]?.status ?? 'MISSING'].tone" :disabled="!p.cells[s.key]?.itemId"
                        @click="openCell(p.cells[s.key]?.itemId)">
                  {{ STATUS[p.cells[s.key]?.status ?? 'MISSING'].l }}<sup v-if="(p.cells[s.key]?.count ?? 0) > 1">×{{ p.cells[s.key].count }}</sup>
                </button>
              </td>
              <td><span class="chip" :class="p.complete ? 'chip--mint' : 'chip--apricot'">{{ p.complete ? '齐全' : '未齐' }}</span></td>
            </tr>
          </tbody>
        </table>
      </div>
      <p v-else class="muted">导入名单后，这里显示每个人每项材料的状态。</p>
    </section>

    <section v-if="board" class="card pad">
      <header class="hd">
        <h2>文件</h2>
        <div class="tabs" role="tablist">
          <button type="button" role="tab" :aria-selected="filter === 'todo'" :class="{ on: filter === 'todo' }" @click="filter = 'todo'">待处理</button>
          <button type="button" role="tab" :aria-selected="filter === 'all'" :class="{ on: filter === 'all' }" @click="filter = 'all'">全部</button>
        </div>
      </header>
      <div class="items">
        <article v-for="it in shown" :key="it.id" class="it" :class="{ sel: it.id === selected }" @click="selected = it.id">
          <div class="top">
            <span class="st" :class="STATUS[it.status].tone">{{ STATUS[it.status].l }}</span>
            <span class="path" :title="it.relativePath">{{ it.relativePath }}</span>
            <span class="mono sz">{{ bytes(it.sizeBytes) }}</span>
          </div>
          <div class="asg" @click.stop>
            <select class="select select--sm" :value="it.personKey ?? ''" aria-label="指定人员"
                    @change="assign(it, ($event.target as HTMLSelectElement).value || null, it.slotKey ?? null)">
              <option value="">— 人员 —</option>
              <option v-for="p in people" :key="p.key" :value="p.key">{{ p.values[project.roster!.nameField] }}</option>
            </select>
            <select class="select select--sm" :value="it.slotKey ?? ''" aria-label="指定材料项"
                    @change="assign(it, it.personKey ?? null, ($event.target as HTMLSelectElement).value || null)">
              <option value="">— 材料项 —</option>
              <option v-for="s in slots" :key="s.key" :value="s.key">{{ s.label }}</option>
            </select>
            <span class="how">{{ it.matchMethod === 'MANUAL' ? '手动' : it.matchMethod === 'AUTO' ? '自动' : '' }}{{ it.matchNote ? ' · ' + it.matchNote : '' }}</span>
            <button type="button" class="x" :aria-label="`移出 ${it.relativePath}`" @click="remove(it)">×</button>
          </div>
          <template v-if="it.id === selected && it.check">
            <ul v-if="it.check.violations.length" class="viol">
              <li v-for="v in it.check.violations" :key="v.rule" :class="{ hard: !v.fixable }">
                <strong>{{ v.label }}</strong> 期望 {{ v.expected }}，实际 {{ v.actual }}
                <em>{{ v.fixable ? '可自动修正' : (v.why ?? '需重新提交') }}</em>
              </li>
            </ul>
            <p v-if="it.check.fix" class="fix">修正方式：{{ it.check.fix.reason }}</p>
            <p v-if="it.check.afterFix" class="fix">
              修正后重新检查：{{ it.check.afterFix.status === 'OK' ? '全部符合' : it.check.afterFix.violations.map((v) => v.label + ' ' + v.actual).join('；') }}
            </p>
            <p v-if="it.check.fixError" class="fix bad">修正失败：{{ it.check.fixError }}</p>
          </template>
        </article>
        <p v-if="!shown.length" class="muted">{{ filter === 'todo' ? '没有待处理的文件。' : '还没有放入文件。' }}</p>
      </div>
      <p v-if="current && !current.check && current.status === 'UNMATCHED'" class="muted">选择人员和材料项后会立即按规范检查。</p>
    </section>
  </div>
</template>

<style scoped>
.ib { display: grid; grid-template-columns: minmax(0, 1fr); gap: var(--sp-4); }
.pad { display: grid; gap: var(--sp-3); padding: var(--sp-5); min-width: 0; }
h2 { font-size: var(--fs-lg); }
.muted { font-size: var(--fs-xs); color: var(--text-3); }
.warn { font-size: var(--fs-xs); color: var(--apricot-ink); }
.err { color: var(--danger); font-size: var(--fs-sm); }
.drop { display: grid; gap: 8px; justify-items: center; padding: var(--sp-6) var(--sp-4); border: 1.5px dashed var(--line-strong); border-radius: var(--r-md); text-align: center; background: var(--surface); }
.drop.on { border-color: var(--mint-ink); background: var(--accent-soft); }
.drop span { font-size: var(--fs-xs); color: var(--text-2); max-width: 560px; line-height: 1.6; }
.btns { display: flex; gap: 8px; }
.queue { display: grid; gap: 2px; max-height: 220px; overflow: auto; margin: 0; padding: 0; list-style: none; font-size: var(--fs-xs); }
.queue li { display: flex; gap: 10px; justify-content: space-between; padding: 3px 0; border-top: 1px solid var(--line); }
.queue .nm { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.queue .q-err .st { color: var(--danger); }
.queue .q-ok .st { color: var(--ok); }
.hd { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; }
.sum { font-size: var(--fs-sm); color: var(--text-2); }
.grid-wrap { max-width: 100%; overflow-x: auto; }
.grid { border-collapse: collapse; font-size: var(--fs-xs); min-width: 100%; }
.grid th, .grid td { padding: 4px 6px; border-top: 1px solid var(--line); text-align: left; white-space: nowrap; }
.grid thead th { color: var(--text-3); font-weight: 400; border-top: 0; }
.cell { min-width: 64px; min-height: 30px; padding: 0 8px; border: 0; border-radius: var(--r-pill); font-size: 11px; }
.cell:disabled { cursor: default; }
.t-ok { background: var(--mint); color: var(--mint-ink); }
.t-warn { background: var(--apricot); color: var(--apricot-ink); }
.t-run { background: var(--fog); color: var(--fog-ink); }
.t-bad { background: var(--danger-soft); color: var(--danger); }
.t-muted { background: var(--surface-sunk); color: var(--text-3); }
.t-miss { background: transparent; color: var(--text-3); border: 1px dashed var(--line-strong) !important; }
.tabs { display: flex; gap: 4px; padding: 3px; margin-left: auto; border-radius: var(--r-pill); background: var(--surface-sunk); }
.tabs button { min-height: 30px; padding: 0 12px; border: 0; border-radius: var(--r-pill); background: transparent; font-size: var(--fs-xs); }
.tabs button.on { background: var(--surface); box-shadow: var(--shadow-1); }
.items { display: grid; gap: 6px; }
.it { display: grid; gap: 6px; padding: 10px 12px; border: 1px solid var(--line); border-radius: var(--r-sm); background: var(--surface); cursor: pointer; }
.it.sel { border-color: var(--mint-ink); }
.top { display: flex; align-items: center; gap: 8px; min-width: 0; }
.st { flex: none; padding: 1px 8px; border-radius: var(--r-pill); font-size: 11px; }
.path { flex: 1; min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; font-size: var(--fs-sm); }
.sz { font-size: 11px; color: var(--text-3); }
.asg { display: flex; flex-wrap: wrap; align-items: center; gap: 6px; }
.how { font-size: 11px; color: var(--text-3); }
.x { margin-left: auto; width: 32px; height: 32px; border: 0; border-radius: var(--r-sm); background: none; font-size: 18px; color: var(--text-3); }
.viol { margin: 0; padding-left: 18px; font-size: var(--fs-xs); line-height: 1.7; }
.viol em { margin-left: 6px; font-style: normal; color: var(--apricot-ink); }
.viol .hard em { color: var(--danger); }
.fix { font-size: var(--fs-xs); color: var(--text-2); }
.fix.bad { color: var(--danger); }
</style>
