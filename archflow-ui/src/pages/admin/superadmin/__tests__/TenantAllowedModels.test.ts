/**
 * Modelos permitidos do tenant, editáveis na criação e no detalhe (RCHFLW-79).
 *
 * <p>Antes: a criação não tinha o campo e o detalhe só o mostrava — a lista só mudava pela API, e nada a
 * aplicava. Aqui: o campo oferece o catálogo ATIVO (desligados não aparecem), grava a lista no
 * POST/PUT, e esvaziar volta a "todos os modelos do catálogo".</p>
 *
 * <p>O MultiSelect do Mantine vira um {@code <select multiple>} nativo: o que se testa é a lista de
 * opções e o que vai para a API, não o combobox (que abre num portal).</p>
 */
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { createElement as h, act } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { MantineProvider } from '@mantine/core'
import { fireEvent, waitFor, within } from '@testing-library/dom'

const tenantApiMock = vi.hoisted(() => ({ get: vi.fn(), update: vi.fn(), create: vi.fn() }))
// A criação lê os padrões de plano do /admin/global (RCHFLW-178); aqui nenhum, os limites ficam os do formulário.
const globalConfigApiMock = vi.hoisted(() => ({ getModels: vi.fn(), getPlanDefaults: vi.fn(() => Promise.resolve([])) }))

vi.mock('react-i18next', () => ({
  useTranslation: () => ({
    t: (k: string) => k,
    i18n: { language: 'pt-BR', resolvedLanguage: 'pt-BR' },
  }),
}))
vi.mock('../../../../services/admin-api', () => ({
  tenantApi: tenantApiMock,
  globalConfigApi: globalConfigApiMock,
}))
vi.mock('@mantine/notifications', () => ({ notifications: { show: vi.fn() } }))

type Item = { value: string; label: string }
type Group = { group: string; items: Item[] }

vi.mock('@mantine/core', async (importOriginal) => {
  const real = await importOriginal<typeof import('@mantine/core')>()
  const { createElement } = await import('react')
  const MultiSelect = (props: {
    label?: string; value?: string[]; data?: (Item | Group)[]; onChange?: (v: string[]) => void; disabled?: boolean
  }) => {
    const items = (props.data ?? []).flatMap(d => ('group' in d ? d.items : [d]))
    return createElement('select', {
      multiple: true,
      'data-testid': 'allowed-models-select',
      'data-disabled': props.disabled ? 'sim' : 'nao',
      value: props.value ?? [],
      onChange: (e: { target: HTMLSelectElement }) =>
        props.onChange?.(Array.from(e.target.selectedOptions).map(o => o.value)),
    }, items.map(i => createElement('option', { key: i.value, value: i.value }, i.label)))
  }
  const Select = (props: {
    label?: string; value?: string | null; data?: (string | Item)[]; onChange?: (v: string | null) => void
  }) => createElement('select', {
    'data-select': props.label,
    value: props.value ?? '',
    onChange: (e: { target: { value: string } }) => props.onChange?.(e.target.value),
  }, [createElement('option', { key: '', value: '' }, '—'),
    ...(props.data ?? []).map(d => typeof d === 'string' ? { value: d, label: d } : d)
      .map(i => createElement('option', { key: i.value, value: i.value }, i.label))])
  return { ...real, MultiSelect, Select }
})

import TenantDetail from '../TenantDetail'
import TenantNew from '../TenantNew'

const CATALOGO = [
  { id: 'openai/gpt-6-luna', name: 'openai/gpt-6-luna', provider: 'openrouter', status: 'active',
    costInputPer1M: null, costOutputPer1M: null },
  { id: 'google/gemma-4-31b-it:free', name: 'google/gemma-4-31b-it:free', provider: 'openrouter', status: 'beta',
    costInputPer1M: null, costOutputPer1M: null },
  { id: 'nvidia/nemotron-3-ultra-550b-a55b:free', name: 'Nemotron', provider: 'openrouter', status: 'deprecated',
    costInputPer1M: null, costOutputPer1M: null },
]

function tenant(allowedModels: string[]) {
  return {
    id: 'acme', name: 'Acme', plan: 'professional', status: 'active', adminEmail: 'a@acme.com', sector: 'Other',
    limits: { executionsPerDay: 1, tokensPerMonth: 1, maxWorkflows: 1, maxUsers: 1, allowedModels: [], featuresEnabled: [] },
    usage: { executionsToday: 0, tokensThisMonth: 0, workflowCount: 0, userCount: 0 },
    allowedModels, createdAt: '2026-11',
  }
}

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
  globalConfigApiMock.getModels.mockResolvedValue(CATALOGO)
})

afterEach(async () => {
  await act(async () => { root?.unmount() })
  root = null
  container?.remove()
})

