<script setup lang="ts">
import { ref } from 'vue'
import { gsap, useGsap } from '@/composables/useMotion'

/** Four "shots". Each pairs a capability with a fragment styled from the real product UI (illustrative, no claims). */
const shots = [
  {
    id: 'compress',
    tc: '00:00:12:04',
    kicker: '视频压缩 · Compress',
    title: '体积，听你的。',
    body: '设定目标大小、分辨率、H.264 / H.265、帧率与音频码率。提交前先评估目标能否实现，做不到会直说，并给出可行的建议。',
  },
  {
    id: 'audio',
    tc: '00:00:24:16',
    kicker: '音频提取 · Extract',
    title: '把声音，单独带走。',
    body: 'MP3、AAC、M4A、WAV、FLAC，按格式配置码率、采样率与声道。进度来自真实的编码时间轴，不是动画。',
  },
  {
    id: 'privacy',
    tc: '00:00:37:02',
    kicker: '隐私清理 · Privacy',
    title: '发布之前，抹去坐标。',
    body: '查看并编辑标题、作者、拍摄时间与 GPS。一键清理位置信息后，系统会重新读取输出文件，独立核验它真的干净了。',
  },
  {
    id: 'ai',
    tc: '00:00:49:20',
    kicker: '智能模式 · Agents',
    title: '说出想法，剩下的交给它。',
    body: '四位 Agent 分工协作：理解需求、规划步骤、调用真实工具执行、审核输出。关键参数总会先请你确认。',
  },
] as const

const root = ref<HTMLElement | null>(null)

useGsap(({ reduced }) => {
  const el = root.value!
  const q = gsap.utils.selector(el)
  if (reduced) return

  const mm = gsap.matchMedia()
  mm.add('(min-width: 900px)', () => {
    const texts = q('.shot-text')
    const frames = q('.shot .frame')
    gsap.set([texts.slice(1), frames.slice(1)], { autoAlpha: 0 })
    const tl = gsap.timeline({
      defaults: { ease: 'power2.inOut' },
      scrollTrigger: {
        trigger: q('.stage')[0],
        start: 'top top',
        end: () => `+=${window.innerHeight * (shots.length - 0.4)}`,
        pin: true,
        scrub: 0.8,
        anticipatePin: 1,
      },
    })
    for (let i = 1; i < shots.length; i++) {
      tl.to(texts[i - 1], { autoAlpha: 0, y: -40, duration: 0.5 })
        .to(frames[i - 1], { autoAlpha: 0, y: -24, scale: 0.97, duration: 0.5 }, '<')
        .fromTo(texts[i], { autoAlpha: 0, y: 40 }, { autoAlpha: 1, y: 0, duration: 0.6 }, '>-0.15')
        .fromTo(frames[i], { autoAlpha: 0, y: 40, scale: 1.03 }, { autoAlpha: 1, y: 0, scale: 1, duration: 0.6 }, '<')
        .to(q('.progress-fill'), { scaleX: (i + 1) / shots.length, duration: 0.6 }, '<')
        .to({}, { duration: 0.5 }) // hold the shot
    }
    // parallax depth: the big shot number travels slower than the content
    tl.to(q('.depth'), { yPercent: -30, ease: 'none', duration: tl.duration() }, 0)
  })
  mm.add('(max-width: 899px)', () => {
    q('.shot').forEach((s: Element) => {
      gsap.from(s, { autoAlpha: 0, y: 30, duration: 0.8, ease: 'power3.out', scrollTrigger: { trigger: s, start: 'top 82%' } })
    })
  })
})
</script>

