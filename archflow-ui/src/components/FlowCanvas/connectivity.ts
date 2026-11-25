import type { Edge, Node } from '@xyflow/react'
import { isAnnotationStep } from './constants'
import type { FlowNodeData } from './types'

/**
 * Nós executáveis sem nenhuma aresta, quando o fluxo tem mais de um nó executável.
 *
 * O usuário não vê a ordem em que um nó solto roda: sem nenhuma aresta, o motor executa os nós na
 * ordem do documento (a de criação, não a do canvas); com arestas, um nó solto é mais um ponto de
 * entrada e roda à parte com a entrada da execução (RCHFLW-88). Anotações (nota, moldura, divisória)
 * ficam fora: o motor as ignora e elas nunca se ligam a nada.
 */
export function disconnectedNodes(nodes: Node<FlowNodeData>[], edges: Edge[]): Node<FlowNodeData>[] {
  const executable = nodes.filter(
    n => !isAnnotationStep({ componentId: n.data?.componentId, type: n.data?.nodeType ?? n.type }),
  )
  if (executable.length < 2) return []
  const linked = new Set<string>()
  for (const e of edges) {
    linked.add(e.source)
    linked.add(e.target)
  }
  return executable.filter(n => !linked.has(n.id))
}
