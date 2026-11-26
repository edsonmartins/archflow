import { describe, expect, it } from 'vitest'
import { referencedInputFields } from '../controlExpressions'

/*
 * RCHFLW-189 (2): o copiloto declara ioFields em toda Entrada, e o Executar avisava "a Entrada declara os campos
 * pergunta, e esta entrada não os traz" num fluxo Entrada → llm-chat que nada compara — a execução terminava
 * normalmente. O aviso vale só para os campos que alguma referência lê.
 */
describe('referencedInputFields — os campos da Entrada que o fluxo lê', () => {
  const input = { id: 'in', componentId: 'input', type: 'input', config: { ioFields: ['pergunta', 'valor'] } }

  it('Entrada → llm-chat sem referência ao campo: nenhum campo esperado', () => {
    const steps = [input, { id: 'chat', componentId: 'llm-chat', type: 'llm-chat', config: { systemPrompt: 'x' } }]

    expect(referencedInputFields(steps)).toEqual([])
  })

  it('campo lido por condition, switch, userMessage ou state do decision: entra', () => {
    const cond = { id: 'c', type: 'condition', config: { conditionExpression: '${valor.total} > 500' } }
    const chat = { id: 'chat', type: 'llm-chat', config: { userMessage: 'Pergunta: ${pergunta}' } }

    expect(referencedInputFields([input, cond])).toEqual(['valor'])
    expect(referencedInputFields([input, chat])).toEqual(['pergunta'])
    expect(referencedInputFields([input, { id: 's', type: 'switch', config: { switchExpression: '${pergunta}' } }]))
      .toEqual(['pergunta'])
    expect(referencedInputFields([input, {
      id: 'd', componentId: 'decision', type: 'decision', config: { state: { ticket: '${valor}' } },
    }])).toEqual(['valor'])
  })

  it('o systemPrompt e os textos da aprovação não resolvem referências: não contam', () => {
    const chat = { id: 'chat', type: 'llm-chat', config: { systemPrompt: 'Use ${pergunta}' } }
    const approval = { id: 'a', type: 'approval', config: { approvalProposal: '${valor}' } }

    expect(referencedInputFields([input, chat, approval])).toEqual([])
  })

  // O gate canônico do RCHFLW-123 é ${entrada.valor} > 500: a Entrada repassa o JSON, e ${input.valor} lê o objeto
  // que RunInputSeeding grava em input. Contar só a raiz apagava o aviso justamente nesses fluxos.
  it('campo lido pelo id da Entrada ou por ${input.campo}: entra', () => {
    const viaEntrada = { id: 'c', type: 'condition', config: { conditionExpression: '${in.valor} > 500' } }
    const viaInput = { id: 'c', type: 'condition', config: { conditionExpression: '${input.valor} > 500' } }
    const viaOutroPasso = { id: 'c', type: 'condition', config: { conditionExpression: '${c.valor} > 500' } }

    expect(referencedInputFields([input, viaEntrada])).toEqual(['valor'])
    expect(referencedInputFields([input, viaInput])).toEqual(['valor'])
    // O mesmo nome sob outro passo é a saída desse passo, não o campo da entrada.
    expect(referencedInputFields([input, viaOutroPasso])).toEqual([])
  })

  it('condition escrita à mão numa aresta (steps[].connections) também lê o campo', () => {
    expect(referencedInputFields([input], ['${valor} > 500'])).toEqual(['valor'])
    expect(referencedInputFields([input], ['${in.pergunta} contains \'troca\''])).toEqual(['pergunta'])
    expect(referencedInputFields([input], [])).toEqual([])
  })

  it('referência a um passo, não a um campo declarado: nenhum campo esperado', () => {
    const cond = { id: 'c', type: 'condition', config: { conditionExpression: "${in} contains 'x'" } }

    expect(referencedInputFields([input, cond])).toEqual([])
  })
})
