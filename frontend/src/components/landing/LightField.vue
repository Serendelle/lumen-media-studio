<script setup lang="ts">
/**
 * The opening "shot": soft daylight through a window — drifting low-saturation light pools, two diagonal
 * light shafts and static film grain. Pure CSS gradients animated with transform/opacity only (GPU friendly);
 * no video, no image downloads.
 */
</script>

<template>
  <div class="field" aria-hidden="true">
    <div class="layer pools">
      <span class="pool p1" />
      <span class="pool p2" />
      <span class="pool p3" />
      <span class="pool p4" />
    </div>
    <div class="layer shafts">
      <span class="shaft s1" />
      <span class="shaft s2" />
    </div>
    <div class="grain" />
    <div class="vignette" />
  </div>
</template>

<style scoped>
.field {
  position: absolute;
  inset: 0;
  overflow: hidden;
  background:
    radial-gradient(120% 90% at 50% 0%, #fbfbf6 0%, var(--cream) 55%, #eef0ea 100%);
}
.layer {
  position: absolute;
  inset: -10%;
  will-change: transform;
}
.pool {
  position: absolute;
  border-radius: 50%;
  filter: blur(60px);
  opacity: 0.9;
  will-change: transform;
}
.p1 {
  width: 52vmax;
  height: 52vmax;
  left: -8%;
  top: 8%;
  background: radial-gradient(circle at 40% 40%, var(--mint), transparent 68%);
  animation: drift1 26s var(--ease-film) infinite alternate;
}
.p2 {
  width: 46vmax;
  height: 46vmax;
  right: -6%;
  top: -6%;
  background: radial-gradient(circle at 50% 50%, var(--fog), transparent 66%);
  animation: drift2 32s var(--ease-film) infinite alternate;
}
.p3 {
  width: 40vmax;
  height: 40vmax;
  right: 12%;
  bottom: -14%;
  background: radial-gradient(circle at 50% 50%, var(--apricot), transparent 64%);
  animation: drift3 28s var(--ease-film) infinite alternate;
}
.p4 {
  width: 24vmax;
  height: 24vmax;
  left: 34%;
  top: 30%;
  background: radial-gradient(circle, rgba(255, 255, 255, 0.9), transparent 70%);
  animation: drift2 22s var(--ease-film) infinite alternate-reverse;
}
.shaft {
  position: absolute;
  top: -20%;
  width: 22vw;
  height: 150%;
  background: linear-gradient(90deg, transparent, rgba(255, 255, 255, 0.55) 45%, rgba(255, 255, 255, 0.55) 55%, transparent);
  transform: rotate(18deg);
  filter: blur(10px);
  mix-blend-mode: soft-light;
  animation: breathe 9s ease-in-out infinite alternate;
}
.s1 {
  left: 18%;
}
.s2 {
  left: 46%;
  width: 10vw;
  animation-delay: -4s;
  opacity: 0.7;
}
.grain {
  position: absolute;
  inset: 0;
  opacity: 0.22;
  mix-blend-mode: multiply;
  background-image: url("data:image/svg+xml;utf8,<svg xmlns='http://www.w3.org/2000/svg' width='160' height='160'><filter id='n'><feTurbulence type='fractalNoise' baseFrequency='.9' numOctaves='2' stitchTiles='stitch'/><feColorMatrix values='0 0 0 0 .19  0 0 0 0 .23  0 0 0 0 .22  0 0 0 .55 0'/></filter><rect width='100%' height='100%' filter='url(%23n)'/></svg>");
}
.vignette {
  position: absolute;
  inset: 0;
  background: radial-gradient(ellipse at 50% 45%, transparent 55%, rgba(48, 58, 55, 0.09) 100%);
}
@keyframes drift1 {
  to {
    transform: translate3d(8vw, 6vh, 0) scale(1.08);
  }
}
@keyframes drift2 {
  to {
    transform: translate3d(-7vw, 8vh, 0) scale(0.94);
  }
}
@keyframes drift3 {
  to {
    transform: translate3d(-5vw, -7vh, 0) scale(1.1);
  }
}
@keyframes breathe {
  from {
    opacity: 0.35;
  }
  to {
    opacity: 0.85;
  }
}
@media (prefers-reduced-motion: reduce) {
  .pool,
  .shaft {
    animation: none;
  }
}
</style>
