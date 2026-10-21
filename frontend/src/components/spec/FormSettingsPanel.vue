<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { formApi, randomFormKey, type FormConfig, type FieldType } from '@/api/forms'
import { useToast } from '@/composables/useToast'
const props = defineProps<{ projectId: string }>()
const emit = defineEmits<{ saved: [config: FormConfig] }>()
const api = formApi({ projectId: props.projectId }), toast = useToast()
const config = ref<FormConfig | null>(null), busy = ref(false), error = ref('')
const members = ref<Awaited<ReturnType<typeof api.members>>>([])
const memberName = ref(''), role = ref<'EDITOR' | 'VIEWER'>('EDITOR')
const existingRows = ref(false), originalKeys = ref<Set<string>>(new Set())
const optionsText = ref<Record<string, string>>({})
const types: { value: FieldType; label: string }[] = [
  { value: 'TEXT', label: '短文本' }, { value: 'LONG_TEXT', label: '长文本' }, { value: 'NUMBER', label: '数字' },
  { value: 'SELECT', label: '单选' }, { value: 'DATE', label: '日期' }, { value: 'CHECKBOX', label: '勾选' },
]
async function load() {
  try {
    const [c, m, board] = await Promise.all([api.settings(), api.members(), api.board()])
    config.value = c; members.value = m; existingRows.value = board.total > 0
    originalKeys.value = new Set(c.fields.map(f => f.key)); emit('saved', c); error.value = ''
    optionsText.value = {}
  } catch (e) { error.value = (e as Error).message }
}
onMounted(load)
function preset(value: string) {
  if (!config.value) return
  config.value.viewMode = value === 'ALL' ? 'ALL' : 'OWN'
  config.value.editMode = value === 'DESIGNATED' ? 'DESIGNATED' : value === 'LOCKED' ? 'LOCKED' : value === 'ALL' ? 'ALL' : 'OWN'
}
function add() {
  if (config.value && config.value.fields.length < 20) config.value.fields.push({ key: `f_${randomFormKey(5)}`, label: '', type: 'TEXT', required: false })
}
function examples() {
  if (!config.value || config.value.fields.length) return
  config.value.fields = [
    { key: 'department', label: '部门 / 小组', type: 'TEXT', required: true },
    { key: 'quantity', label: '数量', type: 'NUMBER', required: true },
    { key: 'category', label: '类别', type: 'SELECT', required: true, options: ['资料', '作品', '其他'] },
    { key: 'note', label: '补充说明', type: 'LONG_TEXT', required: false },
  ]
}
async function save() {
  if (!config.value) return
  busy.value = true; error.value = ''
  try { config.value = await api.save(config.value); originalKeys.value = new Set(config.value.fields.map(f => f.key)); emit('saved', config.value); toast.ok('收集字段和权限已保存') }
  catch (e) { error.value = (e as Error).message }
  finally { busy.value = false }
}
async function member(username: string, value: 'EDITOR' | 'VIEWER' | null) {
  busy.value = true
  try { await api.member(username, value); members.value = await api.members(); memberName.value = ''; toast.ok('成员权限已更新') }
  catch (e) { error.value = (e as Error).message }
  finally { busy.value = false }
}
</script>

