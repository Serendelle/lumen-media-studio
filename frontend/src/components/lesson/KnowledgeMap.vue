<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue'
import type { LessonNode } from '@/api/types'

/**
 * A mind map that grows with the video: in "grow" mode a node appears once playback reaches its start time, the
 * node being explained right now is highlighted together with its path, and clicking a node seeks the video there.
 * Pure SVG with a small tidy-tree layout (no chart library).
 */
const props = defineProps<{ nodes: LessonNode[]; time: number; grow: boolean }>()
const emit = defineEmits<{ seek: [t: number]; focus: [n: LessonNode] }>()

const COL = 210
const ROW = 46
const PAD = 16

const visible = computed(() => (props.grow ? props.nodes.filter((n) => n.start <= props.time + 0.25) : props.nodes))

/** deepest node whose [start, end) contains the current time */
const active = computed(() => {
  let best: LessonNode | null = null
  let depthBest = -1
  for (const n of props.nodes) {
    if (props.time >= n.start && props.time < n.end) {
      const d = depth(n)
      if (d > depthBest) {
        best = n
        depthBest = d
      }
    }
  }
  return best
})
const byId = computed(() => new Map(props.nodes.map((n) => [n.id, n])))
function depth(n: LessonNode) {
  let d = 0
  for (let p = n.parentId; p; p = byId.value.get(p)?.parentId ?? null) d++
  return d
}
const path = computed(() => {
  const s = new Set<string>()
  for (let n = active.value; n; n = n.parentId ? byId.value.get(n.parentId) ?? null : null) s.add(n.id)
  return s
})

interface Placed { node: LessonNode; x: number; y: number; w: number }
const layout = computed(() => {
  const nodes = visible.value
  const ids = new Set(nodes.map((n) => n.id))
  const kids = new Map<string | null, LessonNode[]>()
  for (const n of nodes) {
    const parent = n.parentId && ids.has(n.parentId) ? n.parentId : null
    if (!kids.has(parent)) kids.set(parent, [])
    kids.get(parent)!.push(n)
  }
  for (const list of kids.values()) list.sort((a, b) => a.start - b.start)
  const placed: Placed[] = []
  let row = 0
  function place(n: LessonNode, d: number): number {
    const children = kids.get(n.id) ?? []
    let y: number
    if (!children.length) y = row++ * ROW
    else {
      const ys = children.map((c) => place(c, d + 1))
      y = (ys[0] + ys[ys.length - 1]) / 2
    }
    placed.push({ node: n, x: d * COL, y, w: width(n.title) })
    return y
  }
  for (const r of kids.get(null) ?? []) place(r, 0)
  const pos = new Map(placed.map((p) => [p.node.id, p]))
  const edges = placed
    .filter((p) => p.node.parentId && pos.has(p.node.parentId))
    .map((p) => {
      const parent = pos.get(p.node.parentId!)!
      const x1 = parent.x + parent.w, y1 = parent.y + 16, x2 = p.x, y2 = p.y + 16
      const mx = (x1 + x2) / 2
      return { id: p.node.id, d: `M${x1},${y1} C${mx},${y1} ${mx},${y2} ${x2},${y2}`, onPath: path.value.has(p.node.id) }
    })
  const maxX = Math.max(0, ...placed.map((p) => p.x + p.w))
  return { placed, edges, width: maxX + PAD * 2, height: Math.max(1, row) * ROW + PAD * 2 }
})

function width(title: string) {
  let w = 0
  for (const ch of title) w += /[\u0000-ÿ]/.test(ch) ? 8 : 14
  return Math.min(180, Math.max(76, w + 28))
}
function short(title: string, w: number) {
  let out = ''
  let used = 28
  for (const ch of title) {
    used += /[\u0000-ÿ]/.test(ch) ? 8 : 14
    if (used > w) return out + '…'
    out += ch
  }
  return out
}
function clock(t: number) {
  const s = Math.floor(t)
  return `${String(Math.floor(s / 60)).padStart(2, '0')}:${String(s % 60).padStart(2, '0')}`
}

