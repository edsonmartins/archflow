/**
 * RCHFLW-112: a tradução das formas do Linktor para as telas do Inbox.
 *
 * <p>As amostras seguem as tags JSON do Linktor (`internal/domain/entity`: Conversation, Contact,
 * Channel, MessagePreview, Message; `handlers/user.go`: UserResponse) — snake_case. As páginas liam
 * camelCase que o Linktor nunca mandou, e o Inbox mostrava "—" em todas as colunas.</p>
 */
import { afterEach, describe, expect, it, vi } from 'vitest'
import {
    canalDoInbox,
    conversaDoLinktor,
    conversaEncerrada,
    emOrdemCronologica,
    linktorInboxApi,
    linktorRecusouCredenciais,
    linktorRecusouListarUsuarios,
    mensagemDoLinktor,
    operadorAtivo,
    operadorDoLinktor,
} from '../linktor-inbox-api'
import { ApiError } from '../api'

/** Uma conversa como `GET /conversations` do Linktor a lista. */
const CONVERSA_DO_LINKTOR = {
    id: '6f1c2a10-0000-4000-8000-000000000001',
    tenant_id: 't-1',
    contact_id: 'c-1',
    channel_id: 'ch-1',
    environment: 'production',
    status: 'open',
    priority: 'normal',
    unread_count: 2,
    last_message_at: '2026-11-25T09:00:00.5Z',
    created_at: '2026-11-25T08:00:00Z',
    updated_at: '2026-11-25T09:00:00.5Z',
    contact: { id: 'c-1', tenant_id: 't-1', name: 'Maria Souza', created_at: '', updated_at: '' },
    channel: { id: 'ch-1', tenant_id: 't-1', type: 'webchat', name: 'Site', enabled: true,
               connection_status: 'connected', environment: 'production' },
    last_message: { id: 'm-9', content: 'Meu pedido não chegou', content_type: 'text',
                    sender_type: 'contact', created_at: '2026-11-25T09:00:00.5Z' },
}

const mensagem = (id: string, sender_type: string, created_at: string, content = id) =>
    ({ id, conversation_id: 'conv-1', sender_type, content_type: 'text', content, status: 'sent', created_at })

afterEach(() => { vi.unstubAllGlobals() })

describe('conversa do Linktor → linha do Inbox', () => {
    it('lê contato, canal, atualização, não lidas e a última mensagem do snake_case real', () => {
        expect(conversaDoLinktor(CONVERSA_DO_LINKTOR)).toEqual({
            id: CONVERSA_DO_LINKTOR.id,
            status: 'open',
            atribuidaA: null,
            contato: 'Maria Souza',
            ultimaMensagem: 'Meu pedido não chegou',
            canalTipo: 'webchat',
            canalNome: 'Site',
            atualizadaEm: '2026-11-25T09:00:00.5Z',
            naoLidas: 2,
        })
    })

    it('o que o Linktor não mandou fica vazio — nada de camelCase nem valor inventado', () => {
        // O detalhe (GET /conversations/:id) não traz last_message; um contato do WebChat pode não ter nome.
        const c = conversaDoLinktor({
            id: 'conv-2', status: 'resolved', assigned_user_id: 'u-1', unread_count: 0,
            updated_at: '2026-11-25T10:00:00Z', contact: { id: 'c-2' },
            // Campos que as telas liam antes: não existem no Linktor e não podem voltar a valer.
            lastMessagePreview: 'inventado', channelType: 'whatsapp', updatedAt: '1999-01-01T00:00:00Z',
        })
        expect(c.contato).toBeNull()
        expect(c.ultimaMensagem).toBeNull()
        expect(c.canalTipo).toBeNull()
        expect(c.atualizadaEm).toBe('2026-11-25T10:00:00Z')
        expect(c.atribuidaA).toBe('u-1')
    })
})

