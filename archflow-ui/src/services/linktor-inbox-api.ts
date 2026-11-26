import { api, ApiError } from './api'
import { mensagemDoErro } from '../lib/errorMessage'

/**
 * Formas cruas do Linktor, como o backend as repassa (sem o envelope `{success, data}`). O Linktor
 * responde em snake_case (`updated_at`, `unread_count`, `sender_type`…, tags JSON das entidades em
 * `internal/domain/entity`); as telas não leem essas formas — leem as de baixo, traduzidas aqui.
 *
 * <p>Este arquivo é o ponto único de tradução (RCHFLW-112): as páginas liam `channelType`,
 * `updatedAt`, `lastMessagePreview` e `direction`, campos que o Linktor nunca mandou, e mostravam "—"
 * em todas as colunas e "user" como autor de toda mensagem.</p>
 */
export type LinktorConversation = Record<string, unknown>
export type LinktorMessage      = Record<string, unknown>
export type LinktorContact      = Record<string, unknown>
export type LinktorChannel      = Record<string, unknown>
export type LinktorUser         = Record<string, unknown>

/** Uma linha do Inbox. Campo que o Linktor não mandou fica `null` — a tela mostra "—", não inventa. */
export type ConversaDoInbox = {
    id: string
    /** `open`, `pending`, `resolved` ou `closed` (`ConversationStatus` do Linktor). */
    status: string
    /** `assigned_user_id`: o Linktor não tem status "atribuída", tem o usuário atribuído. */
    atribuidaA: string | null
    contato: string | null
    /** `last_message.content`: o Linktor manda a mais recente na listagem; no detalhe não manda. */
    ultimaMensagem: string | null
    canalTipo: string | null
    canalNome: string | null
    atualizadaEm: string | null
    naoLidas: number
}

/** Quem escreveu, pelo `sender_type` do Linktor (`contact`, `user`, `bot`, `system`). */
export type RemetenteDoLinktor = 'contato' | 'operador' | 'bot' | 'sistema' | 'desconhecido'

export type MensagemDoInbox = {
    id: string
    remetente: RemetenteDoLinktor
    conteudo: string
    criadaEm: string | null
}

/** Um usuário do Linktor que pode receber a conversa (`GET /users`, `UserResponse`). */
export type OperadorDoLinktor = {
    id: string
    nome: string
    email: string
    papel: string
    status: string
}

export type CanalDoInbox = {
    id: string
    nome: string
    tipo: string
    /** `connection_status` (connected, disconnected…). */
    conexao: string | null
}

/**
 * A conversa terminou: `resolved` ou `closed`. O Linktor trata os dois igual (grava `resolved_at` para
 * ambos e reabre ambos quando o contato escreve de novo); as telas também, para que a lista e o detalhe
 * não discordem sobre a mesma conversa.
 */
export const conversaEncerrada = (status: string): boolean => status === 'resolved' || status === 'closed'

/**
 * Pode receber a conversa: o Linktor atribui a qualquer usuário, sem olhar o status, e um usuário
 * `inactive` ou `suspended` nunca a veria.
 */
export const operadorAtivo = (o: OperadorDoLinktor): boolean => o.status === 'active'

const texto = (v: unknown): string | null => (typeof v === 'string' && v.trim() ? v : null)
const objeto = (v: unknown): Record<string, unknown> =>
    (v && typeof v === 'object' && !Array.isArray(v) ? v as Record<string, unknown> : {})

export function conversaDoLinktor(raw: LinktorConversation): ConversaDoInbox {
    const contato = objeto(raw.contact)
    const canal = objeto(raw.channel)
    const ultima = objeto(raw.last_message)
    return {
        id: String(raw.id ?? ''),
        status: texto(raw.status) ?? 'open',
        atribuidaA: texto(raw.assigned_user_id),
        contato: texto(contato.name),
        ultimaMensagem: texto(ultima.content),
        canalTipo: texto(canal.type),
        canalNome: texto(canal.name),
        atualizadaEm: texto(raw.updated_at),
        naoLidas: typeof raw.unread_count === 'number' ? raw.unread_count : 0,
    }
}

const REMETENTES: Record<string, RemetenteDoLinktor> = {
    contact: 'contato', user: 'operador', bot: 'bot', system: 'sistema',
}

export function mensagemDoLinktor(raw: LinktorMessage): MensagemDoInbox {
    return {
        id: String(raw.id ?? ''),
        remetente: REMETENTES[String(raw.sender_type ?? '')] ?? 'desconhecido',
        conteudo: typeof raw.content === 'string' ? raw.content : '',
        criadaEm: texto(raw.created_at),
    }
}

