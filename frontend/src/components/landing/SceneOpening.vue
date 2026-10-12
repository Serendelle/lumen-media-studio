<script setup lang="ts">
import { ref } from 'vue'
import { RouterLink } from 'vue-router'
import LightField from './LightField.vue'
import { gsap, useGsap } from '@/composables/useMotion'
import { useAuthStore } from '@/stores/auth'

const auth = useAuthStore()
const root = ref<HTMLElement | null>(null)

useGsap(({ reduced }) => {
  const el = root.value!
  const q = gsap.utils.selector(el)
  // Content must never depend on animation frames to become visible: a tab opened in the background gets no
  // requestAnimationFrame, so in that case (and for reduced motion) the final state is shown directly.
  if (reduced || document.visibilityState === 'hidden') {
    gsap.set(q('[data-reveal]'), { opacity: 1, y: 0 })
    gsap.set(q('.bar'), { scaleY: 0 })
    return
  }
  // slow camera push-in on the light field, then the title "develops" line by line
  const push = gsap.fromTo(q('.lens'), { scale: 1.12 }, { scale: 1, duration: 3.2, ease: 'power2.out' })
  const tl = gsap.timeline({ defaults: { ease: 'power3.out' }, delay: 0.25 })
  // safety net: if frames stall (throttled tab, busy main thread), jump to the end instead of leaving a blank hero
  window.setTimeout(() => {
    if (tl.progress() < 0.05) {
      tl.progress(1)
      push.progress(1)
      gsap.set(q('.bar'), { scaleY: 0 })
    }
  }, 2500)
  tl.from(q('.timecode'), { opacity: 0, duration: 0.6 })
    .fromTo(q('.line > span'), { yPercent: 110 }, { yPercent: 0, duration: 1.15, stagger: 0.14 }, '-=0.3')
    .fromTo(q('[data-reveal]'), { opacity: 0, y: 18 }, { opacity: 1, y: 0, duration: 0.9, stagger: 0.1 }, '-=0.7')
    // letterbox "curtain" opens, revealing the navigation like the start of a film
    .to(q('.bar'), { scaleY: 0, duration: 1.2, ease: 'power3.inOut' }, '-=0.6')

  // as the visitor scrolls away the shot drifts and fades like a dolly-out
  gsap.to(q('.copy'), {
    yPercent: -18,
    opacity: 0.25,
    ease: 'none',
    scrollTrigger: { trigger: el, start: 'top top', end: 'bottom top', scrub: true },
  })
  gsap.to(q('.lens'), {
    scale: 1.06,
    yPercent: 6,
    ease: 'none',
    scrollTrigger: { trigger: el, start: 'top top', end: 'bottom top', scrub: true },
  })
})
</script>

<template>
  <section ref="root" class="opening" aria-labelledby="hero-title">
    <div class="lens"><LightField /></div>
    <span class="bar top" aria-hidden="true" />
    <span class="bar bottom" aria-hidden="true" />

    <div class="timecode mono" aria-hidden="true">
      <span>SCENE 01 — OPENING</span>
      <span class="rec"><i /> 00:00:01:12</span>
    </div>

    <div class="copy container">
      <p class="eyebrow" data-reveal>Lumen · 流明智媒 · Media Studio for Creators</p>
      <h1 id="hero-title" class="title">
        <span class="line"><span>让每一帧，</span></span>
        <span class="line"><span>都<em class="display">恰到好处</em>。</span></span>
      </h1>
      <p class="en display" data-reveal>Every frame, in its best light.</p>
      <p class="lede" data-reveal>
        压缩、转码、提取音轨、清理隐私信息——用专业参数精确掌控，或者直接说出你的想法，
        让 AI 规划、确认、执行。两种方式，同一套真实的处理引擎。
      </p>
      <div class="actions" data-reveal>
        <RouterLink class="btn" :to="auth.isAuthenticated ? '/studio' : '/register'">开始创作</RouterLink>
        <a class="btn btn--ghost" href="#capabilities">看看它能做什么</a>
      </div>
    </div>

    <a class="scroll-cue" href="#capabilities" aria-label="向下滚动">
      <span class="mono">SCROLL</span>
      <span class="tick" />
    </a>
  </section>
</template>

<style scoped>
.opening {
  position: relative;
  min-height: 100svh;
  display: grid;
  align-items: center;
  overflow: hidden;
  isolation: isolate;
}
.lens {
  position: absolute;
  inset: 0;
  z-index: -1;
  transform-origin: 50% 40%;
  will-change: transform;
}
.bar {
  position: absolute;
  left: 0;
  right: 0;
  height: clamp(56px, 9vh, 88px);
  background: var(--ink);
  z-index: 50; /* above the nav while closed */
  will-change: transform;
}
.bar.top {
  top: 0;
  transform-origin: top;
}
.bar.bottom {
  bottom: 0;
  transform-origin: bottom;
}
.timecode {
  position: absolute;
  top: 84px;
  left: 0;
  right: 0;
  display: flex;
  justify-content: space-between;
  max-width: var(--maxw);
  margin: 0 auto;
  padding: 0 var(--gutter);
  font-size: 11px;
  letter-spacing: 0.16em;
  color: var(--text-3);
}
.rec {
  display: inline-flex;
  align-items: center;
  gap: 8px;
}
.rec i {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: #c47a5a;
  animation: blink 1.6s steps(2, start) infinite;
}
@keyframes blink {
  to {
    visibility: hidden;
  }
}
.copy {
  position: relative;
  padding-top: 150px;
  padding-bottom: 120px;
  will-change: transform, opacity;
}
.title {
  margin-top: var(--sp-4);
  font-size: var(--fs-hero);
  font-weight: 300;
  line-height: 1.08;
  letter-spacing: -0.02em;
}
.line {
  display: block;
  overflow: hidden;
  padding-bottom: 0.06em;
}
.line > span {
  display: inline-block;
  will-change: transform;
}
.title em {
  font-style: normal; /* no faux italics on CJK */
  font-weight: 400;
  color: var(--mint-ink);
}
.en {
  margin-top: var(--sp-4);
  font-size: var(--fs-xl);
  font-style: italic;
  color: var(--text-2);
}
.lede {
  max-width: 34em;
  margin-top: var(--sp-5);
  font-size: var(--fs-lg);
  color: var(--text-2);
}
.actions {
  display: flex;
  flex-wrap: wrap;
  gap: var(--sp-3);
  margin-top: var(--sp-6);
}
.scroll-cue {
  position: absolute;
  left: 50%;
  bottom: 28px;
  transform: translateX(-50%);
  display: grid;
  justify-items: center;
  gap: 8px;
  font-size: 10px;
  letter-spacing: 0.3em;
  color: var(--text-3);
  text-decoration: none;
}
.tick {
  width: 1px;
  height: 34px;
  background: linear-gradient(var(--ink-3), transparent);
  transform-origin: top;
  animation: tick 2.2s var(--ease-film) infinite;
}
@keyframes tick {
  0% {
    transform: scaleY(0);
  }
  50% {
    transform: scaleY(1);
  }
  100% {
    transform: scaleY(1);
    opacity: 0;
  }
}
@media (max-width: 640px) {
  .timecode {
    display: none;
  }
  .copy {
    padding-top: 110px;
  }
  .lede {
    font-size: var(--fs-md);
  }
}
</style>
