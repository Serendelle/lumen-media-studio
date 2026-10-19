<script setup lang="ts">
import { computed } from 'vue'
import type { Verification } from '@/api/types'
import { bytes } from '@/utils/format'

/** Shows the server's independent evidence for one output: every check with what was expected and what was measured. */
const props = defineProps<{ verification: Verification; compact?: boolean }>()

const STATUS: Record<string, string> = {
  VERIFIED: '已核验 · 全部达标',
  TARGET_NOT_MET: '未达到目标体积',
  OUTPUT_INVALID: '输出未通过核验',
}
const ok = computed(() => props.verification.status === 'VERIFIED')
const failed = computed(() => props.verification.checks.filter((c) => !c.ok).length)
const m = computed(() => props.verification.measured ?? {})
</script>

<template>
  <section class="vc" :class="{ bad: !ok }" aria-label="输出核验报告">
    <header class="hd">
      <span class="dot" aria-hidden="true" />
      <strong>{{ STATUS[verification.status] ?? verification.status }}</strong>
      <span class="sub">{{ verification.checks.length }} 项检查{{ failed ? `，${failed} 项未通过` : '' }} · 数据来自对输出文件的重新检测</span>
    </header>
    <ul class="checks">
      <li v-for="c in verification.checks" :key="c.name" :class="{ no: !c.ok }">
        <span class="mark" :aria-label="c.ok ? '通过' : '未通过'">{{ c.ok ? '✓' : '✕' }}</span>
        <span class="name">{{ c.name }}</span>
        <span class="exp">期望 {{ c.expected }}</span>
        <span class="act mono">实际 {{ c.actual }}</span>
      </li>
    </ul>
    <p v-if="!compact && m.actualBytes != null" class="bytes mono">
      实际 {{ m.actualBytes.toLocaleString('zh-CN') }} 字节（{{ bytes(m.actualBytes) }}）
      <template v-if="m.targetBytes != null"> · 目标上限 {{ m.targetBytes.toLocaleString('zh-CN') }} 字节</template>
    </p>
  </section>
</template>

<style scoped>
.vc {
  display: grid;
  gap: 10px;
  padding: 12px 14px;
  border-radius: var(--r-md);
  border: 1px solid rgba(59, 115, 96, 0.25);
  background: rgba(217, 237, 228, 0.35);
}
.vc.bad {
  border-color: rgba(165, 70, 62, 0.3);
  background: var(--danger-soft);
}
.hd {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
  font-size: var(--fs-sm);
}
.dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: var(--ok);
}
.bad .dot {
  background: var(--danger);
}
.bad .hd strong {
  color: var(--danger);
}
.sub {
  font-size: var(--fs-xs);
  color: var(--text-3);
}
.checks {
  display: grid;
  gap: 4px;
  margin: 0;
  padding: 0;
  list-style: none;
}
.checks li {
  display: grid;
  grid-template-columns: 18px minmax(70px, auto) minmax(0, 1fr) minmax(0, 1fr);
  align-items: baseline;
  gap: 8px;
  font-size: var(--fs-xs);
  color: var(--text-2);
}
.mark {
  color: var(--ok);
  font-weight: 600;
}
.no .mark,
.no .name {
  color: var(--danger);
}
.name {
  color: var(--text);
}
.act {
  overflow-wrap: anywhere;
}
.bytes {
  font-size: 11px;
  color: var(--text-3);
}
@media (max-width: 560px) {
  .checks li {
    grid-template-columns: 18px 1fr;
  }
  .exp,
  .act {
    grid-column: 2;
  }
}
</style>
