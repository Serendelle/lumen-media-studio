<script setup lang="ts">
import { computed } from 'vue'
import type { Plan, Review } from '@/api/ai'
import Icon from '@/components/common/Icon.vue'
import { downloadFile } from '@/utils/download'
import { bytes } from '@/utils/format'
import { useToast } from '@/composables/useToast'

const props = defineProps<{ review: Review; plan: Plan | null }>()
const toast = useToast()

/** Deliverables = successful outputs no later step consumed (e.g. the cleaned copy, not the intermediate). */
const consumed = computed(() => {
  const s = new Set<number>()
  for (const step of props.plan?.steps ?? []) for (const i of step.inputs) if (i.startsWith('step:')) s.add(Number(i.slice(5)))
  return s
})
const deliverables = computed(() => props.review.outputs.filter((o) => o.fileId && o.status === 'SUCCEEDED' && !consumed.value.has(o.stepId)))
const intermediates = computed(() => props.review.outputs.filter((o) => o.fileId && o.status === 'SUCCEEDED' && consumed.value.has(o.stepId)))
const stepTitle = (id: number) => props.plan?.steps.find((s) => s.id === id)?.title ?? `步骤 ${id}`

async function dl(fileId: string, name?: string) {
  try {
    await downloadFile(fileId, name)
  } catch (e) {
    toast.error((e as Error).message)
  }
}
</script>

<template>
  <article class="review card" :class="{ ok: review.allPassed }" aria-label="质量审核结果">
    <header class="head">
      <span class="eyebrow">Agent 04 · 质量审核官</span>
      <h3>{{ review.allPassed ? '全部核验通过' : '部分结果未达到要求' }}</h3>
      <p class="sub">输出文件已由服务端重新读取并逐项核验<template v-if="review.retries"> · 自动重试 {{ review.retries }} 次</template>
        <template v-if="review.verdict"> · 结论 <span class="mono">{{ review.verdict }}</span></template></p>
    </header>

    <ul class="checks">
      <li v-for="(c, i) in review.checks" :key="i" :class="{ bad: !c.ok, retry: c.afterRetry }">
        <span class="mark" aria-hidden="true"><Icon :name="c.ok ? 'check' : 'x'" :size="13" /></span>
        <span class="what">{{ c.stepId ? `步骤 ${c.stepId} · ` : '' }}{{ c.name }}<em v-if="c.afterRetry">（重试后）</em></span>
        <span class="mono val">{{ c.actual }}</span>
        <span class="sr-only">{{ c.ok ? '通过' : '未通过' }}，期望 {{ c.expected }}</span>
      </li>
    </ul>

    <div v-if="deliverables.length" class="files">
      <p class="label">交付文件</p>
      <div v-for="o in deliverables" :key="o.fileId" class="file">
        <Icon name="file" />
        <span class="fn">
          <span class="nm">{{ o.filename }}</span>
          <span class="meta">{{ stepTitle(o.stepId) }} · {{ bytes(o.sizeBytes) }}</span>
        </span>
        <button type="button" class="btn btn--sm" @click="dl(o.fileId!, o.filename)"><Icon name="download" :size="14" />下载</button>
      </div>
    </div>
    <details v-if="intermediates.length" class="mid">
      <summary>中间文件（{{ intermediates.length }}）— 已被后续步骤处理，通常不需要</summary>
      <div v-for="o in intermediates" :key="o.fileId" class="file small">
        <span class="fn"><span class="nm">{{ o.filename }}</span><span class="meta">{{ stepTitle(o.stepId) }} · {{ bytes(o.sizeBytes) }}</span></span>
        <button type="button" class="btn btn--sm btn--ghost" @click="dl(o.fileId!, o.filename)">下载</button>
      </div>
    </details>
  </article>
</template>

<style scoped>
.review {
  display: grid;
  gap: var(--sp-4);
  padding: var(--sp-5);
  border-radius: var(--r-lg);
  background: linear-gradient(160deg, var(--paper), rgba(243, 227, 215, 0.6));
}
.review.ok {
  background: linear-gradient(160deg, var(--paper), rgba(217, 237, 228, 0.7));
}
.head h3 {
  margin-top: 4px;
  font-size: var(--fs-lg);
}
.sub {
  font-size: var(--fs-xs);
  color: var(--text-3);
}
.checks {
  margin: 0;
  padding: 0;
  list-style: none;
  display: grid;
  gap: 6px;
}
.checks li {
  display: grid;
  grid-template-columns: 20px minmax(0, 1fr) auto;
  gap: 8px;
  align-items: center;
  font-size: var(--fs-xs);
}
.mark {
  width: 18px;
  height: 18px;
  display: grid;
  place-items: center;
  border-radius: 50%;
  background: var(--mint-ink);
  color: var(--paper);
}
.bad .mark {
  background: var(--danger);
}
.bad .val {
  color: var(--danger);
}
.what em {
  font-style: normal;
  color: var(--text-3);
}
.val {
  color: var(--text-2);
  text-align: right;
  overflow-wrap: anywhere;
}
.label {
  font-size: var(--fs-xs);
  color: var(--text-3);
  letter-spacing: 0.06em;
}
.files {
  display: grid;
  gap: 8px;
}
.file {
  display: grid;
  grid-template-columns: auto minmax(0, 1fr) auto;
  align-items: center;
  gap: 10px;
  padding: 10px 12px;
  border-radius: var(--r-md);
  background: var(--surface);
  border: 1px solid var(--line);
}
.file.small {
  grid-template-columns: minmax(0, 1fr) auto;
  margin-top: 6px;
}
.fn {
  display: grid;
  min-width: 0;
}
.nm {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: var(--fs-sm);
}
.meta {
  font-size: var(--fs-xs);
  color: var(--text-3);
}
.mid summary {
  cursor: pointer;
  font-size: var(--fs-xs);
  color: var(--text-3);
}
</style>
