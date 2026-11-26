/**
 * QA2: cancelar pela observabilidade é o mesmo cancelar de `/api/executions` — o servidor exige
 * `execution:manage` (hoje só o ADMIN). Sem ela a ação some da linha, como as de escrita da lista de
 * fluxos (RCHFLW-74), em vez de oferecer um cancelar que vira 403.
 *
 * <p>A tabela é a real: no jsdom ela desenha só o espelho acessível, em que cada ação visível é um botão.</p>
 */
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { entrarComo } from '../../../../lib/__tests__/papeisDeTeste'
import { createElement as h, act } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { MantineProvider } from '@mantine/core'
import { waitFor } from '@testing-library/dom'

const apiMock = vi.hoisted(() => ({ listRunningFlows: vi.fn(), cancelRunningFlow: vi.fn() }))

vi.mock('react-i18next', () => ({
  useTranslation: () => ({ t: (k: string) => k, i18n: { language: 'pt-BR', resolvedLanguage: 'pt-BR' } }),
}))
vi.mock('@mantine/notifications', () => ({ notifications: { show: vi.fn() } }))
vi.mock('../../../../services/observability-api', () => ({ observabilityApi: apiMock }))

import RunningFlowsPage from '../RunningFlowsPage'

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
  apiMock.listRunningFlows.mockResolvedValue([{
    flowId: 'exec-1', tenantId: 't', status: 'RUNNING', currentStepId: 's1', stepIndex: 1, stepCount: 3,
    startedAt: new Date().toISOString(), durationMs: 1000,
  }])
})

afterEach(async () => {
  await act(async () => { root?.unmount() })
  root = null
  container?.remove()
})

async function temCancelar(): Promise<boolean> {
  container = document.createElement('div')
  document.body.appendChild(container)
  root = createRoot(container)
  await act(async () => { root!.render(h(MantineProvider, null, h(RunningFlowsPage))) })
  const linha = await waitFor(() => {
    const tr = [...container.querySelectorAll('tbody tr')].find(r => r.textContent?.includes('exec-1'))
    expect(tr).toBeTruthy()
    return tr as HTMLElement
  })
  return [...linha.querySelectorAll('button')]
    .some(b => b.textContent === 'admin.observability.running.cancelTooltip')
}

describe('RunningFlowsPage — cancelar exige execution:manage (QA2)', () => {
  for (const papel of ['VIEWER', 'EXECUTOR', 'DESIGNER']) {
    it(`${papel}: sem a ação de cancelar`, async () => {
      entrarComo(papel)
      expect(await temCancelar()).toBe(false)
    })
  }

  it('ADMIN: a ação de cancelar aparece', async () => {
    entrarComo('ADMIN')
    expect(await temCancelar()).toBe(true)
  })
})
