<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue'
import PlanCard from './PlanCard.vue'
import ExecutionCard from './ExecutionCard.vue'
import ReviewCard from './ReviewCard.vue'
import Icon from '@/components/common/Icon.vue'
import { AGENTS, useAiStore } from '@/stores/ai'
import type { Plan } from '@/api/ai'

const ai = useAiStore()
const scroller = ref<HTMLElement | null>(null)

type Item =
  | { kind: 'msg'; key: string; role: string; agent?: string; content: string }
  | { kind: 'tools'; key: string; agent: string; calls: { name: string; ok: boolean; summary: string; repair?: boolean }[] }
  | { kind: 'plan'; key: string; plan: Plan }
  | { kind: 'exec'; key: string; plan: Plan }
  | { kind: 'review'; key: string }
  | { kind: 'error'; key: string; message: string }
  | { kind: 'usage'; key: string; text: string }

const TOOL_LABEL: Record<string, string> = {
  list_files: '查看文件列表', inspect_media: '读取媒体信息', read_metadata: '读取元数据', estimate_compression: '评估压缩可行性',
  get_capabilities: '查询平台能力', submit_plan: '提交计划',
}

/** Projects the ordered event log into thread items; consecutive tool calls of one agent collapse into a trail. */
const items = computed<Item[]>(() => {
  const out: Item[] = []
  let lastPlan: Plan | null = null
  for (const e of ai.events) {
    const p = e.payload
    switch (e.type) {
      case 'message':
        out.push({ kind: 'msg', key: `m${e.seq}`, role: p.role, agent: p.agent, content: p.content })
        break
      case 'tool.done': {
        const prev = out[out.length - 1]
        const call = { name: p.name, ok: p.ok, summary: p.summary ?? '', repair: p.name === 'submit_plan' }
        if (prev && prev.kind === 'tools' && prev.agent === p.agent) prev.calls.push(call)
        else out.push({ kind: 'tools', key: `t${e.seq}`, agent: p.agent, calls: [call] })
        break
      }
      case 'plan':
        lastPlan = p
        out.push({ kind: 'plan', key: `p${e.seq}`, plan: p })
        break
      case 'confirmed':
        if (lastPlan) out.push({ kind: 'exec', key: `x${e.seq}`, plan: lastPlan })
        break
      case 'review':
        out.push({ kind: 'review', key: `r${e.seq}` })
        break
      case 'error':
        out.push({ kind: 'error', key: `e${e.seq}`, message: p.message })
        break
      case 'usage':
        out.push({ kind: 'usage', key: `u${e.seq}`, text: `${p.model} · ${p.llmCalls} 次模型调用 · ${p.promptTokens + p.completionTokens} tokens` })
        break
    }
  }
  return out
})

const agentTitle = (id?: string) => AGENTS.find((a) => a.id === id)?.title ?? (id === 'system' ? '系统' : '助手')
const agentNo = (id?: string) => AGENTS.find((a) => a.id === id)?.no ?? ''

/** Safe light formatting: escape everything first, then allow **bold** and "- " bullets only. */
function format(text: string): string {
  const esc = text.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;').replace(/'/g, '&#39;')
  return esc
    .replace(/\*\*(.+?)\*\*/g, '<strong>$1</strong>')
    .replace(/\n\s*\n+/g, '\n') // model paragraphs: one break is enough in a chat bubble
    .trim()
    .split('\n')
    .map((l) => (/^\s*[-·•]\s+/.test(l) ? `<span class="li">${l.replace(/^\s*[-·•]\s+/, '')}</span>` : l))
    .join('<br>')
}

// keep the newest content in view
watch(
  () => [ai.events.length, ai.streaming?.text.length],
  async () => {
    await nextTick()
    const el = scroller.value
    if (el && el.scrollHeight - el.scrollTop - el.clientHeight < 240) el.scrollTop = el.scrollHeight
  },
)
</script>

