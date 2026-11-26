/**
 * RCHFLW-188 (item 11): depois de Versões > Restaurar, a lista marcava a versão restaurada em PRODUCTION e a
 * notificação dizia "Produção restaurada para a versão 1", mas o banner do topo continuava "Versão imutável 3 ativa
 * em PRODUCTION" — ele lia a última versão publicada, não a implantação. Agora o banner segue a implantação.
 *
 * <p>Arquivo .ts (createElement em vez de JSX) porque o vitest.config só inclui *.test.ts.</p>
 */
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { createElement as h, act } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { MemoryRouter } from 'react-router-dom'
import { MantineProvider } from '@mantine/core'
import { fireEvent, waitFor, within } from '@testing-library/dom'

const apiMock = vi.hoisted(() => {
  class ApiError extends Error {
    constructor(public status: number, message: string) {
      super(message)
    }
  }
  return {
    ApiError,
    workflowApi: { publish: vi.fn(), versions: vi.fn(), deployment: vi.fn(), deploy: vi.fn() },
  }
})

vi.mock('react-i18next', () => ({
  useTranslation: () => ({
    t: (k: string, o?: Record<string, unknown>) => (o ? `${k} ${JSON.stringify(o)}` : k),
  }),
}))
vi.mock('../../services/api', () => ({ workflowApi: apiMock.workflowApi, ApiError: apiMock.ApiError }))
vi.mock('@mantine/notifications', () => ({ notifications: { show: vi.fn(), hide: vi.fn() } }))

import { PublishModal } from '../PublishModal'

const api = apiMock.workflowApi

function version(n: number) {
  return { id: `wfv-${n}`, workflowId: 'wf-1', number: n, document: { n }, comment: null,
    createdAt: '2026-11-25T12:00:00Z' }
}

function deployment(n: number) {
  return { workflowId: 'wf-1', environment: 'PRODUCTION', versionId: `wfv-${n}`, deployedAt: 'x' }
}

let root: Root | null = null
let container: HTMLDivElement
let ativa = 2

beforeEach(() => {
  vi.clearAllMocks()
  ;(globalThis as any).ResizeObserver ??= class { observe() {} unobserve() {} disconnect() {} }
  ;(globalThis as any).IS_REACT_ACT_ENVIRONMENT = true
  window.matchMedia ??= ((q: string) => ({
    matches: false, media: q, onchange: null,
    addListener() {}, removeListener() {}, addEventListener() {}, removeEventListener() {},
    dispatchEvent: () => false,
  })) as any
  ativa = 2
  let publicadas = [version(2), version(1)]
  api.versions.mockImplementation(async () => publicadas)
  api.deployment.mockImplementation(async () => deployment(ativa))
  api.publish.mockImplementation(async () => {
    publicadas = [version(3), ...publicadas]
    ativa = 3
    return { version: version(3), deployment: deployment(3) }
  })
  api.deploy.mockImplementation(async (_id: string, _env: string, versionId: string) => {
    ativa = Number(versionId.replace('wfv-', ''))
    return deployment(ativa)
  })
})

afterEach(async () => {
  await act(async () => { root?.unmount() })
  root = null
  container?.remove()
})

async function click(el: HTMLElement) {
  await act(async () => { fireEvent.click(el) })
}

const body = () => within(document.body)

describe('PublishModal — banner da versão ativa (RCHFLW-188)', () => {
  it('depois de Restaurar, o banner mostra a versão restaurada, não a última publicada', async () => {
    container = document.createElement('div')
    document.body.appendChild(container)
    root = createRoot(container)
    await act(async () => {
      root!.render(h(MantineProvider, null, h(MemoryRouter, null,
        h(PublishModal, { workflowId: 'wf-1', opened: true, onClose: () => {} }))))
    })

    await click(await waitFor(() => body().getByText('editor.publish.createVersion')))
    const banner = await waitFor(() => body().getByTestId('publish-deployed'))
    expect(banner.textContent).toContain('"number":3')

    await click(body().getByText('editor.publish.tabs.history'))
    // Os botões Restaurar das versões não ativas: v2 e v1, nessa ordem.
    const restaurar = await waitFor(() => {
      const botoes = body().getAllByText('editor.publish.rollback')
      expect(botoes).toHaveLength(2)
      return botoes
    })
    await click(restaurar[1])

    await waitFor(() => expect(api.deploy).toHaveBeenCalledWith('wf-1', 'PRODUCTION', 'wfv-1'))
    await waitFor(() => expect(body().getByTestId('publish-deployed').textContent).toContain('"number":1'))
    expect(body().getByTestId('publish-deployed').textContent).toContain('wfv-1')
  })
})
