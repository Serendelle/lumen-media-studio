<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { RouterLink, useRouter } from 'vue-router'
import { specsApi } from '@/api/endpoints'
import type { SpecProjectSummary } from '@/api/types'
import { useToast } from '@/composables/useToast'
import { relativeTime } from '@/utils/format'

const router = useRouter()
const toast = useToast()
const list = ref<SpecProjectSummary[]>([])
const loading = ref(true)
const error = ref<string | null>(null)
const name = ref('')
const creating = ref(false)

onMounted(async () => {
  try {
    list.value = (await specsApi.list()).items
  } catch (e) {
    error.value = (e as Error).message
  } finally {
    loading.value = false
  }
})

async function create() {
  if (name.value.trim().length < 2) return
  creating.value = true
  try {
    const p = await specsApi.create(name.value.trim())
    void router.push(`/workspace/specs/${p.id}`)
  } catch (e) {
    toast.error((e as Error).message)
  } finally {
    creating.value = false
  }
}
</script>

<template>
  <main id="main" class="sl workspace-page">
    <header class="head">
      <h1>材料规范官</h1>
      <p>创建在线收集表，设置字段与查看、编辑权限，分享链接邀请大家填写并上传附件。信息自动汇总，支持多人协作、一键统计和 Excel 导出；材料还可交给 AI 分类，再检查、修正和打包交付。</p>
    </header>

    <form class="card new" @submit.prevent="create">
      <label for="pn">新建项目</label>
      <input id="pn" v-model="name" class="input" maxlength="80" placeholder="例如：第三届科普讲解大赛" />
      <button type="submit" class="btn" :disabled="creating || name.trim().length < 2">创建</button>
    </form>

    <p v-if="error" class="err" role="alert">{{ error }}</p>
    <section class="list" :aria-busy="loading">
      <RouterLink v-for="p in list" :key="p.id" :to="`/workspace/specs/${p.id}`" class="card item">
        <strong>{{ p.name }}</strong>
        <span class="meta"><template v-if="p.records">{{ p.records }} 条表格记录 · {{ p.files }} 个文件</template><template v-else>{{ p.slots }} 项材料 · {{ p.people }} 人 · {{ p.files }} 个文件</template></span>
        <span class="when">{{ relativeTime(p.updatedAt) }}</span>
      </RouterLink>
      <p v-if="!loading && !list.length && !error" class="empty">还没有项目。</p>
    </section>
  </main>
</template>

<style scoped>
.sl { display: grid; gap: var(--sp-4); }
.head h1 { font-size: var(--fs-2xl); }
.head p { margin-top: 6px; color: var(--text-2); font-size: var(--fs-sm); line-height: 1.7; max-width: 720px; }
.new { display: flex; align-items: center; gap: var(--sp-3); flex-wrap: wrap; padding: var(--sp-4) var(--sp-5); }
.new label { font-size: var(--fs-sm); }
.new .input { flex: 1; min-width: 200px; }
.list { display: grid; gap: var(--sp-2); }
.item { display: grid; grid-template-columns: 1fr auto; gap: 4px 12px; padding: var(--sp-4) var(--sp-5); color: var(--text); text-decoration: none; }
.item:hover { box-shadow: var(--shadow-2, var(--shadow-1)); }
.meta { grid-column: 1; font-size: var(--fs-xs); color: var(--text-3); }
.when { grid-row: 1 / span 2; grid-column: 2; align-self: center; font-size: var(--fs-xs); color: var(--text-3); }
.empty { padding: var(--sp-6) 0; text-align: center; color: var(--text-3); font-size: var(--fs-sm); }
.err { color: var(--danger); font-size: var(--fs-sm); }
</style>
