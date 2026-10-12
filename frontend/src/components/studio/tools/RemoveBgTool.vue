<script setup lang="ts">
import { ref } from 'vue'
import ToolShell from '../ToolShell.vue'
import MediaPicker from '../MediaPicker.vue'
import { mediaApi } from '@/api/endpoints'
import { useTaskSubmit } from '@/composables/useTaskSubmit'

const selected = ref<string[]>([])
const { busy, error, run } = useTaskSubmit()

function submit() {
  void run(() => mediaApi.removeBackground(selected.value[0]), '抠图任务已提交')
}
</script>

<template>
  <ToolShell icon="magic" kicker="Image · Background" title="AI 抠图" subtitle="使用本地 rembg（u2net 模型）去除背景，输出透明 PNG。模型在本机运行，图片不会上传到任何第三方。首次运行需要加载模型，会稍慢一些。"
             feature="image.removebg" tools="rembg（Conda 环境）" :busy="busy" :error="error" :can-submit="!!selected[0]" @submit="submit">
    <MediaPicker v-model="selected" :kinds="['IMAGE']" label="图片" accept="image/png,image/jpeg,image/webp" />
  </ToolShell>
</template>
