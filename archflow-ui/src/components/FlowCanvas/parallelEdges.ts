import { addEdge, type Edge } from '@xyflow/react'

/**
 * Arestas paralelas de ramo (RCHFLW-149, RCHFLW-155).
 *
 * <p>O {@code addEdge} do React Flow descarta, calado, a aresta cujo par origem/destino (e handles) já existe — e os
 * nós têm um único handle de saída, então o ramo ({@code data.branch}) não entra na comparação. Um switch com os
 * casos {@code media} e {@code baixa} indo para a mesma Resposta Automática ficava com uma aresta só, e o caso que
 * sobrava não tinha para onde ir; o {@code alta} e o {@code default} indo os dois para a aprovação, idem — e o
 * {@code default} é onde cai a decisão que falhou. O copiloto ainda ouvia {@code ok:true} e resumia "ligado".</p>
 *
 * <p>O motor aceita duas arestas de ramo para o mesmo destino (cada aresta é uma entrada do alvo, e ele roda uma vez
 * se qualquer uma ativar — DefaultFlowExecutor.settle). Então o canvas também aceita: com ramos distintos, a aresta
 * entra ao lado da outra. Sem ramo em uma das duas, é a mesma ligação (a sem ramo sempre segue) e continua recusada —
 * agora com a verdade dita a quem pediu.</p>
 */

const branchOf = (e: Pick<Edge, 'data'>): string | undefined => {
  const b = (e.data as { branch?: unknown } | undefined)?.branch
  return typeof b === 'string' && b.trim() ? b : undefined
}

/**
 * A aresta que já ocupa a ligação pedida, ou undefined quando ela cabe. Duas arestas do mesmo par só convivem quando
 * as duas têm ramo e os ramos são diferentes.
 */
export function conflictingEdge<E extends Pick<Edge, 'source' | 'target' | 'data'>>(
  edges: readonly E[], sourceId: string, targetId: string, branch: string | undefined,
): E | undefined {
  return edges.find(e => e.source === sourceId && e.target === targetId
    && (branch === undefined || branchOf(e) === undefined || branchOf(e) === branch))
}

/**
 * {@code edges} com {@code edge}, ou null quando a ligação já existe (ver {@link conflictingEdge}). Sem par repetido
 * vale o {@code addEdge} de sempre; com ramo novo num par que já tem outro ramo, a aresta é acrescentada direto —
 * o {@code addEdge} a descartaria.
 */
export function withEdge(edges: Edge[], edge: Edge): Edge[] | null {
  const branch = branchOf(edge)
  if (conflictingEdge(edges, edge.source, edge.target, branch)) return null
  const parallel = edges.some(e => e.source === edge.source && e.target === edge.target)
  return parallel ? [...edges, edge] : addEdge(edge, edges)
}
