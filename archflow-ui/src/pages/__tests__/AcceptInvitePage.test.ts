/**
 * Aceite de convite (RCHFLW-85): o link traz o token; a página define a senha pelo backend e diz com que
 * usuário entrar. O que o backend recusa (link usado, vencido) aparece pelo código da recusa, no idioma da
 * página; sem código, como a frase dele.
 *
 * <p>Arquivo .ts (createElement em vez de JSX) porque o vitest.config só inclui *.test.ts.</p>
 */
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { createElement as h, act } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { MantineProvider } from '@mantine/core'

const acceptMock = vi.hoisted(() => vi.fn())

vi.mock('react-i18next', () => ({
  useTranslation: () => ({
    t: (k: string, o?: Record<string, unknown>) => (o && 'username' in o ? `${k}:${String(o.username)}` : k),
    i18n: { language: 'pt-BR', resolvedLanguage: 'pt-BR' },
  }),
}))
vi.mock('../../services/admin-api', () => ({ acceptInvitation: acceptMock }))
vi.mock('../../components/layout/LanguageSwitcher', () => ({ default: () => null }))

import AcceptInvitePage from '../AcceptInvitePage'

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
})

afterEach(async () => {
  await act(async () => { root?.unmount() })
  root = null
  container?.remove()
})

async function render(url: string) {
  container = document.createElement('div')
  document.body.appendChild(container)
  root = createRoot(container)
  await act(async () => {
    root!.render(h(MantineProvider, null,
      h(MemoryRouter, { initialEntries: [url] },
        h(Routes, null, h(Route, { path: '/accept-invite', element: h(AcceptInvitePage) })))))
  })
}

const byTestId = (id: string) => document.body.querySelector(`[data-testid="${id}"]`) as HTMLElement | null

async function type(input: HTMLInputElement, value: string) {
  await act(async () => {
    const setter = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value')!.set!
    setter.call(input, value)
    input.dispatchEvent(new Event('input', { bubbles: true }))
  })
}

async function submit(password: string, confirm = password) {
  const [pw, conf] = Array.from(document.body.querySelectorAll('input[type="password"]')) as HTMLInputElement[]
  await type(pw, password)
  await type(conf, confirm)
  const form = byTestId('accept-invite')!.querySelector('form')!
  await act(async () => {
    form.dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }))
  })
}

describe('AcceptInvitePage', () => {
  it('define a senha com o token do link e diz com que usuário entrar', async () => {
    acceptMock.mockResolvedValue({ username: 'viewer.teste@example.com' })
    await render('/accept-invite?token=user-2.SEGREDO')

    await submit('senha-nova-123')

    expect(acceptMock).toHaveBeenCalledWith('user-2.SEGREDO', 'senha-nova-123')
    expect(byTestId('accept-invite-done')!.textContent).toContain('viewer.teste@example.com')
  })

  it('senhas diferentes ou curtas não chegam ao backend', async () => {
    await render('/accept-invite?token=user-2.SEGREDO')

    await submit('senha-nova-123', 'outra-senha-456')
    await submit('curta')

    expect(acceptMock).not.toHaveBeenCalled()
  })

  it('link usado ou vencido: a frase do backend aparece', async () => {
    acceptMock.mockRejectedValue(new Error('This invitation link is invalid or has already been used'))
    await render('/accept-invite?token=user-2.SEGREDO')

    await submit('senha-nova-123')

    expect(byTestId('accept-invite-error')!.textContent).toContain('already been used')
    expect(byTestId('accept-invite-done')).toBeNull()
  })

  it('recusa com código: a frase sai no idioma da página, não a frase em inglês do backend', async () => {
    acceptMock.mockRejectedValue(Object.assign(
      new Error('This invitation has expired; ask the administrator for a new one'), { code: 'INVITATION_EXPIRED' }))
    await render('/accept-invite?token=user-2.SEGREDO')

    await submit('senha-nova-123')

    const text = byTestId('accept-invite-error')!.textContent
    expect(text).toContain('workspaceUserErrors.INVITATION_EXPIRED')
    expect(text).not.toContain('has expired')
  })

  it('sem token no link, diz isso em vez de mostrar o formulário', async () => {
    await render('/accept-invite')

    expect(byTestId('accept-invite-error')!.textContent).toContain('acceptInvite.missingToken')
    expect(document.body.querySelector('input[type="password"]')).toBeNull()
    // RCHFLW-195 item 18: o caminho de volta ao login, que faltava.
    expect(byTestId('accept-invite-login')?.getAttribute('href')).toBe('/login')
  })

  it('só o formulário: o grid de uma coluna, sem a coluna vazia da marca (RCHFLW-195 item 18)', async () => {
    await render('/accept-invite?token=abc')

    expect(byTestId('accept-invite-page')!.classList.contains('af-login--single')).toBe(true)
  })
})
