/**
 * RCHFLW-74: a lista de fluxos só oferece as ações que o papel do usuário concede — as mesmas que o
 * backend aceitaria (Role.java: VIEWER lê; EXECUTOR lê e executa; DESIGNER cria, edita, apaga e
 * executa; ADMIN tudo). O card continua abrindo o editor, que fica só leitura.
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

const workflowApiMock = vi.hoisted(() => ({
  list: vi.fn(), execute: vi.fn(), setStatus: vi.fn(), get: vi.fn(), create: vi.fn(),
  update: vi.fn(), delete: vi.fn(),
}))

vi.mock('react-i18next', () => ({
  useTranslation: () => ({ t: (k: string) => k, i18n: { language: 'pt-BR', resolvedLanguage: 'pt-BR' } }),
}))
vi.mock('../../services/api', () => ({ workflowApi: workflowApiMock, executionApi: { list: vi.fn() } }))

import WorkflowListPage from '../WorkflowListPage'
import { useWorkflowStore } from '../../stores/workflow-store'
import { useAuthStore } from '../../stores/auth-store'

let root: Root | null = null
let container: HTMLDivElement

beforeEach(() => {
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
  workflowApiMock.list.mockResolvedValue([
    { id: 'wf-1', name: 'Triagem', description: '', status: 'draft', version: '1.0.0', stepCount: 2, updatedAt: '2026-11-23T00:00:00Z' },
  ])
})

afterEach(async () => {
  await act(async () => { root?.unmount() })
  root = null
  container?.remove()
})

async function render(path = '/workflows') {
  container = document.createElement('div')
  document.body.appendChild(container)
  root = createRoot(container)
  await act(async () => {
    root!.render(h(MantineProvider, null,
      h(MemoryRouter, { initialEntries: [path] },
        h(Routes, null, h(Route, { path: '/workflows', element: h(WorkflowListPage) })))))
  })
  const ui = within(container)
  await waitFor(() => ui.getByTestId('workflow-card-wf-1'))
  return ui
}

function acoes(ui: ReturnType<typeof within>) {
  const has = (name: string) => ui.queryByRole('button', { name }) !== null
  return {
    novo: ui.queryByTestId('workflow-new') !== null,
    editar: has('workflows.actions.edit'),
    status: has('workflows.actions.activate') || has('workflows.actions.deactivate'),
    apagar: has('workflows.actions.delete'),
    executar: has('workflows.actions.execute'),
  }
}

describe('WorkflowListPage — ações por papel (RCHFLW-74)', () => {
  it('VIEWER: só lê — sem Novo, Editar, status, Excluir nem Executar', async () => {
    entrarComo('VIEWER')
    const ui = await render()
    expect(acoes(ui)).toEqual({ novo: false, editar: false, status: false, apagar: false, executar: false })
    // O card segue clicável: abre o editor em modo leitura.
    expect(ui.getByTestId('workflow-card-wf-1').getAttribute('role')).toBe('button')
  })

  it('EXECUTOR: executa, mas não cria, edita nem apaga', async () => {
    entrarComo('ROLE_EXECUTOR')
    const ui = await render()
    expect(acoes(ui)).toEqual({ novo: false, editar: false, status: false, apagar: false, executar: true })
  })

  it('DESIGNER e ADMIN: todas as ações', async () => {
    for (const papel of ['DESIGNER', 'ADMIN']) {
      entrarComo(papel)
      const ui = await render()
      expect(acoes(ui)).toEqual({ novo: true, editar: true, status: true, apagar: true, executar: true })
      await act(async () => { root?.unmount() })
      container.remove()
    }
    root = null
  })

  it('sem token, ou com papel que o backend não conhece, nenhuma escrita aparece', async () => {
    useAuthStore.setState({ token: null })
    let ui = await render()
    expect(acoes(ui)).toEqual({ novo: false, editar: false, status: false, apagar: false, executar: false })
    await act(async () => { root?.unmount() })
    container.remove()

    entrarComo('EDITOR')
    ui = await render()
    expect(acoes(ui)).toEqual({ novo: false, editar: false, status: false, apagar: false, executar: false })
  })

  it('o atalho ?new=1 do dashboard não abre a criação para quem não pode criar', async () => {
    entrarComo('VIEWER')
    await render('/workflows?new=1')
    expect(within(document.body).queryByTestId('create-blank')).toBeNull()
  })
})