// keep the active node in view while the video plays
const box = ref<HTMLDivElement | null>(null)
watch(() => active.value?.id, async (id) => {
  if (!id || !box.value) return
  if (active.value) emit('focus', active.value)
  await nextTick()
  const p = layout.value.placed.find((x) => x.node.id === id)
  if (!p) return
  const el = box.value
  const top = p.y + PAD - el.clientHeight / 2 + 16
  const left = p.x + PAD - el.clientWidth / 3
  el.scrollTo({ top: Math.max(0, top), left: Math.max(0, left), behavior: matchMedia('(prefers-reduced-motion: reduce)').matches ? 'auto' : 'smooth' })
})
</script>

<template>
  <div ref="box" class="km" role="tree" aria-label="知识图谱">
    <svg :width="layout.width" :height="layout.height" class="svg">
      <g :transform="`translate(${PAD},${PAD})`">
        <path v-for="e in layout.edges" :key="e.id" :d="e.d" class="edge" :class="{ on: e.onPath }" />
        <g v-for="p in layout.placed" :key="p.node.id" class="node"
           :class="{ active: active?.id === p.node.id, path: path.has(p.node.id), root: !p.node.parentId }"
           :style="{ transform: `translate(${p.x}px, ${p.y}px)` }"
           role="treeitem" tabindex="0" :aria-label="`${p.node.title}，${clock(p.node.start)} 开始`"
           :aria-current="active?.id === p.node.id ? 'step' : undefined"
           @click="emit('seek', p.node.start)" @keydown.enter.prevent="emit('seek', p.node.start)" @focus="emit('focus', p.node)">
          <rect :width="p.w" height="32" rx="10" />
          <text x="14" y="20" class="t">{{ short(p.node.title, p.w) }}</text>
          <title>{{ p.node.title }} · {{ clock(p.node.start) }}–{{ clock(p.node.end) }}</title>
        </g>
      </g>
    </svg>
    <p v-if="!layout.placed.length" class="wait">播放后，知识点会随着讲解逐个出现。</p>
  </div>
</template>

<style scoped>
.km {
  position: relative;
  height: 100%;
  min-height: 260px;
  overflow: auto;
  border-radius: var(--r-md);
  background:
    radial-gradient(circle at 1px 1px, rgba(48, 58, 55, 0.08) 1px, transparent 0) 0 0 / 18px 18px,
    var(--surface);
}
.svg {
  display: block;
}
.edge {
  fill: none;
  stroke: var(--line-strong);
  stroke-width: 1.5;
  transition: stroke 200ms;
}
.edge.on {
  stroke: var(--mint-ink);
  stroke-width: 2;
}
.node {
  cursor: pointer;
  transition: transform 260ms var(--ease-out, ease-out), opacity 220ms;
  animation: grow 260ms var(--ease-out, ease-out);
}
@keyframes grow {
  from { opacity: 0; }
  to { opacity: 1; }
}
.node rect {
  fill: var(--paper);
  stroke: var(--line-strong);
  stroke-width: 1;
  transition: fill 200ms, stroke 200ms;
}
.node.root rect {
  fill: var(--fog);
  stroke: transparent;
}
.node.path rect {
  stroke: var(--mint-ink);
}
.node.active rect {
  fill: var(--ink);
  stroke: var(--ink);
}
.node .t {
  font-size: 13px;
  fill: var(--text);
  pointer-events: none;
}
.node.active .t {
  fill: var(--cream);
}
.node:focus-visible {
  outline: none;
}
.node:focus-visible rect {
  stroke: var(--focus);
  stroke-width: 2.5;
}
.wait {
  position: absolute;
  inset: 0;
  display: grid;
  place-items: center;
  margin: 0;
  font-size: var(--fs-sm);
  color: var(--text-3);
}
@media (prefers-reduced-motion: reduce) {
  .node,
  .edge,
  .node rect {
    transition: none;
    animation: none;
  }
}
</style>
