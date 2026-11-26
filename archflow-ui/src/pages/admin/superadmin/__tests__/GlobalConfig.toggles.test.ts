/**
 * Toggles do /admin/global (RCHFLW-179): nenhum caminho de execução os lê, mas a seção dizia "Recursos
 * ligados ou desligados para toda a plataforma". Até cada um ser ligado ao recurso que nomeia (decisão de
 * produto), a página diz que são preferências registradas e o que vale hoje em cada chave.
 *
 * <p>Arquivo .ts (createElement em vez de JSX) porque o vitest.config só inclui *.test.ts.</p>
 */
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { createElement as h, act } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { MantineProvider } from '@mantine/core'
import { waitFor } from '@testing-library/dom'

const apiMock = vi.hoisted(() => ({
  getModels: vi.fn(), getPlanDefaults: vi.fn(), getToggles: vi.fn(), updateToggles: vi.fn(), toggleModel: vi.fn(),
}))

vi.mock('react-i18next', () => ({
  useTranslation: () => ({ t: (k: string) => k, i18n: { language: 'pt-BR', resolvedLanguage: 'pt-BR' } }),
}))
vi.mock('../../../../services/admin-api', () => ({ globalConfigApi: apiMock }))
vi.mock('@mantine/notifications', () => ({ notifications: { show: vi.fn() } }))

import GlobalConfig from '../GlobalConfig'

let root: Root | null = null
let container: HTMLDivElement

beforeEach(() => {
  vi.clearAllMocks()
  ;(globalThis as any).IS_REACT_ACT_ENVIRONMENT = true
  window.matchMedia ??= ((q: string) => ({
    matches: false, media: q, onchange: null,
    addListener() {}, removeListener() {}, addEventListener() {}, removeEventListener() {},
    dispatchEvent: () => false,
  })) as any
  ;(globalThis as any).ResizeObserver ??= class { observe() {} unobserve() {} disconnect() {} }
  apiMock.getModels.mockResolvedValue([])
  apiMock.getPlanDefaults.mockResolvedValue([])
  apiMock.getToggles.mockResolvedValue({
    allowLocalModels: true, humanInTheLoop: true, brainSentry: true, debugMode: false,
    linktorNotifications: false, auditLog: true,
  })
})

afterEach(async () => {
  await act(async () => { root?.unmount() })
  root = null
  container?.remove()
})

describe('GlobalConfig — toggles', () => {
  it('diz que as chaves não estão ligadas ao runtime e o que vale hoje em cada uma', async () => {
    container = document.createElement('div')
    document.body.appendChild(container)
    root = createRoot(container)
    await act(async () => { root!.render(h(MantineProvider, null, h(GlobalConfig))) })

    await waitFor(() => expect(document.body.querySelector('[data-testid="toggle-auditLog"]')).not.toBeNull())
    expect(document.body.querySelector('[data-testid="toggles-not-applied"]')).not.toBeNull()
    for (const key of ['allowLocalModels', 'humanInTheLoop', 'brainSentry', 'debugMode', 'linktorNotifications',
      'auditLog']) {
      expect(document.body.textContent).toContain(`admin.superadmin.globalConfig.toggleEffects.${key}`)
    }
  })
})
