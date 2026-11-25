import type { AgUiRunInput } from '../services/agui-client'

/**
 * Entrada de uma execução disparada pela UI (editor, lista de fluxos, "Executar novamente").
 *
 * <p>O usuário digita um texto livre. Se ele é um objeto JSON, cada chave vira uma variável do
 * contexto (é o que o POST /workflows/{id}/execute faz com o corpo); qualquer outra coisa vira a
 * variável <code>input</code> — o nome que os fluxos já leem (<code>${input}</code>) e que o curl de
 * exemplo do Publicar usa. Vazio continua valendo: é a execução sem entrada, e o servidor
 * (RunInputSeeding) define <code>input = ""</code> — assim <code>${input} != ''</code> dá false (RCHFLW-101).</p>
 */
export type ParsedRunInput =
    | { kind: 'empty' }
    | { kind: 'variables'; value: Record<string, unknown> }
    | { kind: 'text'; value: string }

export function parseRunInput(text: string): ParsedRunInput {
    if (text.trim() === '') return { kind: 'empty' }
    try {
        const parsed: unknown = JSON.parse(text)
        // Só objeto: um array, número ou string JSON não tem chaves para virar variáveis, então segue
        // como texto — mandar `[1,2]` como mapa daria 400 (ou chaves "0", "1") no backend.
        if (parsed !== null && typeof parsed === 'object' && !Array.isArray(parsed)) {
            return { kind: 'variables', value: parsed as Record<string, unknown> }
        }
    } catch {
        // Não é JSON: é texto livre.
    }
    return { kind: 'text', value: text }
}

/**
 * Corpo do POST /workflows/{id}/execute. Vazio → sem corpo (undefined), exatamente como as
 * chamadas sem entrada de antes desta tela.
 */
export function toExecuteBody(text: string): Record<string, unknown> | undefined {
    const parsed = parseRunInput(text)
    switch (parsed.kind) {
        case 'empty': return undefined
        case 'variables': return parsed.value
        case 'text': return { input: parsed.value }
    }
}

/**
 * RunAgentInput do AG-UI (editor). O AgUiController.seedInput lê duas coisas: <code>state</code>
 * (cada chave vira variável e <code>input</code> recebe o mapa inteiro) e o <code>content</code> da
 * última mensagem (vira <code>input</code>). Por isso o texto vai como mensagem, não como
 * <code>state: {input}</code> — nesse caso o seedInput sobrescreveria <code>input</code> com o mapa
 * <code>{input: "..."}</code> e o fluxo receberia um objeto em vez do texto, diferente do REST.
 */
export function toAgUiRunInput(text: string): AgUiRunInput {
    const parsed = parseRunInput(text)
    switch (parsed.kind) {
        case 'empty': return {}
        case 'variables': return { state: parsed.value }
        case 'text': return { messages: [{ role: 'user', content: parsed.value }] }
    }
}

const STORAGE_PREFIX = 'archflow.runInput.'

/**
 * Última entrada usada por fluxo, como padrão do modal. sessionStorage pode faltar ou lançar
 * (aba privada, dados bloqueados): aí o modal só abre vazio — nunca impede a execução.
 */
export function loadLastRunInput(workflowId: string): string {
    try {
        return sessionStorage.getItem(STORAGE_PREFIX + workflowId) ?? ''
    } catch {
        return ''
    }
}

export function saveLastRunInput(workflowId: string, text: string): void {
    try {
        if (text === '') sessionStorage.removeItem(STORAGE_PREFIX + workflowId)
        else sessionStorage.setItem(STORAGE_PREFIX + workflowId, text)
    } catch {
        // Armazenamento indisponível: lembrar é conveniência, não requisito.
    }
}
