<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref } from 'vue'
import { prefersReducedMotion } from '@/composables/useMotion'

/**
 * The hero's "shot": a viewfinder framing a quiet landscape in natural light (pure SVG, no image download).
 * Layers drift with the pointer for depth (transform only); static when reduced motion is preferred.
 */
const root = ref<HTMLElement | null>(null)
const tilt = ref({ x: 0, y: 0 })
let raf = 0
let target = { x: 0, y: 0 }

function onMove(e: PointerEvent) {
  const r = root.value?.getBoundingClientRect()
  if (!r) return
  target = { x: (e.clientX - r.left) / r.width - 0.5, y: (e.clientY - r.top) / r.height - 0.5 }
  if (!raf) raf = requestAnimationFrame(step)
}
function step() {
  raf = 0
  const t = tilt.value
  const nx = t.x + (target.x - t.x) * 0.08
  const ny = t.y + (target.y - t.y) * 0.08
  tilt.value = { x: nx, y: ny }
  if (Math.abs(nx - target.x) > 0.001 || Math.abs(ny - target.y) > 0.001) raf = requestAnimationFrame(step)
}
const layer = (depth: number) => ({ transform: `translate3d(${tilt.value.x * depth}px, ${tilt.value.y * depth * 0.6}px, 0)` })

onMounted(() => {
  if (!prefersReducedMotion()) window.addEventListener('pointermove', onMove, { passive: true })
})
onBeforeUnmount(() => {
  window.removeEventListener('pointermove', onMove)
  cancelAnimationFrame(raf)
})
</script>

<template>
  <figure ref="root" class="frame" aria-hidden="true">
    <svg class="scene" viewBox="0 0 640 400" preserveAspectRatio="xMidYMid slice">
      <defs>
        <linearGradient id="hf-sky" x1="0" y1="0" x2="0" y2="1">
          <stop offset="0" stop-color="#C9DCE8" />
          <stop offset=".58" stop-color="#EEE9E0" />
          <stop offset="1" stop-color="#F3E3D7" />
        </linearGradient>
        <radialGradient id="hf-sun" cx=".5" cy=".5" r=".5">
          <stop offset="0" stop-color="#FFF7EE" />
          <stop offset=".32" stop-color="#F2CDB2" stop-opacity=".95" />
          <stop offset="1" stop-color="#F3E3D7" stop-opacity="0" />
        </radialGradient>
        <linearGradient id="hf-h1" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#C9DDD6" /><stop offset="1" stop-color="#DCE8E2" /></linearGradient>
        <linearGradient id="hf-h2" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#92B8AA" /><stop offset="1" stop-color="#B9D2C7" /></linearGradient>
        <linearGradient id="hf-h3" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#5F8F7E" /><stop offset="1" stop-color="#7FA898" /></linearGradient>
        <linearGradient id="hf-water" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#D3E1E6" /><stop offset="1" stop-color="#EDE6DC" /></linearGradient>
        <filter id="hf-blur"><feGaussianBlur stdDeviation="6" /></filter>
      </defs>
      <rect width="640" height="400" fill="url(#hf-sky)" />
      <g class="l" :style="layer(6)"><circle cx="370" cy="178" r="130" fill="url(#hf-sun)" /><circle cx="370" cy="178" r="22" fill="#FFF6EC" /></g>
      <g class="l" :style="layer(10)"><path d="M0 238 C 90 196 170 214 250 200 S 420 170 520 196 S 610 212 640 204 V 400 H 0 Z" fill="url(#hf-h1)" /></g>
      <rect class="mist" x="-40" y="226" width="720" height="40" fill="#FBFAF6" opacity=".55" filter="url(#hf-blur)" />
      <g class="l" :style="layer(18)"><path d="M0 268 C 120 232 210 250 300 244 S 470 222 560 246 S 620 258 640 252 V 400 H 0 Z" fill="url(#hf-h2)" /></g>
      <g class="l" :style="layer(28)">
        <rect y="292" width="640" height="108" fill="url(#hf-water)" />
        <g stroke="#FFFFFF" stroke-width="1.4" stroke-linecap="round" opacity=".75">
          <path d="M330 312h80M344 324h52M320 338h104M352 352h40" />
        </g>
        <path d="M0 296 C 80 278 150 290 230 286 S 330 300 420 292 S 560 284 640 296 V 300 H 0 Z" fill="url(#hf-h3)" opacity=".9" />
      </g>
    </svg>

    <div class="grain" />
    <!-- viewfinder overlay -->
    <span class="corner tl" /><span class="corner tr" /><span class="corner bl" /><span class="corner br" />
    <span class="focus" />
    <span class="hud top mono"><i class="rec" />REC&nbsp;&nbsp;00:00:01:12</span>
    <span class="hud right mono">24 fps · 1/50</span>
    <span class="hud bottom mono">Lumen · natural light</span>
    <span class="thirds" />
  </figure>
