<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { RouterView, useRoute, useRouter } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import { useLibraryStore } from '@/stores/library'
import { useSystemStore } from '@/stores/system'
import { useTasksStore } from '@/stores/tasks'
import { useToast } from '@/composables/useToast'
import '@/styles/workspace.css'

const router = useRouter()
const route = useRoute()
const auth = useAuthStore()
const lib = useLibraryStore()
const system = useSystemStore()
const tasks = useTasksStore()
const toast = useToast()
const host = ref<HTMLElement>()
const collapsed = ref(false)
try { collapsed.value = localStorage.getItem('lumen.sidebar-collapsed') === 'true' } catch { /* session-only layout */ }
const title = computed(() => String(route.meta.title ?? '概览').split(' · ')[0])
let chrome: ReturnType<typeof import('@/react/mount-workspace').mountWorkspace> | undefined
let disposed = false

function setCollapsed(value: boolean) {
  collapsed.value = value
  try { localStorage.setItem('lumen.sidebar-collapsed', String(value)) } catch { /* session-only layout */ }
}
function logout() {
  tasks.reset()
  auth.logout()
  void router.replace('/')
}
const props = () => ({
  path: route.fullPath,
  title: title.value,
  displayName: auth.user?.displayName || auth.user?.username || '用户',
  username: auth.user?.username ?? '',
  activeTasks: tasks.active.length,
  collapsed: collapsed.value,
  onCollapsedChange: setCollapsed,
  navigate: (path: string) => { void router.push(path) },
  logout,
})

onMounted(() => {
  void import('@/react/mount-workspace').then(({ mountWorkspace }) => {
    if (!disposed && host.value) chrome = mountWorkspace(host.value, props())
  }).catch(() => toast.error('导航加载失败，请刷新页面重试'))
  void Promise.allSettled([
    system.load(),
    lib.refresh(),
    tasks.restore().catch((e) => toast.error(`任务列表加载失败：${(e as Error).message}`)),
  ])
})
watch(() => [route.fullPath, title.value, auth.user?.displayName, auth.user?.username, tasks.active.length, collapsed.value], () => chrome?.update(props()))
onBeforeUnmount(() => {
  disposed = true
  chrome?.unmount()
  tasks.stopAll()
})
</script>

<template>
  <!-- "dark" turns on the component's dark: variants (Tailwind custom variant: .dark *) -->
  <div class="workspace-shell dark" :data-sidebar-collapsed="collapsed">
    <div ref="host" class="tw-root workspace-chrome" />
    <div class="workspace-content">
      <p v-if="system.error" class="workspace-offline" role="alert">无法连接服务：{{ system.error }}</p>
      <RouterView />
    </div>
  </div>
</template>
