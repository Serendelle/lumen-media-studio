import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import ts from 'typescript'

// Transpile just this pure module, keeping these tests usable with every supported Node version.
const source = readFileSync(new URL('../src/utils/formMerge.ts', import.meta.url), 'utf8')
const compiled = ts.transpileModule(source, { compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.ES2022 } }).outputText
const { changedFormValues, mergeFormDraft } = await import(`data:text/javascript;base64,${Buffer.from(compiled).toString('base64')}`)

test('editing a department preserves a concurrent edit to quantity', () => {
  const base = { department: '设计', quantity: '2' }, draft = { ...base, department: '研发' }, server = { ...base, quantity: '5' }
  const result = mergeFormDraft(base, draft, server)
  assert.deepEqual(result, { values: { department: '研发', quantity: '5' }, conflicts: [] })
  assert.deepEqual(changedFormValues(server, result.values), { department: '研发' })
  assert.deepEqual(base, { department: '设计', quantity: '2' })
})
test('competing edits to one cell require a user choice', () => {
  const result = mergeFormDraft({ quantity: '1', note: null }, { quantity: '2', note: '我的说明' }, { quantity: '3', note: null })
  assert.deepEqual(result.conflicts, ['quantity'])
  assert.equal(result.values.note, '我的说明')
})
test('identical changes are not reported as conflicts', () => {
  assert.deepEqual(mergeFormDraft({ confirmed: false }, { confirmed: true }, { confirmed: true }).conflicts, [])
})
test('new fields and explicit clearing survive a schema refresh', () => {
  assert.deepEqual(mergeFormDraft({ note: '旧值' }, { note: null }, { note: '旧值', date: null }).values, { note: null, date: null })
})
