<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { RouterLink, useRoute, useRouter } from 'vue-router'
import { ApiError } from '@/api/http'
import {
  collaborationApi as api, dateTime, isAccessError, projectEvents, publicationLabel, roleLabel,
  type Activity, type Project, type ProjectMember, type PublicationStatus,
} from '@/api/collaboration'
import { useToast } from '@/composables/useToast'
import TeamMembersPanel from '@/components/collaboration/TeamMembersPanel.vue'
import TeamDocumentsPanel from '@/components/collaboration/TeamDocumentsPanel.vue'
import TeamFilesPanel from '@/components/collaboration/TeamFilesPanel.vue'
import TeamTasksPanel from '@/components/collaboration/TeamTasksPanel.vue'
import TeamReviewPanel from '@/components/collaboration/TeamReviewPanel.vue'
import '@/components/collaboration/team.css'

const route = useRoute(), router = useRouter(), toast = useToast()
const projectId = computed(() => typeof route.params.id === 'string' ? route.params.id : '')
const projects = ref<Project[]>([]), listTotal = ref(0), listPage = ref(0), loading = ref(true), error = ref(''), busy = ref(false)
const newName = ref(''), newDescription = ref(''), createOpen = ref(false)
const project = ref<Project>(), members = ref<ProjectMember[]>([]), revision = ref(0)
const activities = ref<Activity[]>([]), activityTotal = ref(0), activityPage = ref(0), activityError = ref(''), activityLoading = ref(false)
const tabs = [{ id: 'documents', label: '腾讯文档' }, { id: 'files', label: '共享文件' }, { id: 'tasks', label: '待办与交付' }, { id: 'checks', label: '检查与 AI 整理' }, { id: 'members', label: '项目成员' }, { id: 'activity', label: '活动记录' }]
const tab = ref('documents')
const owner = computed(() => project.value?.role === 'OWNER')
const canEdit = computed(() => owner.value || project.value?.role === 'EDITOR')
const canReview = computed(() => owner.value || project.value?.role === 'REVIEWER')
const connection = ref<'connecting' | 'live' | 'retrying' | 'closed'>('closed')
const connectionLabels = { connecting: '正在连接更新', live: '实时更新已连接', retrying: '网络断开，正在重连', closed: '更新连接已关闭' }
const editing = ref(false), editName = ref(''), editDescription = ref(''), editVersion = ref(0), editConflict = ref(false)
let request = 0, activityRequest = 0
let stream: ReturnType<typeof projectEvents> | undefined
let refreshTimer: ReturnType<typeof setTimeout> | undefined
let disposed = false

async function loadList() {
  const seq = ++request; loading.value = true; error.value = ''
  try { const result = await api.list(listPage.value); if (seq === request) { projects.value = result.items; listTotal.value = result.total } }
  catch (e) { if (seq === request) error.value = (e as Error).message }
  finally { if (seq === request) loading.value = false }
}
function deny() {
  request++; activityRequest++; stream?.close(); clearTimeout(refreshTimer)
  project.value = undefined; members.value = []; activities.value = []; loading.value = false
  error.value = '当前账号已无权访问此项目，或项目已被移除。请联系负责人。'
}
async function loadProject(initial = false) {
  const id = projectId.value
  if (!id || disposed) return
  const seq = ++request
  if (initial) loading.value = true
  try {
    const [result, memberResult] = await Promise.all([api.project(id), api.members(id)])
    if (seq !== request || disposed) return
    project.value = result; members.value = memberResult; revision.value++; error.value = ''
    if (initial) connect(id)
    if (tab.value === 'activity') void loadActivities()
  } catch (e) {
    if (seq !== request || disposed) return
    if (isAccessError(e)) deny()
    else error.value = (e as Error).message
  } finally { if (seq === request) loading.value = false }
}
function scheduleRefresh() {
  clearTimeout(refreshTimer)
  refreshTimer = setTimeout(() => { void loadProject() }, 300)
}
function connect(id: string) {
  stream?.close()
  stream = projectEvents(id, {
    onChange: scheduleRefresh,
    onStatus: value => { if (projectId.value === id && !disposed) connection.value = value },
    onPresence: ids => { if (projectId.value === id) members.value = members.value.map(m => ({ ...m, online: ids.includes(m.userId) })) },
    onDenied: deny,
  })
}
watch(projectId, () => {
  request++; activityRequest++; stream?.close(); clearTimeout(refreshTimer)
  project.value = undefined; members.value = []; activities.value = []; error.value = ''; editing.value = false; tab.value = 'documents'
  if (projectId.value) void loadProject(true)
  else void loadList()
}, { immediate: true })
watch(listPage, () => { if (!projectId.value) void loadList() })
watch(tab, value => { if (value === 'activity') void loadActivities() })
watch(activityPage, () => { void loadActivities() })
watch(owner, value => { if (!value) editing.value = false })