</template>

<style scoped>
.frame {
  position: relative;
  margin: 0;
  aspect-ratio: 4 / 5;
  max-height: 72vh;
  border-radius: 26px;
  overflow: hidden;
  isolation: isolate;
  box-shadow: 0 40px 90px rgba(48, 58, 55, 0.16), 0 0 0 1px rgba(48, 58, 55, 0.06);
  transform: rotate(-1.2deg);
}
.scene {
  position: absolute;
  inset: -4%;
  width: 108%;
  height: 108%;
  animation: kenburns 30s var(--ease-film) infinite alternate;
}
.l {
  will-change: transform;
}
.mist {
  animation: mist 18s ease-in-out infinite alternate;
}
.grain {
  position: absolute;
  inset: 0;
  opacity: 0.12;
  mix-blend-mode: multiply;
  background-image: url("data:image/svg+xml;utf8,<svg xmlns='http://www.w3.org/2000/svg' width='140' height='140'><filter id='n'><feTurbulence type='fractalNoise' baseFrequency='.95' numOctaves='2' stitchTiles='stitch'/><feColorMatrix values='0 0 0 0 .19  0 0 0 0 .23  0 0 0 0 .22  0 0 0 .6 0'/></filter><rect width='100%' height='100%' filter='url(%23n)'/></svg>");
}
.corner {
  position: absolute;
  width: 22px;
  height: 22px;
  border: 1.5px solid rgba(255, 255, 255, 0.9);
}
.tl { top: 18px; left: 18px; border-right: 0; border-bottom: 0; }
.tr { top: 18px; right: 18px; border-left: 0; border-bottom: 0; }
.bl { bottom: 18px; left: 18px; border-right: 0; border-top: 0; }
.br { bottom: 18px; right: 18px; border-left: 0; border-top: 0; }
.focus {
  position: absolute;
  left: 50%;
  top: 46%;
  width: 64px;
  height: 44px;
  border: 1px solid rgba(255, 255, 255, 0.85);
  border-radius: 4px;
  transform: translate(-50%, -50%);
  animation: focus 4.5s var(--ease-film) infinite;
}
.thirds {
  position: absolute;
  inset: 0;
  pointer-events: none;
  background:
    linear-gradient(90deg, transparent calc(33.33% - 0.5px), rgba(255, 255, 255, 0.22) 33.33%, transparent calc(33.33% + 0.5px),
      transparent calc(66.66% - 0.5px), rgba(255, 255, 255, 0.22) 66.66%, transparent calc(66.66% + 0.5px)),
    linear-gradient(0deg, transparent calc(33.33% - 0.5px), rgba(255, 255, 255, 0.22) 33.33%, transparent calc(33.33% + 0.5px),
      transparent calc(66.66% - 0.5px), rgba(255, 255, 255, 0.22) 66.66%, transparent calc(66.66% + 0.5px));
}
.hud {
  position: absolute;
  font-size: 10px;
  letter-spacing: 0.16em;
  color: rgba(255, 255, 255, 0.95);
  text-shadow: 0 1px 6px rgba(48, 58, 55, 0.35);
}
.hud.top { top: 24px; left: 50%; transform: translateX(-50%); display: inline-flex; align-items: center; gap: 6px; }
.hud.right { right: 26px; bottom: 26px; }
.hud.bottom { left: 26px; bottom: 26px; }
.rec {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: #e07a5f;
  animation: blink 1.6s steps(2, start) infinite;
}
@keyframes kenburns {
  to { transform: scale(1.06) translate3d(-1.5%, -1%, 0); }
}
@keyframes mist {
  to { transform: translateX(-30px); opacity: 0.35; }
}
@keyframes focus {
  0%, 70%, 100% { transform: translate(-50%, -50%) scale(1); }
  80% { transform: translate(-50%, -50%) scale(0.86); }
  88% { transform: translate(-50%, -50%) scale(1.04); }
}
@keyframes blink {
  to { visibility: hidden; }
}
@media (prefers-reduced-motion: reduce) {
  .scene, .mist, .focus, .rec { animation: none; }
}
</style>
