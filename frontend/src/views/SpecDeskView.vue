<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { RouterLink, useRoute, useRouter } from 'vue-router'
import SpecEditor from '@/components/spec/SpecEditor.vue'
import RosterPanel from '@/components/spec/RosterPanel.vue'
import IntakeBoard from '@/components/spec/IntakeBoard.vue'
import DeliveryPanel from '@/components/spec/DeliveryPanel.vue'
import CollectionPanel from '@/components/spec/CollectionPanel.vue'
import { specsApi } from '@/api/endpoints'
import type { SpecBoard, SpecProject } from '@/api/types'
import { useToast } from '@/composables/useToast'

const route = useRoute()
const router = useRouter()
const toast = useToast()
const id = computed(() => String(route.params.id))
const project = ref<SpecProject | null>(null)
const error = ref<string | null>(null)
const summary = ref<SpecBoard['summary'] | null>(null)

const STEPS = [
  { k: 'spec', l: '① 规范' },
  { k: 'roster', l: '② 名单' },
  { k: 'collect', l: '③ 分享收集' },
  { k: 'intake', l: '④ 材料与 AI 整理' },
  { k: 'deliver', l: '⑤ 交付' },
] as const
type Step = (typeof STEPS)[number]['k']
const step = computed<Step>({
  get: () => (STEPS.some((s) => s.k === route.query.step) ? (route.query.step as Step) : 'spec'),
  set: (s) => void router.replace({ query: { ...route.query, step: s } }),
})
const done = computed<Record<Step, boolean>>(() => ({
  spec: (project.value?.spec.slots.length ?? 0) > 0,
  roster: !!project.value?.roster,
  collect: false,
  intake: (summary.value?.files ?? 0) > 0,
  deliver: false,
}))

onMounted(async () => {
  try {
    project.value = await specsApi.get(id.value)
    summary.value = (await specsApi.board(id.value)).summary
  } catch (e) {
    error.value = (e as Error).message
  }
})

function saved(p: SpecProject) {
  project.value = p
}

const dlg = ref<HTMLDialogElement | null>(null)
async function remove() {
  try {
    await specsApi.remove(id.value)
    toast.ok('项目已删除，文件仍保留在「我的文件」中')
    void router.replace('/workspace/specs')
  } catch (e) {
    toast.error((e as Error).message)
  }
}
</script>

<template>
  <main id="main" class="desk workspace-page">
    <p v-if="error" class="err" role="alert">{{ error }} <RouterLink to="/workspace/specs">返回项目列表</RouterLink></p>
    <template v-else-if="project">
      <header class="head">
        <RouterLink to="/workspace/specs" class="back">← 材料规范官</RouterLink>
        <h1>{{ project.name }}</h1>
        <span v-if="summary" class="sum">{{ summary.complete }} / {{ summary.people }} 人齐全 · {{ summary.files }} 个文件</span>
        <button type="button" class="btn btn--sm btn--ghost del" @click="dlg?.showModal()">删除项目</button>
      </header>

      <nav class="steps" role="tablist" aria-label="步骤">
        <button v-for="s in STEPS" :key="s.k" type="button" role="tab" :aria-selected="step === s.k"
                :class="{ on: step === s.k, done: done[s.k] }" @click="step = s.k">
          {{ s.l }}<span v-if="done[s.k]" class="ok" aria-label="已完成">✓</span>
        </button>
      </nav>

      <SpecEditor v-if="step === 'spec'" :project="project" @saved="saved" />
      <RosterPanel v-else-if="step === 'roster'" :project="project" @saved="saved" />
      <CollectionPanel v-else-if="step === 'collect'" :project="project" />
      <IntakeBoard v-else-if="step === 'intake'" :project="project" @changed="(b) => (summary = b.summary)" @saved="saved" />
      <DeliveryPanel v-else :project="project" :complete="summary?.complete ?? 0" :people="summary?.people ?? project.roster?.people.length ?? 0" @saved="saved" />

      <dialog ref="dlg" class="dlg" aria-labelledby="dt">
        <h2 id="dt">删除「{{ project.name }}」？</h2>
        <p>项目的规范、名单匹配结果和交付记录会被删除，<strong>无法恢复</strong>。已上传的文件不会删除，会恢复为默认的 24 小时保留期。</p>
        <div class="row">
          <button type="button" class="btn btn--ghost" @click="dlg?.close()">取消</button>
          <button type="button" class="btn btn--danger" @click="remove">删除项目</button>
        </div>
      </dialog>
    </template>
    <p v-else class="muted">加载中…</p>
  </main>
</template>

<style scoped>
.desk { display: grid; grid-template-columns: minmax(0, 1fr); gap: var(--sp-4); }
.head { display: flex; align-items: baseline; gap: var(--sp-3); flex-wrap: wrap; }
.back { font-size: var(--fs-sm); color: var(--text-3); text-decoration: none; }
.head h1 { font-size: var(--fs-xl); }
.sum { font-size: var(--fs-sm); color: var(--text-2); }
.del { margin-left: auto; }
.steps { display: flex; gap: 4px; padding: 4px; border-radius: var(--r-pill); background: var(--surface-sunk); width: max-content; max-width: 100%; overflow-x: auto; }
.steps button { min-height: 38px; padding: 0 16px; border: 0; border-radius: var(--r-pill); background: transparent; font-size: var(--fs-sm); color: var(--text-2); white-space: nowrap; }
.steps button.on { background: var(--ink); color: var(--cream); }
.ok { margin-left: 6px; color: var(--mint-ink); }
.steps button.on .ok { color: var(--mint); }
.err, .muted { font-size: var(--fs-sm); }
.err { color: var(--danger); }
.muted { color: var(--text-3); }
.dlg { width: min(460px, calc(100vw - 32px)); padding: var(--sp-5); border: 0; border-radius: var(--r-lg, 16px); background: var(--surface); box-shadow: 0 30px 80px rgba(0, 0, 0, 0.25); }
.dlg::backdrop { background: rgba(30, 36, 34, 0.45); }
.dlg h2 { font-size: var(--fs-lg); }
.dlg p { margin: var(--sp-3) 0; font-size: var(--fs-sm); line-height: 1.6; color: var(--text-2); }
.row { display: flex; justify-content: flex-end; gap: var(--sp-2); }
</style>
