import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import test from 'node:test'
import { createPinia, setActivePinia } from 'pinia'
import ts from 'typescript'

const source = await readFile(new URL('../src/stores/tasks.ts', import.meta.url), 'utf8')
const compiled = ts.transpileModule(source, { compilerOptions: { module: ts.ModuleKind.ESNext, target: ts.ScriptTarget.ES2022 } }).outputText
let fixtureId = 0
globalThis.__taskHistoryHarnesses = new Map()
const terminal = (taskId, finishedAt = 10) => ({ taskId, status: 'SUCCEEDED', createdAt: 1, finishedAt, progress: 100, attempt: 1, type: 'document.convert', origin: 'PRO' })
const running = taskId => ({ ...terminal(taskId), status: 'RUNNING', finishedAt: undefined, progress: 20 })
const page = content => ({ content, number: 0, size: 30, totalPages: content.length ? 1 : 0, totalElements: content.length })
const deferred = () => { let resolve, reject; const promise = new Promise((ok, fail) => { resolve = ok; reject = fail }); return { promise, resolve, reject } }

async function harness(initial = []) {
  const id = ++fixtureId
  const state = {
    items: initial,
    streams: [],
    api: {
      list: async (_page, _size, statuses) => page(state.items.filter(t => !statuses || statuses.includes(t.status))),
      get: async taskId => {
        const item = state.items.find(t => t.taskId === taskId)
        if (!item) throw Object.assign(new Error('not found'), { status: 404 })
        return item
      },
      remove: async taskId => ({ deletedCount: 1, taskIds: [taskId] }),
      removeMany: async taskIds => ({ deletedCount: taskIds.length, taskIds }),
      clearHistory: async () => {
        state.items = state.items.filter(t => t.status === 'RUNNING' || t.status === 'QUEUED' || t.finishedAt > 100)
        return { deletedCount: 1, taskIds: [], clearedBefore: 100 }
      },
      retry: async taskId => ({ ...running(taskId), status: 'QUEUED' }),
      cancel: async taskId => ({ ...terminal(taskId), status: 'CANCELLED' }),
    },
    openSse: (_url, event, options) => {
      const stream = { event, options, closed: false, close() { this.closed = true; options.onClose?.() } }
      state.streams.push(stream)
      return stream
    },
  }
  globalThis.__taskHistoryHarnesses.set(id, state)
  const mockUrl = `data:text/javascript;base64,${Buffer.from(`const h=globalThis.__taskHistoryHarnesses.get(${id}); export const tasksApi=h.api; export const openSse=h.openSse`).toString('base64')}`
  const code = compiled.replaceAll("from 'pinia'", `from '${import.meta.resolve('pinia')}'`)
    .replaceAll("from 'vue'", `from '${import.meta.resolve('vue')}'`)
    .replaceAll("from '@/api/endpoints'", `from '${mockUrl}'`)
    .replaceAll("from '@/api/sse'", `from '${mockUrl}'`)
  const { useTasksStore } = await import(`data:text/javascript;base64,${Buffer.from(`${code}\n// harness ${id}`).toString('base64')}`)
  setActivePinia(createPinia())
  state.store = useTasksStore()
  await state.store.restore()
  return state
}

test('a deletion changes the live store only after server success; failure preserves the record', async () => {
  const h = await harness([terminal('done'), running('active')])
  const response = deferred()
  h.api.remove = () => response.promise
  const deletion = h.store.remove('done')
  assert.ok(h.store.byId.done)
  response.reject(new Error('permission denied'))
  await assert.rejects(deletion, /permission denied/)
  assert.ok(h.store.byId.done)
  assert.equal(h.store.historyRevision, 0)
  h.api.remove = async () => ({ deletedCount: 1, taskIds: ['done'] })
  await h.store.remove('done')
  assert.equal(h.store.byId.done, undefined)
  assert.deepEqual(h.store.active.map(t => t.taskId), ['active'])
  assert.equal(h.store.historyRevision, 1)
})

test('late progress, old status and an in-flight REST result cannot revive a deleted task', async () => {
  const h = await harness([running('task')])
  const stream = h.streams[0]
  stream.event({ event: 'status', data: JSON.stringify(terminal('task')) })
  const stale = deferred()
  h.api.get = () => stale.promise
  const refresh = h.store.refreshOne('task')
  await h.store.remove('task')
  assert.equal(stream.closed, true)
  stream.event({ event: 'status', data: JSON.stringify(running('task')) })
  stream.event({ event: 'progress', data: JSON.stringify({ progress: 90 }) })
  stale.resolve(terminal('task'))
  await refresh
  assert.equal(h.store.byId.task, undefined)
  assert.deepEqual(h.store.list, [])
})

test('clear uses the server cutoff and preserves active tasks and newly completed results', async () => {
  const h = await harness([terminal('old'), running('active'), terminal('new', 120)])
  await h.store.clearHistory()
  assert.deepEqual(h.store.list.map(t => t.taskId), ['active', 'new'])
  // A fresh authoritative response can include a completion with an equal/older timestamp (clock precision).
  h.api.get = async () => terminal('unseen-old', 100)
  await h.store.refreshOne('unseen-old')
  assert.equal(h.store.byId['unseen-old'].finishedAt, 100)
  const stream = h.streams.at(-1)
  stream.event({ event: 'status', data: JSON.stringify(terminal('active', 125)) })
  assert.equal(h.store.byId.active.finishedAt, 125)
})

test('a retry racing with clear may become active without being permanently tombstoned', async () => {
  const h = await harness([{ ...terminal('retry'), status: 'FAILED' }])
  const result = deferred()
  h.api.retry = () => result.promise
  const retry = h.store.retry('retry')
  await h.store.clearHistory()
  h.api.get = async () => ({ ...running('retry'), status: 'QUEUED' })
  result.resolve({ ...running('retry'), status: 'QUEUED' })
  await retry
  assert.equal(h.store.byId.retry.status, 'QUEUED')
  assert.deepEqual(h.store.active.map(t => t.taskId), ['retry'])
})

test('a late retry response cannot revive a task that finished and was cleared on the server', async () => {
  const h = await harness([{ ...terminal('retry'), status: 'FAILED' }])
  const response = deferred()
  h.api.retry = () => response.promise
  const retry = h.store.retry('retry')
  // Server already completed the retry while the old POST response remained in flight.
  h.items = [terminal('retry', 50)]
  await h.store.clearHistory()
  response.resolve({ ...running('retry'), status: 'QUEUED' })
  await retry
  assert.equal(h.store.byId.retry, undefined)
  assert.deepEqual(h.store.active, [])
})

test('clear reconciles a locally active snapshot when the server already finished and cleared it', async () => {
  const h = await harness([running('stale-active')])
  const oldStream = h.streams[0]
  h.items = [terminal('stale-active', 50)]
  await h.store.clearHistory()
  oldStream.event({ event: 'status', data: JSON.stringify(running('stale-active')) })
  assert.equal(oldStream.closed, true)
  assert.equal(h.store.byId['stale-active'], undefined)
  assert.deepEqual(h.store.active, [])
})

test('clear retries its authoritative snapshot if another missing record changes the history revision', async () => {
  const h = await harness([running('stale-active')])
  h.items = [terminal('stale-active', 50)]
  const all = deferred(), active = deferred()
  let requests = 0
  h.api.list = (_page, _size, statuses) => {
    requests++
    return requests <= 2 ? statuses ? active.promise : all.promise : Promise.resolve(page([]))
  }
  const clearing = h.store.clearHistory()
  await Promise.resolve()
  assert.equal(requests, 2)
  await h.store.refreshOne('missing-record')
  all.resolve(page([])); active.resolve(page([]))
  await clearing
  assert.ok(requests >= 4)
  assert.equal(h.store.byId['stale-active'], undefined)
  assert.equal(h.store.historySyncError, '')
})

test('restoring replaces obsolete cached history and obtains older active tasks separately', async () => {
  const h = await harness([terminal('deleted-on-another-device'), running('older-active')])
  h.api.list = async (_page, _size, statuses) => page(statuses ? [running('older-active')] : [terminal('recent')])
  await h.store.restore()
  assert.equal(h.store.byId['deleted-on-another-device'], undefined)
  assert.deepEqual(h.store.list.map(t => t.taskId), ['recent', 'older-active'])
})

test('a list response cannot move a task back to running after its newer terminal event', async () => {
  const h = await harness([running('active')])
  const all = deferred(), active = deferred()
  h.api.list = (_page, _size, statuses) => statuses ? active.promise : all.promise
  const restoring = h.store.restore()
  h.streams[0].event({ event: 'status', data: JSON.stringify(terminal('active', 150)) })
  all.resolve(page([running('active')]))
  active.resolve(page([running('active')]))
  await restoring
  assert.equal(h.store.byId.active.status, 'SUCCEEDED')
  assert.equal(h.store.byId.active.finishedAt, 150)
})

test('batch deletion updates every view through the same revision and leaves other records alone', async () => {
  const h = await harness([terminal('one'), terminal('two'), running('active')])
  await h.store.removeMany(['one', 'two'])
  assert.deepEqual(h.store.list.map(t => t.taskId), ['active'])
  assert.equal(h.store.isHistoryVisible(terminal('one')), false)
  assert.equal(h.store.historyRevision, 1)
})

test('responses from the previous login cannot repopulate a reset task store', async () => {
  const h = await harness([terminal('previous-user')])
  const stale = deferred()
  h.api.get = () => stale.promise
  const refresh = h.store.refreshOne('previous-user')
  h.store.reset()
  stale.resolve(terminal('previous-user'))
  await refresh
  assert.deepEqual(h.store.list, [])
  h.api.list = async () => page([terminal('new-user')])
  await h.store.restore()
  assert.deepEqual(h.store.list.map(t => t.taskId), ['new-user'])
})
