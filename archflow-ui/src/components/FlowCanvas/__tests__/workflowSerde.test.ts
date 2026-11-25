/**
 * Formato do designer/template → documento canônico (RCHFLW-45), e o resgate dos fluxos que o clone
 * antigo já gravou no formato errado.
 */
import { describe, it, expect } from 'vitest'
import type { WorkflowConnection, WorkflowStep } from '../types'
import {
  canvasToDocumentSteps, connectionsFromSteps,
} from '../workflowSerde'

const steps: WorkflowStep[] = [
  { id: 'a', type: 'input', componentId: 'input', label: 'Entrada', category: 'io', position: { x: 0, y: 0 }, config: {} },
  { id: 'b', type: 'condition', componentId: 'condition', label: 'Roteia', category: 'control',
    position: { x: 250, y: 0 }, config: { strategy: 'llm' } },
  { id: 'c', type: 'llm-chat', componentId: 'openai', label: 'Responde', category: 'agent',
    position: { x: 500, y: 0 } },
]
const connections: WorkflowConnection[] = [
  { id: 'c1', sourceId: 'a', targetId: 'b', isErrorPath: false },
  { id: 'c2', sourceId: 'b', targetId: 'c', isErrorPath: false, branch: 'true' },
  { id: 'c3', sourceId: 'b', targetId: 'a', isErrorPath: true, condition: "${b.x} == 'y'" },
]

describe('canvasToDocumentSteps', () => {
  it('põe a config em configuration e as arestas dentro do passo de origem', () => {
    const doc = canvasToDocumentSteps(steps, connections)

    expect(doc[1]).toEqual({
      id: 'b', type: 'condition', componentId: 'condition', label: 'Roteia', position: { x: 250, y: 0 },
      configuration: { strategy: 'llm' },
      connections: [
        { sourceId: 'b', targetId: 'c', isErrorPath: false, branch: 'true' },
        { sourceId: 'b', targetId: 'a', isErrorPath: true, condition: "${b.x} == 'y'" },
      ],
    })
    // Sem config no canvas: objeto vazio, nunca a chave `config` do formato do designer.
    expect(doc[2]).not.toHaveProperty('config')
    expect(doc[2].configuration).toEqual({})
    // O type cru não é reescrito (o roteamento de adapter casa por ele).
    expect(doc[2].type).toBe('llm-chat')
  })

  it('volta pelo leitor do editor sem perder aresta, condição nem ramo', () => {
    const back = connectionsFromSteps(canvasToDocumentSteps(steps, connections))

    const semId = (c: WorkflowConnection) => ({ ...c, id: undefined })
    expect(back.map(semId)).toEqual(connections.map(semId))
  })
})
