import type { WorkflowConnection, WorkflowData } from './types'
import { NODE_TYPE_TO_CATEGORY } from './constants'

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

/**
 * Do documento do fluxo (o que {@code GET /api/workflows/{id}} devolve: {@code steps[]} com as conexões dentro
 * de cada passo) para o {@link WorkflowData} do canvas. É a leitura do editor; o web-component
 * {@code <archflow-designer>} usa a mesma ao buscar o fluxo pela API (RCHFLW-87) — com o documento cru o
 * canvas quebrava, porque procura as arestas em {@code connections} no topo.
 */
export function workflowDataFromDocument(detail: any): WorkflowData {
  const steps = (detail.steps ?? []).map((step: any, i: number) => {
    // A YAML/API-authored execution operation lives at the node level; fold it
    // into `config.operation` so a plain canvas save round-trips it instead of
    // dropping it (the backend's DefaultFlowStepFactory reads config.operation
    // first). Only do this when a distinct `label` is present: pre-schema saves
    // stored the DISPLAY NAME in `operation`, so folding a label-less step's
    // `operation` would make the factory run a bogus operation.
    const config = { ...(step.configuration ?? {}) }
    if (step.operation && step.label && config.operation == null) config.operation = step.operation
    return {
      id:          step.id ?? `step-${i}`,
      type:        step.type?.toLowerCase() ?? 'custom',
      componentId: step.componentId ?? step.type?.toLowerCase() ?? 'custom',
      label:       step.label ?? step.operation ?? step.componentId ?? step.type ?? 'Node',
      category:    NODE_TYPE_TO_CATEGORY[step.type?.toLowerCase()] ?? 'tool' as const,
      position:    step.position ?? { x: 200 + i * 250, y: 150 },
      config,
    }
  })

  // Condition and branch survive the round trip — dropping them made a flow opened and saved in the
  // designer follow every branch (see workflowSerde).
  const connections = connectionsFromSteps(detail.steps)

  return { steps, connections }
}
