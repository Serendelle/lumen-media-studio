<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { compareApi } from '@/api/endpoints'
import type { MediaCompare, MeasuredMedia } from '@/api/types'
import { previewUrl, downloadFile } from '@/utils/download'
import { bytes, duration, kbps } from '@/utils/format'

/**
 * Source vs result, played in lock-step from one set of controls. Time is aligned proportionally (position / duration)
 * so clips whose durations differ still line up; either side failing to load leaves the other fully usable.
 * All numbers in the table come from GET /api/v2/media/compare (fresh FFprobe + real byte counts).
 */
const props = defineProps<{ sourceFileId: string; outputFileId: string; outputName?: string }>()

const srcEl = ref<HTMLVideoElement | null>(null)
const outEl = ref<HTMLVideoElement | null>(null)
const srcUrl = ref<string | null>(null)
const outUrl = ref<string | null>(null)
const srcErr = ref<string | null>(null)
const outErr = ref<string | null>(null)
const data = ref<MediaCompare | null>(null)
const dataErr = ref<string | null>(null)
const playing = ref(false)
const pos = ref(0) // 0..1
const durS = ref(0)
const durO = ref(0)
const audioFrom = ref<'output' | 'source'>('output')

let reqId = 0
let raf = 0
async function load() {
  const id = ++reqId // a later call supersedes this one: its responses are dropped, so files never mix
  pause()
  srcUrl.value = outUrl.value = srcErr.value = outErr.value = dataErr.value = null
  data.value = null
  pos.value = 0
  const [s, o, c] = await Promise.allSettled([
    previewUrl(props.sourceFileId),
    previewUrl(props.outputFileId),
    compareApi.media(props.sourceFileId, props.outputFileId),
  ])
  if (id !== reqId) return
  if (s.status === 'fulfilled') srcUrl.value = s.value
  else srcErr.value = (s.reason as Error).message
  if (o.status === 'fulfilled') outUrl.value = o.value
  else outErr.value = (o.reason as Error).message
  if (c.status === 'fulfilled') data.value = c.value
  else dataErr.value = (c.reason as Error).message
}

const live = () => [srcErr.value ? null : srcEl.value, outErr.value ? null : outEl.value].filter(Boolean) as HTMLVideoElement[]
/** The clock both sides follow: the result when it plays, otherwise the source. */
const master = () => (!outErr.value && outEl.value && durO.value ? outEl.value : srcEl.value)

const mismatch = computed(() => durS.value > 0 && durO.value > 0 && Math.abs(durS.value - durO.value) > 0.25)

function sync() {
  const m = master()
  if (!m || !m.duration) return
  const ratio = m.currentTime / m.duration
  pos.value = ratio
  for (const el of live()) {
    if (el === m || !el.duration) continue
    const target = ratio * el.duration
    if (Math.abs(el.currentTime - target) > 0.2) el.currentTime = target
  }
}

function loop() {
  sync()
  if (playing.value) raf = requestAnimationFrame(loop)
}

async function play() {
  const els = live()
  if (!els.length) return
  playing.value = true
  applyMute()
  await Promise.allSettled(els.map((e) => e.play()))
  cancelAnimationFrame(raf)
  raf = requestAnimationFrame(loop)
}
function pause() {
  playing.value = false
  cancelAnimationFrame(raf)
  for (const el of live()) el.pause()
}
function toggle() {
  if (playing.value) pause()
  else void play()
}
function seek(v: number) {
  pos.value = v
  for (const el of live()) if (el.duration) el.currentTime = v * el.duration
}
function applyMute() {
  if (srcEl.value) srcEl.value.muted = audioFrom.value !== 'source'
  if (outEl.value) outEl.value.muted = audioFrom.value !== 'output'
}
watch(audioFrom, applyMute)

function onMeta(side: 'src' | 'out', e: Event) {
  const d = (e.target as HTMLVideoElement).duration
  if (side === 'src') durS.value = Number.isFinite(d) ? d : 0
  else durO.value = Number.isFinite(d) ? d : 0
  applyMute()
}
function onEnded(e: Event) {
  if (e.target === master()) pause()
}
function onMediaError(side: 'src' | 'out') {
  const msg = '浏览器无法播放此编码，可下载后用本地播放器查看'
  if (side === 'src') srcErr.value = msg
  else outErr.value = msg
}
onBeforeUnmount(() => {
  reqId++
  pause()
})
// registered after every helper above exists: the immediate first run calls pause()/live()
watch(() => [props.sourceFileId, props.outputFileId], load, { immediate: true })

/** Player clock: 0 shows as 0:00 (the shared duration() formatter renders 0 as "—" for "unknown"). */
const clock = (sec: number) => (sec > 0 ? duration(sec) : '0:00')
const masterDur = computed(() => (!outErr.value && durO.value ? durO.value : durS.value))