<template>
  <section class="card form-settings">
    <header><div><span class="eyebrow">COLLECT TOGETHER</span><h2>在线收集表与协作权限</h2><p>设置要填写的信息，分享后自动形成汇总表。材料规范中的文件可作为每条记录的附件提交。</p></div></header>
    <fieldset v-if="config" :disabled="busy" class="settings-content">
      <div class="presets"><button class="btn btn--sm btn--ghost" @click="preset('OWN')">每人仅看自己</button><button class="btn btn--sm btn--ghost" @click="preset('ALL')">所有人协作编辑</button><button class="btn btn--sm btn--ghost" @click="preset('DESIGNATED')">指定成员编辑</button><button class="btn btn--sm btn--ghost" @click="preset('LOCKED')">提交后仅负责人修改</button></div>
      <div class="permission-grid">
        <label>普通参与者可以查看<select v-model="config.viewMode" class="select"><option value="OWN">仅自己的记录和留言</option><option value="ALL">全部记录和留言</option></select></label>
        <label>允许编辑的范围<select v-model="config.editMode" class="select"><option value="OWN">各自修改自己的记录</option><option value="ALL">所有参与者修改全部记录</option><option value="DESIGNATED">仅负责人和指定编辑成员</option><option value="LOCKED">仅负责人修改</option></select></label>
      </div>
      <p class="note">指定成员按登录账号授权，可以查看全表；编辑成员可以修改全表，只读成员不能修改。选择「仅负责人修改」后，指定成员也不能编辑。个人记录的身份依据登录账号或个人凭证确认。</p>
      <div class="toggles"><label><input v-model="config.accepting" type="checkbox" /> 开放填写与修改</label><label><input v-model="config.allowMultiple" type="checkbox" /> 每人可填写多条记录</label><label><input v-model="config.allowComments" type="checkbox" /> 允许记录下留言</label></div>
      <div class="section-heading"><h3>收集字段 <small>{{ config.fields.length }} / 20</small></h3><button v-if="!config.fields.length" class="btn btn--sm btn--ghost" @click="examples">使用示例字段</button><button class="btn btn--sm" :disabled="config.fields.length >= 20" @click="add">＋ 添加字段</button></div>
      <p v-if="!config.fields.length" class="note">添加字段后，链接将显示在线收集表；不设置字段时继续使用原有的文件收集页。</p>
      <div v-for="(field, index) in config.fields" :key="field.key" class="field-row">
        <span class="field-index">{{ String(index + 1).padStart(2, '0') }}</span><input v-model="field.label" class="input" maxlength="40" placeholder="字段名称，例如部门、数量" :aria-label="`第 ${index + 1} 个字段名称`" />
        <select v-model="field.type" class="select" :disabled="existingRows && originalKeys.has(field.key)" aria-label="字段类型"><option v-for="type in types" :key="type.value" :value="type.value">{{ type.label }}</option></select>
        <label class="required"><input v-model="field.required" type="checkbox" /> 必填</label>
        <button class="btn btn--sm btn--ghost" :disabled="existingRows && originalKeys.has(field.key)" aria-label="移除字段" @click="config.fields.splice(index, 1)">移除</button>
        <label v-if="field.type === 'SELECT'" class="options">可选项（每行一个）<textarea :value="optionsText[field.key] ?? field.options?.join('\n') ?? ''" rows="3" @input="optionsText[field.key] = ($event.target as HTMLTextAreaElement).value; field.options = optionsText[field.key].split('\n').filter(s => s.trim()).map(s => s.trim())" /></label>
      </div>
      <p v-if="existingRows" class="note">已有记录的字段保留原类型与标识，避免历史数据丢失；可以改名称、增加字段或调整要求。</p>
      <div class="actions"><button class="btn" :disabled="busy" @click="save">{{ busy ? '保存中…' : '保存字段与权限' }}</button><button class="btn btn--ghost" :disabled="busy" @click="load">重新加载设置</button></div>
      <div class="members"><h3>指定成员</h3><p class="note">填写对方在本站的登录账号，再把收集链接发给对方。移除成员后，后续请求立即按普通参与者权限处理。</p>
        <div class="member-add"><input v-model="memberName" class="input" placeholder="成员登录账号" aria-label="成员登录账号" /><select v-model="role" class="select" aria-label="成员角色"><option value="EDITOR">可编辑全表</option><option value="VIEWER">全表只读</option></select><button class="btn btn--sm" :disabled="busy || !memberName.trim()" @click="member(memberName.trim(), role)">添加 / 更新</button></div>
        <ul><li v-for="m in members" :key="m.username"><span><strong>{{ m.displayName }}</strong> · {{ m.username }}</span><span>{{ m.role === 'EDITOR' ? '编辑成员' : '只读成员' }}</span><button class="btn btn--sm btn--ghost" :disabled="busy" @click="member(m.username, null)">移除授权</button></li></ul>
      </div>
    </fieldset>
    <p v-if="error" class="err" role="alert">{{ error }}</p>
  </section>
</template>

<style scoped>
.settings-content { border:0;padding:0;display:grid;gap:20px;min-width:0; }
.form-settings { padding:28px;display:grid;gap:20px; }h2 { font-size:24px;margin:10px 0; }h3 { font-size:16px; }.eyebrow { color:var(--mint-ink);font-size:11px;letter-spacing:.15em; }header p,.note { font-size:13px;color:var(--text-2);line-height:1.8; }.presets,.toggles,.actions,.section-heading,.member-add { display:flex;gap:12px;align-items:center;flex-wrap:wrap; }.permission-grid { display:grid;grid-template-columns:1fr 1fr;gap:20px; }.permission-grid label { display:grid;gap:10px;font-size:13px; }.toggles { font-size:13px;gap:22px; }.toggles label,.required { display:flex;gap:7px;align-items:center; }.section-heading h3 { flex:1; }.section-heading small { color:var(--text-3);font-size:12px; }.field-row { display:grid;grid-template-columns:24px minmax(100px,1fr) 120px 64px 64px;gap:12px;align-items:center;padding:14px;border:1px solid var(--line);border-radius:12px; }.field-index { font-size:11px;color:var(--text-3); }.required { font-size:12px; }.options { grid-column:2/-1;font-size:12px;display:grid;gap:8px; }.options textarea { border:1px solid var(--line);border-radius:8px;padding:10px;background:var(--surface);color:var(--text); }.members { display:grid;gap:12px;border-top:1px solid var(--line);padding-top:24px; }.member-add .input { flex:1;min-width:160px; }.member-add .select { width:160px; }ul { list-style:none;padding:0; }li { display:flex;gap:14px;align-items:center;padding:10px 0;font-size:13px;border-bottom:1px solid var(--line); }li>span:first-child { flex:1; }.err { color:var(--danger);font-size:13px;line-height:1.7; }@media(max-width:650px) { .form-settings { padding:20px; }.permission-grid { grid-template-columns:1fr; }.field-row { grid-template-columns:20px 1fr 90px; }.required { grid-column:2; }.options { grid-column:1/-1; } }
</style>
