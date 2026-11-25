/**
 * Sessão de teste com papéis (RCHFLW-74): as páginas leem as permissões do claim `roles` do access
 * token. Sem token nenhuma ação de escrita aparece; os testes que exercitam Salvar/Executar/Publicar
 * entram com um papel que as concede.
 */
import { useAuthStore } from '../../stores/auth-store'

function base64Url(value: object): string {
  return btoa(JSON.stringify(value)).replace(/=+$/, '').replace(/\+/g, '-').replace(/\//g, '_')
}

/** JWT só com o payload que a UI lê; a assinatura é falsa porque quem confere é o backend. */
export function tokenComPapeis(roles: string[]): string {
  return `${base64Url({ alg: 'HS256', typ: 'JWT' })}.${base64Url({ sub: 'u-teste', type: 'access', roles })}.assinatura`
}

export function entrarComo(...roles: string[]): void {
  useAuthStore.setState({ token: tokenComPapeis(roles) })
}
