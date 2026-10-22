<script setup lang="ts">
import TaskTray from '@/components/studio/TaskTray.vue'
import UniversalConversionPanel from '@/components/document/UniversalConversionPanel.vue'
</script>

<template>
  <div class="docs workspace-page pane-page">
    <div class="panes">
      <main id="main" class="pane pane--main"><UniversalConversionPanel title="文档转换工作台" /></main>
      <div class="pane pane--side"><TaskTray /></div>
    </div>
  </div>
</template>

<style scoped>
.pane-page > .panes { grid-template-columns: minmax(0, 1fr) 320px; }
@media (max-width: 1180px) { .pane-page > .panes { grid-template-columns: minmax(0, 1fr); } }
</style>
