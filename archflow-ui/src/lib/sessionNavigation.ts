/**
 * Navegações de sessão: ir para o login guardando a página pedida (RCHFLW-188, item 5) e sair da sessão sem
 * passar por cima do aviso de alterações não salvas do editor (RCHFLW-142).
 */

/** Parâmetro do /login com a página a que o usuário volta depois de entrar. */
export const LOGIN_FROM_PARAM = 'from'

/**
 * Estado da navegação do botão Sair. O logout não acontece no clique: o clique só navega para o /login com
 * este estado, e quem encerra a sessão é a página de login, quando a navegação chega lá. Assim o useBlocker
 * do editor (RCHFLW-124) vê a saída como qualquer outra e pode segurá-la — antes o token sumia primeiro, o
 * ProtectedRoute desmontava o editor e a edição se perdia sem o modal (e o "Salvar e sair" nem teria token).
 */
export const LOGOUT_NAVIGATION_STATE = { logout: true } as const

export function isLogoutNavigation(state: unknown): boolean {
    return !!state && typeof state === 'object' && (state as { logout?: unknown }).logout === true
}

/**
 * Caracteres de controle (C0 e DEL). O parser de URL do navegador (WHATWG) descarta tab e quebras de linha, então
 * `/<tab>/evil.com` passa pela checagem de `//` e vira `//evil.com` na navegação — recusar antes fecha o desvio.
 */
// eslint-disable-next-line no-control-regex
const CONTROLE = /[\u0000-\u001F\u007F]/

/**
 * Destino seguro para depois do login: só caminhos do próprio app. `//host` e `/\host` o navegador leva para
 * outro domínio, e o próprio /login não é destino (voltaria para a tela de login já logado).
 */
export function safeReturnTarget(raw: string | null | undefined): string {
    if (!raw || CONTROLE.test(raw)) return '/'
    if (!raw.startsWith('/') || raw.startsWith('//') || raw.startsWith('/\\')) return '/'
    if (raw === '/login' || raw.startsWith('/login?') || raw.startsWith('/login/') || raw.startsWith('/login#')) {
        return '/'
    }
    return raw
}

/** /login com a página pedida (caminho + query + hash), para o login devolver o usuário a ela. */
export function loginPathFor(destino: string | null | undefined): string {
    const alvo = safeReturnTarget(destino)
    return alvo === '/' ? '/login' : `/login?${LOGIN_FROM_PARAM}=${encodeURIComponent(alvo)}`
}
