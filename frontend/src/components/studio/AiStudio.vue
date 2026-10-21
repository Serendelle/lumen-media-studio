<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import AgentConstellation from './ai/AgentConstellation.vue'
import AiThread from './ai/AiThread.vue'
import MediaPicker from './MediaPicker.vue'
import Icon from '@/components/common/Icon.vue'
import { useAiStore } from '@/stores/ai'
import { useLibraryStore } from '@/stores/library'
import { useSystemStore } from '@/stores/system'
import { useToast } from '@/composables/useToast'
import { useTasksStore } from '@/stores/tasks'
import { relativeTime } from '@/utils/format'

const ai = useAiStore()
const lib = useLibraryStore()
const system = useSystemStore()
const toast = useToast()
const tasks = useTasksStore()
const route = useRoute()
const router = useRouter()

const text = ref('')
const attached = ref<string[]>([])
const picking = ref(false)
const showHistory = ref(false)
const sending = ref(false)

const configured = computed(() => system.caps?.ai.configured ?? true)
const attachedFiles = computed(() => attached.value.map((id) => lib.files.find((f) => f.fileId === id)).filter(Boolean))
const canSend = computed(() => configured.value && !ai.busy && !sending.value && text.value.trim().length > 0)
const empty = computed(() => ai.events.length === 0)

const EXAMPLES = [
  '帮我把这个视频压缩到 20MB 以内，尽量保证画质，再把音频提取成 MP3，最后清除可能泄露位置的信息。',
  '这张照片里有没有会暴露我位置的信息？有的话帮我去掉，其他信息保留。',
  '把视频转成适合发朋友圈的大小，720p 就够了。',
  '把这几张图片压缩到每张 300KB 以内，转成 WebP。',
]

async function openFromRoute() {
  const id = typeof route.query.conv === 'string' ? route.query.conv : null
  if (id && id !== ai.current?.id) await ai.open(id)
}

onMounted(async () => {
  await ai.refreshList()
  await openFromRoute()
})
onBeforeUnmount(() => ai.close())
watch(() => route.query.conv, openFromRoute)

// AI steps run on the same task engine: show them in the unified task tray (marked "AI") as they appear
const tracked = new Set<string>()
watch(
  () => Object.values(ai.steps).map((s) => s.taskId).filter(Boolean) as string[],
  (ids) => {
    for (const id of ids) {
      if (!tracked.has(id)) {
        tracked.add(id)
        void tasks.track(id)
      }
    }
  },
)

function select(id: string) {
  showHistory.value = false
  void router.replace({ query: { ...route.query, conv: id } })
}

async function newConversation() {
  showHistory.value = false
  const c = await ai.startNew()
  attached.value = []
  void router.replace({ query: { ...route.query, conv: c.id } })
}

async function send(override?: string) {
  const t = (override ?? text.value).trim()
  if (!t || ai.busy || sending.value) return
  sending.value = true
  try {
    if (!ai.current) {
      const c = await ai.startNew()
      void router.replace({ query: { ...route.query, conv: c.id } })
    }
    await ai.send(t, attached.value)
    text.value = ''
    attached.value = []
    picking.value = false
  } catch (e) {
    toast.error((e as Error).message)
  } finally {
    sending.value = false
  }
}

function onKey(e: KeyboardEvent) {
  if (e.key === 'Enter' && !e.shiftKey && !e.isComposing) {
    e.preventDefault()
    void send()
  }
}

async function stop() {
  try {
    await ai.cancel()
  } catch (e) {
    toast.error((e as Error).message)
  }
}

const STATE_LABEL: Record<string, string> = {
  IDLE: '待开始', ANALYZING: '理解需求中', AWAITING_INPUT: '等你补充', PLANNING: '规划中', AWAITING_CONFIRMATION: '等你确认',
  EXECUTING: '处理中', REVIEWING: '审核中', COMPLETED: '已完成', FAILED: '出错', CANCELLED: '已取消',
}
</script>