<template>
  <section id="capabilities" ref="root" class="caps" aria-labelledby="caps-title">
    <div class="stage">
      <div class="depth display" aria-hidden="true">02</div>
      <div class="container grid">
        <header class="head">
          <p class="eyebrow">Scene 02 — Capabilities</p>
          <h2 id="caps-title" class="sr-only">核心能力</h2>
          <div class="progress" aria-hidden="true"><span class="progress-fill" /></div>
        </header>

        <div class="shots">
          <article v-for="s in shots" :key="s.id" class="shot">
            <div class="shot-text">
              <p class="tc mono">{{ s.tc }}</p>
              <p class="kicker">{{ s.kicker }}</p>
              <h3 class="title">{{ s.title }}</h3>
              <p class="body">{{ s.body }}</p>
            </div>
            <div class="frame" aria-hidden="true">
              <template v-if="s.id === 'compress'">
                <div class="ui-card">
                  <div class="row between"><span class="label">目标体积</span><span class="mono strong">20 MB</span></div>
                  <div class="slider"><span class="knob" style="left: 38%" /></div>
                  <div class="chips"><span class="chip chip--mint">1080p</span><span class="chip">720p</span><span class="chip">H.265</span><span class="chip">30 fps</span></div>
                  <div class="note"><span class="dot ok" />提交前评估：目标可实现时才开始编码</div>
                </div>
                <div class="ui-card small float">
                  <span class="label">两遍编码</span>
                  <div class="mini-bar"><span style="transform: scaleX(.62)" /></div>
                  <span class="mono muted">encoding</span>
                </div>
              </template>
              <div v-else-if="s.id === 'audio'" class="ui-card">
                <span class="label">音轨</span>
                <div class="wave">
                  <i v-for="n in 42" :key="n" :style="{ transform: `scaleY(${0.25 + Math.abs(Math.sin(n * 0.7)) * 0.75})` }" />
                </div>
                <div class="chips"><span class="chip chip--fog">MP3</span><span class="chip">AAC</span><span class="chip">WAV</span><span class="chip">FLAC</span></div>
                <div class="row between"><span class="label">采样率</span><span class="mono">48 kHz · 立体声</span></div>
              </div>
              <div v-else-if="s.id === 'privacy'" class="ui-card">
                <span class="label">元数据</span>
                <dl class="meta">
                  <div><dt>拍摄设备</dt><dd>保留</dd></div>
                  <div><dt>拍摄时间</dt><dd>保留</dd></div>
                  <div class="struck"><dt>GPS 纬度</dt><dd class="mono">31.2304°N</dd></div>
                  <div class="struck"><dt>GPS 经度</dt><dd class="mono">121.4737°E</dd></div>
                </dl>
                <div class="note"><span class="dot ok" />重新读取输出文件，核验无位置标签</div>
              </div>
              <div v-else class="ui-card">
                <p class="bubble">帮我把这个视频压到 20MB 左右，再把音频单独存成 MP3，顺便清掉位置信息。</p>
                <ol class="agents">
                  <li class="done"><span class="dot ok" />需求理解官 · 识别 3 项操作</li>
                  <li class="done"><span class="dot ok" />任务规划官 · 生成执行计划</li>
                  <li class="now"><span class="dot run" />处理执行官 · 等待你确认参数</li>
                  <li><span class="dot" />质量审核官</li>
                </ol>
              </div>
            </div>
          </article>
        </div>
      </div>
    </div>
  </section>
</template>