<template>
  <div ref="scroller" class="thread" aria-live="polite">
    <template v-for="it in items" :key="it.key">
      <div v-if="it.kind === 'msg'" class="msg" :class="it.role === 'user' ? 'me' : `bot a-${it.agent}`">
        <span v-if="it.role !== 'user'" class="who"><span class="mono">{{ agentNo(it.agent) && 'AGENT ' + agentNo(it.agent) }}</span> {{ agentTitle(it.agent) }}</span>
        <!-- eslint-disable-next-line vue/no-v-html -->
        <p class="bubble" v-html="format(it.content)" />
      </div>

      <div v-else-if="it.kind === 'tools'" class="tools" :aria-label="`${agentTitle(it.agent)}的工具调用`">
        <span class="who mono">{{ agentTitle(it.agent) }} · 调用工具</span>
        <ul>
          <li v-for="(c, i) in it.calls" :key="i" :class="{ bad: !c.ok }">
            <Icon :name="c.ok ? 'tool' : 'x'" :size="13" />
            <span class="nm">{{ TOOL_LABEL[c.name] ?? c.name }}</span>
            <span class="sum">{{ c.summary }}</span>
          </li>
        </ul>
      </div>

      <PlanCard v-else-if="it.kind === 'plan'" :plan="it.plan" />
      <ExecutionCard v-else-if="it.kind === 'exec'" :plan="it.plan" />
      <ReviewCard v-else-if="it.kind === 'review' && ai.review" :review="ai.review" :plan="ai.plan" />
      <p v-else-if="it.kind === 'error'" class="err" role="alert">{{ it.message }}</p>
      <p v-else-if="it.kind === 'usage'" class="usage mono">{{ it.text }}</p>
    </template>

    <div v-if="ai.streaming" class="msg bot streaming">
      <span class="who">{{ agentTitle(ai.streaming.agent) }} · 正在输入</span>
      <!-- eslint-disable-next-line vue/no-v-html -->
      <p class="bubble" v-html="format(ai.streaming.text) + '<span class=&quot;caret&quot;></span>'" />
    </div>
    <div v-else-if="ai.busy" class="thinking" aria-label="处理中"><span /><span /><span /></div>
  </div>
</template>

<style scoped>
.thread {
  display: grid;
  align-content: start;
  gap: 14px;
  min-height: 0;
  overflow-y: auto;
  padding: 4px 4px 12px;
  scroll-behavior: smooth;
}
.msg {
  display: grid;
  gap: 4px;
  max-width: min(640px, 92%);
}
.msg.me {
  justify-self: end;
}
.who {
  font-size: 11px;
  color: var(--text-3);
  letter-spacing: 0.04em;
}
.bubble {
  padding: 12px 14px;
  border-radius: 16px 16px 16px 6px;
  background: var(--surface);
  border: 1px solid var(--line);
  font-size: var(--fs-sm);
  line-height: 1.7;
  overflow-wrap: anywhere;
}
.me .bubble {
  border-radius: 16px 16px 6px 16px;
  background: var(--ink);
  border-color: var(--ink);
  color: var(--cream);
}
.a-quality-reviewer .bubble {
  background: linear-gradient(160deg, var(--paper), rgba(217, 237, 228, 0.45));
}
.bubble :deep(.li) {
  display: block;
  padding-left: 14px;
  position: relative;
}
.bubble :deep(.li)::before {
  content: '';
  position: absolute;
  left: 2px;
  top: 0.8em;
  width: 5px;
  height: 5px;
  border-radius: 50%;
  background: var(--mint-ink);
}
.bubble :deep(.caret) {
  display: inline-block;
  width: 7px;
  height: 1em;
  margin-left: 2px;
  vertical-align: -2px;
  background: var(--mint-ink);
  animation: caret 1s steps(2, start) infinite;
}
@keyframes caret {
  to {
    visibility: hidden;
  }
}
.tools {
  display: grid;
  gap: 6px;
  padding-left: 12px;
  border-left: 2px solid var(--mint);
}
.tools ul {
  margin: 0;
  padding: 0;
  list-style: none;
  display: grid;
  gap: 4px;
}
.tools li {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: var(--fs-xs);
  color: var(--text-2);
}
.tools li.bad {
  color: var(--warn);
}
.tools .nm {
  color: var(--text);
}
.tools .sum {
  color: var(--text-3);
  overflow-wrap: anywhere;
}
.err {
  padding: 10px 14px;
  border-radius: var(--r-md);
  background: var(--danger-soft);
  color: var(--danger);
  font-size: var(--fs-sm);
}
.usage {
  justify-self: center;
  font-size: 10px;
  color: var(--text-3);
  letter-spacing: 0.04em;
}
.thinking {
  display: flex;
  gap: 5px;
  padding: 10px 4px;
}
.thinking span {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: var(--mint-ink);
  opacity: 0.35;
  animation: dot 1.2s infinite ease-in-out;
}
.thinking span:nth-child(2) {
  animation-delay: 0.15s;
}
.thinking span:nth-child(3) {
  animation-delay: 0.3s;
}
@keyframes dot {
  50% {
    opacity: 1;
    transform: translateY(-3px);
  }
}
</style>
