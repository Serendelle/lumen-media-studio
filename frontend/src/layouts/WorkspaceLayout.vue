<script setup lang="ts">
import { onBeforeUnmount, onMounted } from 'vue'
import { RouterLink, RouterView, useRouter } from 'vue-router'
import BrandMark from '@/components/common/BrandMark.vue'
import Icon from '@/components/common/Icon.vue'
import { useAuthStore } from '@/stores/auth'
import { useLibraryStore } from '@/stores/library'
import { useSystemStore } from '@/stores/system'
import { useTasksStore } from '@/stores/tasks'
import { useToast } from '@/composables/useToast'

const router = useRouter()
const auth = useAuthStore()
const lib = useLibraryStore()
const system = useSystemStore()
const tasks = useTasksStore()
const toast = useToast()

/** Primary navigation. Modules are only listed once they are actually implemented. */
const NAV: { to: string; label: string; icon: string; exact?: boolean }[] = [
  { to: '/workspace', label: '概览', icon: 'grid', exact: true },
  { to: '/workspace/media', label: '媒体工作台', icon: 'film' },
  { to: '/workspace/documents', label: '文档工作台', icon: 'pdf' },
  { to: '/workspace/specs', label: '材料规范官', icon: 'check' },
  { to: '/workspace/files', label: '我的文件', icon: 'folder' },
  { to: '/workspace/tasks', label: '任务中心', icon: 'list' },
]

onMounted(async () => {
  await Promise.all([
    system.load(),
    lib.refresh(),
    tasks.restore().catch((e) => toast.error(`任务列表加载失败：${(e as Error).message}`)),
  ])
})
onBeforeUnmount(() => tasks.stopAll())

function logout() {
  tasks.reset()
  auth.logout()
  void router.replace('/')
}
</script>

<template>
  <div class="ws">
    <header class="bar">
      <RouterLink to="/" class="home" aria-label="返回首页"><BrandMark compact /></RouterLink>
      <nav class="nav" aria-label="主导航">
        <RouterLink v-for="n in NAV" :key="n.to" :to="n.to" class="nl"
                    :exact-active-class="n.exact ? 'on' : ''" :active-class="n.exact ? '' : 'on'">
          <Icon :name="n.icon" :size="16" />
          <span>{{ n.label }}</span>
          <span v-if="n.to === '/workspace/tasks' && tasks.active.length" class="badge" :aria-label="`${tasks.active.length} 个进行中`">
            {{ tasks.active.length }}
          </span>
        </RouterLink>
      </nav>
      <div class="user">
        <span class="name">{{ auth.user?.displayName ?? auth.user?.username }}</span>
        <button type="button" class="btn btn--sm btn--ghost" @click="logout">退出</button>
      </div>
    </header>

    <p v-if="system.error" class="offline" role="alert">无法连接后端服务：{{ system.error }}。请确认后端已启动（默认端口 8080）。</p>

    <RouterView />
  </div>
</template>

<style scoped>
.ws {
  min-height: 100svh;
  background:
    radial-gradient(50% 40% at 100% 0%, rgba(220, 232, 240, 0.8), transparent 70%),
    radial-gradient(40% 40% at 0% 100%, rgba(217, 237, 228, 0.6), transparent 70%),
    var(--cream);
}
.bar {
  position: sticky;
  top: 0;
  z-index: 20;
  display: grid;
  grid-template-columns: auto 1fr auto;
  align-items: center;
  gap: var(--sp-5);
  padding: 10px var(--gutter);
  background: rgba(247, 247, 242, 0.88);
  backdrop-filter: blur(12px);
  border-bottom: 1px solid var(--line);
}
.home {
  text-decoration: none;
}
.nav {
  display: flex;
  gap: 2px;
  justify-self: center;
  overflow-x: auto;
  scrollbar-width: none;
}
.nl {
  position: relative;
  display: inline-flex;
  align-items: center;
  gap: 7px;
  min-height: 40px;
  padding: 0 14px;
  border-radius: var(--r-pill);
  font-size: var(--fs-sm);
  color: var(--text-2);
  text-decoration: none;
  white-space: nowrap;
  transition: background var(--t-fast), color var(--t-fast);
}
.nl:hover {
  color: var(--text);
  background: rgba(255, 255, 255, 0.6);
}
.nl.on {
  color: var(--cream);
  background: var(--ink);
}
.nl:focus-visible {
  outline: 2px solid var(--focus);
  outline-offset: 2px;
}
.badge {
  min-width: 18px;
  height: 18px;
  padding: 0 5px;
  border-radius: var(--r-pill);
  background: var(--mint);
  color: var(--mint-ink);
  font-size: 11px;
  line-height: 18px;
  text-align: center;
}
.user {
  display: flex;
  align-items: center;
  gap: 10px;
  font-size: var(--fs-sm);
  color: var(--text-2);
}
.offline {
  margin: 12px var(--gutter) 0;
  padding: 10px 14px;
  border-radius: var(--r-sm);
  background: var(--danger-soft);
  color: var(--danger);
  font-size: var(--fs-sm);
}
@media (max-width: 900px) {
  .bar {
    grid-template-columns: auto 1fr;
    gap: var(--sp-3);
  }
  .nav {
    grid-column: 1 / -1;
    grid-row: 2;
    justify-self: stretch;
  }
  .user {
    justify-self: end;
  }
  .user .name {
    display: none;
  }
}
@media (prefers-reduced-motion: reduce) {
  .nl {
    transition: none;
  }
}
</style>
