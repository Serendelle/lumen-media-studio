// Copies the Pyodide runtime (WebAssembly CPython + stdlib) next to the app so the Python sandbox loads it from
// the same origin — no CDN, works offline. Runs before `dev` and `build`.
import { cpSync, existsSync, mkdirSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'

const root = join(dirname(fileURLToPath(import.meta.url)), '..')
const src = join(root, 'node_modules', 'pyodide')
const dst = join(root, 'public', 'pyodide')
const files = ['pyodide.mjs', 'pyodide.asm.js', 'pyodide.asm.wasm', 'python_stdlib.zip', 'pyodide-lock.json']
if (!existsSync(src)) {
  console.error('pyodide is not installed (npm ci)')
  process.exit(1)
}
mkdirSync(dst, { recursive: true })
for (const f of files) cpSync(join(src, f), join(dst, f))
console.log(`pyodide runtime copied to public/pyodide (${files.length} files)`)
