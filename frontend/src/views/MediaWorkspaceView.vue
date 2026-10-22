<script setup lang="ts">
import { computed, defineAsyncComponent } from 'vue'
import { useRoute } from 'vue-router'
import TaskTray from '@/components/studio/TaskTray.vue'
import { useSystemStore } from '@/stores/system'

const route = useRoute()
const system = useSystemStore()

const TOOLS = [
  { id: 'compress', icon: 'compress', label: '视频压缩', group: '视频', feature: 'video.compress', comp: defineAsyncComponent(() => import('@/components/studio/tools/CompressTool.vue')) },
  { id: 'convert', icon: 'convert', label: '格式转换', group: '视频', feature: 'video.convert', comp: defineAsyncComponent(() => import('@/components/studio/tools/ConvertTool.vue')) },
  { id: 'watermark', icon: 'watermark', label: '视频水印', group: '视频', feature: 'video.watermark', comp: defineAsyncComponent(() => import('@/components/studio/tools/WatermarkTool.vue')) },
  { id: 'audio', icon: 'audio', label: '音频提取', group: '音频', feature: 'audio.extract', comp: defineAsyncComponent(() => import('@/components/studio/tools/AudioTool.vue')) },
  { id: 'image', icon: 'image', label: '图片处理', group: '图片', feature: 'image.process', comp: defineAsyncComponent(() => import('@/components/studio/tools/ImageTool.vue')) },
  { id: 'removebg', icon: 'magic', label: 'AI 抠图', group: '图片', feature: 'image.removebg', comp: defineAsyncComponent(() => import('@/components/studio/tools/RemoveBgTool.vue')) },
  { id: 'pdf', icon: 'pdf', label: 'PDF / 文档转换', group: '文档', feature: 'document.convert', comp: defineAsyncComponent(() => import('@/components/studio/tools/PdfTool.vue')) },
  { id: 'metadata', icon: 'shield', label: '元数据与隐私', group: '隐私', feature: 'metadata.view', comp: defineAsyncComponent(() => import('@/components/studio/tools/MetadataTool.vue')) },
] as const

// the tool is chosen in the sidebar; smart mode has its own page (/workspace/ai)
const toolId = computed(() => (TOOLS.some((t) => t.id === route.query.tool) ? (route.query.tool as string) : 'compress'))
const tool = computed(() => TOOLS.find((t) => t.id === toolId.value)!)
const unavailable = computed(() => !!system.caps && !system.feature(tool.value.feature))
</script>

<template>
  <div class="studio workspace-page pane-page">
    <div class="panes">
      <main id="main" class="pane pane--main">
        <p v-if="unavailable" class="na" role="note">「{{ tool.label }}」在当前服务器上不可用：缺少所需的处理组件，请查看部署文档或运行环境检查脚本。</p>
        <KeepAlive>
          <component :is="tool.comp" :key="tool.id" />
        </KeepAlive>
      </main>
      <div class="pane pane--side"><TaskTray /></div>
    </div>
  </div>
</template>

<style scoped>
.na {
  margin-bottom: var(--sp-4);
  padding: 10px 12px;
  border-radius: var(--r-sm);
  background: var(--apricot);
  color: var(--apricot-ink);
  font-size: var(--fs-sm);
}
</style>
