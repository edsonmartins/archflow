import { describe, it, expect } from 'vitest'
import { branchOptions, nextFreeBranch } from '../branches'
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
