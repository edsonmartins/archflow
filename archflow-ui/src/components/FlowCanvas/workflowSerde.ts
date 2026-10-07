import type { WorkflowConnection } from './types'

/**
 * As conexões do documento do fluxo, de um lado e do outro.
 *
 * Existe porque o editor <b>descartava a {@code condition}</b> das arestas ao carregar e ao salvar —
 * só {@code sourceId}, {@code targetId} e {@code isErrorPath} sobreviviam. Um fluxo aberto e salvo no
 * designer perdia toda condição e passava a seguir todos os ramos. Carregar e gravar passam por aqui,
 * e o teste fixa que nada se perde na ida e na volta.
 */

interface RawConnection {
  sourceId?:    string
  targetId?:    string
  isErrorPath?: boolean
  condition?:   string
  branch?:      string
}

interface RawStep {
  id?:          string
  connections?: RawConnection[]
}

/** Do documento para o canvas: as conexões de todos os passos, com condição e ramo. */
export function connectionsFromSteps(steps: RawStep[] | undefined): WorkflowConnection[] {
  return (steps ?? []).flatMap(step =>
    (step.connections ?? []).map((conn, j) => ({
      id:          `conn-${step.id}-${j}`,
      sourceId:    conn.sourceId ?? (step.id as string),
      targetId:    conn.targetId as string,
      isErrorPath: conn.isErrorPath ?? false,
      ...(conn.condition ? { condition: conn.condition } : {}),
      ...(conn.branch ? { branch: conn.branch } : {}),
    })),
  )
}

/** Do canvas para o documento: as conexões que saem do passo, com condição e ramo quando houver. */
export function connectionsForStep(stepId: string, connections: WorkflowConnection[]): RawConnection[] {
  return connections
    .filter(c => c.sourceId === stepId)
    .map(c => ({
      sourceId:    c.sourceId,
      targetId:    c.targetId,
      isErrorPath: c.isErrorPath,
      ...(c.condition ? { condition: c.condition } : {}),
      ...(c.branch ? { branch: c.branch } : {}),
    }))
}
