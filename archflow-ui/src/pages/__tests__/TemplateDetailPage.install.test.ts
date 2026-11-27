/**
 * RCHFLW-204 — "Usar este modelo" de um template do backend (SPI) vai pelo POST /api/templates/{id}/install, com os
 * parâmetros obrigatórios pedidos num formulário. Antes todo template era clonado no navegador com o grafo que o GET
 * já resolvera com os defaults: os parâmetros nunca eram pedidos e o knowledgeBaseId do customer-support ficava
 * vazio. Os templates do catálogo local continuam pelo clone.
 *
 * <p>Arquivo .ts (createElement em vez de JSX) porque o vitest.config só inclui *.test.ts.</p>
 */
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { entrarComo } from '../../lib/__tests__/papeisDeTeste'
import { createElement as h, act } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { MantineProvider } from '@mantine/core'
import { fireEvent, waitFor, within } from '@testing-library/dom'
import type { WorkflowTemplate } from '../../data/templates'
import { notifications } from '@mantine/notifications'

const templateApiMock = vi.hoisted(() => ({
  list: vi.fn(), listSync: vi.fn(), get: vi.fn(), clone: vi.fn(), install: vi.fn(),
}))
const workflowApiMock = vi.hoisted(() => ({ list: vi.fn() }))
const listBases = vi.hoisted(() => vi.fn())

vi.mock('react-i18next', () => ({
  useTranslation: () => ({
    t: (k: string, o?: Record<string, unknown>) => (o?.name ? `${k}[${o.name}]` : k),
    i18n: { language: 'pt-BR', resolvedLanguage: 'pt-BR' },
  }),
}))
vi.mock('@mantine/notifications', () => ({ notifications: { show: vi.fn() } }))
vi.mock('../../services/template-api', () => ({ templateApi: templateApiMock }))
vi.mock('../../services/api', () => ({ workflowApi: workflowApiMock }))
vi.mock('../../services/knowledge-api', () => ({ knowledgeApi: { listBases: () => listBases() } }))

type Item = string | { value: string; label: string }

// Select nativo: o que importa é o valor escolhido e o que o onChange grava.
vi.mock('@mantine/core', async (importOriginal) => {
  const real = await importOriginal<typeof import('@mantine/core')>()
  const { createElement } = await import('react')
  const Select = (props: {
    label?: string; value?: string | null; data?: Item[]; onChange?: (v: string | null) => void
    'data-testid'?: string
  }) => createElement('select', {
    'data-label': props.label,
    'data-testid': props['data-testid'],
    value: props.value ?? '',
    onChange: (e: { target: { value: string } }) => props.onChange?.(e.target.value === '' ? null : e.target.value),
  }, [createElement('option', { key: '', value: '' }, '—'),
    ...(props.data ?? []).map((i) => {
      const item = typeof i === 'string' ? { value: i, label: i } : i
      return createElement('option', { key: item.value, value: item.value }, item.label)
    })])
  return { ...real, Select }
})

import TemplateDetailPage from '../TemplateDetailPage'

const passos: WorkflowTemplate['steps'] = [
  { id: 'input', type: 'input', componentId: 'input', label: 'Entrada', category: 'io', position: { x: 0, y: 0 } },
  { id: 'kb', type: 'vector-search', componentId: 'vector-search', label: 'Busca', category: 'vector',
    position: { x: 1, y: 0 } },
]

/** Como toWorkflowTemplate entrega o customer-support do backend: com os parâmetros do install. */
const doBackend: WorkflowTemplate = {
  id: 'customer-support', name: 'Customer Support with RAG', icon: '📄', summary: 's', description: 'd',
  category: 'support' as WorkflowTemplate['category'], tags: [], complexity: 'starter',
  steps: passos, connections: [], unsupportedNodeTypes: [],
  parameters: [
    { name: 'knowledgeBaseId', description: 'ID of the knowledge base', type: 'String', required: true },
    { name: 'responseLanguage', type: 'String', defaultValue: 'en', required: true, options: ['en', 'pt'] },
    { name: 'maxIterations', type: 'Integer', defaultValue: 5, required: false },
  ],
}
/** Do catálogo local: sem `parameters`. */
const local: WorkflowTemplate = { ...doBackend, id: 'sac-basic', name: 'SAC', parameters: undefined }
const all = [doBackend, local]

let root: Root | null = null
let container: HTMLDivElement

beforeEach(() => {
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
  workflowApiMock.list.mockResolvedValue([])
  listBases.mockResolvedValue([
    { id: 'kb-1', tenantId: 't', workspaceId: null, name: 'suporte', createdAt: '' },
  ])
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
          h(Route, { path: '/templates/:id', element: h(TemplateDetailPage) }),
          h(Route, { path: '/editor/:id', element: h('div', { 'data-testid': 'editor' }, 'editor') })))))
  })
  return within(document.body)
}

async function abrirFormulario(ui: ReturnType<typeof within>) {
  const use = await waitFor(() => ui.getByTestId('template-clone'))
  await act(async () => { fireEvent.click(use) })
  const confirm = await waitFor(() => ui.getByTestId('template-install-confirm') as HTMLButtonElement)
  // A lista de bases chega por uma promise: deixa o efeito do seletor terminar.
  await act(async () => { await Promise.resolve() })
  return confirm
}

