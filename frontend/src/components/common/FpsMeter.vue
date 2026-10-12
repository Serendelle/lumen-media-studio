<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref } from 'vue'

/**
 * Opt-in frame-rate meter (append ?fps to any URL). Lets anyone verify animation smoothness on real hardware:
 * shows the current fps, the 1% low over the last ~10 s, and the share of frames slower than 20 ms.
 */
const fps = ref(0)
const low = ref(0)
const slow = ref(0)
let raf = 0
let last = 0
let windowStart = 0
let frames = 0
const deltas: number[] = []

function loop(t: number) {
  if (last) {
    const d = t - last
    deltas.push(d)
    if (deltas.length > 600) deltas.shift()
    frames++
    if (t - windowStart >= 500) {
      fps.value = Math.round((frames * 1000) / (t - windowStart))
      frames = 0
      windowStart = t
      const sorted = [...deltas].sort((a, b) => b - a)
      const p99 = sorted[Math.floor(sorted.length * 0.01)] ?? d
      low.value = Math.round(1000 / p99)
      slow.value = Math.round((deltas.filter((x) => x > 20).length / deltas.length) * 1000) / 10
    }
  } else {
    windowStart = t
  }
  last = t
  raf = requestAnimationFrame(loop)
}

onMounted(() => (raf = requestAnimationFrame(loop)))
onBeforeUnmount(() => cancelAnimationFrame(raf))
</script>

<template>
  <div class="fps mono" aria-hidden="true">
    <b :class="{ bad: fps < 50 }">{{ fps }}</b> fps · 1% low {{ low }} · &gt;20ms {{ slow }}%
  </div>
</template>

<style scoped>
.fps {
  position: fixed;
  left: 12px;
  bottom: 12px;
  z-index: 200;
  padding: 6px 10px;
  border-radius: 8px;
  background: rgba(48, 58, 55, 0.88);
  color: #f7f7f2;
  font-size: 11px;
  pointer-events: none;
}
b {
  color: #d9edE4;
}
b.bad {
  color: #f0b3a8;
}
</style>
