/**
 * RCHFLW-89 — o modal de entrada é a última tela antes de rodar, nos três caminhos, e diz qual versão vai
 * executar: o rascunho (editor) ou a versão publicada em produção, com o número (lista, Executar
 * novamente). Antes, o mesmo fluxo rodava coisas diferentes pela lista e pelo editor sem nenhum aviso.
 *
 * <p>Arquivo .ts (createElement em vez de JSX) porque o vitest.config só inclui *.test.ts.</p>
 */
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { createElement as h, act } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { MantineProvider } from '@mantine/core'
import { waitFor, within } from '@testing-library/dom'

const workflowApiMock = vi.hoisted(() => ({ deployment: vi.fn(), versions: vi.fn() }))

vi.mock('react-i18next', () => ({
  useTranslation: () => ({
    t: (k: string, o?: { number?: number }) => (o?.number !== undefined ? `${k}:${o.number}` : k),
    i18n: { language: 'pt-BR', resolvedLanguage: 'pt-BR' },
  }),
}))
vi.mock('../../services/api', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../../services/api')>()
  return { ApiError: actual.ApiError, workflowApi: workflowApiMock }
})

import { RunInputModal } from '../RunInputModal'
import { ApiError } from '../../services/api'

let root: Root | null = null
let container: HTMLDivElement

beforeEach(() => {
  vi.clearAllMocks()
  sessionStorage.clear()
  ;(globalThis as any).IS_REACT_ACT_ENVIRONMENT = true
  ;(globalThis as any).ResizeObserver ??= class { observe() {} unobserve() {} disconnect() {} }
  window.matchMedia ??= ((q: string) => ({
    matches: false, media: q, onchange: null,
    addListener() {}, removeListener() {}, addEventListener() {}, removeEventListener() {},
    dispatchEvent: () => false,
  })) as any
})

afterEach(async () => {
  await act(async () => { root?.unmount() })
  root = null
  container?.remove()
})

async function open(runs?: 'draft' | 'production') {
  container = document.createElement('div')
  document.body.appendChild(container)
  root = createRoot(container)
  await act(async () => {
    root!.render(h(MantineProvider, null,
      h(RunInputModal, { workflowId: 'wf-1', opened: true, onClose: () => {}, onConfirm: () => {}, runs })))
  })
  const body = within(document.body)
  await waitFor(() => body.getByTestId('run-input-text'))
  return body
}

const versions = [
  { id: 'wfv-a', workflowId: 'wf-1', number: 1, document: {}, comment: null, createdAt: '' },
  { id: 'wfv-b', workflowId: 'wf-1', number: 2, document: {}, comment: null, createdAt: '' },
]

describe('RunInputModal — qual versão vai rodar', () => {
  it('caminho de produção com versão implantada: diz o número dela', async () => {
    workflowApiMock.deployment.mockResolvedValue({ workflowId: 'wf-1', environment: 'PRODUCTION', versionId: 'wfv-b' })
    workflowApiMock.versions.mockResolvedValue(versions)

    const body = await open('production')

    const target = await waitFor(() => body.getByTestId('run-input-target'))
    expect(target.getAttribute('data-target')).toBe('published')
    expect(target.textContent).toBe('runInput.target.published:2')
    expect(workflowApiMock.deployment).toHaveBeenCalledWith('wf-1', 'PRODUCTION')
  })

  it('caminho de produção sem implantação (404): diz que roda o rascunho, como o backend faz', async () => {
    workflowApiMock.deployment.mockRejectedValue(new ApiError(404, 'Not Found'))

    const body = await open('production')

    const target = await waitFor(() => body.getByTestId('run-input-target'))
    expect(target.getAttribute('data-target')).toBe('unpublished')
    expect(workflowApiMock.versions).not.toHaveBeenCalled()
  })

  it('falha que não é 404: não afirma nada', async () => {
    workflowApiMock.deployment.mockRejectedValue(new ApiError(500, 'boom'))

    const body = await open('production')
    await act(async () => { await new Promise(r => setTimeout(r, 10)) })

    expect(body.queryByTestId('run-input-target')).toBeNull()
  })

  it('editor: o rascunho, sem consultar a implantação', async () => {
    const body = await open('draft')

    expect(body.getByTestId('run-input-target').getAttribute('data-target')).toBe('draft')
    expect(workflowApiMock.deployment).not.toHaveBeenCalled()
  })
})
