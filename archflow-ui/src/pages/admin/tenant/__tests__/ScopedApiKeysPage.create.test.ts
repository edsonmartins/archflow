/**
 * Diálogo "Nova chave com escopo" (RCHFLW-83): o seletor de escopos é montado com o vocabulário que o
 * backend publica (`GET /api/apikeys/scopes`), a criação envia esses nomes, o banner mostra a credencial
 * completa que vai no `Authorization: Bearer` e uma falha aparece como frase dentro do diálogo — nunca o
 * JSON da resposta.
 *
 * <p>Arquivo .ts (createElement em vez de JSX) porque o vitest.config só inclui *.test.ts.</p>
 */
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { createElement as h, act } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { MantineProvider } from '@mantine/core'

const apiMock = vi.hoisted(() => ({
  scopes: vi.fn(), list: vi.fn(), get: vi.fn(), create: vi.fn(), remove: vi.fn(),
}))

vi.mock('react-i18next', () => ({
  useTranslation: () => ({
    t: (k: string) => k,
    i18n: { language: 'pt-BR', resolvedLanguage: 'pt-BR' },
  }),
}))
vi.mock('../../../../services/scoped-apikey-api', () => ({ scopedApiKeyApi: apiMock }))
vi.mock('@mantine/notifications', () => ({ notifications: { show: vi.fn() } }))
vi.mock('../../../../lib/confirm', () => ({ confirmAction: vi.fn() }))

import ScopedApiKeysPage from '../ScopedApiKeysPage'
import { confirmAction } from '../../../../lib/confirm'
import { useTenantStore } from '../../../../stores/useTenantStore'

const SCOPES = [
  { name: 'WORKFLOW_READ', permission: 'workflow:read', description: 'Read workflows', grantable: true, opensEndpoints: true },
  { name: 'WORKFLOW_EXECUTE', permission: 'workflow:execute', description: 'Execute workflows', grantable: true, opensEndpoints: true },
]

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
  Element.prototype.scrollIntoView ??= function () {}
  apiMock.list.mockResolvedValue([])
  apiMock.scopes.mockResolvedValue(SCOPES)
})

afterEach(async () => {
  useTenantStore.setState({ impersonating: null })
  await act(async () => { root?.unmount() })
  root = null
  container?.remove()
})

async function render() {
  container = document.createElement('div')
  document.body.appendChild(container)
  root = createRoot(container)
  await act(async () => {
    root!.render(h(MantineProvider, null, h(ScopedApiKeysPage)))
  })
}

const byTestId = (id: string) => document.body.querySelector(`[data-testid="${id}"]`) as HTMLElement | null

async function click(el: Element | null) {
  expect(el).not.toBeNull()
  await act(async () => {
    el!.dispatchEvent(new MouseEvent('mousedown', { bubbles: true }))
    el!.dispatchEvent(new MouseEvent('click', { bubbles: true }))
  })
}

/** O Modal do Mantine monta e desmonta o conteúdo depois da transição. */
async function aguardaDialogo(aberto = true) {
  for (let i = 0; i < 50 && (byTestId('scoped-key-dialog') === null) === aberto; i++) {
    await act(async () => { await new Promise(r => setTimeout(r, 20)) })
  }
  expect(byTestId('scoped-key-dialog') !== null).toBe(aberto)
}

async function type(input: HTMLInputElement, value: string) {
  await act(async () => {
    const setter = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value')!.set!
    setter.call(input, value)
    input.dispatchEvent(new Event('input', { bubbles: true }))
  })
}

/** Abre o diálogo, preenche o nome e escolhe um escopo pelo seletor (a opção que o backend publicou). */
async function preencheDialogo(nome: string, rotuloDoEscopo: string) {
  await click(byTestId('scoped-key-new'))
  await aguardaDialogo()
  const dialog = byTestId('scoped-key-dialog')!
  const nameInput = dialog.querySelector('input:not([type="hidden"])') as HTMLInputElement
  await type(nameInput, nome)
  const scopesInput = byTestId('scoped-key-scopes') as HTMLInputElement
  await click(scopesInput)
  const option = Array.from(document.body.querySelectorAll('[role="option"]'))
    .find(o => o.textContent?.includes(rotuloDoEscopo))
  await click(option ?? null)
}

