/**
 * RCHFLW-188 (item 5): o login volta para a página pedida. O destino vem da URL (`/login?from=`), então só
 * caminhos do próprio app valem — `//host` levaria o usuário recém-logado para outro domínio.
 */
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { loginPathFor, safeReturnTarget } from '../sessionNavigation'
import { api } from '../../services/api'

describe('safeReturnTarget', () => {
  it.each([
    ['/workflows', '/workflows'],
    ['/executions?status=FAILED', '/executions?status=FAILED'],
    ['/admin/workspace/users#x', '/admin/workspace/users#x'],
  ])('aceita %s', (raw, esperado) => {
    expect(safeReturnTarget(raw)).toBe(esperado)
  })

  it.each([null, '', 'https://evil.example', '//evil.example/x', '/\\evil.example', 'workflows', '/login',
    '/login?from=%2Fworkflows'])('recusa %s e cai no início', (raw) => {
    expect(safeReturnTarget(raw)).toBe('/')
  })

  // O parser de URL descarta tab e quebras de linha: `/<tab>/evil.example` passava pela checagem de `//` e a
  // navegação o lia como `//evil.example` (outro domínio).
  it.each(['/\t/evil.example', '/\n/evil.example', '/\r/evil.example', '/work\u0000flows', '/x\u007F'])(
    'recusa caractere de controle em %j', (raw) => {
      expect(safeReturnTarget(raw)).toBe('/')
    })
})

describe('loginPathFor', () => {
  it('leva a página com a query, codificada', () => {
    expect(loginPathFor('/executions?status=FAILED')).toBe('/login?from=%2Fexecutions%3Fstatus%3DFAILED')
  })

  it('o início e o próprio login não viram destino', () => {
    expect(loginPathFor('/')).toBe('/login')
    expect(loginPathFor('/login')).toBe('/login')
  })
})

describe('401 da API guarda a página de onde a sessão caiu', () => {
  const original = window.location
  let href = ''

  beforeEach(() => {
    sessionStorage.clear()
    href = ''
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    delete (window as any).location
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    ;(window as any).location = {
      pathname: '/executions', search: '?workflow=wf-1', hash: '',
      get href() { return href },
      set href(v: string) { href = v },
    }
    vi.stubGlobal('fetch', vi.fn(async () => new Response('', { status: 401 })))
  })

  afterEach(() => {
    vi.unstubAllGlobals()
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    ;(window as any).location = original
  })

  it('redireciona para /login?from=<página>', async () => {
    await expect(api.get('/executions')).rejects.toThrow('Unauthorized')
    expect(href).toBe('/login?from=%2Fexecutions%3Fworkflow%3Dwf-1')
  })

  // Senha errada no /login?from=: o 401 do POST /auth/login recarregava a página num /login sem o ?from=, e o
  // acerto seguinte caía no início.
  it('o 401 do POST /auth/login é erro do formulário: não recarrega e traz a mensagem do backend', async () => {
    ;(window.location as unknown as { pathname: string }).pathname = '/login'
    ;(window.location as unknown as { search: string }).search = '?from=%2Fexecutions%2Fabc'
    vi.stubGlobal('fetch', vi.fn(async () => new Response(
      JSON.stringify({ error: 'Unauthorized', message: 'Invalid credentials', path: '/api/auth/login' }),
      { status: 401 })))

    await expect(api.post('/auth/login', { username: 'a', password: 'b' })).rejects.toThrow('Invalid credentials')
    expect(href).toBe('')
  })

  it('já no /login, um 401 qualquer (ex.: o logout com o token vencido) não recarrega a página', async () => {
    ;(window.location as unknown as { pathname: string }).pathname = '/login'
    ;(window.location as unknown as { search: string }).search = '?from=%2Fexecutions%2Fabc'
    sessionStorage.setItem('archflow_token', 'vencido')

    await expect(api.post('/auth/logout')).rejects.toThrow('Unauthorized')
    expect(href).toBe('')
    expect(sessionStorage.getItem('archflow_token')).toBeNull()
  })
})
