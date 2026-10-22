import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import test from 'node:test'
import ts from 'typescript'

const source = await readFile(new URL('../src/components/document/conversionOptions.ts', import.meta.url), 'utf8')
const { outputText } = ts.transpileModule(source, { compilerOptions: { module: ts.ModuleKind.ESNext, target: ts.ScriptTarget.ES2022 } })
const { DOCUMENT_FORMATS, availableModes, conversionRoutes, routeSupports } = await import(`data:text/javascript;base64,${Buffer.from(outputText).toString('base64')}`)
const route = (from, to, extra = {}) => ({ from, to, engine: 'local', available: true, taskType: 'document.convert', ...extra })

test('the picker exposes exactly the seven real document and image formats', () => {
  assert.deepEqual(new Set(DOCUMENT_FORMATS.map(format => format.value)), new Set(['docx', 'xlsx', 'html', 'ofd', 'pdf', 'png', 'jpg']))
})

test('mixed PNG and JPG sources require an available route for every format', () => {
  const routes = [route('png', 'pdf'), route('jpg', 'pdf', { available: false, reason: 'missing dependency' })]
  assert.equal(routeSupports(routes, ['png', 'png'], 'pdf'), true)
  assert.equal(routeSupports(routes, ['png', 'jpg'], 'pdf'), false)
  assert.equal(routeSupports(routes, ['ofd'], 'pdf'), false)
  assert.equal(routeSupports(routes, [], 'pdf'), false)
})

test('the available modes are the intersection of the selected routes', () => {
  const pdf = route('pdf', 'docx', { modes: ['layout', 'editable'], defaultMode: 'layout' })
  const image = route('png', 'docx', { modes: ['layout'], defaultMode: 'layout' })
  assert.deepEqual(availableModes([pdf]), ['layout', 'editable'])
  assert.deepEqual(availableModes([pdf, image]), ['layout'])
  assert.deepEqual(availableModes([]), [])
})

test('route lookup does not substitute generic image or same-format routes', () => {
  const ofd = route('ofd', 'xlsx', { modes: ['layout', 'editable'] })
  const routes = [ofd, route('image', 'pdf'), route('pdf', 'png')]
  assert.deepEqual(conversionRoutes(routes, ['ofd', 'ofd'], 'xlsx'), [ofd])
  assert.equal(routeSupports(routes, ['png'], 'pdf'), false)
  assert.equal(routeSupports(routes, ['pdf'], 'pdf'), false)
})
