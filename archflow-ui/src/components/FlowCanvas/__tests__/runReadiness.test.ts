/**
 * O que impede o Executar do editor (RCHFLW-11, RCHFLW-92): nós sem execução e campos obrigatórios vazios, cada
 * um nomeado — o usuário precisa saber qual nó (e qual campo) corrigir.
 */
import { describe, it, expect } from 'vitest'
import type { TFunction } from 'i18next'
import {
  CONDITIONAL_REQUIRED_FIELDS, hasRunBlockers, runBlockers, runBlockersMessage, REQUIRED_NODE_FIELDS,
} from '../runReadiness'
import { NOT_EXECUTABLE_NODE_TYPES, PALETTE_NODES, isNotExecutableNode } from '../constants'
import ptBR from '../../../i18n/locales/pt-BR.json'

const t = ((k: string, o?: Record<string, string>) =>
  o ? `${k}[${Object.values(o).join('|')}]` : k) as unknown as TFunction

describe('runReadiness', () => {
  it('nó de tipo sem execução impede o run, nomeando o nó e o tipo', () => {
    const blockers = runBlockers([
      { id: 'a', label: 'Entrada', componentId: 'input', type: 'input', config: {} },
      { id: 'b', label: 'Agente de vendas', componentId: 'agent', type: 'agent', config: {} },
      { id: 'c', label: 'Laço', componentId: 'loop', type: 'loop', config: {} },
    ])

    expect(hasRunBlockers(blockers)).toBe(true)
    expect(blockers.notExecutable).toEqual([
      { id: 'b', label: 'Agente de vendas', type: 'agent' },
      { id: 'c', label: 'Laço', type: 'loop' },
    ])
    expect(runBlockersMessage(t, blockers))
      .toBe('editor.notif.runBlockedNotExecutable["Agente de vendas" (agent), "Laço" (loop)]')
  })

  it('campos que o motor exige e o nó não tem impedem o run, nomeando nó e campo', () => {
    const blockers = runBlockers([
      { id: 'c', label: 'Urgente?', componentId: 'condition', type: 'condition', config: {} },
      { id: 's', label: 'Rota', componentId: 'switch', type: 'switch', config: { switchExpression: '${d.route}', switchCases: '  ' } },
      { id: 'd', label: 'Triagem', componentId: 'decision', type: 'decision', config: { questions: {} } },
    ])

    expect(blockers.notExecutable).toEqual([])
    expect(blockers.missing).toEqual([
      { id: 'c', label: 'Urgente?', labelKey: 'editor.properties.fields.expression' },
      { id: 's', label: 'Rota', labelKey: 'editor.properties.fields.switchCases' },
      { id: 'd', label: 'Triagem', labelKey: 'editor.properties.fields.decisionQuestions' },
    ])
    expect(runBlockersMessage(t, blockers)).toBe('editor.notif.runBlockedMissing['
      + '"Urgente?" → editor.properties.fields.expression, "Rota" → editor.properties.fields.switchCases, '
      + '"Triagem" → editor.properties.fields.decisionQuestions]')
  })

  // RCHFLW-116: a condição que o "Gerar com IA" montou mandava o pedido urgente para o ramo errado, em silêncio.
  it('expressão fora do formato do avaliador ou lendo passo inexistente impede o run, nomeando nó e campo', () => {
    const blockers = runBlockers([
      { id: 'a', label: 'Receber Ticket', componentId: 'input', type: 'input' },
      { id: 'b', label: 'Classificar Assunto', componentId: 'llm-chat', type: 'llm-chat', config: {} },
      { id: 'c', label: 'É Urgente?', componentId: 'condition', type: 'condition',
        config: { conditionExpression: 'ticket.urgente == true' } },
      { id: 's', label: 'Rota', componentId: 'switch', type: 'switch',
        config: { switchExpression: '${Triagem.route}', switchCases: 'AUTO' } },
      // A anotação não é passo: o id dela não vale como referência.
      { id: 'n', componentId: 'sticky-note', type: 'sticky-note' },
      { id: 'd', label: 'Lê nota', componentId: 'condition', type: 'condition',
        config: { conditionExpression: "${n.text} == 'x'" } },
    ])

    expect(hasRunBlockers(blockers)).toBe(true)
    expect(blockers.missing).toEqual([])
    expect(blockers.invalidExpressions).toEqual([
      { id: 'c', label: 'É Urgente?', labelKey: 'editor.properties.fields.expression',
        problem: 'BARE_OPERAND', fragment: 'ticket.urgente' },
      { id: 's', label: 'Rota', labelKey: 'editor.properties.fields.switchExpression',
        problem: 'UNKNOWN_STEP', fragment: 'Triagem.route' },
      { id: 'd', label: 'Lê nota', labelKey: 'editor.properties.fields.expression',
        problem: 'UNKNOWN_STEP', fragment: 'n.text' },
    ])
    expect(runBlockersMessage(t, blockers)).toBe('editor.notif.runBlockedInvalidExpression['
      + '"É Urgente?" → editor.properties.fields.expression: editor.notif.expressionProblem.BARE_OPERAND[ticket.urgente|ticket]; '
      + '"Rota" → editor.properties.fields.switchExpression: editor.notif.expressionProblem.UNKNOWN_STEP[Triagem.route|Triagem]; '
      + '"Lê nota" → editor.properties.fields.expression: editor.notif.expressionProblem.UNKNOWN_STEP[n.text|n]]')
  })

  it('cada motivo de expressão recusada tem texto no pt-BR', () => {
    const problems = (ptBR as any).editor.notif.expressionProblem
    for (const code of ['MALFORMED', 'BARE_OPERAND', 'UNKNOWN_STEP', 'TEXT_OUTPUT_FIELD', 'NO_REFERENCE']) {
      expect(problems[code]).toContain('{{fragment}}')
    }
    // A chave a declarar na Entrada é a raiz do caminho: sem o {{root}}, o aviso não diria qual.
    expect(problems.UNKNOWN_STEP).toContain('{{root}}')
  })

  it('fluxo executável não tem bloqueio: Entrada, LLM, Condição preenchida, llm-streaming e anotação', () => {
    const blockers = runBlockers([
      { id: 'a', componentId: 'input', type: 'input' },
      { id: 'b', componentId: 'llm-chat', type: 'llm-chat', config: {} },
      { id: 'c', componentId: 'condition', type: 'condition', config: { conditionExpression: '${b} != ""' } },
      { id: 'd', componentId: 'llm-streaming', type: 'llm-streaming' },
      { id: 'n', componentId: 'sticky-note', type: 'sticky-note' },
      { id: 'q', componentId: 'decision', type: 'decision', config: { questions: { a: { type: 'noul' } } } },
    ])

    expect(hasRunBlockers(blockers)).toBe(false)
  })

  it('vale o componentId, como no motor: type "agent" com componentId de plugin roda', () => {
    expect(isNotExecutableNode({ componentId: 'research-agent', type: 'AGENT' })).toBe(false)
    expect(isNotExecutableNode({ type: 'AGENT' })).toBe(true)
    expect(runBlockers([{ id: 'x', componentId: 'research-agent', type: 'agent' }]).notExecutable).toEqual([])
  })

  it('os campos obrigatórios citam rótulos que existem no pt-BR', () => {
    const fields = (ptBR as any).editor.properties.fields
    for (const list of Object.values(REQUIRED_NODE_FIELDS)) {
      for (const f of list) expect(fields[f.labelKey.replace('editor.properties.fields.', '')]).toBeTruthy()
    }
    // E os condicionais, nas combinações que mais pedem campos.
    const configs = [{}, { vectorBackend: 'pgvector' }, { vectorBackend: 'redis' }, { vectorBackend: 'pinecone' },
      { embeddingProvider: 'local', vectorBackend: 'pgvector' }, { memoryBackend: 'redis' }]
    for (const fn of Object.values(CONDITIONAL_REQUIRED_FIELDS)) {
      for (const config of configs) {
        for (const f of fn(config)) expect(fields[f.labelKey.replace('editor.properties.fields.', '')]).toBeTruthy()
      }
    }
  })

  it('a lista sem execução só cita tipos da paleta, e o llm-streaming saiu da paleta (RCHFLW-57)', () => {
    const palette = new Set<string>(PALETTE_NODES.map(n => n.componentId))
    expect([...NOT_EXECUTABLE_NODE_TYPES].filter(t => !palette.has(t))).toEqual([])
    expect(palette.has('llm-streaming')).toBe(false)
    expect(palette.has('llm-chat')).toBe(true)
  })
})