// ---- comparison table ----
type Row = { k: string; s: string; o: string; delta?: string; tone?: 'good' | 'bad' }
const NA = '未检测'
const fmtRes = (m: MeasuredMedia) => (m.width && m.height ? `${m.width}×${m.height}` : NA)
const rows = computed<Row[]>(() => {
  const d = data.value
  if (!d) return []
  const s = d.source.measured
  const o = d.output.measured
  const out: Row[] = [
    {
      k: '文件体积',
      s: bytes(d.source.sizeBytes),
      o: bytes(d.output.sizeBytes),
      delta: d.savedPercent != null ? `${d.savedPercent > 0 ? '-' : '+'}${Math.abs(d.savedPercent)}%` : undefined,
      tone: d.deltaBytes < 0 ? 'good' : d.deltaBytes > 0 ? 'bad' : undefined,
    },
    { k: '字节数', s: d.source.sizeBytes.toLocaleString('zh-CN'), o: d.output.sizeBytes.toLocaleString('zh-CN'),
      delta: `${d.deltaBytes > 0 ? '+' : ''}${d.deltaBytes.toLocaleString('zh-CN')}` },
    { k: '封装', s: s.formatName ?? NA, o: o.formatName ?? NA },
    { k: '时长', s: s.duration ? duration(s.duration) : NA, o: o.duration ? duration(o.duration) : NA,
      delta: d.durationDeltaMs != null && Math.abs(d.durationDeltaMs) > 0 ? `${d.durationDeltaMs > 0 ? '+' : ''}${d.durationDeltaMs} ms` : undefined },
    { k: '分辨率', s: fmtRes(s), o: fmtRes(o) },
    { k: '帧率', s: s.fps ? `${s.fps} fps` : NA, o: o.fps ? `${o.fps} fps` : NA },
    { k: '视频编码', s: s.videoCodec ?? NA, o: o.videoCodec ?? NA },
    { k: '视频码率', s: s.videoBitrate ? kbps(s.videoBitrate) : NA, o: o.videoBitrate ? kbps(o.videoBitrate) : NA },
    { k: '总码率', s: s.bitrate ? kbps(s.bitrate) : NA, o: o.bitrate ? kbps(o.bitrate) : NA },
    { k: '音轨', s: s.audioStreams != null ? `${s.audioStreams} 条` : NA, o: o.audioStreams != null ? `${o.audioStreams} 条` : NA },
    { k: '音频编码', s: s.audioCodec ?? (s.audioStreams === 0 ? '无' : NA), o: o.audioCodec ?? (o.audioStreams === 0 ? '无' : NA) },
    { k: '音频码率', s: s.audioBitrate ? kbps(s.audioBitrate) : NA, o: o.audioBitrate ? kbps(o.audioBitrate) : NA },
  ]
  return out
})
</script>

