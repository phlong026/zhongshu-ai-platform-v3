import { readdirSync, readFileSync } from 'node:fs'
import { join } from 'node:path'
import type { Plugin } from 'vite'

/** Serve PDF fonts/decoders locally; course documents never depend on a public CDN. */
export function trainingPdfAssets(root: string): Plugin {
  const assets = new Map<string, Buffer>()
  for (const folder of ['cmaps', 'standard_fonts', 'wasm', 'iccs']) {
    for (const entry of readdirSync(join(root, folder), { withFileTypes: true })) {
      if (entry.isFile()) assets.set(`${folder}/${entry.name}`, readFileSync(join(root, folder, entry.name)))
    }
  }
  assets.set('LICENSE', readFileSync(join(root, 'LICENSE')))
  return {
    name: 'training-pdf-assets',
    configureServer(server) {
      server.middlewares.use((request, response, next) => {
        const prefix = `${server.config.base}training-pdf/`
        const url = request.url?.split('?')[0] || ''
        if (!url.startsWith(prefix)) return next()
        const name = url.slice(prefix.length), bytes = assets.get(name)
        if (!bytes) return next()
        response.setHeader('Content-Type', name.endsWith('.wasm') ? 'application/wasm' : 'application/octet-stream')
        response.end(bytes)
      })
    },
    generateBundle() {
      for (const [name, source] of assets) this.emitFile({ type: 'asset', fileName: `training-pdf/${name}`, source })
    }
  }
}
