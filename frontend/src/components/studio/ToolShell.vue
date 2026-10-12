<script setup lang="ts">
import { computed } from 'vue'
import { useSystemStore } from '@/stores/system'

const props = defineProps<{
  title: string
  subtitle: string
  feature: string
  tools?: string
  busy?: boolean
  canSubmit?: boolean
  submitLabel?: string
  error?: string | null
}>()
defineEmits<{ submit: [] }>()

const system = useSystemStore()
// unknown until capabilities load; don't block the form on a slow first request
const available = computed(() => (system.caps ? system.feature(props.feature) : true))
</script>

<template>
  <section class="shell" :aria-labelledby="`t-${feature}`">
    <header class="head">
      <h2 :id="`t-${feature}`" class="title">{{ title }}</h2>
      <p class="sub">{{ subtitle }}</p>
    </header>

    <div v-if="!available" class="unavailable" role="note">
      <strong>此功能当前不可用</strong>
      <span>后端未检测到 {{ tools ?? '所需的处理工具' }}，请按部署文档安装后重启后端。</span>
    </div>

    <form class="body" :class="{ disabled: !available }" novalidate @submit.prevent="$emit('submit')">
      <fieldset :disabled="!available || busy">
        <slot />
      </fieldset>

      <p v-if="error" class="error" role="alert">{{ error }}</p>
      <slot name="after-error" />

      <footer class="foot">
        <slot name="secondary" />
        <button class="btn" type="submit" :disabled="!available || busy || canSubmit === false">
          {{ busy ? '提交中…' : submitLabel ?? '开始处理' }}
        </button>
      </footer>
    </form>
  </section>
</template>

<style scoped>
.shell {
  display: grid;
  gap: var(--sp-5);
}
.title {
  font-size: var(--fs-xl);
}
.sub {
  margin-top: 6px;
  color: var(--text-2);
  font-size: var(--fs-sm);
  max-width: 46em;
}
.unavailable {
  display: grid;
  gap: 4px;
  padding: 12px 14px;
  border-radius: var(--r-md);
  background: var(--apricot);
  color: var(--apricot-ink);
  font-size: var(--fs-sm);
}
.body {
  display: grid;
  gap: var(--sp-5);
}
fieldset {
  display: grid;
  gap: var(--sp-5);
  margin: 0;
  padding: 0;
  border: 0;
  min-width: 0;
}
.disabled fieldset {
  opacity: 0.55;
}
.error {
  padding: 10px 12px;
  border-radius: var(--r-sm);
  background: var(--danger-soft);
  color: var(--danger);
  font-size: var(--fs-sm);
}
.foot {
  display: flex;
  flex-wrap: wrap;
  justify-content: flex-end;
  gap: var(--sp-3);
  padding-top: var(--sp-4);
  border-top: 1px solid var(--line);
}
</style>
