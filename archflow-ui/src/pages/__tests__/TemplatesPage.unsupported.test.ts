/**
 * A galeria marca o template que tem nós sem executor no motor, e lista os tipos (RCHFLW-18,
 * opção A): esconder o problema era o defeito — o clone abria, a execução falhava em silêncio.
 * O detalhe repete o aviso, marca cada passo e não oferece "Usar" quando não há passos (antes o
 * texto dizia que "os nós são criados quando você o usa", e nada os criava).
 *
 * <p>Arquivo .ts (createElement em vez de JSX) porque o vitest.config só inclui *.test.ts.</p>
 */
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { entrarComo } from '../../lib/__tests__/papeisDeTeste'
import { createElement as h, act } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { MantineProvider } from '@mantine/core'
import { waitFor, within } from '@testing-library/dom'
import type { WorkflowTemplate } from '../../data/templates'

const templateApiMock = vi.hoisted(() => ({
  list: vi.fn(), listSync: vi.fn(), get: vi.fn(), clone: vi.fn(),
}))

vi.mock('react-i18next', () => ({
  useTranslation: () => ({
    t: (k: string, o?: Record<string, unknown>) => (o?.types ? `${k}[${o.types}]` : k),
    i18n: { language: 'pt-BR', resolvedLanguage: 'pt-BR' },
  }),
}))
vi.mock('../../services/template-api', () => ({ templateApi: templateApiMock }))

import TemplatesPage from '../TemplatesPage'
import TemplateDetailPage from '../TemplateDetailPage'

const base: Omit<WorkflowTemplate, 'id' | 'name'> = {
  icon: '📄', summary: 's', description: 'd', category: 'rag', tags: [], complexity: 'starter',
  steps: [], connections: [],
}

const marcado: WorkflowTemplate = {
  ...base, id: 'customer-support', name: 'Customer Support with RAG',
  steps: [
    { id: 'input', type: 'input', componentId: 'input', label: 'Entrada', category: 'io', position: { x: 0, y: 0 } },
    { id: 'route', type: 'condition', componentId: 'condition', label: 'Roteia', category: 'control',
      position: { x: 1, y: 0 } },
    { id: 'kb', type: 'vector-search', componentId: 'vector-search', label: 'Busca', category: 'vector',
      position: { x: 2, y: 0 } },
  ],
  unsupportedNodeTypes: ['input', 'vector-search'],
}
const executavel: WorkflowTemplate = { ...marcado, id: 'ok', name: 'Tudo roda', unsupportedNodeTypes: [] }
const vazio: WorkflowTemplate = { ...base, id: 'vazio', name: 'Sem passos' }
// Verificação não respondeu (backend sem o endpoint, falha passageira): `unsupportedNodeTypes` ausente.
const naoVerificado: WorkflowTemplate = { ...marcado, id: 'nao-verificado', name: 'Sem verificação',
  unsupportedNodeTypes: undefined }
// Formato do designer/pacote: config em `config`, arestas no topo do template.
const comArestas: WorkflowTemplate = {
  ...base, id: 'com-arestas', name: 'Com arestas', unsupportedNodeTypes: [],
  steps: [
    { id: 'a', type: 'input', componentId: 'input', label: 'A', category: 'io', position: { x: 0, y: 0 } },
    { id: 'b', type: 'llm-chat', componentId: 'llm-chat', label: 'B', category: 'llm', position: { x: 1, y: 0 },
      config: { model: 'gpt-4o' } },
  ],
  connections: [{ id: 'c1', sourceId: 'a', targetId: 'b', isErrorPath: false, condition: "${x} == 'y'" }],
}
const all = [marcado, executavel, vazio, naoVerificado, comArestas]

let root: Root | null = null
let container: HTMLDivElement

beforeEach(() => {
  // Papel que concede editar e executar: sem ele as ações somem (RCHFLW-74).
  entrarComo('DESIGNER')
  vi.clearAllMocks()
  ;(globalThis as any).IS_REACT_ACT_ENVIRONMENT = true
  ;(globalThis as any).ResizeObserver ??= class { observe() {} unobserve() {} disconnect() {} }
  window.matchMedia ??= ((q: string) => ({
    matches: false, media: q, onchange: null,
    addListener() {}, removeListener() {}, addEventListener() {}, removeEventListener() {},
    dispatchEvent: () => false,
  })) as any
  templateApiMock.list.mockResolvedValue(all)
  templateApiMock.listSync.mockReturnValue([])
  templateApiMock.get.mockImplementation((id: string) => all.find((t) => t.id === id))
})

