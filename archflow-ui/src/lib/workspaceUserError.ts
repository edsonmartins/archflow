import { mensagemDoErro } from './errorMessage'

/**
 * Frase das recusas da tela de usuários e do aceite de convite no idioma da UI (RCHFLW-85).
 *
 * <p>O backend (`WorkspaceUserService`) devolve `{error, code, message}`: `message` é a frase pronta em
 * inglês, para clientes de API, e aparecia como estava na UI em pt-BR — inclusive na página pública de
 * aceite, que o convidado vê. `code` é o caso exato da recusa; aqui ele vira a chave
 * `workspaceUserErrors.<code>`. Um código fora desta lista (de uma versão mais nova do backend) e erros
 * sem código (rede, 500) seguem pela frase legível de `mensagemDoErro` — nunca o JSON cru.</p>
 */

/** O mesmo mínimo que o backend confere (`WorkspaceUserService.MIN_PASSWORD_LENGTH`). */
export const MIN_INVITE_PASSWORD = 8

/** Os códigos de `WorkspaceUserException.Code` — os que têm frase traduzida. */
const CODES = new Set([
  'BODY_REQUIRED', 'EMAIL_INVALID', 'ROLE_UNKNOWN', 'USER_EXISTS', 'USER_NOT_FOUND', 'SELF_ROLE_CHANGE',
  'SELF_REMOVE', 'INVITATION_PENDING', 'INVITATION_ALREADY_ACCEPTED', 'INVITATION_INVALID',
  'INVITATION_EXPIRED', 'PASSWORD_TOO_SHORT', 'INVITATION_ALREADY_SENT',
])

type Traduzir = (chave: string, opcoes?: Record<string, unknown>) => string

/** O `code` de um erro lançado pelos clientes de `services/admin-api`, se houver. */
export function codigoDoErro(erro: unknown): string | null {
  const code = erro && typeof erro === 'object' ? (erro as { code?: unknown }).code : null
  return typeof code === 'string' && code ? code : null
}

export function mensagemDeUsuarioDoWorkspace(erro: unknown, t: Traduzir, fallback = ''): string {
  const code = codigoDoErro(erro)
  if (code && CODES.has(code)) return t(`workspaceUserErrors.${code}`, { min: MIN_INVITE_PASSWORD })
  return mensagemDoErro(erro, fallback)
}
