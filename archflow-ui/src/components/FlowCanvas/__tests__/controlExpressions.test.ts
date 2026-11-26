/**
 * RCHFLW-116: a expressão de condition/switch no formato do avaliador e lendo passos que existem. O "Gerar com IA"
 * montou `ticket.urgente == true` e o pedido urgente seguia o ramo false, sem erro nem aviso.
 *
 * <p>Os casos de {@code controlExpressionCases.json} são lidos também pelo servidor (ControlExpressionRulesTest):
 * o editor não pode recusar o que o motor roda, nem deixar passar o que ele recusa.</p>
 */
import { describe, it, expect } from 'vitest'
import cases from './controlExpressionCases.json'
import {
  checkCondition, checkSwitch, checkTemplate, describeFindingForAgent, flowRefsOf, invalidControlExpressions,
  isWellFormed, templateReferences,
} from '../controlExpressions'

const refs = flowRefsOf(cases.steps.map(s => ({
  id: s.id, componentId: s.kind, type: s.kind, config: 'ioFields' in s ? { ioFields: s.ioFields } : undefined,
})))

describe('controlExpressions', () => {
  it.each(cases.cases.map(c => [c.field, c.expression, c.problems] as const))(
    '%s %s → %j (os mesmos casos do servidor)', (field, expression, problems) => {
      const found = field === 'switchExpression' ? checkSwitch(expression, refs)
        : field === 'userMessage' ? checkTemplate(expression, refs) : checkCondition(expression, refs)
      expect(found.map(f => f.problem)).toEqual(problems)
    })

  it('isWellFormed segue o avaliador: operador solto e comparação sem operando são malformados', () => {
    expect(isWellFormed('${a} == 1 && ${b} == 2')).toBe(true)
    expect(isWellFormed("${msg} == 'a && b'")).toBe(true)
    expect(isWellFormed('${a} == 1 ||')).toBe(false)
    expect(isWellFormed('== 1')).toBe(false)
    expect(isWellFormed('${a} contains')).toBe(false)
  })

  it('nomeia o nó e o campo de cada expressão recusada, e ignora o nó que não é de controle', () => {
    const invalid = invalidControlExpressions([
      { id: 'receber', componentId: 'input', type: 'input' },
      { id: 'classificar', label: 'Classificar Assunto', componentId: 'llm-chat', type: 'llm-chat',
        config: { systemPrompt: '${nada.aqui}' } },
      { id: 'urgente', label: 'É Urgente?', componentId: 'condition', type: 'condition',
        config: { conditionExpression: 'ticket.urgente == true' } },
      { id: 'rota', label: 'Rota', componentId: 'switch', type: 'SWITCH', config: { switchExpression: '${classificar.x}' } },
      { id: 'ok', componentId: 'condition', type: 'condition', config: { conditionExpression: "${classificar} contains 'URGENTE'" } },
    ])

    expect(invalid).toEqual([
      { id: 'urgente', label: 'É Urgente?', field: 'conditionExpression',
        findings: [{ problem: 'BARE_OPERAND', fragment: 'ticket.urgente' }] },
      { id: 'rota', label: 'Rota', field: 'switchExpression',
        findings: [{ problem: 'TEXT_OUTPUT_FIELD', fragment: 'classificar.x' }] },
    ])
  })

  it('o tipo vale pelo componentId, como no motor; o type llm-chat também marca saída de texto', () => {
    const refsDoNo = flowRefsOf([{ id: 'c', componentId: 'openai', type: 'llm-chat' }])
    expect(checkCondition('${c.urgente} == true', refsDoNo).map(f => f.problem)).toEqual(['TEXT_OUTPUT_FIELD'])
  })

  it('o motivo para o agente diz a forma certa, com o id e a chave a declarar na Entrada', () => {
    const [bare] = checkCondition('ticket.urgente == true', refs)
    const [unknown] = checkCondition('${pedido.urgente} == true', refs)
    const [text] = checkCondition('${classificar.urgente} == true', refs)

    expect(describeFindingForAgent(bare)).toContain('"ticket.urgente" is not a value')
    expect(describeFindingForAgent(unknown)).toContain('{"ioFields": ["pedido"]}')
    expect(describeFindingForAgent(text)).toContain("${classificar} contains 'URGENT'")
    // RCHFLW-133: o = sozinho diz ao agente a forma certa, não "reference data as ${…}".
    const [single] = checkCondition("${input} = 'x'", refs)
    expect(describeFindingForAgent(single)).toContain(`write == ("\${input} == 'x'")`)
  })

  it('a chave declarada em ioFields da Entrada vale como raiz; em outro tipo de nó, não', () => {
    const semCampo = [{ id: 'entrada', componentId: 'input', type: 'input' }]
    const comCampo = [{ id: 'entrada', componentId: 'input', type: 'input', config: { ioFields: [' ticket ', 3] } }]
    const noErrado = [{ id: 'saida', componentId: 'output', type: 'output', config: { ioFields: ['ticket'] } }]

    expect(checkCondition('${ticket.urgente} == true', flowRefsOf(semCampo)).map(f => f.problem)).toEqual(['UNKNOWN_STEP'])
    expect(checkCondition('${ticket.urgente} == true', flowRefsOf(comCampo))).toEqual([])
    expect(checkCondition('${ticket.urgente} == true', flowRefsOf(noErrado)).map(f => f.problem)).toEqual(['UNKNOWN_STEP'])
  })

  it('RCHFLW-120: a Mensagem do usuário do LLM Chat entra na mesma lista, pelo tipo do nó', () => {
    const invalid = invalidControlExpressions([
      { id: 'receber', componentId: 'input', type: 'input' },
      { id: 'responder', label: 'Responder', componentId: 'openrouter', type: 'llm-chat',
        config: { userMessage: 'Cliente: ${receber' } },
      { id: 'ok', componentId: 'llm-chat', type: 'llm-chat', config: { userMessage: '${receber}' } },
      // A chave num nó que não é de chat não é lida pelo motor.
      { id: 'agente', componentId: 'agent', type: 'agent', config: { userMessage: '${nada' } },
    ])

    expect(invalid).toEqual([{ id: 'responder', label: 'Responder', field: 'userMessage',
      findings: [{ problem: 'MALFORMED_TEMPLATE', fragment: '${receber' }] }])
    expect(describeFindingForAgent(invalid[0].findings[0])).toContain('"${receber" is not a reference')
  })

  it('templateReferences lê como o MessageTemplate do servidor: em ordem, sem repetir; ${ quebrado é o trecho', () => {
    expect(templateReferences('${a} x ${ b.c } ${a}')).toEqual({ paths: ['a', 'b.c'] })
    expect(templateReferences('custa $10 {sem chave}')).toEqual({ paths: [] })
    expect(templateReferences('${a${b}}')).toEqual({ malformed: '${a${b}' })
    expect(templateReferences('x ${}')).toEqual({ malformed: '${}' })
  })
})