async function render(path: string, route: string, element: unknown) {
  container = document.createElement('div')
  document.body.appendChild(container)
  root = createRoot(container)
  await act(async () => {
    root!.render(h(MantineProvider, null,
      h(MemoryRouter, { initialEntries: [path] },
        h(Routes, null,
          h(Route, { path: route, element: element as never }),
          h(Route, { path: '/admin/tenants', element: h('div', null, 'lista') })))))
  })
  return within(container)
}

function escolher(select: HTMLSelectElement, values: string[]) {
  Array.from(select.options).forEach(o => { o.selected = values.includes(o.value) })
  fireEvent.change(select)
}

describe('TenantDetail — modelos permitidos editáveis', () => {
  it('sem lista mostra "todos os modelos do catálogo", não um traço', async () => {
    tenantApiMock.get.mockResolvedValue(tenant([]))
    const ui = await render('/admin/tenants/acme', '/admin/tenants/:id', h(TenantDetail))

    await waitFor(() => ui.getByText('admin.superadmin.tenantDetail.allowedModelsAll'))
  })

  it('editar oferece só o catálogo ativo (+ o já gravado) e grava a lista no PUT', async () => {
    tenantApiMock.get.mockResolvedValue(tenant(['antigo/fora-do-catalogo']))
    tenantApiMock.update.mockResolvedValue(tenant(['openai/gpt-6-luna']))
    const ui = await render('/admin/tenants/acme', '/admin/tenants/:id', h(TenantDetail))

    await act(async () => { fireEvent.click(await waitFor(() => ui.getByTestId('edit-allowed-models'))) })
    const select = await waitFor(() => {
      const s = ui.getByTestId('allowed-models-select') as HTMLSelectElement
      expect(s.dataset.disabled).toBe('nao')
      return s
    })
    expect(Array.from(select.options).map(o => o.value)).toEqual([
      'openai/gpt-6-luna', 'google/gemma-4-31b-it:free', 'antigo/fora-do-catalogo',
    ])

    await act(async () => { escolher(select, ['openai/gpt-6-luna']) })
    await act(async () => { fireEvent.click(ui.getByTestId('save-allowed-models')) })

    await waitFor(() => expect(tenantApiMock.update).toHaveBeenCalledWith('acme', { allowedModels: ['openai/gpt-6-luna'] }))
    await waitFor(() => ui.getByText('openai/gpt-6-luna'))
  })

  it('esvaziar grava [] — "sem restrição", não "nenhum modelo"', async () => {
    tenantApiMock.get.mockResolvedValue(tenant(['openai/gpt-6-luna']))
    tenantApiMock.update.mockResolvedValue(tenant([]))
    const ui = await render('/admin/tenants/acme', '/admin/tenants/:id', h(TenantDetail))

    await act(async () => { fireEvent.click(await waitFor(() => ui.getByTestId('edit-allowed-models'))) })
    const select = await waitFor(() => ui.getByTestId('allowed-models-select') as HTMLSelectElement)
    await act(async () => { escolher(select, []) })
    await act(async () => { fireEvent.click(ui.getByTestId('save-allowed-models')) })

    await waitFor(() => expect(tenantApiMock.update).toHaveBeenCalledWith('acme', { allowedModels: [] }))
  })
})

describe('TenantNew — modelos permitidos na criação', () => {
  it('o POST leva a lista escolhida', async () => {
    tenantApiMock.create.mockResolvedValue(tenant(['google/gemma-4-31b-it:free']))
    const ui = await render('/admin/tenants/new', '/admin/tenants/new', h(TenantNew))

    const select = await waitFor(() => {
      const s = ui.getByTestId('allowed-models-select') as HTMLSelectElement
      expect(s.options.length).toBe(2)
      return s
    })
    await act(async () => { escolher(select, ['google/gemma-4-31b-it:free']) })

    const inputs = container.querySelectorAll('input')
    // nome (gera o id), e-mail; o setor é um Select.
    await act(async () => {
      fireEvent.change(inputs[0], { target: { value: 'Acme' } })
      fireEvent.change(inputs[2], { target: { value: 'a@acme.com' } })
      const setor = container.querySelector('select[data-select="admin.superadmin.tenantNew.form.sector"]')!
      fireEvent.change(setor, { target: { value: 'Other' } })
    })
    await act(async () => { fireEvent.click(ui.getByText('admin.superadmin.tenantNew.createBtn')) })

    await waitFor(() => expect(tenantApiMock.create).toHaveBeenCalledTimes(1))
    expect(tenantApiMock.create.mock.calls[0][0]).toMatchObject({
      tenantId: 'acme', allowedModels: ['google/gemma-4-31b-it:free'],
    })
  })
})
