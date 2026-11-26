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
import en from '../../../i18n/locales/en.json'

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
      { id: 'd', label: 'Triagem', componentId: 'decision', type: 'decision', config: { provider: 'llm', questions: {} } },
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

  // RCHFLW-133: com `${input} = 'x'` a mensagem mandava escrever ${…} a quem já tinha escrito e não dizia "use ==".
  it('= sozinho na condição: o aviso sugere == com a expressão corrigida, nos dois idiomas', () => {
    const blockers = runBlockers([
      { id: 'c', label: 'Confere', componentId: 'condition', type: 'condition',
        config: { conditionExpression: "${input} = 'x'" } },
    ])
    expect(blockers.invalidExpressions).toEqual([{ id: 'c', label: 'Confere',
      labelKey: 'editor.properties.fields.expression', problem: 'SINGLE_EQUALS', fragment: "${input} = 'x'" }])

    const real = (locale: any) => ((k: string, o?: Record<string, string>) => {
      const raw = k.split('.').reduce((acc: any, part) => acc?.[part], locale) as string
      return raw.replace(/\{\{(\w+)\}\}/g, (_: string, name: string) => o?.[name] ?? '')
    }) as unknown as TFunction
    const pt = runBlockersMessage(real(ptBR), blockers)
    expect(pt).toContain("«${input} = 'x'» usa = sozinho, que não compara — para comparar, use == («${input} == 'x'»)")
    expect(pt).not.toContain('não é um valor')
    expect(runBlockersMessage(real(en), blockers)).toContain("to compare, use == («${input} == 'x'»)")
  })

  it('RCHFLW-120: a Mensagem do usuário do LLM Chat que não resolve impede o run, nomeando nó e campo', () => {
    const triagem = (userMessage: unknown, type = 'llm-chat') => runBlockers([
      { id: 'receber', label: 'Receber pedido', componentId: 'input', type: 'input' },
      { id: 'classificar', label: 'Classificar', componentId: 'llm-chat', type: 'llm-chat', config: {} },
      { id: 'responder', label: 'Responder com LLM', componentId: type, type, config: { userMessage } },
    ])

    const blockers = triagem('Cliente: ${receber} ${Receber pedido.texto} ${classificar.x}')
    expect(hasRunBlockers(blockers)).toBe(true)
    expect(blockers.invalidExpressions).toEqual([
      { id: 'responder', label: 'Responder com LLM', labelKey: 'editor.properties.fields.userMessage',
        problem: 'UNKNOWN_STEP', fragment: 'Receber pedido.texto' },
      { id: 'responder', label: 'Responder com LLM', labelKey: 'editor.properties.fields.userMessage',
        problem: 'TEXT_OUTPUT_FIELD', fragment: 'classificar.x' },
    ])
    expect(runBlockersMessage(t, triagem('${receber', 'llm-streaming'))).toBe('editor.notif.runBlockedInvalidExpression['
      + '"Responder com LLM" → editor.properties.fields.userMessage: '
      + 'editor.notif.expressionProblem.MALFORMED_TEMPLATE[${receber|${receber]]')
    // Vazio, só texto, a Entrada e ${input} rodam; um valor que não é texto não é template (o motor o ignora).
    for (const ok of ['', '  ', 'Responda com cordialidade.', '${receber}', 'Cliente: ${receber}', '${input}', 42]) {
      expect(hasRunBlockers(triagem(ok))).toBe(false)
    }
    // Num nó que não é de chat a chave não é lida — e não é recusada.
    expect(hasRunBlockers(runBlockers([
      { id: 'fim', label: 'Fim', componentId: 'output', type: 'output', config: { userMessage: '${nada' } },
    ]))).toBe(false)
  })

  it('cada motivo de expressão recusada tem texto no pt-BR', () => {
    const problems = (ptBR as any).editor.notif.expressionProblem
    for (const code of ['MALFORMED', 'BARE_OPERAND', 'UNKNOWN_STEP', 'TEXT_OUTPUT_FIELD', 'NO_REFERENCE',
      'SINGLE_EQUALS', 'MALFORMED_TEMPLATE']) {
      expect(problems[code]).toContain('{{fragment}}')
      expect((en as any).editor.notif.expressionProblem[code]).toContain('{{fragment}}')
    }
    expect((ptBR as any).editor.properties.fields.userMessage).toBe('Mensagem do usuário')
    expect((en as any).editor.properties.fields.userMessage).toBe('User message')
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
      // Com o provider llm (o LLM da plataforma): o padrão, http-decisions, exigiria o model (RCHFLW-116).
      { id: 'q', componentId: 'decision', type: 'decision', config: { provider: 'llm', questions: { a: { type: 'noul' } } } },
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
      { embeddingProvider: 'local', vectorBackend: 'pgvector' }, { memoryBackend: 'redis' },
      { memoryBackend: 'redis', operation: 'add' }]
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

/**
 * RCHFLW-116 (2ª rodada): o "Gerar com IA" montou um decision sem provider nem model e o editor deixou executar. O
 * padrão do motor é o http-decisions, que recusa a chamada sem o model; com onError ESCALATE, todo pedido — urgente ou
 * comum — saiu pela escalada e o fluxo terminou COMPLETED. As regras vêm do DecisionComponent (initialize,
 * parseQuestions), de Question/RoutePolicy e dos provedores.
 */
describe('runReadiness — decision que o motor recusa', () => {
  const decision = (id: string, config: Record<string, unknown>) =>
    ({ id, label: id, componentId: 'decision', type: 'decision', config })
  const assunto = { assunto: { type: 'choice', criteria: { financeiro: 'f', suporte_tecnico: 's', outro: 'o' } } }

  it('o nó do defeito: sem provider (http-decisions) nem model, o Executar é impedido nomeando o campo Modelo', () => {
    const blockers = runBlockers([decision('Triagem', { questions: assunto, primary: 'assunto' })])

    expect(hasRunBlockers(blockers)).toBe(true)
    expect(blockers.missing).toEqual([{ id: 'Triagem', label: 'Triagem', labelKey: 'editor.properties.fields.decisionModel' }])
    expect(runBlockersMessage(t, blockers))
      .toBe('editor.notif.runBlockedMissing["Triagem" → editor.properties.fields.decisionModel]')
  })

  it('o model só é exigido pelos provedores que o exigem; llm (LLM da plataforma) e rules rodam sem ele', () => {
    const model = (config: Record<string, unknown>) =>
      runBlockers([decision('d', { questions: assunto, ...config })]).missing.map(m => m.labelKey)
    const modelo = ['editor.properties.fields.decisionModel']

    expect(model({ provider: 'http-decisions' })).toEqual(modelo)
    expect(model({ provider: 'langchain4j-decisions', model: '  ' })).toEqual(modelo)
    expect(model({ provider: 'http-decisions', model: 'typesafe/jev-1.13' })).toEqual([])
    expect(model({ provider: 'llm' })).toEqual([])
    expect(model({ provider: 'rules', providerOptions: { rules: { rules: { assunto: { otherwise: 'outro' } } } } }))
      .toEqual([])
  })

  it('pergunta sem tipo, sem critérios ou que não é objeto: cada uma nomeada, como o componente a recusaria', () => {
    const blockers = runBlockers([decision('d', {
      provider: 'llm',
      questions: {
        semTipo: { instructions: 'É urgente?' },
        tipoErrado: { type: 'yes-no' },
        choiceSemCriterios: { type: 'choice' },
        choiceEmLista: { type: 'choice', criteria: ['a', 'b'] },
        scoreCurto: { type: 'score', criteria: ['só um'] },
        texto: 'é urgente?',
      },
    })])

    expect(blockers.missing).toEqual([])
    expect(blockers.invalidDecisions!.map(d => [d.problem, d.subject, d.labelKey])).toEqual([
      ['QUESTION_TYPE', 'semTipo', 'editor.properties.fields.decisionQuestions'],
      ['QUESTION_TYPE', 'tipoErrado', 'editor.properties.fields.decisionQuestions'],
      ['CHOICE_CRITERIA', 'choiceSemCriterios', 'editor.properties.fields.decisionQuestions'],
      ['CHOICE_CRITERIA', 'choiceEmLista', 'editor.properties.fields.decisionQuestions'],
      ['SCORE_CRITERIA', 'scoreCurto', 'editor.properties.fields.decisionQuestions'],
      ['QUESTION_NOT_OBJECT', 'texto', 'editor.properties.fields.decisionQuestions'],
    ])
    expect(runBlockersMessage(t, { notExecutable: [], missing: [], invalidDecisions: blockers.invalidDecisions!.slice(0, 1) }))
      .toBe('editor.notif.runBlockedInvalidDecision['
        + '"d" → editor.properties.fields.decisionQuestions: editor.notif.decisionProblem.QUESTION_TYPE[semTipo]]')
  })

  it('o resto que o initialize recusa: perguntas em lista, primary/gate fora das perguntas, limiares e escolhas', () => {
    const problems = (config: Record<string, unknown>) =>
      runBlockers([decision('d', { provider: 'llm', ...config })]).invalidDecisions!.map(d => `${d.problem}:${d.subject}`)

    expect(problems({ questions: [{ id: 'urgente', type: 'noul' }] })).toEqual(['QUESTIONS_NOT_OBJECT:'])
    expect(problems({ questions: assunto, primary: 'urgente', gate: ['assunto', 'urgente'] }))
      .toEqual(['UNKNOWN_GATE:urgente', 'UNKNOWN_PRIMARY:urgente'])
    expect(problems({ questions: assunto, onError: 'IGNORE' })).toEqual(['ON_ERROR:IGNORE'])
    expect(problems({ questions: assunto, thresholds: { auto: 0.4, review: 0.6 } }))
      .toEqual(['THRESHOLDS:auto=0.4, review=0.6'])
    // Sem auto, o padrão (0,9) vale: review 0,95 > 0,9.
    expect(problems({ questions: assunto, thresholds: { review: 0.95 } })).toEqual(['THRESHOLDS:auto=0.9, review=0.95'])
    expect(problems({ questions: assunto, acceptAt: 1.5 })).toEqual(['ACCEPT_AT:1.5'])
    expect(problems({ questions: assunto, consensus: { onDisagree: 'AUTO' } })).toEqual(['ON_DISAGREE:AUTO'])
    // O rules recusa a pergunta sem regra ("sem regras para a pergunta") — e o nó escalaria sempre.
    expect(problems({ provider: 'rules', questions: { ...assunto, urgente: { type: 'noul' } },
      providerOptions: { rules: { rules: { assunto: { otherwise: 'outro' } } } } })).toEqual(['RULES_MISSING:urgente'])
  })

  it('cascade/fallbacks que respondem no lugar do principal: o motor marca o principal FAILED e decide, não trava',
    () => {
      const blockers = (config: Record<string, unknown>) => {
        const b = runBlockers([decision('d', { questions: { ...assunto, urgente: { type: 'noul' } }, ...config })])
        return [...b.missing.map(m => m.labelKey), ...b.invalidDecisions!.map(d => `${d.problem}:${d.subject}`)]
      }
      const soAssunto = { rules: { rules: { assunto: { otherwise: 'outro' } } } }
      const modelo = 'editor.properties.fields.decisionModel'

      // rules sem a regra de urgente, com o llm na cascata (a cascata típica rules → llm).
      expect(blockers({ provider: 'rules', providerOptions: soAssunto, cascade: [{ provider: 'llm' }] })).toEqual([])
      // http-decisions sem model, com o llm de reserva (comReserva).
      expect(blockers({ provider: 'http-decisions', fallbacks: [{ provider: 'llm' }] })).toEqual([])
      // Um degrau com as regras completas nas próprias options também responde.
      expect(blockers({ provider: 'http-decisions', cascade: [{ provider: 'rules', options: { rules: {
        assunto: { otherwise: 'outro' }, urgente: { otherwise: false } } } }] })).toEqual([])

      // Degraus que também são recusados não salvam o nó: escalaria sempre.
      expect(blockers({ provider: 'http-decisions', fallbacks: [{ provider: 'langchain4j-decisions' }] }))
        .toEqual([modelo])
      expect(blockers({ provider: 'http-decisions', cascade: [{ model: 'sem-provider' }] })).toEqual([modelo])
      expect(blockers({ provider: 'rules', providerOptions: soAssunto, fallbacks: [{ provider: 'rules' }] }))
        .toEqual(['RULES_MISSING:urgente'])
      // As options do degrau substituem a chave rules do nó inteira (tentar junta chave a chave).
      expect(blockers({ provider: 'http-decisions', cascade: [{ provider: 'rules', options: { rules: 'x' } }],
        providerOptions: { rules: { rules: { assunto: {}, urgente: {} } } } })).toEqual([modelo])
    })

  it('o que o motor aceita não é recusado: caixa do tipo, noul sem critérios, sinônimos, chaves extras', () => {
    const blockers = runBlockers([decision('d', {
      provider: 'llm',
      questions: {
        a: { type: 'Choice', criteria: { x: '' } },
        b: { type: 'noul' },
        c: { type: 'boolean', criteria: { true: 'sim' } },
        d: { type: 'yesno', instructions: 'x', extra: 1 },
        e: { type: 'score', criteria: ['baixa', 'alta'] },
      },
      primary: 'e', gate: ['b'], onError: 'fail', thresholds: { auto: 0.8, review: 0.4 }, acceptAt: 0.7,
      consensus: { onDisagree: 'escalate' }, timeoutMs: 5000,
    })])

    expect(hasRunBlockers(blockers)).toBe(false)
  })

  it('cada motivo de decisão recusada tem texto nos dois idiomas, com o assunto quando há um', () => {
    const codes = ['QUESTIONS_NOT_OBJECT', 'QUESTION_ID', 'QUESTION_NOT_OBJECT', 'QUESTION_TYPE', 'CHOICE_CRITERIA',
      'SCORE_CRITERIA', 'UNKNOWN_PRIMARY', 'UNKNOWN_GATE', 'ON_ERROR', 'THRESHOLDS', 'ACCEPT_AT', 'ON_DISAGREE',
      'RULES_MISSING']
    for (const locale of [ptBR, en] as any[]) {
      expect(locale.editor.notif.runBlockedInvalidDecision).toContain('{{fields}}')
      for (const code of codes) expect(locale.editor.notif.decisionProblem[code]).toBeTruthy()
      for (const code of codes.filter(c => c !== 'QUESTIONS_NOT_OBJECT' && c !== 'QUESTION_ID')) {
        expect(locale.editor.notif.decisionProblem[code]).toContain('{{subject}}')
      }
    }
    // Os campos que a mensagem nomeia existem no painel.
    const fields = (ptBR as any).editor.properties.fields
    for (const key of ['decisionQuestions', 'decisionPrimary', 'decisionGate', 'decisionOnError', 'thresholdAuto',
      'decisionAcceptAt', 'decisionOnDisagree', 'decisionRules', 'decisionModel']) {
      expect(fields[key]).toBeTruthy()
    }
  })
})

describe('runReadiness — regras das arestas', () => {
  const sw = (cases: string) => ({
    id: 'sw', label: 'Motivo', componentId: 'switch', type: 'switch',
    config: { switchExpression: '${input}', switchCases: cases },
  })
  const out = (id: string, label: string) => ({ id, label, componentId: 'output', type: 'output', config: {} })

  it('RCHFLW-147: aresta num caso renomeado impede o run, nomeando origem, destino, ramo e os ramos que existem', () => {
    const blockers = runBlockers([sw('defeito\narrependido'), out('r', 'Resposta ao cliente')],
      [{ sourceId: 'sw', targetId: 'r', branch: 'arrependimento' }])

    expect(hasRunBlockers(blockers)).toBe(true)
    expect(blockers.invalidBranches).toEqual([{
      id: 'sw', label: 'Motivo', target: 'Resposta ao cliente', branch: 'arrependimento',
      options: ['defeito', 'arrependido', 'default'],
    }])
    expect(runBlockersMessage(t, blockers)).toBe('editor.notif.runBlockedInvalidBranch['
      + 'editor.notif.invalidBranchEdge[Motivo|Resposta ao cliente|arrependimento|defeito, arrependido, default]]')
  })

  it('ramo declarado, default implícito, condição à mão e aresta de erro não impedem', () => {
    const blockers = runBlockers([sw('defeito'), out('r', 'R')], [
      { sourceId: 'sw', targetId: 'r', branch: 'defeito' },
      { sourceId: 'sw', targetId: 'r', branch: 'default' },
      { sourceId: 'sw', targetId: 'r', branch: 'velho', condition: "${a.x} == 'y'" },
      { sourceId: 'sw', targetId: 'r', branch: 'velho', isErrorPath: true },
    ])
    expect(hasRunBlockers(blockers)).toBe(false)
  })

  it('RCHFLW-188 (13): conexão para um passo que não existe impede o run', () => {
    const blockers = runBlockers([out('a', 'Saída')], [{ sourceId: 'a', targetId: 'node-6' }])
    expect(blockers.danglingConnections).toEqual([{ id: 'a', label: 'Saída', target: 'node-6' }])
    expect(runBlockersMessage(t, blockers)).toBe('editor.notif.runBlockedDanglingConnection["Saída" → node-6]')
  })

  it('RCHFLW-188 (12): ciclo impede o run, nomeando os passos na ordem das arestas', () => {
    const steps = [out('e', 'Entrada'), out('d', 'Decisão'), out('ap', 'Aprovação'), out('l', 'LLM Chat')]
    const blockers = runBlockers(steps, [
      { sourceId: 'e', targetId: 'd' }, { sourceId: 'd', targetId: 'ap' },
      { sourceId: 'ap', targetId: 'l' }, { sourceId: 'l', targetId: 'd' },
    ])
    expect(blockers.cycle).toEqual(['Decisão', 'Aprovação', 'LLM Chat', 'Decisão'])
    expect(runBlockersMessage(t, blockers))
      .toBe('editor.notif.runBlockedCycle["Decisão" → "Aprovação" → "LLM Chat" → "Decisão"]')
  })

  it('sem as conexões, as regras das arestas não rodam (quem só tem os passos)', () => {
    expect(runBlockers([sw('a'), out('r', 'R')])).toEqual(
      { notExecutable: [], missing: [], invalidExpressions: [], invalidDecisions: [] })
  })

  it('as frases novas existem nos dois idiomas', () => {
    for (const locale of [ptBR, en]) {
      const notif = (locale as { editor: { notif: Record<string, unknown> } }).editor.notif
      for (const k of ['runBlockedInvalidBranch', 'invalidBranchEdge', 'runBlockedDanglingConnection', 'runBlockedCycle']) {
        expect(typeof notif[k]).toBe('string')
      }
    }
  })
})