describe('mensagens do Linktor', () => {
    it.each([
        ['contact', 'contato'],
        ['user', 'operador'],
        ['bot', 'bot'],
        ['system', 'sistema'],
        ['outra-coisa', 'desconhecido'],
    ])('sender_type %s → %s', (senderType, remetente) => {
        expect(mensagemDoLinktor(mensagem('m', senderType, '2026-11-25T09:00:00Z')).remetente).toBe(remetente)
    })

    it('ordena da mais antiga para a mais nova pelo instante, não pelo texto', () => {
        // Como o Linktor devolve: a mais nova primeiro. "…:00Z" < "…:00.5Z" no tempo, mas não na string.
        const doLinktor = [
            mensagem('3', 'user', '2026-11-25T09:00:01Z'),
            mensagem('2', 'contact', '2026-11-25T09:00:00.5Z'),
            mensagem('1', 'contact', '2026-11-25T09:00:00Z'),
        ].map(mensagemDoLinktor)

        expect(emOrdemCronologica(doLinktor).map(m => m.id)).toEqual(['1', '2', '3'])
    })

    it('no mesmo instante, vale a ordem inversa da resposta (a do Linktor é a mais nova primeiro)', () => {
        const doLinktor = [
            mensagem('b', 'user', '2026-11-25T09:00:00Z'),
            mensagem('a', 'contact', '2026-11-25T09:00:00Z'),
        ].map(mensagemDoLinktor)

        expect(emOrdemCronologica(doLinktor).map(m => m.id)).toEqual(['a', 'b'])
    })

    it('listMessages devolve a conversa em ordem cronológica, com o remetente traduzido', async () => {
        vi.stubGlobal('fetch', vi.fn(async () => new Response(JSON.stringify([
            mensagem('m2', 'user', '2026-11-25T09:01:00Z', 'Já verifico'),
            mensagem('m1', 'contact', '2026-11-25T09:00:00Z', 'Meu pedido não chegou'),
        ]), { status: 200 })))

        const ms = await linktorInboxApi.listMessages('conv-1')

        expect(ms.map(m => [m.remetente, m.conteudo])).toEqual([
            ['contato', 'Meu pedido não chegou'],
            ['operador', 'Já verifico'],
        ])
    })
})

describe('operadores e canais', () => {
    it('usuário do Linktor → operador com id, nome e e-mail', () => {
        expect(operadorDoLinktor({ id: 'u-1', tenant_id: 't', email: 'ana@x.com', name: 'Ana', role: 'agent',
            status: 'active', created_at: '', updated_at: '' }))
            .toEqual({ id: 'u-1', nome: 'Ana', email: 'ana@x.com', papel: 'agent', status: 'active' })
    })

    it('canal lê connection_status', () => {
        expect(canalDoInbox({ id: 'ch-1', type: 'webchat', name: 'Site', connection_status: 'disconnected' }))
            .toEqual({ id: 'ch-1', nome: 'Site', tipo: 'webchat', conexao: 'disconnected' })
    })

    it('a recusa do Linktor em listar usuários é reconhecida pelo linktorStatus do 502', () => {
        const recusa = (s: number) => new ApiError(502, 'Linktor /users returned HTTP ' + s,
            JSON.stringify({ error: 'Linktor /users returned HTTP ' + s, linktorStatus: s }))
        // 403 vem do RequireRole (falta papel admin/owner); 401, do middleware de autenticação (chave
        // inválida ou expirada). São causas diferentes, e a tela não pode diagnosticar uma pela outra.
        expect(linktorRecusouListarUsuarios(recusa(403))).toBe(true)
        expect(linktorRecusouListarUsuarios(recusa(401))).toBe(false)
        expect(linktorRecusouCredenciais(recusa(401))).toBe(true)
        expect(linktorRecusouCredenciais(recusa(403))).toBe(false)
        expect(linktorRecusouListarUsuarios(recusa(500))).toBe(false)
        expect(linktorRecusouCredenciais(recusa(500))).toBe(false)
        expect(linktorRecusouListarUsuarios(new ApiError(502, 'rede', '{"error":"rede"}'))).toBe(false)
        expect(linktorRecusouListarUsuarios(new Error('x'))).toBe(false)
        expect(linktorRecusouCredenciais(new Error('x'))).toBe(false)
    })

    it('só usuário active pode receber a conversa', () => {
        const usuario = (status: string) => operadorDoLinktor({ id: 'u', name: 'U', email: '', role: 'agent', status })
        expect(operadorAtivo(usuario('active'))).toBe(true)
        expect(operadorAtivo(usuario('inactive'))).toBe(false)
        expect(operadorAtivo(usuario('suspended'))).toBe(false)
    })

    it('resolved e closed encerram a conversa; open e pending não', () => {
        expect(['open', 'pending', 'resolved', 'closed'].map(conversaEncerrada)).toEqual([false, false, true, true])
    })
})
