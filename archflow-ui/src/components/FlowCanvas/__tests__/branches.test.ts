import { describe, it, expect } from 'vitest'
import { branchOptions, defaultBranch, nextFreeBranch, renamedCase, undeclaredBranch } from '../branches'
import { connectionsForStep, connectionsFromSteps } from '../workflowSerde'

describe('branchOptions', () => {
  it('condition: true and false', () => {
    expect(branchOptions('condition', {})).toEqual(['true', 'false'])
  })

  it('switch: the declared cases plus default, deduplicated', () => {
    expect(branchOptions('switch', { switchCases: 'sales\ncomplaint\n\n sales ' }))
      .toEqual(['sales', 'complaint', 'default'])
  })

  it('switch: does not duplicate an explicit default', () => {
    expect(branchOptions('switch', { switchCases: 'a\ndefault' })).toEqual(['a', 'default'])
  })

  it('decision: the three routes', () => {
    expect(branchOptions('decision', {})).toEqual(['AUTO', 'REVIEW', 'ESCALATE'])
  })

  it('nodes that do not branch offer nothing', () => {
    expect(branchOptions('llm-chat', {})).toEqual([])
    expect(branchOptions('agent', undefined)).toEqual([])
  })
})

describe('nextFreeBranch', () => {
  it('picks the first branch not used by the edges already leaving the node', () => {
    expect(nextFreeBranch(['true', 'false'], [])).toBe('true')
    expect(nextFreeBranch(['true', 'false'], ['true'])).toBe('false')
    expect(nextFreeBranch(['AUTO', 'REVIEW', 'ESCALATE'], ['AUTO', undefined, 'ESCALATE'])).toBe('REVIEW')
  })

  it('returns undefined when all are taken or the node has no branches', () => {
    expect(nextFreeBranch(['true', 'false'], ['true', 'false'])).toBeUndefined()
    expect(nextFreeBranch([], [])).toBeUndefined()
  })
})

describe('workflowSerde — condition and branch survive the round trip', () => {
  const steps = [
    {
      id: 'triage',
      connections: [
        { sourceId: 'triage', targetId: 'auto',  branch: 'AUTO' },
        { sourceId: 'triage', targetId: 'human', branch: 'ESCALATE', condition: "${triage.confidence} < 0.5" },
        { sourceId: 'triage', targetId: 'err',   isErrorPath: true },
      ],
    },
    { id: 'auto', connections: [] },
  ]

  it('loads condition and branch into the canvas model', () => {
    const conns = connectionsFromSteps(steps)

    expect(conns).toHaveLength(3)
    expect(conns[0]).toMatchObject({ sourceId: 'triage', targetId: 'auto', branch: 'AUTO', isErrorPath: false })
    expect(conns[1]).toMatchObject({ branch: 'ESCALATE', condition: '${triage.confidence} < 0.5' })
    expect(conns[2].isErrorPath).toBe(true)
    expect(conns[2]).not.toHaveProperty('condition')
  })

  it('saves them back: nothing is lost opening and saving a flow in the designer', () => {
    const saved = connectionsForStep('triage', connectionsFromSteps(steps))

    expect(saved).toEqual([
      { sourceId: 'triage', targetId: 'auto',  isErrorPath: false, branch: 'AUTO' },
      { sourceId: 'triage', targetId: 'human', isErrorPath: false, branch: 'ESCALATE', condition: '${triage.confidence} < 0.5' },
      { sourceId: 'triage', targetId: 'err',   isErrorPath: true },
    ])
  })

  it('only the connections leaving the step are saved on it', () => {
    expect(connectionsForStep('auto', connectionsFromSteps(steps))).toEqual([])
  })

  it('tolerates steps without connections', () => {
    expect(connectionsFromSteps(undefined)).toEqual([])
    expect(connectionsFromSteps([{ id: 'x' }])).toEqual([])
  })
})

describe('defaultBranch', () => {
  it('does not pre-assign a route to edges leaving a decision', () => {
    // AUTO por padrão condicionaria o passo seguinte a `${id.route} == 'AUTO'` sem o usuário escolher.
    expect(defaultBranch('decision', {}, [])).toBeUndefined()
    expect(defaultBranch('decision', {}, ['AUTO'])).toBeUndefined()
  })

  it('keeps assigning the next free branch for condition and switch', () => {
    expect(defaultBranch('condition', {}, [])).toBe('true')
    expect(defaultBranch('condition', {}, ['true'])).toBe('false')
    expect(defaultBranch('switch', { switchCases: 'a\nb' }, ['a'])).toBe('b')
  })

  it('gives no branch to nodes that do not branch', () => {
    expect(defaultBranch('llm-chat', {}, [])).toBeUndefined()
  })
})

describe('renamedCase (RCHFLW-147)', () => {
  const cfg = (cases: string) => ({ switchCases: cases })

  it('um caso trocado na mesma posição é rename', () => {
    expect(renamedCase(cfg('defeito\narrependimento\ndefault'), cfg('defeito\narrependido\ndefault')))
      .toEqual({ from: 'arrependimento', to: 'arrependido' })
    // O default escrito ou não, não muda nada: ele é sempre um ramo.
    expect(renamedCase(cfg('ate_200\ndefault'), cfg('ate_500'))).toEqual({ from: 'ate_200', to: 'ate_500' })
  })

  it('caso apagado, inserido, dois trocados ou ordem trocada: não adivinha', () => {
    expect(renamedCase(cfg('a\nb'), cfg('a'))).toBeNull()
    expect(renamedCase(cfg('a\nb'), cfg('a\nb\nc'))).toBeNull()
    expect(renamedCase(cfg('a\nb'), cfg('x\ny'))).toBeNull()
    expect(renamedCase(cfg('a\nb'), cfg('b\na'))).toBeNull()
    expect(renamedCase(cfg('a\nb'), cfg('a\nb'))).toBeNull()
  })
})

describe('undeclaredBranch (RCHFLW-147)', () => {
  it('acusa o ramo que o nó não oferece, e só o que vale', () => {
    expect(undeclaredBranch('switch', { switchCases: 'a' }, { branch: 'b' })).toBe('b')
    expect(undeclaredBranch('switch', { switchCases: 'a' }, { branch: 'default' })).toBeNull()
    expect(undeclaredBranch('switch', { switchCases: 'a' }, { branch: 'b', condition: 'x' })).toBeNull()
    expect(undeclaredBranch('switch', { switchCases: 'a' }, { branch: 'b', isErrorPath: true })).toBeNull()
    expect(undeclaredBranch('decision', {}, { branch: 'auto' })).toBe('auto')
    expect(undeclaredBranch('llm-chat', {}, { branch: 'qualquer' })).toBeNull()
  })

  it('compara o ramo sem apóstrofos, como a BranchCondition e o ExecutableNodesValidator', () => {
    // A aresta "d'água" vira `== 'dágua'`: o caso "d'água" nunca a alcança, e o servidor recusa o fluxo.
    expect(undeclaredBranch('switch', { switchCases: "d'água" }, { branch: "d'água" })).toBe("d'água")
    expect(undeclaredBranch('switch', { switchCases: 'dágua' }, { branch: "d'água" })).toBeNull()
  })
})
