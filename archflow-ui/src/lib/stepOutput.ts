/**
 * Saída dos passos de uma execução, como o backend a grava.
 *
 * O `StepRecordingListener` guarda em cada registro de passo um campo `output` em TEXTO: o JSON
 * serializado da saída (ou a própria string, quando a saída já é texto), truncado em ~4 KB com o
 * sufixo "…(truncado)". Texto porque o store JDBC regrava o registro inteiro como JSON a cada evento.
 * Daqui sai de volta um valor exibível: o objeto quando o texto é JSON válido, o texto cru quando não
 * é (string pura ou JSON cortado pelo truncamento).
 */

/** Registro de passo persistido pelo backend (`GET /api/executions/{id}` → `steps[]`). */
export interface StepRecord {
  stepId: string
  type?: string
  stepIndex?: number
  stepCount?: number
  /**
   * PAUSED: portão de aprovação à espera da decisão (não concluiu). REJECTED: portão recusado, por
   * alguém ou pelo prazo — gravado pela fila de Aprovações, com `decidedBy`.
   */
  status: 'RUNNING' | 'COMPLETED' | 'FAILED' | 'SKIPPED' | 'PAUSED' | 'REJECTED' | string
  startedAt?: string
  finishedAt?: string
  /** Quando o portão suspendeu o fluxo para esperar a decisão. */
  suspendedAt?: string
  durationMs?: number
  error?: string
  output?: string
  /** Quem recusou o portão (só em REJECTED). */
  decidedBy?: string
}

/** Status sintético das linhas de passos do fluxo que não têm registro nesta execução. */
export const NOT_EXECUTED = 'NOT_EXECUTED'

/** Linha do detalhe da execução: o registro, o nome do nó e se ele de fato rodou. */
export interface StepRow extends StepRecord {
  label?: string
  notExecuted?: boolean
}

/** Texto gravado → valor: objeto/array/número quando é JSON válido, senão o próprio texto. */
export function parseStepOutput(raw: unknown): unknown {
  if (typeof raw !== 'string') return raw
  const trimmed = raw.trim()
  if (trimmed === '') return raw
  // Só tenta JSON quando parece JSON: "123abc" ou "true story" devem continuar texto.
  if (!/^[[{"]|^-?\d|^(true|false|null)$/.test(trimmed)) return raw
  try {
    return JSON.parse(trimmed)
  } catch {
    return raw
  }
}

/** Valor para exibir em `<pre>`: texto como está, o resto como JSON indentado. */
export function formatStepOutput(value: unknown): string {
  if (typeof value === 'string') return value
  try {
    return JSON.stringify(value, null, 2) ?? String(value)
  } catch {
    return String(value)
  }
}

/** stepId → saída já interpretada, só para os passos que gravaram `output`. */
export function stepOutputsFromExecution(execution: unknown): Record<string, unknown> {
  const steps = (execution as { steps?: unknown } | null | undefined)?.steps
  if (!Array.isArray(steps)) return {}
  const outputs: Record<string, unknown> = {}
  for (const step of steps as Partial<StepRecord>[]) {
    if (step && typeof step.stepId === 'string' && step.output != null) {
      outputs[step.stepId] = parseStepOutput(step.output)
    }
  }
  return outputs
}

interface RawWorkflowStep {
  id?: unknown
  label?: unknown
  operation?: unknown
  componentId?: unknown
  type?: unknown
}

/**
 * Nome legível do passo no documento — mesma ordem de preferência que o editor usa ao carregar
 * (`WorkflowEditorPage`: label → operation → componentId → type), para o detalhe mostrar o nome que
 * a pessoa vê no canvas.
 */
function labelOf(step: RawWorkflowStep): string | undefined {
  for (const candidate of [step.label, step.operation, step.componentId, step.type]) {
    if (typeof candidate === 'string' && candidate.trim() !== '') return candidate
  }
  return undefined
}

/**
 * Junta os registros da execução com os passos do documento: cada registro ganha o nome do nó, e
 * (quando `includeNotExecuted`) os passos do fluxo sem registro entram no fim como "Não executado" —
 * sem eles, um ramo não tomado simplesmente sumia do detalhe e parecia que o fluxo era só aquilo.
 * Quem chama só deve ligar `includeNotExecuted` para execução em status terminal
 * (`isTerminalExecutionStatus`): numa pausa/aprovação, "sem registro" ainda quer dizer "vai rodar".
 */
export function buildStepRows(
  records: StepRecord[],
  workflowSteps: unknown,
  includeNotExecuted: boolean,
): StepRow[] {
  const docSteps = Array.isArray(workflowSteps) ? (workflowSteps as RawWorkflowStep[]) : []
  const labels = new Map<string, string>()
  for (const step of docSteps) {
    const label = step && typeof step.id === 'string' ? labelOf(step) : undefined
    if (label) labels.set(step.id as string, label)
  }

  const rows: StepRow[] = records
    .slice()
    .sort((a, b) => (a.stepIndex ?? 0) - (b.stepIndex ?? 0))
    .map((record) => ({ ...record, label: labels.get(record.stepId) }))

  if (includeNotExecuted) {
    const recorded = new Set(records.map((r) => r.stepId))
    for (const step of docSteps) {
      if (!step || typeof step.id !== 'string' || recorded.has(step.id)) continue
      rows.push({ stepId: step.id, status: NOT_EXECUTED, label: labels.get(step.id), notExecuted: true })
    }
  }
  return rows
}
