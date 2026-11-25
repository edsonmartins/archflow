/**
 * RCHFLW-65/94: a frase do erro sai do corpo HTTP em todos os formatos que os controllers do archflow
 * devolvem hoje — e o JSON cru nunca chega à tela.
 */
import { describe, it, expect } from 'vitest'
import { mensagemDeYamlInvalido, mensagemDoCorpoDeErro, mensagemDoErro } from '../errorMessage'
import { ApiError } from '../../services/api'

describe('mensagemDoCorpoDeErro', () => {
    it('GlobalExceptionHandler / Linktor: {error} vira só a frase', () => {
        expect(mensagemDoCorpoDeErro('{"error":"Linktor apiBaseUrl is not configured"}'))
            .toBe('Linktor apiBaseUrl is not configured')
        expect(mensagemDoCorpoDeErro(
            '{"error":"Resolved LLM config has no provider; cannot build a ChatModel","status":409}'))
            .toBe('Resolved LLM config has no provider; cannot build a ChatModel')
    })

    it('auth/admin e erro padrão do Spring: message vence o código em error', () => {
        expect(mensagemDoCorpoDeErro(
            '{"error":"FORBIDDEN","message":"Administration endpoints require the ADMIN role","status":403}'))
            .toBe('Administration endpoints require the ADMIN role')
        expect(mensagemDoCorpoDeErro(JSON.stringify({
            timestamp: '2026-11-24T10:00:00Z', status: 400, error: 'Bad Request', message: 'YAML payload is empty',
        })))
            .toBe('YAML payload is empty')
    })

    it('message vazia no erro padrão do Spring cai para error', () => {
        expect(mensagemDoCorpoDeErro('{"status":500,"error":"Internal Server Error","message":""}'))
            .toBe('Internal Server Error')
    })

    it('ProblemDetail: detail antes de title', () => {
        expect(mensagemDoCorpoDeErro(
            '{"type":"about:blank","title":"Not Found","status":404,"detail":"Workflow not found: x"}'))
            .toBe('Workflow not found: x')
    })

    it('YAML inválido: devolve message, não o envelope', () => {
        const corpo = JSON.stringify({
            status: 400, error: 'Invalid YAML at line 6, column 1: x', message: 'Invalid YAML at line 6, column 1: x',
            line: 6, column: 1,
        })
        expect(mensagemDoCorpoDeErro(corpo)).toBe('Invalid YAML at line 6, column 1: x')
    })

    it('erro aninhado {error: {message}}', () => {
        expect(mensagemDoCorpoDeErro('{"error":{"message":"quota exceeded","code":429}}')).toBe('quota exceeded')
    })

    it('texto puro passa como está; vazio usa o fallback', () => {
        expect(mensagemDoCorpoDeErro('Service unavailable')).toBe('Service unavailable')
        expect(mensagemDoCorpoDeErro('', 'Bad Gateway')).toBe('Bad Gateway')
        expect(mensagemDoCorpoDeErro(null, 'HTTP 500')).toBe('HTTP 500')
    })

    it('JSON sem campo de frase usa o fallback em vez de mostrar o objeto', () => {
        expect(mensagemDoCorpoDeErro('{"status":500}', 'Internal Server Error')).toBe('Internal Server Error')
    })

    it('página HTML de proxy não é frase: fica o fallback', () => {
        expect(mensagemDoCorpoDeErro('<!DOCTYPE html><html><body><h1>502</h1></body></html>', 'Bad Gateway'))
            .toBe('Bad Gateway')
    })
})

describe('mensagemDoErro', () => {
    it('Error cuja mensagem ainda carrega o corpo JSON (erro montado à mão sobre authFetch)', () => {
        expect(mensagemDoErro(new Error('{"error":"nenhuma execução em andamento"}')))
            .toBe('nenhuma execução em andamento')
    })

    it('Error comum, string e objeto', () => {
        expect(mensagemDoErro(new Error('Failed to fetch'))).toBe('Failed to fetch')
        expect(mensagemDoErro('{"message":"x"}')).toBe('x')
        expect(mensagemDoErro({ error: 'y' })).toBe('y')
        expect(mensagemDoErro(undefined, 'falhou')).toBe('falhou')
    })

    it('sem o prefixo "Error:" que String(e) punha na tela', () => {
        expect(mensagemDoErro(new TypeError('NetworkError when attempting to fetch resource.')))
            .toBe('NetworkError when attempting to fetch resource.')
    })
})

describe('mensagemDeYamlInvalido', () => {
    // `t` falso que devolve a chave e as variáveis: prova qual chave foi escolhida e com que valores.
    const t = (k: string, o?: Record<string, unknown>) => `${k} ${JSON.stringify(o ?? {})}`
    const corpo = (extra: Record<string, unknown>) => JSON.stringify({
        status: 400, error: 'Invalid YAML at line 7, column 3: boom', message: 'Invalid YAML at line 7, column 3: boom',
        ...extra,
    })

    it('com reason/line/column monta a frase traduzida, não repassa a frase em inglês do backend', () => {
        const erro = new ApiError(400, 'Invalid YAML at line 7, column 3: boom',
            corpo({ reason: 'boom', line: 7, column: 3 }))
        expect(mensagemDeYamlInvalido(erro, t)).toBe('editor.yamlInvalidAt {"line":7,"column":3,"reason":"boom"}')
    })

    it('sem coluna usa a chave só com linha; sem posição, a chave sem posição', () => {
        expect(mensagemDeYamlInvalido(new ApiError(400, 'x', corpo({ reason: 'boom', line: 7, column: null })), t))
            .toBe('editor.yamlInvalidAtLine {"line":7,"reason":"boom"}')
        expect(mensagemDeYamlInvalido(new ApiError(400, 'x', corpo({ reason: 'boom', line: null, column: null })), t))
            .toBe('editor.yamlInvalid {"reason":"boom"}')
    })

    it('corpo sem reason (outro endpoint, versão antiga) cai na frase legível, nunca no JSON', () => {
        const erro = new ApiError(400, 'YAML payload is empty', '{"status":400,"error":"YAML payload is empty"}')
        expect(mensagemDeYamlInvalido(erro, t, 'fallback')).toBe('YAML payload is empty')
        expect(mensagemDeYamlInvalido(new TypeError('Failed to fetch'), t, 'fallback')).toBe('Failed to fetch')
        expect(mensagemDeYamlInvalido(undefined, t, 'fallback')).toBe('fallback')
    })
})