<style scoped>
.caps {
  position: relative;
  background: linear-gradient(180deg, var(--cream) 0%, #f2f4ee 100%);
}
.stage {
  position: relative;
  min-height: 100vh;
  display: grid;
  align-items: center;
  overflow: hidden;
  padding: 96px 0;
}
.depth {
  position: absolute;
  right: -2vw;
  top: 8vh;
  font-size: clamp(10rem, 28vw, 26rem);
  line-height: 1;
  color: rgba(48, 58, 55, 0.045);
  pointer-events: none;
  will-change: transform;
}
.grid {
  position: relative;
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(0, 1.05fr);
  grid-template-rows: auto 1fr;
  gap: var(--sp-6) var(--sp-8);
  align-items: center;
}
.head {
  grid-column: 1 / -1;
  display: flex;
  align-items: center;
  gap: var(--sp-5);
}
.progress {
  flex: 1;
  max-width: 240px;
  height: 1px;
  background: var(--line-strong);
}
.progress-fill {
  display: block;
  height: 100%;
  background: var(--ink);
  transform-origin: left;
  transform: scaleX(0.25);
}
.shots {
  grid-column: 1 / -1;
  position: relative;
  display: grid;
}
.shot {
  grid-area: 1 / 1;
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(0, 1.05fr);
  gap: var(--sp-8);
  align-items: center;
}
.tc {
  font-size: 11px;
  letter-spacing: 0.16em;
  color: var(--text-3);
}
.kicker {
  margin-top: var(--sp-3);
  font-size: var(--fs-sm);
  color: var(--mint-ink);
  letter-spacing: 0.08em;
}
.title {
  margin-top: var(--sp-3);
  font-size: var(--fs-2xl);
  font-weight: 300;
}
.body {
  max-width: 30em;
  margin-top: var(--sp-4);
  color: var(--text-2);
}
.frame {
  position: relative;
  min-height: 360px;
  display: grid;
  place-items: center;
  border-radius: var(--r-lg);
  background:
    radial-gradient(80% 70% at 30% 20%, var(--mint) 0%, transparent 60%),
    radial-gradient(70% 70% at 90% 90%, var(--apricot) 0%, transparent 60%),
    var(--fog);
  padding: var(--sp-6);
  will-change: transform, opacity;
}
.ui-card {
  width: min(380px, 100%);
  display: grid;
  gap: 14px;
  padding: 20px;
  border-radius: var(--r-md);
  background: rgba(252, 252, 249, 0.94);
  box-shadow: var(--shadow-2);
}
.ui-card.small {
  width: 200px;
  gap: 8px;
  padding: 14px;
}
.float {
  position: absolute;
  right: 6%;
  bottom: 10%;
}
.row {
  display: flex;
  align-items: center;
}
.between {
  justify-content: space-between;
}
.label {
  font-size: var(--fs-xs);
  color: var(--text-3);
  letter-spacing: 0.06em;
}
.strong {
  font-size: var(--fs-lg);
}
.muted {
  font-size: 11px;
  color: var(--text-3);
}
.slider {
  position: relative;
  height: 4px;
  border-radius: 999px;
  background: linear-gradient(90deg, var(--mint-ink) 38%, var(--surface-sunk) 38%);
}
.knob {
  position: absolute;
  top: 50%;
  width: 16px;
  height: 16px;
  border-radius: 50%;
  background: var(--paper);
  border: 2px solid var(--mint-ink);
  transform: translate(-50%, -50%);
}
.chips {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}
.note {
  display: flex;
  gap: 8px;
  align-items: center;
  font-size: var(--fs-xs);
  color: var(--text-2);
}
.dot {
  flex: none;
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: var(--line-strong);
}
.dot.ok {
  background: var(--mint-ink);
}
.dot.run {
  background: var(--fog-ink);
  box-shadow: 0 0 0 4px rgba(70, 103, 125, 0.18);
}
.mini-bar {
  height: 4px;
  border-radius: 999px;
  overflow: hidden;
  background: var(--surface-sunk);
}
.mini-bar span {
  display: block;
  height: 100%;
  background: var(--fog-ink);
  transform-origin: left;
}
.wave {
  display: flex;
  align-items: center;
  gap: 3px;
  height: 64px;
}
.wave i {
  flex: 1;
  height: 100%;
  border-radius: 2px;
  background: linear-gradient(var(--fog-ink), var(--mint-ink));
  opacity: 0.75;
}
.meta {
  margin: 0;
  display: grid;
  gap: 8px;
  font-size: var(--fs-sm);
}
.meta div {
  display: flex;
  justify-content: space-between;
  gap: 12px;
}
.meta dt {
  color: var(--text-2);
}
.meta dd {
  margin: 0;
}
.struck dd,
.struck dt {
  text-decoration: line-through;
  text-decoration-color: var(--apricot-ink);
  color: var(--text-3);
}
.bubble {
  padding: 12px 14px;
  border-radius: 14px 14px 4px 14px;
  background: var(--ink);
  color: var(--cream);
  font-size: var(--fs-sm);
  line-height: 1.55;
}
.agents {
  margin: 0;
  padding: 0;
  list-style: none;
  display: grid;
  gap: 10px;
  font-size: var(--fs-sm);
  color: var(--text-3);
}
.agents li {
  display: flex;
  align-items: center;
  gap: 10px;
}
.agents .done {
  color: var(--text-2);
}
.agents .now {
  color: var(--text);
  font-weight: 500;
}

/* mobile: stacked shots, each with its own frame */
@media (max-width: 899px) {
  .stage {
    min-height: auto;
    padding: 72px 0;
  }
  .grid {
    grid-template-columns: 1fr;
  }
  .shots {
    gap: var(--sp-8);
  }
  .shot {
    grid-area: auto;
    grid-template-columns: 1fr;
    gap: var(--sp-5);
  }
  .frame {
    min-height: 280px;
    padding: var(--sp-5);
  }
  .depth {
    display: none;
  }
}
</style>