describe('ScopedApiKeysPage — criar chave', () => {
  it('o seletor oferece os escopos do backend, e nenhum do formato antigo', async () => {
    await render()
    await click(byTestId('scoped-key-new'))
    await aguardaDialogo()
    await click(byTestId('scoped-key-scopes'))

    const options = Array.from(document.body.querySelectorAll('[role="option"]')).map(o => o.textContent)
    expect(options).toEqual([
      'admin.tenant.scopedKeys.scopeNames.WORKFLOW_READ (workflow:read)',
      'admin.tenant.scopedKeys.scopeNames.WORKFLOW_EXECUTE (workflow:execute)',
    ])
    expect(document.body.textContent).not.toContain('workflow.read')
  })

  it('cria com o nome do escopo do backend e mostra a credencial completa uma vez', async () => {
    apiMock.create.mockResolvedValue({
      id: 'id-1', keyId: 'ak_abc', keySecret: 'SEGREDO', apiKey: 'ak_abc.SEGREDO', name: 'CI',
      scopes: ['WORKFLOW_EXECUTE'], createdAt: '2026-11-25', enabled: true,
    })
    await render()
    await preencheDialogo('CI', 'workflow:execute')
    await click(byTestId('scoped-key-create'))

    expect(apiMock.create).toHaveBeenCalledWith({ name: 'CI', scopes: ['WORKFLOW_EXECUTE'] })
    expect(byTestId('scoped-key-credential')?.textContent).toBe('ak_abc.SEGREDO')
    await aguardaDialogo(false)
  })

  it('falha na criação: a frase do backend aparece no diálogo, que continua aberto', async () => {
    // `ApiError.message` já é a frase extraída do corpo; um Error com o corpo JSON cru também vira frase.
    apiMock.create.mockRejectedValue(new Error('{"error":"Unknown scope \'x\'. Valid scopes: WORKFLOW_READ","status":400}'))
    await render()
    await preencheDialogo('CI', 'workflow:read')
    await click(byTestId('scoped-key-create'))

    const erro = byTestId('scoped-key-create-error')
    expect(erro?.textContent).toContain("Unknown scope 'x'. Valid scopes: WORKFLOW_READ")
    expect(erro?.textContent).not.toContain('{')
    expect(byTestId('scoped-key-dialog')).not.toBeNull()
    expect(byTestId('scoped-key-created')).toBeNull()
  })

  it('sem a lista de escopos, o diálogo diz por quê e não deixa criar', async () => {
    apiMock.scopes.mockRejectedValue(new Error('Authorization header is required'))
    await render()
    await click(byTestId('scoped-key-new'))
    await aguardaDialogo()

    expect(byTestId('scoped-key-scopes-error')?.textContent).toContain('Authorization header is required')
    expect(byTestId('scoped-key-scopes')).toBeNull()
    expect((byTestId('scoped-key-create') as HTMLButtonElement).disabled).toBe(true)
  })

  it('revogar pede a chave pelo keyId (o id público que o backend procura), não pelo id interno', async () => {
    apiMock.list.mockResolvedValue([{
      id: 'interno-1', keyId: 'ak_abc', name: 'CI', scopes: ['WORKFLOW_EXECUTE'], createdAt: '2026-11-25', enabled: true,
    }])
    apiMock.remove.mockResolvedValue(undefined)
    vi.mocked(confirmAction).mockImplementation(({ onConfirm }) => { void onConfirm() })
    await render()

    const linha = byTestId('scoped-key-row-interno-1')
    await click(linha?.querySelector('button') ?? null)

    expect(apiMock.remove).toHaveBeenCalledWith('ak_abc')
  })

  it('chave já revogada não oferece "Revogar" de novo; a ativa oferece', async () => {
    apiMock.list.mockResolvedValue([
      { id: 'ativa-1', keyId: 'ak_ativa', name: 'Ativa', scopes: ['WORKFLOW_READ'], createdAt: '2026-11-25', enabled: true },
      { id: 'revogada-1', keyId: 'ak_rev', name: 'Revogada', scopes: ['WORKFLOW_READ'], createdAt: '2026-11-25', enabled: false },
    ])
    await render()

    expect(byTestId('scoped-key-row-ativa-1')?.querySelector('button')).not.toBeNull()
    expect(byTestId('scoped-key-row-revogada-1')?.querySelector('button')).toBeNull()
  })

  it('escopo que o papel não concede fica desabilitado, e o que não abre endpoint é avisado', async () => {
    apiMock.scopes.mockResolvedValue([
      ...SCOPES,
      { name: 'WORKFLOW_DELETE', permission: 'workflow:delete', description: 'Delete workflows', grantable: false, opensEndpoints: true },
      { name: 'METRICS_READ', permission: 'metrics:read', description: 'Read metrics', grantable: true, opensEndpoints: false },
    ])
    await render()
    await preencheDialogo('Monitor', 'metrics:read')

    const options = Array.from(document.body.querySelectorAll('[role="option"]'))
    const excluir = options.find(o => o.textContent?.includes('workflow:delete'))!
    expect(excluir.textContent).toContain('admin.tenant.scopedKeys.scopeNotGrantable')
    expect(excluir.getAttribute('data-combobox-disabled')).toBe('true')
    expect(options.find(o => o.textContent?.includes('metrics:read'))?.textContent)
      .toContain('admin.tenant.scopedKeys.scopeOpensNothing')
    expect(byTestId('scoped-key-scopes-unused')?.textContent).toBe('admin.tenant.scopedKeys.scopesOpenNothing')
  })

  it('superadmin vendo outro workspace é avisado de que as chaves são do workspace do login dele', async () => {
    useTenantStore.setState({ impersonating: { id: 'acme', name: 'Acme', plan: 'trial', status: 'active' } })
    await render()

    expect(byTestId('scoped-key-impersonation')?.textContent).toBe('admin.tenant.scopedKeys.impersonationNotice')
  })

  it('sem impersonação, nenhum aviso de workspace', async () => {
    await render()

    expect(byTestId('scoped-key-impersonation')).toBeNull()
  })

  it('a lista mostra a permissão de cada escopo', async () => {
    apiMock.list.mockResolvedValue([{
      id: 'id-1', keyId: 'ak_abc', name: 'CI', scopes: ['WORKFLOW_EXECUTE'], createdAt: '2026-11-25', enabled: true,
    }])
    await render()

    expect(container.textContent).toContain('workflow:execute')
  })
})

