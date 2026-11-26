/**
 * RCHFLW-188 (item 9): em Execuções, filtrar por um fluxo, abrir uma linha e voltar (Voltar do navegador ou o
 * breadcrumb "Execuções" do detalhe) trazia a lista de volta em "Todos os fluxos". O filtro vivia num useState,
 * fora da URL, e o breadcrumb apontava para /executions puro.
 *
 * <p>A tabela (VTable, desenhada em canvas) é simulada por botões que chamam o onRowClick; as duas páginas e o
 * store são os reais.</p>
 *
 * <p>Arquivo .ts (createElement em vez de JSX) porque o vitest.config só inclui *.test.ts.</p>
 */
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { createElement as h, act } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { Route, Routes } from 'react-router-dom'
import { MantineProvider } from '@mantine/core'
import { fireEvent, waitFor, within } from '@testing-library/dom'
import { MemoryDataRouter, ultimoRoteador } from '../../lib/__tests__/roteadorDeTeste'

const workflowApiMock = vi.hoisted(() => ({ get: vi.fn(), list: vi.fn() }))
const executionApiMock = vi.hoisted(() => ({ get: vi.fn(), list: vi.fn() }))

vi.mock('react-i18next', () => ({
  useTranslation: () => ({ t: (k: string) => k, i18n: { language: 'pt-BR', resolvedLanguage: 'pt-BR' } }),
}))
vi.mock('../../services/api', () => ({
  workflowApi: workflowApiMock,
  executionApi: executionApiMock,
  ApiError: class extends Error {},
}))
vi.mock('@mantine/notifications', () => ({ notifications: { show: vi.fn() } }))
vi.mock('../../components/VTable', async () => {
  const { createElement } = await import('react')
  return {
    VxTable: ({ records, onRowClick }: { records: Array<{ id: string }>; onRowClick: (r: unknown) => void }) =>
      createElement('div', { 'data-testid': 'tabela' }, records.map((r) =>
        createElement('button', { key: r.id, 'data-testid': `linha-${r.id}`, onClick: () => onRowClick(r) }, r.id))),
    colunaApagada: (c: unknown) => c,
    colunaStatus: (c: unknown) => c,
  }
})
// O Select do Mantine (combobox em portal) vira um <select> nativo: o que importa aqui é o valor que a página
// lê e grava, não o dropdown.
vi.mock('@mantine/core', async (importOriginal) => {
  const real = await importOriginal<typeof import('@mantine/core')>()
  const { createElement } = await import('react')
  type Props = { 'aria-label'?: string; value?: string | null; onChange?: (v: string | null) => void;
    data?: Array<{ value: string; label: string }> }
  const Select = (props: Props) => createElement('select', {
    'aria-label': props['aria-label'], value: props.value ?? '',
    onChange: (e: { target: { value: string } }) => props.onChange?.(e.target.value || null),
  }, [createElement('option', { key: '', value: '' }, '—'),
    ...(props.data ?? []).map((i) => createElement('option', { key: i.value, value: i.value }, i.label))])
  return { ...real, Select }
})
vi.mock('../../components/VTable/tokens', () => ({ selo: () => ({ tinta: '#000' }) }))

import ExecutionHistoryPage from '../ExecutionHistoryPage'
import ExecutionDetailPage from '../ExecutionDetailPage'
import { useWorkflowStore } from '../../stores/workflow-store'

let root: Root | null = null
let container: HTMLDivElement

const EXECUCOES = [
  { id: 'exec-a1', workflowId: 'wf-a', workflowName: 'Fluxo A', status: 'COMPLETED' },
  { id: 'exec-b1', workflowId: 'wf-b', workflowName: 'Fluxo B', status: 'FAILED' },
]