/**
 * Da mais antiga para a mais nova, como se lê uma conversa. O Linktor pagina para trás e devolve a
 * mais nova primeiro (o cursor aponta para a última da página, a mais antiga). Ordena pelo instante do
 * `created_at`, e não pelo texto: o Go escreve a fração de segundo com tamanho variável ("…:00Z" vs
 * "…:00.5Z"), e a ordem das strings erra. No mesmo instante, ou com instante ilegível, vale a ordem
 * inversa da resposta.
 */
export function emOrdemCronologica(mensagens: MensagemDoInbox[]): MensagemDoInbox[] {
    return mensagens
        .map((m, i) => ({ m, i, t: m.criadaEm ? Date.parse(m.criadaEm) : NaN }))
        .sort((a, b) => {
            const porTempo = Number.isNaN(a.t) || Number.isNaN(b.t) ? 0 : a.t - b.t
            return porTempo !== 0 ? porTempo : b.i - a.i
        })
        .map(x => x.m)
}

export function operadorDoLinktor(raw: LinktorUser): OperadorDoLinktor {
    return {
        id: String(raw.id ?? ''),
        nome: String(raw.name ?? ''),
        email: String(raw.email ?? ''),
        papel: String(raw.role ?? ''),
        status: String(raw.status ?? ''),
    }
}

export function canalDoInbox(raw: LinktorChannel): CanalDoInbox {
    const id = String(raw.id ?? '')
    return {
        id,
        nome: texto(raw.name) ?? id,
        tipo: String(raw.type ?? ''),
        conexao: texto(raw.connection_status),
    }
}

/** O status que o Linktor respondeu, quando o backend o repassou num 502 (`linktorStatus`). */
function statusDoLinktor(e: unknown): unknown {
    return e instanceof ApiError && e.status === 502 ? e.details?.linktorStatus : undefined
}

/**
 * O Linktor recusou listar usuários: `GET /users` exige papel admin/owner (`RequireRole`, 403), e uma
 * chave de API tem o papel `api`. O backend devolve 502 com o status do Linktor em `linktorStatus` — não
 * 401/403, que a UI confundiria com a própria sessão.
 */
export function linktorRecusouListarUsuarios(e: unknown): boolean {
    return statusDoLinktor(e) === 403
}

/**
 * O Linktor não aceitou as credenciais (401 do middleware de autenticação): a chave é inválida ou
 * expirou. É outra causa, com outro remédio — dizer "falta papel admin" aqui mandaria o operador
 * mexer no papel de uma chave que nem autentica.
 */
export function linktorRecusouCredenciais(e: unknown): boolean {
    return statusDoLinktor(e) === 401
}

/**
 * A integração está desligada neste tenant: o backend responde 503 com a frase dele, em inglês ("Linktor
 * integration is disabled; ..."). Não é falha — as telas mostram o aviso traduzido com o atalho para a
 * configuração. Compartilhado pelo Inbox e pela conversa (RCHFLW-188: a conversa mostrava a frase crua).
 */
export function linktorDesligado(erro: unknown): boolean {
    return /disabled/i.test(mensagemDoErro(erro))
}

export const linktorInboxApi = {
    listConversations: async (limit = 25, offset = 0) =>
        (await api.get<LinktorConversation[]>(`/admin/linktor/inbox/conversations?limit=${limit}&offset=${offset}`))
            .map(conversaDoLinktor),
    getConversation: async (id: string) =>
        conversaDoLinktor(await api.get<LinktorConversation>(
            `/admin/linktor/inbox/conversations/${encodeURIComponent(id)}`)),
    listMessages: async (id: string, limit = 50) =>
        emOrdemCronologica((await api.get<LinktorMessage[]>(
            `/admin/linktor/inbox/conversations/${encodeURIComponent(id)}/messages?limit=${limit}`))
            .map(mensagemDoLinktor)),
    sendMessage: (id: string, content: string) =>
        api.post<LinktorMessage>(
            `/admin/linktor/inbox/conversations/${encodeURIComponent(id)}/messages`,
            { content }),
    assign: (id: string, userId: string) =>
        api.post<LinktorConversation>(
            `/admin/linktor/inbox/conversations/${encodeURIComponent(id)}/assign`,
            { userId }),
    resolve: (id: string) =>
        api.post<LinktorConversation>(
            `/admin/linktor/inbox/conversations/${encodeURIComponent(id)}/resolve`),
    listContacts: (limit = 25, offset = 0) =>
        api.get<LinktorContact[]>(`/admin/linktor/inbox/contacts?limit=${limit}&offset=${offset}`),
    listChannels: async () =>
        (await api.get<LinktorChannel[]>(`/admin/linktor/inbox/channels`)).map(canalDoInbox),
    listUsers: async () =>
        (await api.get<LinktorUser[]>(`/admin/linktor/inbox/users`)).map(operadorDoLinktor),
}
