<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import type { TaskView } from '@/api/types'
import { previewUrl, downloadFile } from '@/utils/download'
import { bytes } from '@/utils/format'
import { TASK_LABEL } from '@/utils/labels'

const props = defineProps<{ task: TaskView | null }>()
const emit = defineEmits<{ close: [] }>()

const dialog = ref<HTMLDialogElement | null>(null)
const outUrl = ref<string | null>(null)
const items = ref<{ name: string; inputSize: number; outputSize: number; targetMet?: boolean; quality?: number; suggestion?: string; inUrl?: string; outUrl?: string; outputFileId?: string }[]>([])
const active = ref(0)
const error = ref<string | null>(null)

const kind = computed(() => props.task?.output?.mediaType)

watch(
  () => props.task,
  async (t) => {
    outUrl.value = null
    items.value = []
    active.value = 0
    error.value = null
    if (!t) {
      dialog.value?.close()
      return
    }
    dialog.value?.showModal()
    try {
      const its = t.result?.items as any[] | undefined
      if (its?.length) {
        items.value = await Promise.all(
          its.slice(0, 50).map(async (i) => ({
            ...i,
            inUrl: i.inputFileId ? await previewUrl(i.inputFileId).catch(() => undefined) : undefined,
            outUrl: i.outputFileId ? await previewUrl(i.outputFileId).catch(() => undefined) : undefined,
          })),
        )
      } else if (t.output && t.output.mediaType !== 'ARCHIVE') {
        outUrl.value = await previewUrl(t.output.fileId)
      }
    } catch (e) {
      error.value = (e as Error).message
    }
  },
)

const current = computed(() => items.value[active.value])
</script>

<template>
  <dialog ref="dialog" class="dlg" aria-labelledby="pv-title" @close="emit('close')" @click.self="dialog?.close()">
    <div v-if="task" class="inner">
      <header class="head">
        <h2 id="pv-title">{{ TASK_LABEL[task.type] }} · 结果</h2>
        <button type="button" class="x" aria-label="关闭" @click="dialog?.close()">×</button>
      </header>

      <p v-if="error" class="err">{{ error }}</p>

      <template v-if="items.length && current">
        <div class="compare">
          <figure>
            <figcaption>原图 · <span class="mono">{{ bytes(current.inputSize) }}</span></figcaption>
            <img v-if="current.inUrl" :src="current.inUrl" :alt="`原图 ${current.name}`" loading="lazy" />
            <div v-else class="ph">无法预览</div>
          </figure>
          <figure>
            <figcaption>
              处理后 · <span class="mono">{{ bytes(current.outputSize) }}</span>
              <span v-if="current.quality" class="mono"> · q{{ current.quality }}</span>
              <span v-if="current.targetMet === true" class="chip chip--mint">达到目标</span>
              <span v-if="current.targetMet === false" class="chip chip--apricot">未达到目标</span>
            </figcaption>
            <img v-if="current.outUrl" :src="current.outUrl" :alt="`处理后 ${current.name}`" loading="lazy" />
            <div v-else class="ph">无法预览</div>
          </figure>
        </div>
        <p v-if="current.suggestion" class="sugg">{{ current.suggestion }}</p>
        <ol v-if="items.length > 1" class="thumbs">
          <li v-for="(i, idx) in items" :key="idx">
            <button type="button" :class="{ on: idx === active }" @click="active = idx">
              <span class="nm">{{ i.name }}</span>
              <span class="mono sz">{{ bytes(i.inputSize) }} → {{ bytes(i.outputSize) }}</span>
            </button>
          </li>
        </ol>
        <div class="acts">
          <button v-if="current.outputFileId" type="button" class="btn btn--sm btn--ghost" @click="downloadFile(current.outputFileId!, current.name)">下载这张</button>
          <button v-if="task.output" type="button" class="btn btn--sm" @click="downloadFile(task.output!.fileId, task.output!.filename)">
            {{ task.output.mediaType === 'ARCHIVE' ? '下载全部（zip）' : '下载' }}
          </button>
        </div>
      </template>

      <template v-else-if="outUrl">
        <video v-if="kind === 'VIDEO'" :src="outUrl" controls playsinline class="media" />
        <audio v-else-if="kind === 'AUDIO'" :src="outUrl" controls class="audio" />
        <img v-else-if="kind === 'IMAGE'" :src="outUrl" :alt="task.output?.filename" class="media checker" />
        <p v-else-if="kind === 'PDF'" class="loading">PDF 出于安全考虑以附件形式提供，请下载后查看。</p>
        <div class="acts">
          <span class="mono meta">{{ task.output?.filename }} · {{ bytes(task.output?.sizeBytes) }}</span>
          <button v-if="task.output" type="button" class="btn btn--sm" @click="downloadFile(task.output!.fileId, task.output!.filename)">下载</button>
        </div>
      </template>
      <p v-else-if="!error" class="loading">正在载入预览…</p>
    </div>
  </dialog>