<template>
  <section class="vcmp" aria-label="原视频与结果对比">
    <div class="stage">
      <figure>
        <figcaption>原视频</figcaption>
        <div class="box">
          <video v-if="srcUrl && !srcErr" ref="srcEl" :src="srcUrl" playsinline preload="metadata"
                 @loadedmetadata="onMeta('src', $event)" @ended="onEnded" @error="onMediaError('src')" @timeupdate="!playing && sync()" />
          <div v-else-if="srcErr" class="ph"><span>原视频无法加载</span><small>{{ srcErr }}</small></div>
          <div v-else class="ph">载入中…</div>
        </div>
      </figure>
      <figure>
        <figcaption>处理结果</figcaption>
        <div class="box">
          <video v-if="outUrl && !outErr" ref="outEl" :src="outUrl" playsinline preload="metadata"
                 @loadedmetadata="onMeta('out', $event)" @ended="onEnded" @error="onMediaError('out')" @timeupdate="!playing && sync()" />
          <div v-else-if="outErr" class="ph">
            <span>结果无法在浏览器中播放</span><small>{{ outErr }}</small>
            <button type="button" class="btn btn--sm btn--ghost" @click="downloadFile(outputFileId, outputName)">下载结果</button>
          </div>
          <div v-else class="ph">载入中…</div>
        </div>
      </figure>
    </div>

    <div class="ctrl" role="group" aria-label="同步播放控制">
      <button type="button" class="pp" :aria-label="playing ? '暂停两侧' : '同时播放两侧'" :disabled="!live().length" @click="toggle">
        <svg v-if="!playing" viewBox="0 0 24 24" width="18" height="18" aria-hidden="true"><path d="M8 5v14l11-7z" fill="currentColor" /></svg>
        <svg v-else viewBox="0 0 24 24" width="18" height="18" aria-hidden="true"><path d="M7 5h4v14H7zM13 5h4v14h-4z" fill="currentColor" /></svg>
      </button>
      <input class="seek" type="range" min="0" max="1" step="0.001" :value="pos" aria-label="同步进度"
             :aria-valuetext="`${clock(pos * masterDur)} / ${duration(masterDur)}`"
             @input="seek(Number(($event.target as HTMLInputElement).value))" />
      <span class="time mono">{{ clock(pos * masterDur) }} / {{ duration(masterDur) }}</span>
      <label class="aud">
        <span>声音</span>
        <select v-model="audioFrom" class="select select--sm" aria-label="声音来源">
          <option value="output">结果</option>
          <option value="source">原视频</option>
        </select>
      </label>
    </div>
    <p v-if="mismatch" class="notice" role="note">
      两段时长不同（{{ duration(durS) }} vs {{ duration(durO) }}），时间点已按比例对齐；也可在下方分别下载单独播放。
    </p>

    <p v-if="dataErr" class="err">对比数据加载失败：{{ dataErr }}</p>
    <table v-else-if="rows.length" class="tbl">
      <thead><tr><th scope="col">指标</th><th scope="col">原视频</th><th scope="col">处理结果</th><th scope="col">变化</th></tr></thead>
      <tbody>
        <tr v-for="r in rows" :key="r.k">
          <th scope="row">{{ r.k }}</th>
          <td class="mono" :class="{ na: r.s === NA }">{{ r.s }}</td>
          <td class="mono" :class="{ na: r.o === NA }">{{ r.o }}</td>
          <td class="mono" :class="r.tone">{{ r.delta ?? (r.s !== r.o && r.s !== NA && r.o !== NA ? '已变化' : '') }}</td>
        </tr>
      </tbody>
    </table>
    <p v-else class="loading">正在检测两份文件…</p>
  </section>
</template>

<style scoped>
.vcmp {
  display: grid;
  gap: var(--sp-3);
}
.stage {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: var(--sp-3);
}
figure {
  margin: 0;
  display: grid;
  gap: 6px;
}
figcaption {
  font-size: var(--fs-xs);
  color: var(--text-2);
}
/* both sides share one display size, whatever their pixel dimensions */
.box {
  aspect-ratio: 16 / 9;
  border-radius: var(--r-md);
  overflow: hidden;
  background: #151917;
}
.box video {
  width: 100%;
  height: 100%;
  object-fit: contain;
  display: block;
}
.ph {
  display: grid;
  place-content: center;
  justify-items: center;
  gap: 6px;
  height: 100%;
  padding: 12px;
  text-align: center;
  color: #c9d1cd;
  font-size: var(--fs-sm);
}
.ph small {
  font-size: var(--fs-xs);
  opacity: 0.75;
}
.ctrl {
  display: flex;
  align-items: center;
  gap: 12px;
}
.pp {
  display: grid;
  place-items: center;
  width: 40px;
  height: 40px;
  flex: none;
  border: 0;
  border-radius: 50%;
  background: var(--ink);
  color: var(--cream);
}
.pp:disabled {
  opacity: 0.4;
}
.seek {
  flex: 1;
  min-width: 0;
  accent-color: var(--mint-ink);
}
.time {
  font-size: var(--fs-xs);
  color: var(--text-2);
  white-space: nowrap;
}
.aud {
  display: flex;
  flex: none;
  white-space: nowrap;
  align-items: center;
  gap: 6px;
  font-size: var(--fs-xs);
  color: var(--text-3);
}
.notice {
  padding: 8px 12px;
  border-radius: var(--r-sm);
  background: var(--apricot);
  color: var(--apricot-ink);
  font-size: var(--fs-xs);
}
.tbl {
  width: 100%;
  border-collapse: collapse;
  font-size: var(--fs-xs);
}
.tbl th,
.tbl td {
  padding: 7px 8px;
  border-top: 1px solid var(--line);
  text-align: left;
  vertical-align: top;
}
.tbl thead th {
  border-top: 0;
  color: var(--text-3);
  font-weight: 400;
}
.tbl tbody th {
  font-weight: 400;
  color: var(--text-2);
  white-space: nowrap;
}
.na {
  color: var(--text-3);
}
.good {
  color: var(--ok);
}
.bad {
  color: var(--danger);
}
.err {
  color: var(--danger);
  font-size: var(--fs-sm);
}
.loading {
  color: var(--text-3);
  font-size: var(--fs-sm);
}
@media (max-width: 640px) {
  .stage {
    grid-template-columns: 1fr;
  }
  .aud span {
    display: none;
  }
  .tbl td:nth-child(4),
  .tbl th:nth-child(4) {
    display: none;
  }
}
</style>
