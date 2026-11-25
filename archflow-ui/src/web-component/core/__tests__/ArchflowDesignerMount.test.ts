/**
 * RCHFLW-87 — o <archflow-designer> incorporado precisa de fato RENDERIZAR o canvas.
 *
 * Os testes de ArchflowDesigner.test.ts só olham atributos e eventos: nenhum deles espera o React
 * montar. Foi assim que o embed ficou vazio sem ninguém notar — o FlowCanvas passou a usar
 * componentes Mantine, o web-component não tinha MantineProvider, e o React desmontava a árvore
 * com "MantineProvider was not found" deixando o shadow root só com <link>/<style>.
 */
import { describe, it, expect, beforeAll, afterEach, vi } from 'vitest'
import { act } from 'react'
// Como em web-component/index.ts: o FlowCanvas usa useTranslation.
import '../../../i18n'
import { ArchflowDesigner } from '../ArchflowDesigner'

// O jsdom não tem ResizeObserver nem matchMedia; o xyflow e o Mantine usam os dois.
class ResizeObserverStub {
  observe() {}
  unobserve() {}
  disconnect() {}
}

describe('ArchflowDesigner — montagem do canvas no Shadow DOM', () => {
  const erros: string[] = []
  const onError = (e: ErrorEvent) => {
    erros.push(String(e.error?.message ?? e.message))
    e.preventDefault()
  }

  beforeAll(() => {
    ;(globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true
    vi.stubGlobal('ResizeObserver', ResizeObserverStub)
    if (!window.matchMedia) {
      vi.stubGlobal('matchMedia', (q: string) => ({
        matches: false, media: q, onchange: null,
        addListener() {}, removeListener() {},
        addEventListener() {}, removeEventListener() {}, dispatchEvent: () => false,
      }))
    }
    ArchflowDesigner.register()
    window.addEventListener('error', onError)
  })

  afterEach(() => {
    erros.length = 0
    vi.spyOn(console, 'error').mockRestore()
  })

  it('renderiza o FlowCanvas (sem erro de MantineProvider) com o CSS do Mantine no shadow root', async () => {
    vi.spyOn(console, 'error').mockImplementation(() => {})
    const el = document.createElement('archflow-designer') as InstanceType<typeof ArchflowDesigner>
    await act(async () => {
      document.body.appendChild(el)
    })

    expect(erros).toEqual([])
    const shadow = el.shadowRoot!
    expect(shadow.querySelector('.react-flow')).not.toBeNull()
    // Esquema de cor num ancestral dentro da árvore (seletores `:where([data-…]) .m_…`) e no host
    // (variáveis em `:host([data-…])`).
    expect(shadow.querySelector('.af-scheme-root')?.getAttribute('data-mantine-color-scheme')).toBe('light')
    expect(el.getAttribute('data-mantine-color-scheme')).toBe('light')
    const css = Array.from(shadow.querySelectorAll('style')).map((s) => s.textContent ?? '').join('\n')
    expect(css).toContain('--mantine-')

    await act(async () => {
      el.remove()
    })
  })

  it('workflow-id: carrega o documento da API (arestas dentro de cada passo) e desenha nós e aresta', async () => {
    vi.spyOn(console, 'error').mockImplementation(() => {})
    // O formato que GET /api/workflows/{id} devolve. Com o documento cru o canvas lia `connections` no
    // topo (undefined) e o React desmontava com "Cannot read properties of undefined (reading 'map')".
    const documento = {
      id: 'wf-embed',
      metadata: { name: 'Embed' },
      steps: [
        {
          id: 'a', componentId: 'condition', type: 'CONDITION', label: 'A', position: { x: 0, y: 0 },
          configuration: { conditionExpression: "${x} == 1" },
          connections: [{ sourceId: 'a', targetId: 'b', branch: 'true' }],
        },
        { id: 'b', componentId: 'output', type: 'OUTPUT', label: 'B', position: { x: 300, y: 0 }, connections: [] },
      ],
    }
    const fetchMock = vi.fn(async () => new Response(JSON.stringify(documento), { status: 200 }))
    vi.stubGlobal('fetch', fetchMock)

    const el = document.createElement('archflow-designer') as InstanceType<typeof ArchflowDesigner>
    el.setAttribute('api-base', 'http://archflow.test/api')
    el.setAttribute('workflow-id', 'wf-embed')
    await act(async () => {
      document.body.appendChild(el)
    })
    await act(async () => {
      await new Promise((r) => setTimeout(r, 0))
    })

    expect(fetchMock).toHaveBeenCalledWith('http://archflow.test/api/workflows/wf-embed', expect.anything())
    expect(erros).toEqual([])
    expect(el.workflow?.connections).toEqual([
      expect.objectContaining({ sourceId: 'a', targetId: 'b', branch: 'true' }),
    ])
    expect(el.workflow?.steps.map((s) => s.id)).toEqual(['a', 'b'])

    await act(async () => {
      el.remove()
    })
    vi.unstubAllGlobals()
    vi.stubGlobal('ResizeObserver', ResizeObserverStub)
  })
})