</template>

<style scoped>
.dlg {
  width: min(980px, calc(100vw - 32px));
  max-height: calc(100vh - 48px);
  padding: 0;
  border: 0;
  border-radius: var(--r-lg);
  background: var(--paper);
  color: var(--text);
  box-shadow: var(--shadow-3);
}
.dlg::backdrop {
  background: rgba(48, 58, 55, 0.42);
  backdrop-filter: blur(4px);
}
.inner {
  display: grid;
  gap: var(--sp-4);
  padding: var(--sp-5);
}
.head {
  display: flex;
  align-items: center;
}
.head h2 {
  font-size: var(--fs-lg);
}
.x {
  margin-left: auto;
  border: 0;
  background: none;
  font-size: 24px;
  line-height: 1;
  color: var(--text-2);
}
.compare {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: var(--sp-4);
}
figure {
  margin: 0;
  display: grid;
  gap: 8px;
}
figcaption {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 6px;
  font-size: var(--fs-xs);
  color: var(--text-2);
}
figure img,
.ph {
  width: 100%;
  aspect-ratio: 4 / 3;
  object-fit: contain;
  border-radius: var(--r-md);
  background: repeating-conic-gradient(#ececE5 0 25%, #f7f7f2 0 50%) 50% / 18px 18px;
}
.ph {
  display: grid;
  place-items: center;
  color: var(--text-3);
  font-size: var(--fs-sm);
}
.sugg {
  font-size: var(--fs-sm);
  color: var(--warn);
}
.thumbs {
  margin: 0;
  padding: 0;
  list-style: none;
  display: flex;
  gap: 6px;
  overflow-x: auto;
}
.thumbs button {
  display: grid;
  gap: 2px;
  min-width: 150px;
  padding: 8px 10px;
  border: 1px solid var(--line);
  border-radius: var(--r-sm);
  background: var(--surface);
  text-align: left;
  font-size: var(--fs-xs);
}
.thumbs button.on {
  border-color: var(--mint-ink);
  background: rgba(217, 237, 228, 0.4);
}
.nm {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.sz {
  color: var(--text-3);
}
.media {
  width: 100%;
  max-height: 62vh;
  border-radius: var(--r-md);
  background: #1e2422;
}
.checker {
  object-fit: contain;
  background: repeating-conic-gradient(#ececE5 0 25%, #f7f7f2 0 50%) 50% / 18px 18px;
}
.audio {
  width: 100%;
}
.pdf {
  width: 100%;
  height: 62vh;
  border: 1px solid var(--line);
  border-radius: var(--r-md);
}
.acts {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: flex-end;
  gap: 8px;
}
.meta {
  margin-right: auto;
  font-size: var(--fs-xs);
  color: var(--text-3);
}
.loading,
.err {
  font-size: var(--fs-sm);
  color: var(--text-2);
}
.err {
  color: var(--danger);
}
@media (max-width: 640px) {
  .compare {
    grid-template-columns: 1fr;
  }
}
</style>
