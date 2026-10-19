<script setup lang="ts">
import { computed, defineAsyncComponent } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import TaskTray from '@/components/studio/TaskTray.vue'
import AiStudio from '@/components/studio/AiStudio.vue'
import Icon from '@/components/common/Icon.vue'
import { useSystemStore } from '@/stores/system'

const route = useRoute()
const router = useRouter()
const system = useSystemStore()

const TOOLS = [
  { id: 'compress', icon: 'compress', label: '视频压缩', group: '视频', feature: 'video.compress', comp: defineAsyncComponent(() => import('@/components/studio/tools/CompressTool.vue')) },
  { id: 'convert', icon: 'convert', label: '格式转换', group: '视频', feature: 'video.convert', comp: defineAsyncComponent(() => import('@/components/studio/tools/ConvertTool.vue')) },
  { id: 'watermark', icon: 'watermark', label: '视频水印', group: '视频', feature: 'video.watermark', comp: defineAsyncComponent(() => import('@/components/studio/tools/WatermarkTool.vue')) },
  { id: 'audio', icon: 'audio', label: '音频提取', group: '音频', feature: 'audio.extract', comp: defineAsyncComponent(() => import('@/components/studio/tools/AudioTool.vue')) },
  { id: 'image', icon: 'image', label: '图片处理', group: '图片', feature: 'image.process', comp: defineAsyncComponent(() => import('@/components/studio/tools/ImageTool.vue')) },
  { id: 'removebg', icon: 'magic', label: 'AI 抠图', group: '图片', feature: 'image.removebg', comp: defineAsyncComponent(() => import('@/components/studio/tools/RemoveBgTool.vue')) },
  { id: 'pdf', icon: 'pdf', label: 'PDF 转换', group: '文档', feature: 'pdf.process', comp: defineAsyncComponent(() => import('@/components/studio/tools/PdfTool.vue')) },
  { id: 'metadata', icon: 'shield', label: '元数据与隐私', group: '隐私', feature: 'metadata.view', comp: defineAsyncComponent(() => import('@/components/studio/tools/MetadataTool.vue')) },
] as const

const mode = computed<'pro' | 'ai'>({
  get: () => (route.query.mode === 'ai' ? 'ai' : 'pro'),
  set: (m) => void router.replace({ query: { ...route.query, mode: m } }),
})
const toolId = computed({
  get: () => (TOOLS.some((t) => t.id === route.query.tool) ? (route.query.tool as string) : 'compress'),
  set: (id: string) => void router.replace({ query: { ...route.query, mode: 'pro', tool: id } }),
})
const tool = computed(() => TOOLS.find((t) => t.id === toolId.value)!)

</script>

<template>
  <div class="studio">
    <header class="bar">
      <h1 class="title">媒体工作台</h1>
      <div class="modes" role="tablist" aria-label="工作模式">
        <button type="button" role="tab" :aria-selected="mode === 'pro'" :class="{ on: mode === 'pro' }" @click="mode = 'pro'">
          专业模式
        </button>
        <button type="button" role="tab" :aria-selected="mode === 'ai'" :class="{ on: mode === 'ai' }" @click="mode = 'ai'">
          智能模式
        </button>
        <span class="glider" :style="{ transform: mode === 'ai' ? 'translateX(100%)' : 'none' }" aria-hidden="true" />
      </div>
      <span class="hint">{{ mode === 'pro' ? '精确掌控每个参数' : '一句话描述需求，确认后执行' }}</span>
    </header>

    <div class="layout" :class="`m-${mode}`">
      <nav v-if="mode === 'pro'" class="rail" aria-label="处理工具">
        <template v-for="(t, i) in TOOLS" :key="t.id">
          <span v-if="i === 0 || TOOLS[i - 1].group !== t.group" class="group">{{ t.group }}</span>
          <button type="button" class="tool" :class="{ on: toolId === t.id, off: system.caps && !system.feature(t.feature) }"
                  :aria-current="toolId === t.id ? 'page' : undefined" @click="toolId = t.id">
            <Icon :name="t.icon" :size="17" class="ti" />
            <span class="tl">{{ t.label }}</span>
            <span v-if="system.caps && !system.feature(t.feature)" class="na">不可用</span>
          </button>
        </template>
      </nav>

      <main id="main" class="panel card">
        <KeepAlive v-if="mode === 'pro'">
          <component :is="tool.comp" :key="tool.id" />
        </KeepAlive>
        <AiStudio v-else />
      </main>

      <TaskTray class="tray" />
    </div>
  </div>