afterEach(async () => {
  await act(async () => { root?.unmount() })
  root = null
  container?.remove()
})

async function render(path: string) {
  container = document.createElement('div')
  document.body.appendChild(container)
  root = createRoot(container)
  await act(async () => {
    root!.render(h(MantineProvider, null,
      h(MemoryRouter, { initialEntries: [path] },
        h(Routes, null,
          h(Route, { path: '/templates', element: h(TemplatesPage) }),
          h(Route, { path: '/templates/:id', element: h(TemplateDetailPage) })))))
  })
  return within(container)
}

describe('galeria de templates — nós sem executor', () => {
  it('o card marca o template e lista os tipos; o executável não ganha marca', async () => {
    const ui = await render('/templates')

    const mark = await waitFor(() => ui.getByTestId('template-unsupported-customer-support'))
    expect(mark.textContent).toContain('templates.unsupportedBadge')
    expect(mark.textContent).toContain('templates.unsupportedShort[input, vector-search]')
    expect(ui.queryByTestId('template-unsupported-ok')).toBeNull()
    expect(ui.getByTestId('template-card-vazio').textContent).toContain('templates.stepsServer')
  })

  it('o detalhe avisa, marca cada passo sem executor e deixa clonar', async () => {
    const ui = await render('/templates/customer-support')

    const alert = await waitFor(() => ui.getByTestId('template-unsupported'))
    expect(alert.textContent).toContain('templates.detail.unsupportedHint[input, vector-search]')
    expect(ui.getByTestId('template-step-unsupported-input')).toBeTruthy()
    expect(ui.getByTestId('template-step-unsupported-kb')).toBeTruthy()
    expect(ui.queryByTestId('template-step-unsupported-route')).toBeNull()
    expect((ui.getByTestId('template-clone') as HTMLButtonElement).disabled).toBe(false)
  })

  it('sem passos, "Usar" fica desativado — clonar criaria um fluxo vazio', async () => {
    const ui = await render('/templates/vazio')

    const button = await waitFor(() => ui.getByTestId('template-clone') as HTMLButtonElement)
    expect(button.disabled).toBe(true)
    expect(ui.queryByTestId('template-unsupported')).toBeNull()
    expect(container.textContent).toContain('templates.detail.noSteps')
  })

  it('verificação ausente: selo neutro "não verificado" — sem selo pareceria que roda', async () => {
    const ui = await render('/templates')

    const mark = await waitFor(() => ui.getByTestId('template-unverified-nao-verificado'))
    expect(mark.textContent).toContain('templates.unverifiedBadge')
    expect(ui.queryByTestId('template-unverified-ok')).toBeNull()
    expect(ui.queryByTestId('template-unverified-customer-support')).toBeNull()
    expect(ui.queryByTestId('template-unverified-vazio')).toBeNull()
  })

  it('o JSON "pronto para colar" é o documento canônico: arestas dentro dos passos, config em configuration',
    async () => {
      // Antes o bloco trazia o formato do template (`connections` no topo, `config`), que nenhum leitor
      // aceita: colado como documento, o fluxo abria e executava sem arestas (RCHFLW-45).
      const ui = await render('/templates/com-arestas')

      const block = await waitFor(() => ui.getByTestId('template-workflow-json'))
      const doc = JSON.parse(block.textContent ?? '{}')
      expect(doc).not.toHaveProperty('connections')
      expect(doc.steps[0].connections)
        .toEqual([{ sourceId: 'a', targetId: 'b', isErrorPath: false, condition: "${x} == 'y'" }])
      expect(doc.steps[1].configuration).toEqual({ model: 'gpt-4o' })
      expect(doc.steps[1]).not.toHaveProperty('config')
      expect(doc.steps[1].type).toBe('llm-chat')
    })

  it('verificação ausente no detalhe: aviso neutro, sem o aviso de nós sem executor', async () => {
    const ui = await render('/templates/nao-verificado')

    await waitFor(() => ui.getByTestId('template-unverified'))
    expect(ui.queryByTestId('template-unsupported')).toBeNull()
  })
})

describe('detalhe do template por papel (RCHFLW-74)', () => {
  it('sem workflow:create (VIEWER, EXECUTOR) não oferece "Usar": clonar cria um fluxo', async () => {
    for (const papel of ['VIEWER', 'EXECUTOR']) {
      entrarComo(papel)
      const ui = await render('/templates/ok')
      await waitFor(() => expect(container.textContent).toContain('Tudo roda'))
      expect(ui.queryByTestId('template-clone')).toBeNull()
      await act(async () => { root?.unmount() })
      container.remove()
    }
    root = null
  })
})
