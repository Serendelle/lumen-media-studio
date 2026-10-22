<script setup lang="ts">
import { ref } from 'vue'
import { collaborationApi as api, dateTime, isAccessError, roleLabel, type ProjectMember, type ProjectRole } from '@/api/collaboration'

const props = defineProps<{ projectId: string; owner: boolean; members: ProjectMember[] }>()
const emit = defineEmits<{ changed: []; denied: [] }>()
const username = ref('')
const role = ref<ProjectRole>('EDITOR')
const busy = ref(false)
const error = ref('')
const selectedRoles = ref<Record<number, ProjectRole>>({})
async function change(action: () => Promise<unknown>) {
  busy.value = true; error.value = ''
  try { await action(); emit('changed') }
  catch (e) { error.value = (e as Error).message; if (isAccessError(e)) emit('denied') }
  finally { busy.value = false }
}
async function invite() {
  if (!username.value.trim()) return
  await change(async () => { await api.invite(props.projectId, username.value.trim(), role.value); username.value = '' })
}
function remove(member: ProjectMember) {
  if (window.confirm(`移除 ${member.displayName || member.username}？移除后该账号不能再访问此项目。`)) void change(() => api.removeMember(props.projectId, member.userId))
}
function saveRole(member: ProjectMember) {
  const selected = selectedRoles.value[member.userId]
  if (selected) void change(() => api.setRole(props.projectId, member.userId, selected))
}
</script>

<template>
  <section class="team-card">
    <header class="team-section-head"><div><h2>项目成员</h2><p>负责人管理成员；编辑上传和维护资料；审核员确认文件；查看成员只读。</p></div><span class="team-tag">{{ members.length }} 人</span></header>
    <form v-if="owner" class="team-inline team-form" @submit.prevent="invite">
      <label class="team-grow">邀请已注册账号<input v-model="username" class="input" maxlength="100" placeholder="填写用户名" required :disabled="busy" /></label>
      <label>角色<select v-model="role" class="select" :disabled="busy"><option value="EDITOR">编辑</option><option value="REVIEWER">审核</option><option value="VIEWER">查看</option></select></label>
      <button class="btn" :disabled="busy || !username.trim()">{{ busy ? '提交中…' : '邀请加入' }}</button>
    </form>
    <p v-if="error" class="team-error" role="alert">{{ error }}</p>
    <div class="team-table-wrap"><table class="team-table"><thead><tr><th>成员</th><th>角色</th><th>加入时间</th><th v-if="owner">管理</th></tr></thead><tbody>
      <tr v-for="member in members" :key="member.userId">
        <td><span class="team-presence" :class="{ online: member.online }" :aria-label="member.online ? '在线' : '离线'" /> {{ member.displayName || member.username }}<small class="team-muted">@{{ member.username }}</small></td>
        <td v-if="owner && member.role !== 'OWNER'"><select v-model="selectedRoles[member.userId]" class="select" :disabled="busy" :aria-label="`${member.username}的角色`"><option :value="undefined">{{ roleLabel[member.role] }}</option><option value="EDITOR">编辑</option><option value="REVIEWER">审核</option><option value="VIEWER">查看</option></select></td>
        <td v-else>{{ roleLabel[member.role] }}</td>
        <td class="team-muted">{{ dateTime(member.joinedAt) }}</td>
        <td v-if="owner"><div v-if="member.role !== 'OWNER'" class="team-actions"><button class="btn btn--ghost" :disabled="busy || !selectedRoles[member.userId] || selectedRoles[member.userId] === member.role" @click="saveRole(member)">保存角色</button><button class="team-text-button danger" :disabled="busy" @click="remove(member)">移除</button></div><span v-else class="team-muted">项目负责人</span></td>
      </tr>
    </tbody></table></div>
    <p class="team-footnote">在线状态由当前项目的实际连接判断。关闭页面或断网不会退出项目；账号权限变更由服务器立即生效。</p>
  </section>
</template>