</template>

<style scoped>
.bar {
  display: flex;
  align-items: center;
  gap: var(--sp-4);
  flex-wrap: wrap;
  max-width: 1480px;
  margin: 0 auto;
  padding: var(--sp-5) var(--gutter) 0;
}
.title {
  font-size: var(--fs-xl);
}
.hint {
  font-size: var(--fs-sm);
  color: var(--text-3);
}
.modes {
  position: relative;
  display: grid;
  grid-template-columns: 1fr 1fr;
  padding: 4px;
  border-radius: var(--r-pill);
  background: var(--surface-sunk);
}
.modes button {
  position: relative;
  z-index: 1;
  min-height: 34px;
  padding: 0 18px;
  border: 0;
  background: none;
  border-radius: var(--r-pill);
  font-size: var(--fs-sm);
  color: var(--text-2);
  transition: color var(--t-med);
}
.modes button.on {
  color: var(--cream);
}
.glider {
  position: absolute;
  top: 4px;
  bottom: 4px;
  left: 4px;
  width: calc(50% - 4px);
  border-radius: var(--r-pill);
  background: var(--ink);
  transition: transform var(--t-med) var(--ease-out);
}
.layout {
  display: grid;
  grid-template-columns: 200px minmax(0, 1fr) 340px;
  gap: var(--sp-5);
  align-items: start;
  max-width: 1480px;
  margin: 0 auto;
  padding: var(--sp-5) var(--gutter) var(--sp-8);
}
.layout.m-ai {
  grid-template-columns: minmax(0, 1fr) 340px;
}
.rail {
  position: sticky;
  top: 76px;
  display: grid;
  gap: 2px;
}
.group {
  margin: 12px 0 4px 12px;
  font-family: var(--font-mono);
  font-size: 10px;
  letter-spacing: 0.16em;
  color: var(--text-3);
}
.group:first-child {
  margin-top: 0;
}
.tool {
  position: relative;
  display: flex;
  align-items: center;
  gap: 10px;
  min-height: 40px;
  padding: 0 12px;
  border: 0;
  border-radius: var(--r-sm);
  background: none;
  font-size: var(--fs-sm);
  text-align: left;
  color: var(--text-2);
  transition: background var(--t-fast), color var(--t-fast);
}
.tool:hover {
  background: rgba(255, 255, 255, 0.6);
  color: var(--text);
}
.tool.on {
  background: var(--surface);
  color: var(--text);
  box-shadow: var(--shadow-1);
}
.tool.on::before {
  content: '';
  position: absolute;
  left: 0;
  top: 10px;
  bottom: 10px;
  width: 2px;
  border-radius: 2px;
  background: var(--mint-ink);
}
.tool .ti {
  color: var(--ink-3);
  transition: color var(--t-fast);
}
.tool.on .ti,
.tool:hover .ti {
  color: var(--mint-ink);
}
.tl {
  flex: 1;
}
.tool.off {
  opacity: 0.6;
}
.na {
  font-size: 10px;
  color: var(--apricot-ink);
}
.panel {
  padding: var(--sp-6);
  min-height: 60vh;
}
.tray {
  position: sticky;
  top: 76px;
  max-height: calc(100vh - 100px);
  overflow: auto;
  padding-right: 4px;
}
@media (max-width: 1180px) {
  .layout,
  .layout.m-ai {
    grid-template-columns: 180px minmax(0, 1fr);
  }
  .layout.m-ai {
    grid-template-columns: 1fr;
  }
  .tray {
    grid-column: 1 / -1;
    position: static;
    max-height: none;
  }
}
@media (max-width: 760px) {
  .hint {
    display: none;
  }
  .layout {
    grid-template-columns: 1fr;
    padding-top: var(--sp-4);
  }
  .rail {
    position: static;
    display: flex;
    overflow-x: auto;
    gap: 6px;
    padding-bottom: 4px;
    scrollbar-width: none;
  }
  .group {
    display: none;
  }
  .tool {
    flex: none;
    background: var(--surface);
    border: 1px solid var(--line);
  }
  .tool.on::before {
    display: none;
  }
  .tool.on {
    border-color: var(--mint-ink);
  }
  .panel {
    padding: var(--sp-5);
  }
}
</style>