<template>
  <section class="ai" aria-labelledby="ai-title">
    <header class="top">
      <div class="title-wrap">
        <p class="eyebrow">Smart mode · Multi-agent</p>
        <h2 id="ai-title" class="title">{{ ai.current?.title ?? '智能模式' }}</h2>
      </div>
      <span v-if="ai.current" class="chip" :class="{ 'chip--mint': ai.state === 'COMPLETED', 'chip--apricot': ai.state.startsWith('AWAITING'), 'chip--fog': ai.busy }">
        {{ STATE_LABEL[ai.state] }}
      </span>
      <div class="hist">
        <button type="button" class="btn btn--sm btn--ghost" :aria-expanded="showHistory" @click="showHistory = !showHistory">
          <Icon name="history" :size="15" />历史
        </button>
        <button type="button" class="btn btn--sm" @click="newConversation"><Icon name="plus" :size="15" />新对话</button>
        <div v-if="showHistory" class="menu card" role="menu">
          <p v-if="!ai.conversations.length" class="empty">还没有对话</p>
          <button v-for="c in ai.conversations" :key="c.id" type="button" role="menuitem" class="row" :class="{ on: c.id === ai.current?.id }" @click="select(c.id)">
            <span class="nm">{{ c.title }}</span>
            <span class="meta">{{ STATE_LABEL[c.state] }} · {{ relativeTime(c.updatedAt) }}</span>
          </button>
        </div>
      </div>
    </header>

    <p v-if="!configured" class="notice" role="note">
      AI 尚未配置：请在后端的 <code>.env</code> 中设置 <code>DEEPSEEK_API_KEY</code> 后重启后端。专业模式不受影响。
    </p>

    <AgentConstellation />

    <div class="stage">
      <div v-if="empty" class="hello">
        <p class="display lead">说出你想要的结果。</p>
        <p class="sub">四位 Agent 会读取文件的真实信息、判断能否实现、拟出计划，<strong>经你确认后</strong>才调用处理引擎执行，最后独立核验结果。</p>
        <div class="examples">
          <button v-for="ex in EXAMPLES" :key="ex" type="button" class="ex" @click="text = ex">{{ ex }}</button>
        </div>
      </div>
      <AiThread v-else />
    </div>

    <p v-if="ai.error" class="err" role="alert">{{ ai.error }}</p>

    <footer class="composer card">
      <div v-if="ai.quickReplies.length" class="quick" aria-label="快速回复">
        <button v-for="q in ai.quickReplies" :key="q" type="button" class="chip q" @click="send(q)">{{ q }}</button>
      </div>
      <div v-if="attachedFiles.length" class="att">
        <span v-for="f in attachedFiles" :key="f!.fileId" class="chip chip--fog">
          <Icon name="file" :size="13" />{{ f!.originalFilename }}
          <button type="button" class="x" :aria-label="`移除 ${f!.originalFilename}`" @click="attached = attached.filter((x) => x !== f!.fileId)">×</button>
        </span>
      </div>
      <div v-if="picking" class="picker">
        <MediaPicker v-model="attached" :kinds="['VIDEO', 'AUDIO', 'IMAGE', 'PDF']" multiple :max="10" label="选择要交给 AI 处理的文件" />
      </div>
      <div class="row">
        <button type="button" class="btn btn--sm btn--ghost icon-btn" :aria-pressed="picking" aria-label="添加文件" @click="picking = !picking">
          <Icon name="plus" />
        </button>
        <label class="sr-only" for="ai-input">描述你的需求</label>
        <textarea id="ai-input" v-model="text" rows="2" maxlength="2000" :disabled="!configured"
                  placeholder="例如：帮我把这个视频压缩到 20MB 左右，再提取出 MP3…（Enter 发送，Shift+Enter 换行）" @keydown="onKey" />
        <button v-if="ai.busy" type="button" class="btn btn--danger btn--sm" @click="stop"><Icon name="stop" :size="14" />停止</button>
        <button v-else type="button" class="btn send" :disabled="!canSend" aria-label="发送" @click="send()"><Icon name="send" :size="16" /></button>
      </div>
    </footer>
  </section>
</template>

