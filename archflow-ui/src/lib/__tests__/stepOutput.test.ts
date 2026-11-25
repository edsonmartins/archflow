import { describe, it, expect } from 'vitest'
import {
  NOT_EXECUTED, buildStepRows, formatStepOutput, parseStepOutput, stepOutputsFromExecution,
} from '../stepOutput'
import { isTerminalExecutionStatus } from '../executionStatus'

describe('parseStepOutput', () => {
  it('devolve o objeto quando o texto gravado é JSON', () => {
    expect(parseStepOutput('{"a":1,"b":[true]}')).toEqual({ a: 1, b: [true] })
    expect(parseStepOutput('[1,2]')).toEqual([1, 2])
    expect(parseStepOutput('42')).toBe(42)
  })

  it('mantém texto puro e JSON truncado como texto', () => {
    expect(parseStepOutput('olá mundo')).toBe('olá mundo')
    expect(parseStepOutput('true story')).toBe('true story')
    const truncated = '{"a":"' + 'x'.repeat(10) + '…(truncado)'
    expect(parseStepOutput(truncated)).toBe(truncated)
  })
})

describe('formatStepOutput', () => {
  it('texto sai como está; objeto sai indentado', () => {
    expect(formatStepOutput('linha "com aspas"')).toBe('linha "com aspas"')
    expect(formatStepOutput({ a: 1 })).toBe('{\n  "a": 1\n}')
  })
})

describe('stepOutputsFromExecution', () => {
  it('mapeia stepId → saída só dos passos que gravaram output', () => {
    const record = {
      steps: [
        { stepId: 'a', status: 'COMPLETED', output: '{"r":1}' },
        { stepId: 'b', status: 'FAILED', error: 'x' },
        { stepId: 'c', status: 'COMPLETED', output: 'texto' },
      ],
    }
    expect(stepOutputsFromExecution(record)).toEqual({ a: { r: 1 }, c: 'texto' })
  })

  it('tolera registro sem steps', () => {
    expect(stepOutputsFromExecution(null)).toEqual({})
    expect(stepOutputsFromExecution({})).toEqual({})
  })
})

describe('buildStepRows', () => {
  const doc = [
    { id: 'node-1', label: 'Entrada' },
    { id: 'node-2', operation: 'Classificar' },
    { id: 'node-3', label: 'Ramo não tomado' },
    { id: 'node-4' },
  ]

  it('dá nome aos registros e acrescenta os passos sem registro como não executados', () => {
    const rows = buildStepRows([
      { stepId: 'node-2', status: 'COMPLETED', stepIndex: 1 },
      { stepId: 'node-1', status: 'COMPLETED', stepIndex: 0 },
    ], doc, true)

    expect(rows.map(r => [r.stepId, r.label, r.status, r.notExecuted ?? false])).toEqual([
      ['node-1', 'Entrada', 'COMPLETED', false],
      ['node-2', 'Classificar', 'COMPLETED', false],
      ['node-3', 'Ramo não tomado', NOT_EXECUTED, true],
      ['node-4', undefined, NOT_EXECUTED, true],
    ])
  })

  it('não marca não executado enquanto a execução roda, e funciona sem o documento', () => {
    const records = [{ stepId: 'node-1', status: 'RUNNING' }]
    expect(buildStepRows(records, doc, false)).toHaveLength(1)
    expect(buildStepRows(records, null, true)).toEqual([{ stepId: 'node-1', status: 'RUNNING', label: undefined }])
  })
})

describe('buildStepRows — nome do passo', () => {
  it('segue a ordem do editor: label → operation → componentId → type', () => {
    const doc = [
      { id: 'a', label: 'Rótulo', operation: 'op', componentId: 'comp', type: 'llm-chat' },
      { id: 'b', label: '  ', operation: 'op', componentId: 'comp' },
      { id: 'c', componentId: 'comp', type: 'llm-chat' },
      { id: 'd', type: 'decision' },
      // `name` não é lido pelo editor ao carregar — o detalhe também não usa.
      { id: 'e', name: 'ignorado' },
    ]
    const rows = buildStepRows([], doc, true)
    expect(rows.map(r => [r.stepId, r.label])).toEqual([
      ['a', 'Rótulo'], ['b', 'op'], ['c', 'comp'], ['d', 'decision'], ['e', undefined],
    ])
  })
})

describe('isTerminalExecutionStatus', () => {
  it('só os status terminais do backend contam como fim', () => {
    for (const s of ['COMPLETED', 'FAILED', 'STOPPED', 'CANCELLED']) {
      expect(isTerminalExecutionStatus(s)).toBe(true)
    }
    // Pausa para aprovação humana não é RUNNING, mas também não acabou.
    for (const s of ['RUNNING', 'PAUSED', 'AWAITING_APPROVAL', 'INITIALIZED', '', undefined, null]) {
      expect(isTerminalExecutionStatus(s)).toBe(false)
    }
  })
})

describe('buildStepRows — anotações do canvas (RCHFLW-12)', () => {
  it('nota, quadro e separador não aparecem como "não executado": o motor nem os vê', () => {
    const doc = [
      { id: 'decide', componentId: 'condition', type: 'CONDITION', label: 'decide' },
      { id: 'nota', componentId: 'sticky-note', type: 'STICKY-NOTE', label: 'Nota' },
      { id: 'quadro', type: 'GROUP-FRAME' },
      { id: 'divisor', componentId: 'section-divider' },
      { id: 'resposta', componentId: 'llm-chat', type: 'LLM-CHAT', label: 'resposta' },
    ]
    const rows = buildStepRows([{ stepId: 'decide', status: 'COMPLETED' }], doc, true)
    expect(rows.map(r => r.stepId)).toEqual(['decide', 'resposta'])
    expect(rows[1].notExecuted).toBe(true)
  })
})