describe('ScopedApiKeysPage — validade, datas e nome (RCHFLW-195 itens 11–13)', () => {
  it('chave vencida aparece como vencida, e as datas saem formatadas, não o ISO cru', async () => {
    apiMock.list.mockResolvedValue([{
      id: 'velha-1', keyId: 'ak_velha', name: 'Velha', scopes: ['WORKFLOW_READ'],
      createdAt: '2026-11-20T11:00:00Z', expiresAt: '2026-11-25T13:00:00Z', enabled: true, expired: true,
    }])
    await render()

    const linha = byTestId('scoped-key-row-velha-1')!.textContent ?? ''
    expect(linha).toContain('admin.tenant.scopedKeys.status.expired')
    expect(linha).not.toContain('admin.tenant.scopedKeys.status.active')
    expect(linha).not.toContain('2026-11-20T11:00:00Z')
    expect(linha).toContain(new Date('2026-11-20T11:00:00Z').toLocaleString('pt-BR'))
  })

  it('a validade escolhida vai como instante UTC (com Z)', async () => {
    apiMock.create.mockResolvedValue({
      id: 'id-1', keyId: 'ak_abc', keySecret: 'S', apiKey: 'ak_abc.S', name: 'CI',
      scopes: ['WORKFLOW_EXECUTE'], createdAt: '2026-11-25T10:00:00Z', enabled: true,
    })
    await render()
    await preencheDialogo('CI', 'workflow:execute')
    await type(byTestId('scoped-key-expires') as HTMLInputElement, '2099-12-31T23:59')
    await click(byTestId('scoped-key-create'))

    expect(apiMock.create).toHaveBeenCalledWith({
      name: 'CI', scopes: ['WORKFLOW_EXECUTE'], expiresAt: new Date('2099-12-31T23:59').toISOString(),
    })
  })

  it('validade no passado não deixa criar', async () => {
    await render()
    await preencheDialogo('CI', 'workflow:execute')
    await type(byTestId('scoped-key-expires') as HTMLInputElement, '2020-01-01T10:00')

    expect((byTestId('scoped-key-create') as HTMLButtonElement).disabled).toBe(true)
    expect(document.body.textContent).toContain('admin.tenant.scopedKeys.form.expiresAtPast')
  })

  it('nome igual ao de uma chave ativa é avisado; o segredo criado tem botão de copiar', async () => {
    apiMock.list.mockResolvedValue([{
      id: 'a-1', keyId: 'ak_a', name: 'QA2 adminui leitura', scopes: ['WORKFLOW_READ'],
      createdAt: '2026-11-25T10:00:00Z', enabled: true,
    }])
    apiMock.create.mockResolvedValue({
      id: 'id-2', keyId: 'ak_b', keySecret: 'S', apiKey: 'ak_b.S', name: 'Outra',
      scopes: ['WORKFLOW_READ'], createdAt: '2026-11-25T10:00:00Z', enabled: true,
    })
    await render()
    await preencheDialogo('qa2 adminui leitura ', 'workflow:read')
    expect(byTestId('scoped-key-name-repeated')).not.toBeNull()

    await type(byTestId('scoped-key-name') as HTMLInputElement, 'Outra')
    expect(byTestId('scoped-key-name-repeated')).toBeNull()
    await click(byTestId('scoped-key-create'))

    const banner = byTestId('scoped-key-created')!
    expect(banner.querySelector('button[aria-label]')).not.toBeNull()
  })
})