<style scoped>
.ai {
  display: grid;
  grid-template-rows: auto auto auto 1fr auto auto;
  gap: var(--sp-4);
  min-height: calc(100vh - 160px);
}
.top {
  display: flex;
  flex-wrap: wrap;
  align-items: flex-end;
  gap: 12px;
}
.title-wrap {
  min-width: 0;
  margin-right: auto;
}
.title {
  margin-top: 4px;
  font-size: var(--fs-xl);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  max-width: 30ch;
}
.hist {
  position: relative;
  display: flex;
  gap: 8px;
}
.hist .btn {
  gap: 6px;
}
.menu {
  position: absolute;
  right: 0;
  top: calc(100% + 8px);
  z-index: 10;
  width: min(340px, 86vw);
  max-height: 360px;
  overflow: auto;
  padding: 6px;
  border-radius: var(--r-md);
  box-shadow: var(--shadow-3);
}
.menu .row {
  width: 100%;
  display: grid;
  gap: 2px;
  padding: 8px 10px;
  border: 0;
  border-radius: var(--r-sm);
  background: none;
  text-align: left;
}
.menu .row:hover,
.menu .row.on {
  background: var(--surface-sunk);
}
.menu .nm {
  font-size: var(--fs-sm);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.menu .meta,
.empty {
  font-size: var(--fs-xs);
  color: var(--text-3);
}
.empty {
  padding: 10px;
}
.notice {
  padding: 10px 14px;
  border-radius: var(--r-md);
  background: var(--apricot);
  color: var(--apricot-ink);
  font-size: var(--fs-sm);
}
.stage {
  min-height: 0;
  display: grid;
  max-height: 62vh;
}
.hello {
  display: grid;
  align-content: center;
  gap: var(--sp-4);
  padding: var(--sp-6) var(--sp-2);
}
.lead {
  font-size: var(--fs-2xl);
  font-weight: 300;
}
.sub {
  max-width: 40em;
  color: var(--text-2);
  font-size: var(--fs-sm);
}
.sub strong {
  color: var(--mint-ink);
  font-weight: 500;
}
.examples {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(240px, 1fr));
  gap: 10px;
  margin-top: var(--sp-2);
}
.ex {
  padding: 14px 16px;
  border: 1px solid var(--line);
  border-radius: var(--r-md);
  background: var(--surface);
  text-align: left;
  font-size: var(--fs-sm);
  line-height: 1.6;
  color: var(--text-2);
  transition: border-color var(--t-fast), transform var(--t-fast) var(--ease-out), box-shadow var(--t-med);
}
.ex:hover {
  border-color: var(--mint-ink);
  color: var(--text);
  transform: translateY(-2px);
  box-shadow: var(--shadow-2);
}
.err {
  padding: 10px 14px;
  border-radius: var(--r-md);
  background: var(--danger-soft);
  color: var(--danger);
  font-size: var(--fs-sm);
}
.composer {
  display: grid;
  gap: 10px;
  padding: 10px;
  border-radius: var(--r-lg);
  box-shadow: var(--shadow-2);
}
.quick,
.att {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}
.q {
  border: 1px solid rgba(154, 91, 52, 0.25);
  background: var(--apricot);
  color: var(--apricot-ink);
  cursor: pointer;
  padding: 4px 12px;
}
.att .chip {
  gap: 6px;
}
.x {
  border: 0;
  background: none;
  color: inherit;
  font-size: 14px;
  line-height: 1;
  padding: 0 2px;
}
.picker {
  padding: 8px;
  border-radius: var(--r-md);
  background: var(--surface-sunk);
}
.row {
  display: flex;
  align-items: flex-end;
  gap: 8px;
}
textarea {
  flex: 1;
  min-height: 46px;
  max-height: 180px;
  resize: vertical;
  padding: 10px 12px;
  border: 0;
  border-radius: var(--r-md);
  background: transparent;
  line-height: 1.6;
}
textarea:focus {
  outline: none;
}
.icon-btn {
  width: 40px;
  padding: 0;
}
.icon-btn[aria-pressed='true'] {
  background: var(--mint);
}
.send {
  width: 44px;
  padding: 0;
}
.btn--danger {
  gap: 6px;
}
</style>
