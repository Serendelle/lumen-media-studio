<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
import { formApi, randomFormKey, saveBlob, type FormTarget, type FormBoard, type FormRow, type FormValues, type FormStatistics, type FormDiscussion, type FormConfig } from '@/api/forms'
import type { SpecSlot } from '@/api/types'
import FormFields from './FormFields.vue'
import { useToast } from '@/composables/useToast'
import { bytes } from '@/utils/format'
import { changedFormValues, mergeFormDraft } from '@/utils/formMerge'

const props = defineProps<{ target: FormTarget; slots: SpecSlot[] }>()
const emit = defineEmits<{ config: [config: FormConfig] }>()
const api = formApi(props.target), toast = useToast()
const board = ref<FormBoard | null>(null), error = ref(''), page = ref(0), trash = ref(false)
const busy = ref(false), loading = ref(false), updatedAt = ref(0)
const stats = ref<FormStatistics | null>(null), statsTime = ref(0), groupKey = ref('')
const group = computed(() => stats.value?.fields.find(f => f.key === groupKey.value) ?? stats.value?.fields[0])
const editor = ref<HTMLDialogElement | null>(null), detailDialog = ref<HTMLDialogElement | null>(null)
const draft = ref<FormValues>({}), editing = ref<FormRow | null>(null), editError = ref(''), editConfigVersion = ref(0), requestKey = ref('')
const baseValues = ref<FormValues>({}), conflicts = ref<string[]>([])
const detail = ref<FormRow | null>(null), discussion = ref<FormDiscussion | null>(null), commentText = ref(''), detailError = ref('')
const slotKey = ref(''), uploadProgress = ref<number | null>(null), attachmentInput = ref<HTMLInputElement | null>(null)
let timer: number | undefined
const modes = { OWN: '各自修改自己的记录', ALL: '所有参与者协作编辑', DESIGNATED: '指定成员编辑', LOCKED: '仅负责人修改' }
const actions: Record<string, string> = { CREATE: '创建记录', UPDATE: '修改信息', DELETE: '移入回收站', RESTORE: '恢复记录', ATTACH: '添加附件' }