const baseSelect = () =>
  document.body.querySelector('[data-testid="template-param-knowledgeBaseId"] select') as HTMLSelectElement

describe('"Usar este modelo" de um template do backend (RCHFLW-204)', () => {
  it('pede a base no seletor do painel, instala com os parâmetros e abre o fluxo criado no editor', async () => {
    templateApiMock.install.mockResolvedValue({ workflowId: 'wf-novo', unsupportedNodeTypes: [] })
    const ui = await render('/templates/customer-support')

    const confirm = await abrirFormulario(ui)
    // O enum obrigatório vem com o default do template; o opcional (maxIterations) não aparece.
    expect((ui.getByTestId('template-param-responseLanguage') as HTMLSelectElement).value).toBe('en')
    expect(ui.queryByTestId('template-param-maxIterations')).toBeNull()
    await act(async () => { fireEvent.change(baseSelect(), { target: { value: 'kb-1' } }) })
    expect(confirm.disabled).toBe(false)
    await act(async () => { fireEvent.click(confirm) })

    await waitFor(() => ui.getByTestId('editor'))
    expect(templateApiMock.install).toHaveBeenCalledWith('customer-support', {
      name: 'templates.detail.copyName[Customer Support with RAG]',
      parameters: { knowledgeBaseId: 'kb-1', responseLanguage: 'en' },
    })
    expect(templateApiMock.clone).not.toHaveBeenCalled()
    // O aviso diz que foi o servidor que criou, com os parâmetros — não "clonado".
    expect(notifications.show).toHaveBeenCalledWith(expect.objectContaining({
      message: 'templates.install.createdMsg[templates.detail.copyName[Customer Support with RAG]]',
    }))
  })

  it('nó sem executor que o servidor ainda acha depois do install é avisado, não descartado', async () => {
    templateApiMock.install.mockResolvedValue({ workflowId: 'wf-novo', unsupportedNodeTypes: ['reranker'] })
    const ui = await render('/templates/customer-support')

    const confirm = await abrirFormulario(ui)
    await act(async () => { fireEvent.change(baseSelect(), { target: { value: 'kb-1' } }) })
    await act(async () => { fireEvent.click(confirm) })

    await waitFor(() => ui.getByTestId('editor'))
    expect(notifications.show).toHaveBeenCalledWith(expect.objectContaining({
      title: 'templates.detail.unsupportedTitle', message: 'templates.install.unsupportedMsg', color: 'orange',
    }))
  })

  it('sem nó sem executor no install, só o aviso de criado', async () => {
    templateApiMock.install.mockResolvedValue({ workflowId: 'wf-novo', unsupportedNodeTypes: [] })
    const ui = await render('/templates/customer-support')

    const confirm = await abrirFormulario(ui)
    await act(async () => { fireEvent.change(baseSelect(), { target: { value: 'kb-1' } }) })
    await act(async () => { fireEvent.click(confirm) })

    await waitFor(() => ui.getByTestId('editor'))
    expect(notifications.show).toHaveBeenCalledTimes(1)
  })

  it('obrigatório sem valor: o Instalar fica desativado e nada é enviado', async () => {
    const ui = await render('/templates/customer-support')

    const confirm = await abrirFormulario(ui)
    expect(baseSelect().value).toBe('')
    expect(confirm.disabled).toBe(true)
    await act(async () => { fireEvent.click(confirm) })

    expect(templateApiMock.install).not.toHaveBeenCalled()
    expect(ui.queryByTestId('editor')).toBeNull()
  })

  it('o erro do install (400 com mensagem) aparece no formulário, sem navegar', async () => {
    templateApiMock.install.mockRejectedValue(
      new Error("Required parameter 'knowledgeBaseId' is missing for template 'customer-support'"))
    const ui = await render('/templates/customer-support')

    const confirm = await abrirFormulario(ui)
    await act(async () => { fireEvent.change(baseSelect(), { target: { value: 'kb-1' } }) })
    await act(async () => { fireEvent.click(confirm) })

    const error = await waitFor(() => ui.getByTestId('template-install-error'))
    expect(error.textContent).toContain("Required parameter 'knowledgeBaseId' is missing")
    expect(ui.queryByTestId('editor')).toBeNull()
  })
})

describe('"Usar este modelo" de um template local', () => {
  it('segue pelo clone, sem formulário nem install', async () => {
    templateApiMock.clone.mockResolvedValue({ id: 'wf-clone' })
    const ui = await render('/templates/sac-basic')

    const use = await waitFor(() => ui.getByTestId('template-clone'))
    await act(async () => { fireEvent.click(use) })

    await waitFor(() => ui.getByTestId('editor'))
    expect(templateApiMock.clone).toHaveBeenCalledWith('sac-basic', { name: 'templates.detail.copyName[SAC]' })
    expect(templateApiMock.install).not.toHaveBeenCalled()
    expect(ui.queryByTestId('template-install-confirm')).toBeNull()
    expect(notifications.show).toHaveBeenCalledWith(expect.objectContaining({
      message: 'templates.detail.createdMsg[templates.detail.copyName[SAC]]',
    }))
  })
})
