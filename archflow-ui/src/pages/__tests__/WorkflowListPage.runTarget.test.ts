/**
 * RCHFLW-132 e RCHFLW-133 na lista de fluxos.
 *
 * <ul>
 *   <li>O selo do card mostrava o rótulo do "Ativar" (draft/active) como se fosse o que roda: "Rascunho" num fluxo
 *   com a v1 em produção, "Ativo" num que rodava o rascunho. Agora o selo é o que o Rodar executa
 *   ({@code publishedVersion} da lista), e o rótulo do Ativar aparece à parte, com a diferença explicada.</li>
 *   <li>Pelo editor uma condição inválida impedia o run com aviso; pelo "Rodar" da lista a execução era criada e
 *   terminava FAILED. A lista confere o documento que vai rodar com a mesma regra do editor.</li>
 * </ul>
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

const workflowApiMock = vi.hoisted(() => ({
  list: vi.fn(), execute: vi.fn(), setStatus: vi.fn(), get: vi.fn(), create: vi.fn(),
  update: vi.fn(), delete: vi.fn(), deployment: vi.fn(), versions: vi.fn(), version: vi.fn(),
}))
const notificationsMock = vi.hoisted(() => ({ show: vi.fn() }))

vi.mock('react-i18next', () => ({
  useTranslation: () => ({
    t: (k: string, o?: { number?: number; fields?: string }) =>
      o?.number !== undefined ? `${k}:${o.number}` : o?.fields !== undefined ? `${k}[${o.fields}]` : k,
    i18n: { language: 'pt-BR', resolvedLanguage: 'pt-BR' },
  }),
}))
vi.mock('@mantine/notifications', () => ({ notifications: notificationsMock }))
vi.mock('../../services/api', async (importOriginal) => ({
  ApiError: (await importOriginal<typeof import('../../services/api')>()).ApiError,
  workflowApi: workflowApiMock,
  executionApi: { list: vi.fn() },
}))

import WorkflowListPage from '../WorkflowListPage'
import { useWorkflowStore } from '../../stores/workflow-store'
import { ApiError } from '../../services/api'

let root: Root | null = null
let container: HTMLDivElement

const resumo = (id: string, status: string, publishedVersion: number | null) => ({
  id, name: id, description: '', status, publishedVersion, version: '1.0.0', stepCount: 2,
  updatedAt: '2026-11-25T00:00:00Z',
})

/** O fluxo do QA: Entrada → Condição com `=` sozinho. */
const comCondicao = (expressao: string) => ({
  id: 'wf-1', metadata: { name: 'QA' }, configuration: {},
  steps: [
    { id: 'entrada', type: 'input', componentId: 'input', label: 'Entrada', configuration: {},
      connections: [{ targetId: 'cond' }] },
    { id: 'cond', type: 'condition', componentId: 'condition', label: 'Confere',
      configuration: { conditionExpression: expressao }, connections: [] },
  ],
})

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
  sessionStorage.clear()
  useWorkflowStore.setState({ workflows: [], loading: false, error: null })
  workflowApiMock.execute.mockResolvedValue({ executionId: 'exec-42', status: 'RUNNING' })
})

afterEach(async () => {
  await act(async () => { root?.unmount() })
  root = null
  container?.remove()
})

async function render() {
  container = document.createElement('div')
  document.body.appendChild(container)
  root = createRoot(container)
  await act(async () => {
    root!.render(h(MantineProvider, null,
      h(MemoryRouter, { initialEntries: ['/workflows'] },
        h(Routes, null,
          h(Route, { path: '/workflows', element: h(WorkflowListPage) }),
          h(Route, { path: '/executions/:id', element: h('div', { 'data-testid': 'detail' }) })))))
  })
  return within(container)
}