async function load() {
  if (loading.value) return
  loading.value = true
  try {
    const next = await api.board(page.value, trash.value)
    if (board.value && (next.config.version !== board.value.config.version || next.actor.role !== board.value.actor.role)) stats.value = null
    board.value = next; updatedAt.value = Date.now(); error.value = ''; emit('config', next.config)
    if (detail.value) {
      const latest = next.rows.find(r => r.id === detail.value!.id)
      if (latest) { detail.value = latest; const id = latest.id; const d = await api.discussion(id); if (detail.value?.id === id) discussion.value = d }
      else { detailDialog.value?.close(); detail.value = null; discussion.value = null }
    }
    if (!next.rows.length && page.value > 0) { page.value = Math.max(0, Math.ceil(next.total / next.size) - 1); loading.value = false; await load() }
  } catch (e) {
    error.value = (e as Error).message
    board.value = null; stats.value = null; discussion.value = null; detail.value = null
    editor.value?.close(); detailDialog.value?.close()
  } finally { loading.value = false }
}
onMounted(() => { void load(); timer = window.setInterval(() => { if (!document.hidden) void load() }, 5000) })
onBeforeUnmount(() => window.clearInterval(timer))
async function changePage(value: number) { page.value = value; await load() }
async function toggleTrash() { trash.value = !trash.value; page.value = 0; stats.value = null; await load() }
async function openEditor(row?: FormRow) {
  editing.value = row ?? null; draft.value = row ? JSON.parse(JSON.stringify(row.values)) : {}
  baseValues.value = row ? JSON.parse(JSON.stringify(row.values)) : {}; conflicts.value = []
  editError.value = ''; editConfigVersion.value = board.value?.config.version ?? 0; requestKey.value = randomFormKey()
  await nextTick(); editor.value?.showModal()
}
async function save() {
  busy.value = true; editError.value = ''
  try {
    const patch = changedFormValues(baseValues.value, draft.value)
    const row = editing.value ? await api.update(editing.value.id, patch, editing.value.version, editConfigVersion.value)
      : await api.create(draft.value, requestKey.value, editConfigVersion.value)
    editor.value?.close(); await load(); stats.value = null; toast.ok('信息已保存')
    if (props.slots.length && !editing.value) await showDetail(row)
  } catch (e) { editError.value = (e as Error).message }
  finally { busy.value = false }
}
async function rebaseDraft() {
  if (!editing.value) { editConfigVersion.value = board.value?.config.version ?? 0; editError.value = ''; return }
  await load()
  const latest = board.value?.rows.find(r => r.id === editing.value!.id)
  if (!latest) { editError.value = '这条记录已被删除、无权查看或不在当前页，请关闭草稿后刷新'; return }
  const result = mergeFormDraft(baseValues.value, draft.value, latest.values)
  conflicts.value = result.conflicts; draft.value = result.values
  baseValues.value = { ...latest.values }; editing.value = latest; editConfigVersion.value = board.value!.config.version
  editError.value = conflicts.value.length ? '以下字段也被其他人修改，请逐项选择保留哪份内容。' : '已合并其他人修改的字段，你的草稿改动已保留。请核对后保存。'
}
async function remove(row: FormRow) {
  busy.value = true
  try { await api.remove(row); stats.value = null; await load(); toast.ok('已移入回收站，负责人可恢复') }
  catch (e) { toast.error((e as Error).message) }
  finally { busy.value = false }
}
async function restore(row: FormRow) {
  busy.value = true
  try { await api.restore(row); await load(); toast.ok('记录已恢复') }
  catch (e) { toast.error((e as Error).message) }
  finally { busy.value = false }
}
async function statistics() {
  busy.value = true
  try { stats.value = await api.statistics(); statsTime.value = Date.now(); if (!groupKey.value) groupKey.value = stats.value.fields[0]?.key ?? '' }
  catch (e) { toast.error((e as Error).message) }
  finally { busy.value = false }
}
async function exportSheet() {
  busy.value = true
  try { saveBlob(await api.export(), '收集表汇总.xlsx'); toast.ok('汇总表已导出') }
  catch (e) { toast.error((e as Error).message) }
  finally { busy.value = false }
}
async function showDetail(row: FormRow) {
  detail.value = row; detailError.value = ''; discussion.value = null; commentText.value = ''; slotKey.value = props.slots[0]?.key ?? ''
  await nextTick(); if (!detailDialog.value?.open) detailDialog.value?.showModal()
  try { discussion.value = await api.discussion(row.id) }
  catch (e) { detailError.value = (e as Error).message }
}
async function comment() {
  if (!detail.value || !commentText.value.trim()) return
  busy.value = true
  try { await api.comment(detail.value.id, commentText.value); commentText.value = ''; discussion.value = await api.discussion(detail.value.id) }
  catch (e) { detailError.value = (e as Error).message }
  finally { busy.value = false }
}
async function upload(event: Event) {
  const input = event.target as HTMLInputElement, file = input.files?.[0]; input.value = ''
  if (!file || !detail.value) return
  if (file.size > 200 * 1024 * 1024) { detailError.value = '单个附件不能超过 200MB'; return }
  busy.value = true; detailError.value = ''; uploadProgress.value = 0
  try {
    const id = detail.value.id
    await api.upload(id, file, slotKey.value, randomFormKey(), p => uploadProgress.value = p)
    await load(); if (detail.value) discussion.value = await api.discussion(id)
    toast.ok('附件已收到，已接入材料规范检查')
  } catch (e) { detailError.value = (e as Error).message }
  finally { busy.value = false; uploadProgress.value = null }
}
async function download(file: FormRow['files'][number]) {
  if (!detail.value) return
  try { saveBlob(await api.download(detail.value.id, file.id), file.filename) }
  catch (e) { detailError.value = (e as Error).message }
}
function display(value: unknown) { return value === true ? '已勾选' : value === false ? '未勾选' : value == null || value === '' ? '—' : String(value) }
function changes(event: FormDiscussion['events'][number]) {
  return board.value?.config.fields.filter(f => JSON.stringify(event.before?.[f.key]) !== JSON.stringify(event.after?.[f.key]))
    .map(f => `${f.label}：${display(event.before?.[f.key])} → ${display(event.after?.[f.key])}`) ?? []
}
</script>

