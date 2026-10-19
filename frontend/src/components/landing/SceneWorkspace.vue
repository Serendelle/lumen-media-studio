<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { RouterLink } from 'vue-router'
import { gsap, useGsap } from '@/composables/useMotion'
import { useAuthStore } from '@/stores/auth'
import { useSystemStore } from '@/stores/system'

const auth = useAuthStore()
const system = useSystemStore()
const root = ref<HTMLElement | null>(null)

onMounted(() => void system.load())

const entry = (mode: 'pro' | 'ai') =>
  auth.isAuthenticated ? { path: '/workspace/media', query: { mode } } : { path: '/register', query: { redirect: `/workspace/media?mode=${mode}` } }

/** Real engine status from /api/system/capabilities — not decoration. */
const engines = computed(() => {
  const t = system.caps?.tools
  if (!t) return []
  return [
    { name: 'FFmpeg', ok: t.ffmpeg && t.ffprobe },
    { name: 'ExifTool', ok: t.exiftool },
    { name: 'ImageMagick', ok: t.magick },
    { name: 'Ghostscript', ok: t.ghostscript },
    { name: 'rembg', ok: t.rembg },
    { name: 'DeepSeek', ok: system.caps?.ai.configured ?? false },
  ]
})

useGsap(({ reduced }) => {
  if (reduced) return
  const q = gsap.utils.selector(root.value!)
  gsap.from(q('.reveal'), {
    autoAlpha: 0,
    y: 34,
    duration: 1,
    ease: 'power3.out',
    stagger: 0.12,
    scrollTrigger: { trigger: root.value!, start: 'top 70%' },
  })
  // the two mode cards slide in from opposite sides, like a cut between two angles
  gsap.from(q('.mode--pro'), { xPercent: -8, autoAlpha: 0, duration: 1.1, ease: 'power3.out', scrollTrigger: { trigger: q('.modes')[0], start: 'top 80%' } })
  gsap.from(q('.mode--ai'), { xPercent: 8, autoAlpha: 0, duration: 1.1, ease: 'power3.out', scrollTrigger: { trigger: q('.modes')[0], start: 'top 80%' } })
})
</script>

<template>
  <section id="workspace" ref="root" class="ws" aria-labelledby="ws-title">
    <div class="container">
      <p class="eyebrow reveal">Scene 03 — Workspace</p>
      <h2 id="ws-title" class="heading reveal">两种工作方式，<br />同一套真实引擎。</h2>
      <p class="sub reveal">无论用哪种模式，最终都调用同一个媒体处理服务。区别只在于你是亲手设定每个参数，还是把想法交给 AI 来规划。</p>

      <div class="modes">
        <RouterLink :to="entry('pro')" class="mode mode--pro">
          <span class="tag mono">PROFESSIONAL</span>
          <h3>专业模式</h3>
          <p>为懂参数的创作者准备：码率、CRF、分辨率、编码器、采样率、元数据字段，逐项精确设定，支持批量处理。</p>
          <ul>
            <li>压缩可行性预评估</li>
            <li>实时进度、取消与重试</li>
            <li>处理前后对比与下载</li>
          </ul>
          <span class="go">进入专业模式 →</span>
        </RouterLink>
        <RouterLink :to="entry('ai')" class="mode mode--ai">
          <span class="tag mono">AI · MULTI-AGENT</span>
          <h3>智能模式</h3>
          <p>用一句话描述你的需求。Agent 会读取媒体的真实信息，判断目标能否实现，拟出计划，经你确认后执行并审核结果。</p>
          <ul>
            <li>需求理解 → 规划 → 执行 → 审核</li>
            <li>关键操作执行前必须确认</li>
            <li>协作过程全程可见</li>
          </ul>
          <span class="go">进入智能模式 →</span>
        </RouterLink>
      </div>

      <footer class="foot reveal">
        <div class="engines" aria-label="处理引擎状态">
          <span class="label mono">ENGINE STATUS</span>
          <template v-if="engines.length">
            <span v-for="e in engines" :key="e.name" class="engine" :class="{ off: !e.ok }">
              <i />{{ e.name }}<span class="sr-only">{{ e.ok ? '可用' : '不可用' }}</span>
            </span>
          </template>
          <span v-else-if="system.error" class="engine off"><i />后端未连接</span>
          <span v-else class="engine"><i />检测中…</span>
        </div>
        <p class="copy">© 2026 Lumen 流明智媒 · 本地部署 · 你的文件只保存在这台设备上</p>
      </footer>
    </div>
  </section>
</template>

<style scoped>
.ws {
  position: relative;
  padding: var(--sp-9) 0 var(--sp-6);
  background:
    radial-gradient(60% 50% at 85% 10%, rgba(243, 227, 215, 0.7), transparent 70%),
    radial-gradient(50% 50% at 0% 90%, rgba(217, 237, 228, 0.7), transparent 70%),
    var(--cream);
}
.heading {
  margin-top: var(--sp-4);
  font-size: var(--fs-2xl);
  font-weight: 300;
}
.sub {
  max-width: 38em;
  margin-top: var(--sp-4);
  color: var(--text-2);
}
.modes {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: var(--sp-5);
  margin-top: var(--sp-7);
}
.mode {
  position: relative;
  display: grid;
  align-content: start;
  gap: var(--sp-4);
  min-height: 380px;
  padding: var(--sp-6);
  border-radius: var(--r-lg);
  border: 1px solid var(--line);
  text-decoration: none;
  overflow: hidden;
  transition: transform var(--t-med) var(--ease-out), box-shadow var(--t-med) var(--ease-out);
}
.mode:hover {
  transform: translateY(-4px);
  box-shadow: var(--shadow-3);
}
.mode--pro {
  background: linear-gradient(160deg, var(--paper), var(--fog) 140%);
}
.mode--ai {
  background: linear-gradient(160deg, var(--paper), var(--mint) 130%);
}
.tag {
  font-size: 11px;
  letter-spacing: 0.18em;
  color: var(--text-3);
}
.mode h3 {
  font-size: var(--fs-xl);
  font-weight: 400;
}
.mode p {
  color: var(--text-2);
}
.mode ul {
  margin: 0;
  padding: 0;
  list-style: none;
  display: grid;
  gap: 8px;
  font-size: var(--fs-sm);
}
.mode li::before {
  content: '';
  display: inline-block;
  width: 14px;
  height: 1px;
  margin-right: 10px;
  vertical-align: middle;
  background: var(--ink-3);
}
.go {
  margin-top: auto;
  font-size: var(--fs-sm);
  font-weight: 500;
}
.foot {
  display: flex;
  flex-wrap: wrap;
  justify-content: space-between;
  gap: var(--sp-4);
  margin-top: var(--sp-8);
  padding-top: var(--sp-5);
  border-top: 1px solid var(--line);
  font-size: var(--fs-xs);
  color: var(--text-3);
}
.engines {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 12px;
}
.engine {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  color: var(--text-2);
}
.engine i {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: var(--mint-ink);
}
.engine.off {
  color: var(--text-3);
}
.engine.off i {
  background: transparent;
  border: 1px solid var(--ink-3);
}
@media (max-width: 760px) {
  .modes {
    grid-template-columns: 1fr;
  }
  .mode {
    min-height: auto;
    padding: var(--sp-5);
  }
}
</style>