describe('WorkflowListPage — o selo diz o que o Rodar executa (RCHFLW-132)', () => {
  it('versão publicada com rótulo draft, e rótulo active sem versão: cada um no seu selo', async () => {
    workflowApiMock.list.mockResolvedValue([
      resumo('qa-condicao', 'draft', 1),
      resumo('qa-switch', 'active', null),
    ])
    const ui = await render()

    const publicado = await waitFor(() => ui.getByTestId('workflow-runs-qa-condicao'))
    expect(publicado.textContent).toBe('workflows.runs.published:1')
    expect(ui.queryByTestId('workflow-lifecycle-qa-condicao')).toBeNull()

    expect(ui.getByTestId('workflow-runs-qa-switch').textContent).toBe('workflows.runs.draft')
    expect(ui.getByTestId('workflow-lifecycle-qa-switch').textContent).toBe('workflows.status.active')

    // A contagem também é do que roda: um publicado, um só com rascunho — não "Ativos 0".
    const stat = (label: string) => ui.getByText(label).closest('.af-stat')!.textContent
    expect(stat('workflows.stats.published')).toBe('workflows.stats.published1workflows.hints.published')
    expect(stat('workflows.stats.unpublished')).toBe('workflows.stats.unpublished1workflows.hints.unpublished')
    // E a diferença entre Publicar e Ativar está escrita na tela.
    expect(ui.getByTestId('workflows-publish-vs-activate').textContent).toBe('workflows.publishVsActivate')
  })
})

describe('WorkflowListPage — Rodar confere o fluxo como o Executar do editor (RCHFLW-133)', () => {
  it('condição inválida no rascunho que vai rodar: avisa como o editor e não cria execução', async () => {
    workflowApiMock.list.mockResolvedValue([resumo('wf-1', 'active', null)])
    workflowApiMock.deployment.mockRejectedValue(new ApiError(404, 'Not Found'))
    workflowApiMock.get.mockResolvedValue(comCondicao("${input} = 'x'"))
    const ui = await render()

    await act(async () => { fireEvent.click(await waitFor(() => ui.getByRole('button', { name: 'workflows.actions.execute' }))) })

    await waitFor(() => expect(notificationsMock.show).toHaveBeenCalledTimes(1))
    const aviso = notificationsMock.show.mock.calls[0][0]
    expect(aviso.title).toBe('editor.notif.runBlocked')
    expect(aviso.message).toContain('editor.notif.runBlockedInvalidExpression')
    expect(aviso.message).toContain('"Confere"')
    expect(within(document.body).queryByTestId('run-input-text')).toBeNull()
    expect(workflowApiMock.execute).not.toHaveBeenCalled()
  })

  it('confere a versão publicada, não o rascunho: rascunho quebrado com v2 válida em produção roda', async () => {
    workflowApiMock.list.mockResolvedValue([resumo('wf-1', 'draft', 2)])
    workflowApiMock.deployment.mockResolvedValue({ workflowId: 'wf-1', environment: 'PRODUCTION', versionId: 'wfv-2' })
    workflowApiMock.version.mockResolvedValue(
      { id: 'wfv-2', workflowId: 'wf-1', number: 2, document: comCondicao("${input} == 'x'"), comment: null, createdAt: '' },
    )
    workflowApiMock.get.mockResolvedValue(comCondicao("${input} = 'x'"))
    const ui = await render()

    await act(async () => { fireEvent.click(await waitFor(() => ui.getByRole('button', { name: 'workflows.actions.execute' }))) })

    const body = within(document.body)
    await waitFor(() => body.getByTestId('run-input-text'))
    expect(notificationsMock.show).not.toHaveBeenCalled()
    await act(async () => { fireEvent.click(body.getByTestId('run-input-confirm')) })
    await waitFor(() => expect(workflowApiMock.execute).toHaveBeenCalledWith('wf-1', undefined))
    // A conferência busca só a versão implantada, não a lista de todas com os documentos.
    expect(workflowApiMock.version).toHaveBeenCalledWith('wf-1', 'wfv-2')
  })
})