<template>
  <section class="form-board">
    <p v-if="error" class="error" role="alert">{{ error }} <button class="btn btn--sm btn--ghost" @click="load">重新加载</button></p>
    <template v-if="board">
      <header class="table-heading"><div><span class="eyebrow">{{ trash ? 'RECYCLE BIN' : 'COLLABORATIVE COLLECTION' }}</span><h2>{{ trash ? '回收站' : '在线协作表格' }}</h2>
        <p>{{ board.permissions.seeAll ? '你可以查看全部记录和留言' : '仅展示你的记录和留言，统计和导出也仅包含这些内容' }} · {{ modes[board.config.editMode] }}</p></div>
        <div class="toolbar"><button v-if="board.permissions.create && !trash && board.config.fields.length" class="btn" :disabled="busy" @click="openEditor()">＋ 填写信息</button>
          <button class="btn btn--ghost" :disabled="busy || trash" @click="statistics">一键统计</button><button class="btn btn--ghost" :disabled="busy || trash" @click="exportSheet">导出 Excel</button>
          <button v-if="board.permissions.owner" class="btn btn--sm btn--ghost" @click="toggleTrash">{{ trash ? '返回表格' : '回收站' }}</button></div>
      </header>
      <div class="sync-line"><span :class="board.config.accepting ? 'open-dot' : 'closed-dot'"></span>{{ board.config.accepting ? '收集中' : '已停止填写与修改' }}<span>{{ board.total }} 条{{ trash ? '已删除' : '可见' }}记录</span><span>每 5 秒同步 · 上次 {{ new Date(updatedAt).toLocaleTimeString() }}</span><button class="text-button" @click="load">立即刷新</button></div>
      <p v-if="!board.config.fields.length" class="empty card">还没有收集字段。负责人可先在「收集与权限」中设置；参与者再通过链接填写。</p>
      <div v-else class="sheet card"><table><thead><tr><th>提交人</th><th v-for="field in board.config.fields" :key="field.key">{{ field.label }}<small v-if="field.required"> *</small></th><th>附件</th><th>更新时间</th><th class="sticky">操作</th></tr></thead>
        <tbody><tr v-for="row in board.rows" :key="row.id"><th scope="row">{{ row.submittedBy }}<span v-if="row.mine" class="mine">我</span></th><td v-for="field in board.config.fields" :key="field.key" :title="display(row.values[field.key])">{{ display(row.values[field.key]) }}</td><td>{{ row.files.length }} 个</td><td class="time">{{ new Date(row.updatedAt).toLocaleString() }}</td><td class="sticky row-actions">
          <template v-if="!trash"><button class="text-button" @click="showDetail(row)">详情 / 留言</button><button v-if="row.canEdit" class="text-button" :disabled="busy" @click="openEditor(row)">编辑</button><button v-if="row.canDelete" class="text-button danger" :disabled="busy" @click="remove(row)">移入回收站</button></template>
          <button v-else class="text-button" :disabled="busy" @click="restore(row)">恢复记录</button>
        </td></tr><tr v-if="!board.rows.length"><td :colspan="board.config.fields.length + 4" class="empty">{{ trash ? '回收站为空' : '还没有记录，点击「填写信息」开始收集。' }}</td></tr></tbody></table></div>
      <div v-if="board.total > board.size" class="pagination"><button class="btn btn--sm btn--ghost" :disabled="page === 0" @click="changePage(page - 1)">上一页</button><span>第 {{ page + 1 }} / {{ Math.ceil(board.total / board.size) }} 页</span><button class="btn btn--sm btn--ghost" :disabled="(page + 1) * board.size >= board.total" @click="changePage(page + 1)">下一页</button></div>
      <section v-if="stats && !trash" class="statistics card"><header><h3>收集统计</h3><span>{{ new Date(statsTime).toLocaleTimeString() }} · {{ stats.scope === 'OWN' ? '仅你的记录' : '全部记录' }}</span></header>
        <div class="metrics"><article><strong>{{ stats.rows }}</strong><span>记录</span></article><article><strong>{{ stats.participants }}</strong><span>提交人</span></article><article><strong>{{ stats.attachments }}</strong><span>附件</span></article></div>
        <div class="stat-table"><table><thead><tr><th>字段</th><th>已填 / 未填</th><th>不同值 / 重复值</th><th>合计</th><th>平均</th><th>最小 / 最大</th></tr></thead><tbody><tr v-for="field in stats.fields" :key="field.key"><th>{{ field.label }}</th><td>{{ field.filled }} / {{ field.missing }}</td><td>{{ field.distinct }} / {{ field.duplicates }}</td><td>{{ field.sum ?? '—' }}</td><td>{{ field.average ?? '—' }}</td><td>{{ field.min ?? '—' }} / {{ field.max ?? '—' }}</td></tr></tbody></table></div>
        <label class="group-label">按字段分组<select v-model="groupKey" class="select"><option v-for="field in stats.fields" :key="field.key" :value="field.key">{{ field.label }}</option></select></label>
        <ul class="groups"><li v-for="g in group?.groups" :key="g.value"><span>{{ display(g.value) }}</span><div class="bar"><i :style="{ width: `${100 * g.count / Math.max(1, stats.rows)}%` }"></i></div><strong>{{ g.count }}</strong></li></ul>
        <p class="note">重复值统计每个相同值超出第一次的记录数；分组最多展示前 100 个值{{ group?.groupsTruncated ? '，完整数据请导出查看' : '' }}。</p>
      </section>

      <dialog ref="editor" class="form-dialog" @cancel="busy && $event.preventDefault()"><form @submit.prevent="save"><header><div><span class="eyebrow">{{ editing ? 'EDIT RECORD' : 'NEW RESPONSE' }}</span><h3>{{ editing ? `修改 ${editing.submittedBy} 的记录` : '填写收集信息' }}</h3></div><button type="button" class="close" aria-label="关闭表单" :disabled="busy" @click="editor?.close()">×</button></header>
        <FormFields v-model="draft" :fields="board.config.fields" :disabled="busy" />
        <div v-if="editError" class="conflict" role="alert"><p>{{ editError }}</p><button type="button" class="text-button" :disabled="busy || conflicts.length > 0" @click="rebaseDraft">保留草稿，载入最新版本供核对</button><div v-for="key in conflicts" :key="key"><strong>{{ board.config.fields.find(f => f.key === key)?.label }}</strong><p>服务器：{{ display(baseValues[key]) }}</p><p>我的草稿：{{ display(draft[key]) }}</p><button type="button" class="text-button" @click="draft[key] = baseValues[key]!; conflicts = conflicts.filter(k => k !== key)">使用服务器内容</button> · <button type="button" class="text-button" @click="conflicts = conflicts.filter(k => k !== key)">保留我的修改</button></div></div>
        <p class="note">必填项标注 *。{{ props.slots.length ? '保存后可在记录详情中补交附件。' : '' }}同时编辑发生冲突时，草稿会保留。</p><footer><button type="button" class="btn btn--ghost" :disabled="busy" @click="editor?.close()">取消</button><button class="btn" :disabled="busy || conflicts.length > 0 || (!!editing && !editing.canEdit)">{{ busy ? '保存中…' : '保存信息' }}</button></footer>
      </form></dialog>
      <dialog ref="detailDialog" class="form-dialog detail-dialog" @cancel="busy && $event.preventDefault()"><template v-if="detail"><header><h3>{{ detail.submittedBy }} 的记录</h3><button class="close" aria-label="关闭详情" :disabled="busy" @click="detailDialog?.close()">×</button></header>
        <dl class="values"><template v-for="field in board.config.fields" :key="field.key"><dt>{{ field.label }}</dt><dd>{{ display(detail.values[field.key]) }}</dd></template></dl>
        <h4>附件</h4><ul class="file-list"><li v-for="file in detail.files" :key="file.id"><span>{{ file.filename }}<small>{{ bytes(file.sizeBytes) }} · {{ slots.find(s => s.key === file.slotKey)?.label }}</small></span><button class="text-button" @click="download(file)">下载</button></li></ul><p v-if="!detail.files.length" class="note">尚未添加附件。</p>
        <div v-if="detail.canAttach && slots.length" class="attachment-add"><select v-model="slotKey" class="select" aria-label="附件材料项"><option v-for="slot in slots" :key="slot.key" :value="slot.key">{{ slot.label }}</option></select><button class="btn btn--sm" :disabled="busy" @click="attachmentInput?.click()">{{ uploadProgress === null ? '上传附件' : `上传中 ${uploadProgress}%` }}</button><input ref="attachmentInput" type="file" hidden @change="upload" /></div>
        <h4>留言</h4><div v-for="c in discussion?.comments" :key="c.id" class="comment"><header><strong>{{ c.name }}</strong><time>{{ new Date(c.createdAt).toLocaleString() }}</time></header><p>{{ c.content }}</p></div><p v-if="discussion && !discussion.comments.length" class="note">暂无留言。可见范围与这条记录一致。</p>
        <form v-if="detail.canComment" class="comment-form" @submit.prevent="comment"><textarea v-model="commentText" maxlength="1000" rows="2" placeholder="补充说明或反馈，最多 1000 字" required /><button class="btn btn--sm" :disabled="busy || !commentText.trim()">发送留言</button></form>
        <h4>修改记录</h4><details v-for="(event, index) in discussion?.events" :key="index" class="history-event"><summary>{{ event.name }} · {{ actions[event.action] ?? event.action }} · {{ new Date(event.createdAt).toLocaleString() }}</summary><p v-for="(change, i) in changes(event)" :key="i">{{ change }}</p></details>
        <p v-if="detailError" class="error" role="alert">{{ detailError }}</p>
      </template></dialog>
    </template>
    <p v-else-if="!error" class="note">正在加载协作表格…</p>
  </section>
