<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { useAuthStore } from '@/stores/auth'

// Host for the React landing page: React (and Tailwind) load only when this route is visited.
const host = ref<HTMLElement>()
const router = useRouter()
const auth = useAuthStore()
let app: Awaited<ReturnType<typeof import('@/react/mount').mountLanding>> | undefined

const bridge = () => ({ isAuthenticated: auth.isAuthenticated, navigate: (path: string) => void router.push(path) })

onMounted(async () => {
  const { mountLanding } = await import('@/react/mount')
  if (host.value) app = mountLanding(host.value, bridge())
})
watch(() => auth.isAuthenticated, () => app?.update(bridge()))
onBeforeUnmount(() => app?.unmount())
</script>

<template>
  <div ref="host" class="tw-root dark" />
</template>
