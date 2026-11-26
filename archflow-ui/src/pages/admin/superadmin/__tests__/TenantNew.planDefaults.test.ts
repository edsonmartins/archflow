/**
 * Novo tenant com os padrões de plano do /admin/global (RCHFLW-178): a página nascia com os números do
 * Professional e nunca lia `GET /admin/global/plans` — Trial e Enterprise eram criados com 500 / 5.000.000.
 * O separador de milhar segue o idioma (era "5,000,000" numa página pt-BR).
 *
 * <p>Arquivo .ts (createElement em vez de JSX) porque o vitest.config só inclui *.test.ts.</p>
 */
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { createElement as h, act } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { MantineProvider } from '@mantine/core'
import { MemoryRouter } from 'react-router-dom'
import { waitFor } from '@testing-library/dom'

const apiMock = vi.hoisted(() => ({
  getPlanDefaults: vi.fn(),
  create: vi.fn(),
}))

vi.mock('react-i18next', () => ({
  useTranslation: () => ({ t: (k: string) => k, i18n: { language: 'pt-BR', resolvedLanguage: 'pt-BR' } }),
}))
vi.mock('../../../../services/admin-api', () => ({
  globalConfigApi: { getPlanDefaults: apiMock.getPlanDefaults },
  tenantApi: { create: apiMock.create },
}))
vi.mock('../AllowedModelsInput', () => ({ AllowedModelsInput: () => null }))

import TenantNew from '../TenantNew'

let root: Root | null = null
let container: HTMLDivElement

const input = (id: string) => document.body.querySelector(`[data-testid="${id}"]`) as HTMLInputElement

async function click(el: Element | null) {
  expect(el).not.toBeNull()
  await act(async () => {
    el!.dispatchEvent(new MouseEvent('mousedown', { bubbles: true }))
    el!.dispatchEvent(new MouseEvent('click', { bubbles: true }))
  })
}

async function escolherPlano(rotulo: string) {
  await click(input('tenant-plan'))
  const option = await waitFor(() => {
    const o = Array.from(document.body.querySelectorAll('[role="option"]'))
      .find((el) => el.textContent === rotulo)
    expect(o).toBeTruthy()
    return o!
  })
  await click(option)
}

beforeEach(() => {
  ;(globalThis as any).IS_REACT_ACT_ENVIRONMENT = true
  window.matchMedia ??= ((q: string) => ({
    matches: false, media: q, onchange: null,
    addListener() {}, removeListener() {}, addEventListener() {}, removeEventListener() {},
    dispatchEvent: () => false,
  })) as any
  ;(globalThis as any).ResizeObserver ??= class { observe() {} unobserve() {} disconnect() {} }
  Element.prototype.scrollIntoView ??= () => {}
  vi.clearAllMocks()
  apiMock.getPlanDefaults.mockResolvedValue([
    { plan: 'enterprise', executionsPerDay: 1000, tokensPerMonth: 10_000_000, maxWorkflows: 50, maxUsers: 25 },
    { plan: 'professional', executionsPerDay: 500, tokensPerMonth: 5_000_000, maxWorkflows: 20, maxUsers: 10 },
    { plan: 'trial', executionsPerDay: 50, tokensPerMonth: 100_000, maxWorkflows: 3, maxUsers: 2 },
  ])
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
    root!.render(h(MantineProvider, null, h(MemoryRouter, null, h(TenantNew))))
  })
}

describe('TenantNew — padrões do plano', () => {
  it('trocar o plano aplica os limites dele, com o separador de milhar do pt-BR', async () => {
    await render()
    expect(apiMock.getPlanDefaults).toHaveBeenCalled()

    await escolherPlano('admin.superadmin.tenantNew.plans.trial')
    await waitFor(() => expect(input('tenant-exec').value).toBe('50'))
    expect(input('tenant-tokens').value).toBe('100.000')

    await escolherPlano('admin.superadmin.tenantNew.plans.enterprise')
    await waitFor(() => expect(input('tenant-exec').value).toBe('1.000'))
    expect(input('tenant-tokens').value).toBe('10.000.000')
  })

  it('padrões que chegam depois da troca de plano valem para o plano escolhido, não para o da montagem', async () => {
    let entregar: (v: unknown) => void = () => {}
    const padroes = await apiMock.getPlanDefaults()
    apiMock.getPlanDefaults.mockReturnValue(new Promise((resolve) => { entregar = resolve }))
    await render()

    await escolherPlano('admin.superadmin.tenantNew.plans.trial')
    await act(async () => { entregar(padroes) })

    await waitFor(() => expect(input('tenant-exec').value).toBe('50'))
    expect(input('tenant-tokens').value).toBe('100.000')
  })

  it('plano sem padrão (Interno) avisa que os limites na tela não vêm dele', async () => {
    await render()
    await waitFor(() => expect(input('tenant-exec').value).toBe('500'))
    expect(document.body.querySelector('[data-testid="plan-no-defaults"]')).toBeNull()

    await escolherPlano('admin.superadmin.tenantNew.plans.internal')

    await waitFor(() => expect(document.body.querySelector('[data-testid="plan-no-defaults"]')).not.toBeNull())
    expect(document.body.textContent).toContain('admin.superadmin.tenantNew.planNoDefaults')
  })

  it('sem os padrões, avisa em vez de fingir que os números são do plano', async () => {
    apiMock.getPlanDefaults.mockRejectedValue(new Error('500'))
    await render()

    await waitFor(() => expect(document.body.querySelector('[data-testid="plan-defaults-failed"]')).not.toBeNull())
  })
})