</template>

<style scoped>
.form-board { display:grid;gap:18px;min-width:0; }.table-heading,.toolbar,.sync-line,.statistics header,.pagination,.attachment-add { display:flex;gap:12px;align-items:center;flex-wrap:wrap; }.table-heading { justify-content:space-between;align-items:flex-start; }.table-heading h2 { margin:10px 0;font-size:26px; }.table-heading p,.note { color:var(--text-2,#6a786e);font-size:12px;line-height:1.8; }.eyebrow { color:var(--mint-ink,#407455);font-size:10px;letter-spacing:.15em; }.toolbar { padding-top:16px; }.sync-line { font-size:11px;color:var(--text-3,#7b8a7e);gap:14px; }.open-dot,.closed-dot { height:7px;width:7px;border-radius:50%;background:#5b9a70; }.closed-dot { background:#aaa; }.text-button { border:0;background:none;color:var(--mint-ink,#356d4b);font:inherit;cursor:pointer;white-space:nowrap;padding:4px 0; }.text-button:disabled { opacity:.4; }.danger,.error { color:var(--danger,#b24742); }.error { font-size:13px;line-height:1.8; }.sheet,.stat-table { overflow:auto; }.sheet { padding:0;border:1px solid var(--line,#e0e7dd);border-radius:14px; }.form-board table { border-collapse:collapse;width:100%;font-size:12px;text-align:left; }.form-board th,.form-board td { padding:15px 18px;border-bottom:1px solid var(--line,#e0e7dd);white-space:nowrap;max-width:260px;overflow:hidden;text-overflow:ellipsis; }.form-board thead { background:var(--surface-sunk,#f2f5ef); }.form-board th { font-weight:500; }.form-board tbody th { font-weight:600; }.sticky { position:sticky;right:0;background:var(--surface,#fff);box-shadow:-8px 0 12px #26372a04; }.row-actions { display:flex;gap:12px;max-width:none !important; }.time { color:var(--text-3,#7b8a7e);font-size:11px; }.mine { display:inline-block;margin-left:8px;padding:2px 5px;border-radius:4px;background:#e5f2e5;color:#477457;font-size:10px; }.empty { text-align:center;padding:40px !important;color:var(--text-3,#7b8a7e); }.pagination { justify-content:flex-end;font-size:12px; }.statistics { padding:24px;display:grid;gap:20px; }.statistics header { justify-content:space-between; }.statistics h3 { font-size:18px; }.statistics header>span { font-size:11px;color:var(--text-3,#7b8a7e); }.metrics { display:grid;grid-template-columns:repeat(3,1fr);gap:16px; }.metrics article { padding:20px;background:var(--surface-sunk,#f3f6f0);border-radius:12px;display:grid;gap:10px; }.metrics strong { font-size:30px;font-weight:500; }.metrics span { font-size:12px;color:var(--text-2,#6a786e); }.group-label { display:flex;gap:14px;align-items:center;font-size:13px; }.group-label select { width:220px; }.groups { list-style:none;padding:0;display:grid;gap:12px;max-height:300px;overflow:auto; }.groups li { display:grid;grid-template-columns:minmax(100px,1fr) 3fr 40px;gap:15px;align-items:center;font-size:12px; }.groups li>span { overflow:hidden;text-overflow:ellipsis;white-space:nowrap; }.bar { height:8px;border-radius:4px;background:#edf2e9; }.bar i { display:block;height:100%;border-radius:4px;background:#82ad8a; }.form-dialog { padding:28px;width:min(760px,calc(100vw - 28px));max-height:90vh;overflow:auto;border:1px solid var(--line,#e0e7dd);border-radius:18px;background:var(--surface,#fff);color:var(--text,#263c2c);box-shadow:0 30px 80px #142d2333; }.form-dialog::backdrop { background:#1b322c66; }.form-dialog header { display:flex;justify-content:space-between;gap:20px;align-items:center;margin-bottom:26px; }.form-dialog h3 { font-size:20px;line-height:1.5; }.close { border:0;background:none;color:inherit;font-size:26px;cursor:pointer; }.form-dialog footer { display:flex;justify-content:flex-end;gap:12px;margin-top:24px; }.conflict { background:#fff3df;border-radius:10px;padding:16px;font-size:12px;color:#8a5d25;margin-top:18px;line-height:1.8; }.conflict dl { display:grid;grid-template-columns:1fr 2fr;gap:8px;margin-top:12px; }.values { display:grid;grid-template-columns:140px 1fr;gap:14px;font-size:13px; }.values dt { color:var(--text-3,#7b8a7e); }.values dd { overflow-wrap:anywhere;white-space:pre-wrap; }.detail-dialog h4 { font-size:15px;margin:24px 0 14px;border-top:1px solid var(--line,#e0e7dd);padding-top:22px; }.file-list { list-style:none;padding:0; }.file-list li { display:flex;justify-content:space-between;gap:16px;font-size:13px;padding:10px 0; }.file-list small { display:block;font-size:11px;color:var(--text-3,#7b8a7e);margin-top:6px; }.attachment-add .select { max-width:260px; }.comment { background:var(--surface-sunk,#f3f6f0);padding:15px;border-radius:10px;margin-bottom:12px; }.comment header { margin-bottom:8px;font-size:12px; }.comment time { color:var(--text-3,#7b8a7e);font-size:10px; }.comment p { font-size:13px;white-space:pre-wrap;line-height:1.8; }.comment-form { display:grid;gap:10px; }.comment-form textarea { padding:12px;border:1px solid var(--line,#e0e7dd);border-radius:10px;background:var(--surface,#fff);color:inherit; }.comment-form button { justify-self:end; }.history-event { font-size:11px;color:var(--text-2,#6a786e);line-height:1.8;margin-bottom:10px; }.history-event p { white-space:pre-wrap;overflow-wrap:anywhere;margin:6px 0 0 14px; }@media(max-width:560px) { .toolbar { gap:8px; }.values { grid-template-columns:100px 1fr; }.metrics article { padding:14px; }.metrics strong { font-size:24px; }.statistics,.form-dialog { padding:20px; } }
</style>
