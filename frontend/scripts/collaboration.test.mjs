import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import ts from 'typescript'

const source = readFileSync(new URL('../src/components/collaboration/tencentLink.ts', import.meta.url), 'utf8')
const compiled = ts.transpileModule(source, { compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.ES2022 } }).outputText
const { safeTencentUrl } = await import(`data:text/javascript;base64,${Buffer.from(compiled).toString('base64')}`)

test('official direct document and sheet links preserve allowed share parameters', () => {
  const direct = 'https://docs.qq.com/sheet/DAbcdef123?tab=BB08J2&scode=AbCd_12#cell=A1'
  assert.equal(safeTencentUrl(direct), direct)
  assert.equal(safeTencentUrl('https://DOCS.QQ.COM/doc/DAbcdef123'), 'https://docs.qq.com/doc/DAbcdef123')
})

test('foreign origins, dangerous protocols and authority tricks never become anchors', () => {
  for (const link of [
    'javascript:alert(1)', 'data:text/html,<script>alert(1)</script>', 'http://docs.qq.com/doc/abcd',
    'https://docs.qq.com.evil.test/doc/abcd', 'https://evil.test/docs.qq.com/doc/abcd',
    'https://docs.qq.com@evil.test/doc/abcd', 'https://user:pass@docs.qq.com/doc/abcd',
    'https://docs.qq.com:443/doc/abcd', 'https://docs.qq.com:8443/doc/abcd',
    'https://docs%2eqq.com/doc/abcd', ' https://docs.qq.com/doc/abcd',
    'https://docs.qq.com/doc/abcd\n', 'https://docs.qq.com\\@evil.test/doc/abcd',
  ]) assert.equal(safeTencentUrl(link), null, link)
})

test('redirects, encoded paths, normalized traversal and unrecognized query keys are rejected', () => {
  for (const link of [
    'https://docs.qq.com', 'https://docs.qq.com/redirect?url=https://evil.test',
    'https://docs.qq.com/doc/abcd?url=https://evil.test', 'https://docs.qq.com/doc/abcd?tab=1&tab=2',
    'https://docs.qq.com/doc/abcd?tab=%0A', 'https://docs.qq.com/doc/abcd?',
    'https://docs.qq.com/doc/%61bcd', 'https://docs.qq.com/doc/abcd/../../sheet/abcd',
    'https://docs.qq.com/doc/abcd#<script>',
  ]) assert.equal(safeTencentUrl(link), null, link)
})
