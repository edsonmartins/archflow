// @vitest-environment node
/**
 * RCHFLW-87 — o bundle de `vite build --mode component` é servido como está ao navegador (trecho
 * de Publicar › Incorporar). No modo lib o vite não troca `process.env.*`, e o React lê
 * `process.env.NODE_ENV`: sem o define, importar o bundle lança "process is not defined".
 * Ambiente node: importar o vite.config carrega o esbuild, que não roda sob o jsdom.
 */
import { describe, it, expect } from 'vitest'
import viteConfig from '../../../../vite.config'

describe('vite.config — build do web-component', () => {
  it('o build do componente troca process.env.NODE_ENV (o navegador não tem `process`)', () => {
    const resolve = viteConfig as unknown as (env: { mode: string; command: string }) => {
      define?: Record<string, string>
    }
    const component = resolve({ mode: 'component', command: 'build' })
    expect(component.define?.['process.env.NODE_ENV']).toBe(JSON.stringify('production'))
    // O SPA segue com o comportamento padrão do vite (que já faz a troca fora do modo lib).
    const spa = resolve({ mode: 'production', command: 'build' })
    expect(spa.define?.['process.env.NODE_ENV']).toBeUndefined()
  })
})