beforeEach(() => {
  vi.clearAllMocks()
  ;(globalThis as any).IS_REACT_ACT_ENVIRONMENT = true
  ;(globalThis as any).ResizeObserver ??= class { observe() {} unobserve() {} disconnect() {} }
  window.matchMedia ??= ((q: string) => ({
    matches: false, media: q, onchange: null,
    addListener() {}, removeListener() {}, addEventListener() {}, removeEventListener() {},
    dispatchEvent: () => false,
  })) as any
  window.HTMLElement.prototype.scrollIntoView ??= () => {}
  useWorkflowStore.setState({ workflows: [], executions: [], loading: false, error: null })
  workflowApiMock.list.mockResolvedValue([{ id: 'wf-a', name: 'Fluxo A' }, { id: 'wf-b', name: 'Fluxo B' }])
  workflowApiMock.get.mockResolvedValue({ id: 'wf-a', steps: [] })
  executionApiMock.list.mockImplementation(async ({ workflowId }: { workflowId?: string }) =>
    EXECUCOES.filter((e) => !workflowId || e.workflowId === workflowId))
  executionApiMock.get.mockImplementation(async (id: string) => ({
    ...EXECUCOES.find((e) => e.id === id), steps: [],
  }))
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
      h(MemoryDataRouter, { initialEntries: [path] },
        h(Routes, null,
          h(Route, { path: '/executions', element: h(ExecutionHistoryPage) }),
          h(Route, { path: '/executions/:id', element: h(ExecutionDetailPage) })))))
  })
  return within(container)
}

const onde = () => `${ultimoRoteador!.state.location.pathname}${ultimoRoteador!.state.location.search}`
const linhas = (ui: ReturnType<typeof within>) => ui.queryAllByTestId(/^linha-/).map((b) => b.textContent)

async function filtrarPorFluxoA(ui: ReturnType<typeof within>) {
  await waitFor(() => expect(linhas(ui)).toEqual(['exec-a1', 'exec-b1']))
  const filtro = ui.getByRole('combobox', { name: 'executions.allWorkflows' })
  await waitFor(() => expect(within(filtro).getByRole('option', { name: 'Fluxo A' })).toBeTruthy())
  await act(async () => { fireEvent.change(filtro, { target: { value: 'wf-a' } }) })
  await waitFor(() => expect(linhas(ui)).toEqual(['exec-a1']))
}

describe('Execuções — o filtro de fluxo sobrevive à ida ao detalhe (RCHFLW-188, item 9)', () => {
  it('o filtro vai para a URL, e o Voltar do navegador volta para a lista filtrada', async () => {
    const ui = await render('/executions')
    await filtrarPorFluxoA(ui)
    expect(onde()).toBe('/executions?workflow=wf-a')

    await act(async () => { fireEvent.click(ui.getByTestId('linha-exec-a1')) })
    await waitFor(() => ui.getByTestId('execution-detail'))

    await act(async () => { await ultimoRoteador!.navigate(-1) })

    await waitFor(() => expect(onde()).toBe('/executions?workflow=wf-a'))
    await waitFor(() => expect(linhas(ui)).toEqual(['exec-a1']))
    expect(executionApiMock.list).toHaveBeenLastCalledWith(expect.objectContaining({ workflowId: 'wf-a' }))
  })

  it('o breadcrumb "Execuções" do detalhe volta para a lista com o filtro', async () => {
    const ui = await render('/executions')
    await filtrarPorFluxoA(ui)
    await act(async () => { fireEvent.click(ui.getByTestId('linha-exec-a1')) })
    await waitFor(() => ui.getByTestId('execution-detail'))

    await act(async () => { fireEvent.click(ui.getByRole('link', { name: 'executions.title' })) })

    await waitFor(() => expect(onde()).toBe('/executions?workflow=wf-a'))
    await waitFor(() => expect(linhas(ui)).toEqual(['exec-a1']))
  })

  it('um link direto com ?workflow= abre a lista já filtrada', async () => {
    const ui = await render('/executions?workflow=wf-b')

    await waitFor(() => expect(linhas(ui)).toEqual(['exec-b1']))
  })
})