async function create() {
  if (!newName.value.trim()) return
  busy.value = true; error.value = ''
  try { const result = await api.create(newName.value.trim(), newDescription.value.trim()); newName.value = ''; newDescription.value = ''; createOpen.value = false; await router.push(`/workspace/team/${result.id}`) }
  catch (e) { error.value = (e as Error).message }
  finally { busy.value = false }
}
function editProject() {
  if (!project.value) return
  editName.value = project.value.name; editDescription.value = project.value.description ?? ''; editVersion.value = project.value.version
  editConflict.value = false; editing.value = true; error.value = ''
}
async function saveProject() {
  if (!project.value) return
  busy.value = true; error.value = ''; editConflict.value = false
  try { await api.update(projectId.value, { name: editName.value.trim(), description: editDescription.value.trim(), version: editVersion.value }); editing.value = false; await loadProject() }
  catch (e) {
    error.value = (e as Error).message
    if (e instanceof ApiError && e.status === 409) { editConflict.value = true; await loadProject(); error.value = '项目信息已被其他操作修改。你的草稿已保留，请载入最新信息后重新编辑。' }
    if (isAccessError(e)) deny()
  } finally { busy.value = false }
}
async function publish(status: PublicationStatus) {
  if (!project.value) return
  busy.value = true; error.value = ''
  try { await api.publish(projectId.value, status, project.value.version); await loadProject(); toast.ok(status === 'PUBLISHED' ? '项目已发布' : status === 'REVIEW' ? '项目已进入审核' : '项目已回到准备状态') }
  catch (e) { error.value = (e as Error).message; if (isAccessError(e)) deny(); else if (e instanceof ApiError && e.status === 409) scheduleRefresh() }
  finally { busy.value = false }
}
async function leaveOrDelete() {
  if (!project.value) return
  const message = owner.value ? `确定删除“${project.value.name}”？这将移除该项目的协作记录、关联和资料管理记录。` : `确定退出“${project.value.name}”？退出后将无法访问项目资料。`
  if (!window.confirm(message)) return
  busy.value = true; error.value = ''
  try { if (owner.value) await api.remove(projectId.value); else await api.leave(projectId.value); stream?.close(); await router.push('/workspace/team') }
  catch (e) { error.value = (e as Error).message; if (isAccessError(e)) deny() }
  finally { busy.value = false }
}
async function loadActivities() {
  if (!projectId.value || !project.value) return
  const seq = ++activityRequest; activityLoading.value = true; activityError.value = ''
  try { const result = await api.activities(projectId.value, activityPage.value); if (seq === activityRequest) { activities.value = result.items; activityTotal.value = result.total } }
  catch (e) { if (seq === activityRequest) { activityError.value = (e as Error).message; if (isAccessError(e)) deny() } }
  finally { if (seq === activityRequest) activityLoading.value = false }
}
const actionLabels: Record<string, string> = {
  PROJECT_CREATED: '创建项目', PROJECT_UPDATED: '更新项目信息', MEMBER_JOINED: '加入项目', MEMBER_ADDED: '邀请成员加入', MEMBER_REMOVED: '移除成员', MEMBER_ROLE_CHANGED: '变更成员角色', MEMBER_LEFT: '退出项目',
  DOCUMENT_LINKED: '关联腾讯文档', DOCUMENT_UPDATED: '更新文档关联', DOCUMENT_UNLINKED: '解除文档关联', DOCUMENT_IMPORTED: '导入文档版本', FILE_UPLOADED: '上传文件', FILE_VERSION_CREATED: '上传新版本', FILE_ARCHIVED: '归档文件', FILE_RESTORED: '恢复文件', FILE_REVIEWED: '审核文件',
  TODO_CREATED: '创建待办', TODO_UPDATED: '更新待办', TODO_DELETED: '删除待办', PUBLICATION_CHANGED: '更新发布状态', DELIVERY_CREATED: '创建交付任务', TASK_UPDATED: '更新处理任务',
}
function actionLabel(action: string) { return actionLabels[action.toUpperCase().replaceAll('.', '_')] || action }
onBeforeUnmount(() => { disposed = true; request++; activityRequest++; stream?.close(); clearTimeout(refreshTimer) })
</script>

