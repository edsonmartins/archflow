/**
 * RCHFLW-155 — a decisão que falha (decision nulo, route ESCALATE) tem de chegar ao humano quando o nó de controle
 * depois dela já manda algum ramo para a aprovação.
 */
import { describe, it, expect } from 'vitest'
import {
  failedDecisionMissesHuman, switchCasesWithoutEdge, type HumanPathConnection, type HumanPathStep,
} from '../humanPath'

const s = (id: string, componentId: string, config: Record<string, unknown> = {}): HumanPathStep =>
  ({ id, componentId, label: id, config })
const c = (sourceId: string, targetId: string, branch?: string): HumanPathConnection =>
  ({ sourceId, targetId, ...(branch ? { branch } : {}) })

const base = [s('in', 'input'), s('d', 'decision'), s('ap', 'approval'), s('auto', 'llm-chat')]

describe('failedDecisionMissesHuman — switch sobre a decisão', () => {
  const sw = s('sw', 'switch', { switchExpression: '${d.decision}', switchCases: 'alta\nmedia\nbaixa' })

  it('default na resposta automática e alta na aprovação: falha (o ticket)', () => {
    const steps = [...base, sw]
    const conns = [c('in', 'd'), c('d', 'sw'), c('sw', 'ap', 'alta'), c('sw', 'auto', 'default')]
    expect(failedDecisionMissesHuman(steps, conns).map(x => x.id)).toEqual(['sw'])
  })

  it('sem aresta default: falha também (o valor nulo não tem para onde ir)', () => {
    const conns = [c('d', 'sw'), c('sw', 'ap', 'alta'), c('sw', 'auto', 'media'), c('sw', 'auto', 'baixa')]
    expect(failedDecisionMissesHuman([...base, sw], conns).map(x => x.id)).toEqual(['sw'])
  })

  it('alta e default na aprovação, media e baixa na resposta: certo', () => {
    const conns = [c('d', 'sw'), c('sw', 'ap', 'alta'), c('sw', 'ap', 'default'), c('sw', 'auto', 'media'),
      c('sw', 'auto', 'baixa')]
    expect(failedDecisionMissesHuman([...base, sw], conns)).toEqual([])
  })

  it('default que chega à aprovação por um passo intermediário: certo', () => {
    const steps = [...base, sw, s('resumo', 'llm-chat')]
    const conns = [c('d', 'sw'), c('sw', 'ap', 'alta'), c('sw', 'resumo', 'default'), c('resumo', 'ap')]
    expect(failedDecisionMissesHuman(steps, conns)).toEqual([])
  })

  it('switch sem nenhum ramo para o humano não é cobrado (não há caminho humano a inventar)', () => {
    const conns = [c('d', 'sw'), c('sw', 'auto', 'alta'), c('sw', 'auto', 'default')]
    expect(failedDecisionMissesHuman([...base.filter(x => x.id !== 'ap'), sw], conns)).toEqual([])
    expect(failedDecisionMissesHuman([...base, sw], conns)).toEqual([])
  })

  it('switch que não lê uma decisão fica de fora', () => {
    const other = s('sw', 'switch', { switchExpression: '${in.tipo}', switchCases: 'a' })
    const conns = [c('sw', 'ap', 'a'), c('sw', 'auto', 'default')]
    expect(failedDecisionMissesHuman([...base, other], conns)).toEqual([])
  })
})

describe('failedDecisionMissesHuman — condition sobre a decisão', () => {
  it("${D.decision} == 'alta' sem o || ESCALATE, com true na aprovação: a falha vai para o false (automático)", () => {
    const cond = s('cd', 'condition', { conditionExpression: "${d.decision} == 'alta'" })
    const conns = [c('d', 'cd'), c('cd', 'ap', 'true'), c('cd', 'auto', 'false')]
    expect(failedDecisionMissesHuman([...base, cond], conns).map(x => x.id)).toEqual(['cd'])
  })

  it("com || ${D.route} == 'ESCALATE' a falha vai para o true (aprovação): certo", () => {
    const cond = s('cd', 'condition', {
      conditionExpression: "${d.answers.urgente.result} == true || ${d.route} == 'ESCALATE'",
    })
    const conns = [c('d', 'cd'), c('cd', 'ap', 'true'), c('cd', 'auto', 'false')]
    expect(failedDecisionMissesHuman([...base, cond], conns)).toEqual([])
  })

  it('aprovação no false (o automático é o caso explícito): a falha já vai ao humano', () => {
    const cond = s('cd', 'condition', { conditionExpression: "${d.decision} == 'baixa'" })
    const conns = [c('d', 'cd'), c('cd', 'auto', 'true'), c('cd', 'ap', 'false')]
    expect(failedDecisionMissesHuman([...base, cond], conns)).toEqual([])
  })
})

