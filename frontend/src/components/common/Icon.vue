<script setup lang="ts">
/** Minimal 24px line-icon set (1.6 stroke), drawn inline — no icon font or network request. */
const props = withDefaults(defineProps<{ name: string; size?: number }>(), { size: 18 })

const PATHS: Record<string, string> = {
  compress: 'M4 9V5h4M20 9V5h-4M4 15v4h4M20 15v4h-4M9 12h6M12 9v6',
  convert: 'M4 8h13l-3-3M20 16H7l3 3',
  watermark: 'M4 5h16v14H4zM8 15l2.5-3 2 2.5L15 11l3 4M8.5 9.5h.01',
  audio: 'M9 18V6l10-2v12M9 18a2.5 2.5 0 1 1-5 0 2.5 2.5 0 0 1 5 0zM19 16a2.5 2.5 0 1 1-5 0 2.5 2.5 0 0 1 5 0z',
  image: 'M4 5h16v14H4zM4 16l5-5 4 4 3-3 4 4M15 9h.01',
  magic: 'M5 19 15 9M14 4l1 2 2 1-2 1-1 2-1-2-2-1 2-1zM19 11l.6 1.4L21 13l-1.4.6L19 15l-.6-1.4L17 13l1.4-.6z',
  pdf: 'M7 3h7l5 5v13H7zM14 3v5h5M9.5 13h5M9.5 17h5',
  shield: 'M12 3 5 6v5c0 4.4 3 8.4 7 10 4-1.6 7-5.6 7-10V6zM9 12l2 2 4-4',
  upload: 'M12 16V4M7 9l5-5 5 5M4 16v4h16v-4',
  spark: 'M12 3v4M12 17v4M3 12h4M17 12h4M6 6l2.5 2.5M15.5 15.5 18 18M6 18l2.5-2.5M15.5 8.5 18 6',
  send: 'M4 12 20 4l-6 16-3-7z',
  stop: 'M7 7h10v10H7z',
  plus: 'M12 5v14M5 12h14',
  check: 'M5 12.5 10 17 19 7',
  x: 'M6 6l12 12M18 6 6 18',
  file: 'M7 3h7l5 5v13H7zM14 3v5h5',
  download: 'M12 4v12M7 11l5 5 5-5M4 20h16',
  history: 'M4 12a8 8 0 1 0 2.3-5.7M4 4v3.5h3.5M12 8v4l3 2',
  tool: 'M14.5 6.5a4 4 0 0 0-5 5L4 17l3 3 5.5-5.5a4 4 0 0 0 5-5l-2.5 2.5-2.5-.5-.5-2.5z',
  eye: 'M2 12s3.5-7 10-7 10 7 10 7-3.5 7-10 7S2 12 2 12zM12 9a3 3 0 1 0 0 6 3 3 0 0 0 0-6z',
  arrow: 'M5 12h14M13 6l6 6-6 6',
}
</script>

<template>
  <svg :width="props.size" :height="props.size" viewBox="0 0 24 24" fill="none" stroke="currentColor"
       stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true" class="icon">
    <path :d="PATHS[props.name] ?? PATHS.file" />
  </svg>
</template>

<style scoped>
.icon {
  flex: none;
  display: inline-block;
  vertical-align: middle;
}
</style>
