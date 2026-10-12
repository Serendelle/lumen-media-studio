<script setup lang="ts">
import { useSystemStore } from '@/stores/system'

/**
 * Phase 2 placeholder. The multi-agent workflow is built in phase 3; until then this panel states the real
 * status instead of simulating a conversation.
 */
const system = useSystemStore()
</script>

<template>
  <section class="ai" aria-labelledby="ai-title">
    <p class="eyebrow">Smart mode · Multi-agent</p>
    <h2 id="ai-title" class="title">智能模式</h2>
    <p class="body">
      用自然语言描述需求，由「需求理解 → 任务规划 → 处理执行 → 质量审核」四个 Agent 协作完成，
      底层调用的是与专业模式完全相同的处理服务。
    </p>
    <div class="status" role="status">
      <span class="chip chip--apricot">开发中</span>
      <span>多 Agent 编排正在接入，当前版本尚不可用。</span>
    </div>
    <p class="cfg">
      DeepSeek 配置：
      <strong v-if="system.caps?.ai.configured" class="ok">已配置（{{ system.caps.ai.model }}）</strong>
      <strong v-else class="no">未配置（在后端 .env 中设置 DEEPSEEK_API_KEY）</strong>
    </p>
  </section>
</template>

<style scoped>
.ai {
  display: grid;
  gap: var(--sp-4);
  max-width: 640px;
}
.title {
  font-size: var(--fs-xl);
}
.body {
  color: var(--text-2);
}
.status {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 10px;
  font-size: var(--fs-sm);
}
.cfg {
  font-size: var(--fs-sm);
  color: var(--text-2);
}
.ok {
  color: var(--ok);
  font-weight: 500;
}
.no {
  color: var(--warn);
  font-weight: 500;
}
</style>
