import type { Edge, Node } from '@xyflow/react'
import type { FlowNodeData } from '../../FlowCanvas/types'

/**
 * Referências `${id.campo}` para as expressões dos nós de controle.
 *
 * O motor grava a saída de cada passo sob o **id** do nó (`node-<epoch>`), não sob o rótulo —
 * `${triagem.route}` escrito com o nome do nó não resolve e o switch cai em `default` sem erro.
 * Estes helpers existem para o usuário nunca precisar digitar (nem adivinhar) o id.
 */

/** `${id}` ou `${id.campo}` — montado aqui para o JSX não ter de escapar o `${`. */
export function refExpression(id: string, field?: string): string {
  return '${' + id + (field ? `.${field}` : '') + '}'
}

/**
 * O campo que vale referenciar de cada tipo de nó: a decisão expõe `route`, os nós de controle
 * `branch`; os demais gravam a saída inteira sob o id, então a referência é o próprio `${id}`.
 */
export function referenceFields(nodeType: string): (string | undefined)[] {
  if (nodeType === 'decision') return ['route']
  if (nodeType === 'condition' || nodeType === 'switch') return ['branch']
  return [undefined]
}

export interface UpstreamRef {
  id:      string
  label:   string
  snippet: string
}

/**
 * Todos os nós de onde se chega a `nodeId` pelas arestas (não só os vizinhos diretos: a saída de
 * qualquer passo anterior continua no contexto), do mais próximo para o mais distante.
 */
export function upstreamRefs(
  nodeId: string,
  nodes: Node<FlowNodeData>[],
  edges: Edge[],
): UpstreamRef[] {
  const byId = new Map(nodes.map(n => [n.id, n]))
  const visto = new Set<string>([nodeId])
  const fila = [nodeId]
  const ordem: string[] = []
  while (fila.length > 0) {
    const atual = fila.shift()!
    for (const e of edges) {
      if (e.target === atual && !visto.has(e.source)) {
        visto.add(e.source)
        ordem.push(e.source)
        fila.push(e.source)
      }
    }
  }
  return ordem.flatMap(id => {
    const n = byId.get(id)
    if (!n) return []
    return referenceFields(n.data.nodeType).map(field => ({
      id,
      label:   n.data.label || id,
      snippet: refExpression(id, field),
    }))
  })
}
