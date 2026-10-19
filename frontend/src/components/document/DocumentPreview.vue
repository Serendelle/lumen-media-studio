<script setup lang="ts">
import { onBeforeUnmount, ref, watch } from 'vue'
import { documentsApi } from '@/api/endpoints'

/**
 * Page-by-page preview of a PDF the user owns. Pages are rendered server-side and fetched through a short-lived
 * signed URL; the PDF itself is never handed to a third-party viewer.
 */
const props = defineProps<{ fileId: string }>()

const page = ref(1)
const pageCount = ref(0)
const pageSize = ref('')
const src = ref<string | null>(null)
const error = ref<string | null>(null)
const loading = ref(false)
let req = 0

async function load(p: number) {
  const id = ++req
  loading.value = true
  error.value = null
  try {
    const r = await documentsApi.preview(props.fileId, p)
    if (id !== req) return
    page.value = r.page
    pageCount.value = r.pageCount
    pageSize.value = r.pageSize
    src.value = r.imageTicketUrl
  } catch (e) {
    if (id !== req) return
    error.value = (e as Error).message
    src.value = null
  } finally {
    if (id === req) loading.value = false
  }
}

function go(p: number) {
  if (p < 1 || (pageCount.value && p > pageCount.value) || p === page.value) return
  void load(p)
}

watch(() => props.fileId, () => {
  page.value = 1
  pageCount.value = 0
  void load(1)
}, { immediate: true })
onBeforeUnmount(() => { req++ })
</script>

<template>
  <section class="dp" aria-label="PDF 预览">
    <div class="sheet" :aria-busy="loading">
      <img v-if="src" :src="src" :alt="`第 ${page} 页`" @error="error = '页面图片加载失败（链接可能已过期），请刷新'" />
      <p v-else-if="error" class="err">{{ error }}</p>
      <p v-else class="ph">正在渲染…</p>
    </div>
    <nav v-if="pageCount" class="pager" aria-label="翻页">
      <button type="button" class="btn btn--sm btn--ghost" :disabled="page <= 1 || loading" @click="go(1)">首页</button>
      <button type="button" class="btn btn--sm btn--ghost" :disabled="page <= 1 || loading" aria-label="上一页" @click="go(page - 1)">‹</button>
      <span class="mono">{{ page }} / {{ pageCount }}</span>
      <button type="button" class="btn btn--sm btn--ghost" :disabled="page >= pageCount || loading" aria-label="下一页" @click="go(page + 1)">›</button>
      <button type="button" class="btn btn--sm btn--ghost" :disabled="page >= pageCount || loading" @click="go(pageCount)">末页</button>
      <span class="size">{{ pageSize }}</span>
    </nav>
  </section>
</template>

<style scoped>
.dp {
  display: grid;
  gap: var(--sp-3);
}
.sheet {
  display: grid;
  place-items: center;
  min-height: 320px;
  max-height: 70vh;
  overflow: auto;
  padding: var(--sp-4);
  border-radius: var(--r-md);
  background: var(--surface-sunk);
}
.sheet img {
  max-width: 100%;
  height: auto;
  box-shadow: 0 2px 14px rgba(0, 0, 0, 0.12);
  background: #fff;
}
.ph,
.err {
  font-size: var(--fs-sm);
  color: var(--text-3);
}
.err {
  color: var(--danger);
}
.pager {
  display: flex;
  align-items: center;
  justify-content: center;
  flex-wrap: wrap;
  gap: 6px;
  font-size: var(--fs-sm);
}
.size {
  margin-left: 8px;
  font-size: var(--fs-xs);
  color: var(--text-3);
}
</style>