<template>
  <main id="main" class="workspace-page team-page">
    <template v-if="!projectId">
      <header class="team-page-heading"><div><p class="team-eyebrow">LUMEN · 团队空间</p><h1>团队协作</h1><p>一起收集资料、协作文档、检查版本，将项目推进到审核与交付。</p></div><button class="btn" @click="createOpen = !createOpen">{{ createOpen ? '收起创建' : '新建协作项目' }}</button></header>
      <form v-if="createOpen" class="team-card team-form" @submit.prevent="create"><h2>创建项目</h2><label>项目名称<input v-model="newName" class="input" required maxlength="80" placeholder="例如：秋季创作大赛 · 材料准备" :disabled="busy" /></label><label>项目说明<textarea v-model="newDescription" class="input" rows="3" maxlength="2000" placeholder="说明目标、提交要求或团队分工" :disabled="busy" /></label><div class="team-actions"><button class="btn" :disabled="busy || !newName.trim()">{{ busy ? '创建中…' : '创建项目' }}</button></div></form>
      <p v-if="error" class="team-error" role="alert">{{ error }} <button class="team-text-button" @click="loadList">重试</button></p>
      <p v-if="loading && !projects.length" class="team-card team-empty" role="status">正在加载你的项目…</p>
      <section v-else-if="projects.length" class="team-project-grid" :aria-busy="loading"><RouterLink v-for="item in projects" :key="item.id" :to="`/workspace/team/${item.id}`" class="team-project-card"><div class="team-inline"><span class="team-tag">{{ roleLabel[item.role] }}</span><span class="team-tag" :class="{ success: item.status === 'PUBLISHED' }">{{ publicationLabel[item.status] }}</span></div><h2>{{ item.name }}</h2><p>{{ item.description || '尚未填写项目说明' }}</p><footer><span>{{ dateTime(item.updatedAt) }}</span><span>进入项目 →</span></footer></RouterLink></section>
      <section v-else-if="!error" class="team-card team-empty"><h2>从一个项目开始</h2><p>创建项目，邀请成员，再关联腾讯文档或上传资料。已有材料规范官项目也会出现在这里。</p><button class="btn" @click="createOpen = true">创建第一个项目</button></section>
      <nav v-if="listTotal > 30" class="team-pagination" aria-label="项目分页"><button class="btn btn--ghost" :disabled="!listPage || loading" @click="listPage--">上一页</button><span>第 {{ listPage + 1 }} 页 · {{ listTotal }} 个项目</span><button class="btn btn--ghost" :disabled="(listPage + 1) * 30 >= listTotal || loading" @click="listPage++">下一页</button></nav>
    </template>
    <template v-else>
      <div class="team-inline"><RouterLink to="/workspace/team" class="team-text-button">← 所有项目</RouterLink><span class="team-grow" /><span v-if="project" class="team-connection" :class="connection"><span class="team-presence" :class="{ online: connection === 'live' }" />{{ connectionLabels[connection] }}</span></div>
      <p v-if="error" class="team-error" role="alert">{{ error }} <button v-if="!editing" class="team-text-button" @click="loadProject(!project)">重新加载</button></p>
      <p v-if="loading && !project" class="team-card team-empty" role="status">正在加载项目与成员权限…</p>
      <template v-if="project">
        <header class="team-page-heading"><div><div class="team-inline"><span class="team-tag">{{ roleLabel[project.role] }}</span><span class="team-tag" :class="{ success: project.status === 'PUBLISHED' }">{{ publicationLabel[project.status] }}</span><span class="team-muted">v{{ project.version }}</span></div><h1>{{ project.name }}</h1><p class="team-description">{{ project.description || '负责人还没有填写项目说明。' }}</p></div><div class="team-actions"><button v-if="owner" class="btn btn--ghost" :disabled="busy" @click="editProject">项目信息</button><RouterLink v-if="owner" :to="`/workspace/specs/${project.id}`" class="btn btn--ghost">材料收集与规范</RouterLink><button class="team-text-button danger" :disabled="busy" @click="leaveOrDelete">{{ owner ? '删除项目' : '退出项目' }}</button></div></header>
        <form v-if="editing && owner" class="team-card team-form" @submit.prevent="saveProject"><h2>编辑项目信息</h2><label>名称<input v-model="editName" class="input" required maxlength="80" :disabled="busy" /></label><label>说明<textarea v-model="editDescription" class="input" rows="3" maxlength="2000" :disabled="busy" /></label><p class="team-footnote">编辑基于版本 {{ editVersion }}，保存时会检查是否有其他修改。</p><div class="team-actions"><button class="btn" :disabled="busy || editConflict || !editName.trim()">保存信息</button><button v-if="editConflict" type="button" class="btn btn--ghost" @click="editProject">载入最新信息</button><button type="button" class="team-text-button" :disabled="busy" @click="editing = false">取消</button></div></form>
        <section class="team-project-summary"><div><strong>{{ members.length }}</strong><span>项目成员</span></div><div><strong>{{ members.filter(m => m.online).length }}</strong><span>Lumen 当前在线</span></div><div class="team-summary-date"><span>最近更新</span><strong>{{ dateTime(project.updatedAt) }}</strong></div><div v-if="owner" class="team-actions team-publication"><button v-if="project.status !== 'DRAFT'" class="btn btn--ghost" :disabled="busy" @click="publish('DRAFT')">回到准备</button><button v-if="project.status !== 'REVIEW'" class="btn btn--ghost" :disabled="busy" @click="publish('REVIEW')">提交审核</button><button v-if="project.status !== 'PUBLISHED'" class="btn" :disabled="busy" @click="publish('PUBLISHED')">发布项目</button></div></section>
        <nav class="team-tabs" aria-label="团队项目功能"><button v-for="item in tabs" :key="item.id" :class="{ active: tab === item.id }" :aria-current="tab === item.id ? 'page' : undefined" @click="tab = item.id">{{ item.label }}</button></nav>
        <TeamDocumentsPanel v-if="tab === 'documents'" :key="project.id" :project-id="project.id" :can-edit="canEdit" :owner="owner" :members="members" :revision="revision" @changed="scheduleRefresh" @denied="loadProject()" />
        <TeamFilesPanel v-else-if="tab === 'files'" :key="project.id" :project-id="project.id" :can-edit="canEdit" :can-review="canReview" :revision="revision" @changed="scheduleRefresh" @denied="loadProject()" @open-tasks="tab = 'tasks'" />
        <TeamTasksPanel v-else-if="tab === 'tasks'" :key="project.id" :project-id="project.id" :can-edit="canEdit" :owner="owner" :members="members" :revision="revision" @changed="scheduleRefresh" @denied="loadProject()" />
        <TeamReviewPanel v-else-if="tab === 'checks'" :key="project.id" :project-id="project.id" :revision="revision" @denied="loadProject()" />
        <TeamMembersPanel v-else-if="tab === 'members'" :key="project.id" :project-id="project.id" :owner="owner" :members="members" @changed="scheduleRefresh" @denied="loadProject()" />
        <section v-else-if="tab === 'activity'" class="team-card"><header class="team-section-head"><div><h2>项目活动</h2><p>实际发生的成员、文档、文件、待办与审核操作。</p></div><button class="btn btn--ghost" :disabled="activityLoading" @click="loadActivities">刷新</button></header><p v-if="activityError" class="team-error" role="alert">{{ activityError }}</p><p v-if="activityLoading && !activities.length" class="team-empty" role="status">正在加载活动…</p><p v-else-if="!activities.length && !activityError" class="team-empty">还没有活动记录。</p><ol class="team-activity-list"><li v-for="activity in activities" :key="activity.id"><span class="team-activity-dot" /><div><p><strong>{{ activity.actorName }}</strong> · {{ actionLabel(activity.action) }}</p><time class="team-muted">{{ dateTime(activity.createdAt) }}</time></div></li></ol><nav v-if="activityTotal > 30" class="team-pagination" aria-label="活动分页"><button class="btn btn--ghost" :disabled="!activityPage || activityLoading" @click="activityPage--">上一页</button><span>第 {{ activityPage + 1 }} 页 · {{ activityTotal }} 条</span><button class="btn btn--ghost" :disabled="(activityPage + 1) * 30 >= activityTotal || activityLoading" @click="activityPage++">下一页</button></nav></section>
        <p class="team-footnote">项目角色控制 Lumen 中的操作。腾讯文档的账号、许可及编辑分享权限由腾讯平台独立管理；“在线”仅表示当前连接到此 Lumen 项目。</p>
      </template>
    </template>
  </main>
</template>
