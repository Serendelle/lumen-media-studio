<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref, shallowRef, watch } from 'vue'
// only the core editor + two syntax grammars: no language-server workers, which keeps the bundle small
import * as monaco from 'monaco-editor/esm/vs/editor/editor.api'
import 'monaco-editor/esm/vs/basic-languages/python/python.contribution'
import 'monaco-editor/esm/vs/basic-languages/javascript/javascript.contribution'
import EditorWorker from 'monaco-editor/esm/vs/editor/editor.worker?worker'

;(self as any).MonacoEnvironment = { getWorker: () => new EditorWorker() }

const props = defineProps<{ modelValue: string; language: string; label?: string }>()
const emit = defineEmits<{ 'update:modelValue': [v: string]; run: [] }>()
const host = ref<HTMLDivElement | null>(null)
const editor = shallowRef<monaco.editor.IStandaloneCodeEditor | null>(null)

onMounted(() => {
  editor.value = monaco.editor.create(host.value!, {
    value: props.modelValue,
    language: props.language === 'text' ? 'plaintext' : props.language,
    theme: 'vs-dark',
    fontSize: 14,
    fontFamily: "'JetBrains Mono', Consolas, monospace",
    minimap: { enabled: false },
    scrollBeyondLastLine: false,
    automaticLayout: true,
    tabSize: 4,
    ariaLabel: props.label ?? '代码编辑器',
    padding: { top: 10, bottom: 10 },
  })
  editor.value.onDidChangeModelContent(() => emit('update:modelValue', editor.value!.getValue()))
  // Ctrl/Cmd + Enter runs the code
  editor.value.addCommand(monaco.KeyMod.CtrlCmd | monaco.KeyCode.Enter, () => emit('run'))
})
watch(() => props.modelValue, (v) => {
  if (editor.value && v !== editor.value.getValue()) editor.value.setValue(v)
})
watch(() => props.language, (l) => {
  const m = editor.value?.getModel()
  if (m) monaco.editor.setModelLanguage(m, l === 'text' ? 'plaintext' : l)
})
onBeforeUnmount(() => editor.value?.dispose())
</script>

<template>
  <div ref="host" class="ce" />
</template>

<style scoped>
.ce {
  width: 100%;
  height: 100%;
  min-height: 220px;
}
</style>
