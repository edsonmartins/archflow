import type { TemplateParameter } from '../../data/templates'
import { KNOWLEDGE_BASE_KEY, LEGACY_KNOWLEDGE_BASE_KEY } from '../FlowCanvas/dataNodeKeys'

/**
 * Como cada parâmetro do install vira campo. Só os tipos que o JSON leva do jeito que o
 * WorkflowTemplate.validateParameters confere ({@code type.isInstance}): String, Boolean e inteiros. Um Double não
 * entra — o JSON.stringify de 1.0 é "1", o Jackson lê Integer e o install recusaria um valor que a tela aceitou
 * (RCHFLW-204).
 */
export type FieldKind = 'knowledgeBase' | 'select' | 'text' | 'integer' | 'boolean'

/**
 * O parâmetro que nomeia a base de conhecimento usa as mesmas chaves do nó: o CustomerSupportTemplate pede
 * {@code knowledgeBaseId}; o KnowledgeBaseTemplate pede {@code vectorStoreId} (a chave antiga) e a grava no
 * knowledgeBaseId do nó. Por isso o seletor é o mesmo do painel, com as bases do workspace.
 */
export function fieldKind(p: TemplateParameter): FieldKind | null {
    if (p.type === 'String' && (p.name === KNOWLEDGE_BASE_KEY || p.name === LEGACY_KNOWLEDGE_BASE_KEY)) {
        return 'knowledgeBase'
    }
    if (p.type === 'String') return p.options && p.options.length > 0 ? 'select' : 'text'
    // Só Integer: o Jackson lê um número pequeno do JSON como Integer, e Long.class.isInstance(Integer) é falso — um
    // Long teria o mesmo 400 do Double, então fica de fora como ele (RCHFLW-204).
    if (p.type === 'Integer') return 'integer'
    if (p.type === 'Boolean') return 'boolean'
    return null
}

/**
 * Os campos do formulário: só os obrigatórios — os opcionais ficam com o default do template, que o servidor aplica
 * (resolveParameters). Formulário mínimo de propósito (RCHFLW-204).
 */
export function installFields(parameters: readonly TemplateParameter[] = []): TemplateParameter[] {
    return parameters.filter((p) => p.required && fieldKind(p) !== null)
}

export function initialValues(fields: readonly TemplateParameter[]): Record<string, unknown> {
    const values: Record<string, unknown> = {}
    for (const p of fields) if (p.defaultValue !== undefined) values[p.name] = p.defaultValue
    return values
}

/** Os obrigatórios ainda sem valor: com algum, o Instalar fica desativado — o servidor recusaria com 400. */
export function missingParameters(fields: readonly TemplateParameter[], values: Record<string, unknown>): string[] {
    return fields
        .filter((p) => {
            const v = values[p.name]
            if (v === undefined || v === null) return true
            return typeof v === 'string' && v.trim() === ''
        })
        .map((p) => p.name)
}
