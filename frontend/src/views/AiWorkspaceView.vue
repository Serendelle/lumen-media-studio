<script setup lang="ts">
import TaskTray from '@/components/studio/TaskTray.vue'
import AiStudio from '@/components/studio/AiStudio.vue'
</script>

<template>
  <div class="studio workspace-page pane-page">
    <div class="panes">
      <main id="main" class="pane pane--main">
        <AiStudio />
      </main>
      <div class="pane pane--side"><TaskTray /></div>
    </div>
  </div>
</template>
