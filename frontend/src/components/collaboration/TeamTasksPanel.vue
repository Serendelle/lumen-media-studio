<script setup lang="ts">
import { onBeforeUnmount, ref, watch } from 'vue'
import { collaborationApi as api, dateTime, downloadBlob, isAccessError, type ProjectMember, type ProjectTask, type ProjectTodo } from '@/api/collaboration'

const props = defineProps<{ projectId: string; canEdit: boolean; owner: boolean; members: ProjectMember[]; revision: number }>()
const emit = defineEmits<{ changed: []; denied: [] }>()
const todos = ref<ProjectTodo[]>([]), tasks = ref<ProjectTask[]>([]), title = ref(''), assignee = ref<number | null>(null)
const loading = ref(true), busy = ref(false), error = ref(''), deliveryKey = ref<string>()
const todoLabels = { OPEN: '待处理', DOING: '进行中', DONE: '已完成' }
const taskLabels: Record<string, string> = { QUEUED: '排队中', RUNNING: '处理中', SUCCEEDED: '已完成', FAILED: '失败', CANCELLED: '已取消', OUTPUT_UNAVAILABLE: '结果未登记' }
let request = 0
let refreshTimer: ReturnType<typeof setTimeout> | undefined
async function load() {
  const seq = ++request; loading.value = true; error.value = ''; clearTimeout(refreshTimer)
  try {
    const [todoResult, taskResult] = await Promise.all([api.todos(props.projectId), api.tasks(props.projectId)])
    if (seq === request) {
      todos.value = todoResult; tasks.value = taskResult
      if (taskResult.some(t => ['QUEUED', 'RUNNING'].includes(t.status))) refreshTimer = setTimeout(() => { void load() }, 8000)
    }
  } catch (e) { if (seq === request) { error.value = (e as Error).message; if (isAccessError(e)) { todos.value = []; tasks.value = []; emit('denied') } } }
  finally { if (seq === request) loading.value = false }
}
watch(() => [props.projectId, props.revision], () => { void load() }, { immediate: true })
async function change(action: () => Promise<unknown>) {
  busy.value = true; error.value = ''
  try { await action(); await load(); emit('changed') }
  catch (e) { error.value = (e as Error).message; if (isAccessError(e)) emit('denied') }
  finally { busy.value = false }
}
async function addTodo() { if (title.value.trim()) await change(async () => { await api.addTodo(props.projectId, title.value.trim(), assignee.value); title.value = '' }) }
async function deliver() {
  deliveryKey.value ??= crypto.randomUUID()
  await change(async () => { await api.delivery(props.projectId, deliveryKey.value!); deliveryKey.value = undefined })
}
async function downloadTask(task: ProjectTask) {
  if (!task.outputVersionId) return
  await change(async () => downloadBlob(await api.content(props.projectId, task.outputVersionId!), task.outputFilename || `项目结果-${task.id.slice(0, 8)}`))
}
function assigneeName(userId: number | null) { const member = props.members.find(m => m.userId === userId); return member ? member.displayName || member.username : userId ? '已离开的成员' : '未指定' }
function setTodoStatus(todo: ProjectTodo, event: Event) {
  const status = (event.target as HTMLSelectElement).value as ProjectTodo['status']
  void change(() => api.updateTodo(props.projectId, todo, status))
}
function removeTodo(todo: ProjectTodo) { void change(() => api.removeTodo(props.projectId, todo.id)) }
onBeforeUnmount(() => { request++; clearTimeout(refreshTimer) })
</script>

<template>
  <section class="team-card">
    <header class="team-section-head"><div><h2>项目待办</h2><p>分工、跟进与完成状态对项目成员同步。</p></div><span class="team-tag">{{ todos.filter(t => t.status === 'DONE').length }} / {{ todos.length }} 已完成</span></header>
    <form v-if="canEdit" class="team-inline team-form" @submit.prevent="addTodo"><label class="team-grow">新增待办<input v-model="title" class="input" maxlength="200" required placeholder="例如：补充活动预算表，并提交审核" :disabled="busy" /></label><label>负责人<select v-model="assignee" class="select" :disabled="busy"><option :value="null">未指定</option><option v-for="member in members" :key="member.userId" :value="member.userId">{{ member.displayName || member.username }}</option></select></label><button class="btn" :disabled="busy || !title.trim()">添加待办</button></form>
    <p v-if="error" class="team-error" role="alert">{{ error }}</p>
    <p v-if="loading && !todos.length" class="team-empty" role="status">正在加载待办与任务…</p><p v-else-if="!todos.length && !error" class="team-empty">还没有待办。{{ canEdit ? '将需要补充或复核的内容分配给项目成员。' : '' }}</p>
    <ul class="team-todo-list"><li v-for="todo in todos" :key="todo.id"><div class="team-grow"><strong :class="{ completed: todo.status === 'DONE' }">{{ todo.title }}</strong><small class="team-muted">{{ assigneeName(todo.assigneeId) }} · {{ dateTime(todo.updatedAt) }}</small></div><div class="team-actions"><select v-if="canEdit" class="select" :value="todo.status" :aria-label="`${todo.title}的状态`" :disabled="busy" @change="setTodoStatus(todo, $event)"><option value="OPEN">待处理</option><option value="DOING">进行中</option><option value="DONE">已完成</option></select><span v-else class="team-tag">{{ todoLabels[todo.status] }}</span><button v-if="canEdit" class="team-text-button danger" :disabled="busy" @click="removeTodo(todo)">删除</button></div></li></ul>
  </section>
  <section class="team-card">
    <header class="team-section-head"><div><h2>处理任务与交付</h2><p>这里显示由本项目发起的真实处理任务。交付包使用最新已审核文件，并保留源文件。</p></div><div class="team-actions"><button class="btn btn--ghost" :disabled="loading" @click="load">刷新任务</button><button v-if="owner" class="btn" :disabled="busy" @click="deliver">{{ busy ? '提交中…' : '生成交付包' }}</button></div></header>
    <p class="team-footnote">审核和发布由负责人控制。交付生成在后台执行，完成后可下载；不满足要求时会显示服务器返回的原因。</p>
    <p v-if="!tasks.length && !loading && !error" class="team-empty">还没有项目处理任务。</p>
    <ul class="team-task-list"><li v-for="task in tasks" :key="task.id"><div class="team-grow"><div class="team-inline"><strong>{{ task.type === 'project.deliver' || task.type === 'project.delivery' ? '项目交付打包' : task.type }}</strong><span class="team-tag" :class="{ success: task.status === 'SUCCEEDED', warning: task.status === 'FAILED' }">{{ taskLabels[task.status] || task.status }}</span></div><p class="team-muted">{{ task.message || '等待任务更新' }} · {{ dateTime(task.createdAt) }}</p><progress v-if="['QUEUED', 'RUNNING'].includes(task.status)" :value="task.progress" max="100" :aria-label="`任务进度${task.progress}%`" /><p v-if="task.errorCode" class="team-error">{{ task.errorCode }}</p></div><button v-if="task.outputVersionId && task.status === 'SUCCEEDED'" class="btn" :disabled="busy" @click="downloadTask(task)">下载结果</button></li></ul>
  </section>
</template>