describe('failedDecisionMissesHuman — o caminho da falha é simulado, não presumido por forma (revisão)', () => {
  const sw = s('sw', 'switch', { switchExpression: '${d.decision}', switchCases: 'alta\nmedia\nbaixa' })

  it('default para a aprovação E para a resposta: o motor segue as duas, a falha ainda responde sozinha', () => {
    const conns = [c('d', 'sw'), c('sw', 'ap', 'alta'), c('sw', 'ap', 'default'), c('sw', 'auto', 'media'),
      c('sw', 'auto', 'default')]
    expect(failedDecisionMissesHuman([...base, sw], conns).map(x => x.id)).toEqual(['sw'])
  })

  it('portão ESCALATE: a falha vai direto à aprovação e o switch (só no AUTO) nem roda', () => {
    const conns = [c('in', 'd'), c('d', 'ap', 'ESCALATE'), c('d', 'sw', 'AUTO'), c('sw', 'ap', 'alta'),
      c('sw', 'auto', 'default')]
    expect(failedDecisionMissesHuman([...base, sw], conns)).toEqual([])
  })

  it('portão com o REVIEW no switch: a falha (ESCALATE) não passa por ele', () => {
    const conns = [c('d', 'ap', 'ESCALATE'), c('d', 'sw', 'REVIEW'), c('sw', 'ap', 'alta'), c('sw', 'auto', 'default')]
    expect(failedDecisionMissesHuman([...base, sw], conns)).toEqual([])
  })

  it('o switch também ligado sem ramo à decisão continua cobrado (a falha chega a ele)', () => {
    const conns = [c('d', 'ap', 'ESCALATE'), c('d', 'sw'), c('sw', 'ap', 'alta'), c('sw', 'auto', 'default')]
    expect(failedDecisionMissesHuman([...base, sw], conns).map(x => x.id)).toEqual(['sw'])
  })

  it('${D.confidence} < 0.7 com o true na aprovação: a confiança 0 da falha vai ao true — certo', () => {
    const cond = s('cd', 'condition', { conditionExpression: '${d.confidence} < 0.7' })
    const conns = [c('d', 'cd'), c('cd', 'ap', 'true'), c('cd', 'auto', 'false')]
    expect(failedDecisionMissesHuman([...base, cond], conns)).toEqual([])
  })

  it("${D.decision} != 'alta' com o true na resposta: nulo é diferente de 'alta', a falha vai à resposta", () => {
    const cond = s('cd', 'condition', { conditionExpression: "${d.decision} != 'alta'" })
    const conns = [c('d', 'cd'), c('cd', 'auto', 'true'), c('cd', 'ap', 'false')]
    expect(failedDecisionMissesHuman([...base, cond], conns).map(x => x.id)).toEqual(['cd'])
  })

  it('condição que depende de outro dado não é cobrada: não se sabe para onde a falha vai', () => {
    const cond = s('cd', 'condition', { conditionExpression: "${d.decision} == 'alta' || ${in.vip} == true" })
    const conns = [c('d', 'cd'), c('cd', 'ap', 'true'), c('cd', 'auto', 'false')]
    expect(failedDecisionMissesHuman([...base, cond], conns)).toEqual([])
  })
})

describe('switchCasesWithoutEdge (RCHFLW-149)', () => {
  const sw = s('sw', 'switch', { switchExpression: '${d.decision}', switchCases: 'alta\nmedia\nbaixa' })

  it('os casos declarados que ficaram sem aresta', () => {
    const conns = [c('sw', 'ap', 'alta'), c('sw', 'ap', 'default')]
    expect(switchCasesWithoutEdge([...base, sw], conns).map(x => [x.step.id, x.cases]))
      .toEqual([['sw', ['media', 'baixa']]])
  })

  it('todos os casos ligados, ou switch ainda sem aresta (o nó solto tem o seu aviso): nada', () => {
    const conns = [c('sw', 'ap', 'alta'), c('sw', 'auto', 'media'), c('sw', 'auto', 'baixa'), c('sw', 'ap', 'default')]
    expect(switchCasesWithoutEdge([...base, sw], conns)).toEqual([])
    expect(switchCasesWithoutEdge([...base, sw], [])).toEqual([])
  })
})
