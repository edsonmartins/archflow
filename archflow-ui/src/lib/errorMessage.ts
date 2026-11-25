/**
 * Frase legível a partir de uma resposta de erro do backend (RCHFLW-65/94).
 *
 * <p>O `request()` de `services/api` guardava o corpo HTTP inteiro em `ApiError.message`, e as páginas o
 * jogavam direto em alertas e toasts — o usuário lia `{"error":"...","status":409}` ou, no YAML, a cadeia
 * de referências do Jackson. Os controllers do archflow não falam um formato só, então a extração aceita
 * os que existem hoje:</p>
 * <ul>
 *   <li>`{error: "frase"}` — `GlobalExceptionHandler`, Linktor, VendaX;</li>
 *   <li>`{error: "FORBIDDEN", message: "frase"}` — filtros de auth/admin e o erro padrão do Spring, em que
 *   `error` é só o código/razão HTTP: por isso `message` vence `error`;</li>
 *   <li>`{title, detail}` — ProblemDetail (RFC 9457) do Spring 6+;</li>
 *   <li>texto puro — devolvido como está; HTML (página de erro de proxy) não é frase, cai no fallback.</li>
 * </ul>
 */

const CAMPOS_DA_FRASE = ['message', 'detail', 'error', 'title'] as const

function fraseDoObjeto(corpo: unknown): string | null {
    if (!corpo || typeof corpo !== 'object' || Array.isArray(corpo)) return null
    const registro = corpo as Record<string, unknown>
    for (const campo of CAMPOS_DA_FRASE) {
        const valor = registro[campo]
        if (typeof valor === 'string' && valor.trim()) return valor.trim()
        // `{error: {message: "..."}}` (formato de provedores de LLM repassado por alguns endpoints).
        if (valor && typeof valor === 'object') {
            const aninhada = fraseDoObjeto(valor)
            if (aninhada) return aninhada
        }
    }
    return null
}

/**
 * Extrai a frase de um corpo de erro HTTP cru. `fallback` (ex.: o `statusText`) vale quando o corpo está
 * vazio, é HTML ou é um JSON sem nenhum campo de frase.
 */
export function mensagemDoCorpoDeErro(corpo: string | null | undefined, fallback = ''): string {
    const texto = (corpo ?? '').trim()
    if (!texto) return fallback
    if (texto.startsWith('{') || texto.startsWith('[')) {
        try {
            return fraseDoObjeto(JSON.parse(texto)) ?? (fallback || texto)
        } catch {
            // Parece JSON mas não é (corpo truncado): melhor mostrar o texto do que nada.
            return texto
        }
    }
    if (/^<(!doctype|html)/i.test(texto)) {
        return fallback || texto.replace(/<[^>]*>/g, ' ').replace(/\s+/g, ' ').trim()
    }
    return texto
}

/**
 * Frase para mostrar ao usuário a partir de qualquer coisa capturada num `catch`: `ApiError` (cuja
 * `message` já vem limpa), `Error` comum cuja mensagem ainda carregue um corpo JSON (erros montados à mão a
 * partir de `authFetch`), string ou valor desconhecido.
 */
export function mensagemDoErro(erro: unknown, fallback = ''): string {
    if (erro instanceof Error) return mensagemDoCorpoDeErro(erro.message, fallback) || fallback
    if (typeof erro === 'string') return mensagemDoCorpoDeErro(erro, fallback)
    if (erro && typeof erro === 'object') return fraseDoObjeto(erro) ?? (fallback || String(erro))
    return fallback || String(erro ?? '')
}

/** O `t` do i18next, reduzido ao que o helper usa (evita acoplar `lib/` ao tipo do react-i18next). */
type Traduzir = (chave: string, opcoes?: Record<string, unknown>) => string

function inteiroPositivo(valor: unknown): number | null {
    return typeof valor === 'number' && Number.isInteger(valor) && valor > 0 ? valor : null
}

/**
 * Frase do YAML inválido no idioma da UI (RCHFLW-94): "YAML inválido na linha 10, coluna 3: …".
 *
 * <p>O backend devolve `{message, reason, line, column}`; `message` é a frase pronta em inglês, para clientes
 * de API. Aqui a frase é montada com `line`/`column` e as chaves `editor.yamlInvalid*`, e só `reason` (o
 * problema, que vem do parser e não tem tradução) entra como está. Sem `reason` — erro de outro endpoint,
 * corpo sem os campos, falha de rede — cai na frase legível de `mensagemDoErro`, nunca no JSON cru. O erro é
 * lido por duck typing (`details`) e não por `instanceof ApiError` porque `services/api` importa este módulo.</p>
 */
export function mensagemDeYamlInvalido(erro: unknown, t: Traduzir, fallback = ''): string {
    const details = erro && typeof erro === 'object' ? (erro as { details?: unknown }).details : null
    if (details && typeof details === 'object') {
        const corpo = details as Record<string, unknown>
        const reason = typeof corpo.reason === 'string' ? corpo.reason.trim() : ''
        if (reason) {
            const line = inteiroPositivo(corpo.line)
            const column = inteiroPositivo(corpo.column)
            if (line !== null && column !== null) return t('editor.yamlInvalidAt', { line, column, reason })
            if (line !== null) return t('editor.yamlInvalidAtLine', { line, reason })
            return t('editor.yamlInvalid', { reason })
        }
    }
    return mensagemDoErro(erro, fallback)
}
