/**
 * A expressão de um nó de controle — {@code conditionExpression} do condition, {@code switchExpression} do switch —
 * no formato que o motor avalia e lendo passos que o fluxo tem (RCHFLW-116).
 *
 * <p>O avaliador do motor é permissivo: um operando sem {@code ${}} é procurado como variável e, se não existe,
 * vira o próprio texto. O "Gerar com IA" montou {@code ticket.urgente == true}, que compara a string
 * "ticket.urgente" com true e dá sempre false — o pedido urgente seguia para o LLM, sem erro nem aviso. Esta é a
 * mesma regra do servidor ({@code ControlExpressionRules}, archflow-core), e os dois leem os mesmos casos em
 * {@code __tests__/controlExpressionCases.json}: a gramática abaixo é cópia da do {@code ConditionEvaluator}.</p>
 *
 * <p>A regra, e por que ela não recusa entrada legítima:</p>
 * <ul>
 *   <li>MALFORMED — fora da gramática (operando operador operando, unidos por && ou ||);</li>
 *   <li>BARE_OPERAND — operando da condição que não é {@code ${caminho}}, texto entre aspas, número nem
 *       true/false;</li>
 *   <li>UNKNOWN_STEP — {@code ${X.campo}} em que nenhum prefixo é id de passo nem campo declarado no nó Entrada
 *       (ioFields) e X não é raiz que o servidor grava (input, step, llm, archflow, __archflow, approvalPayload,
 *       payload, vendax). Um {@code ${nome}} sem ponto é aceito: as chaves do JSON de entrada viram variáveis e só
 *       existem na execução. O campo aninhado de uma chave da entrada ({@code ${ticket.urgente}}) resolve na execução,
 *       mas só se distingue de uma variável inventada quando a chave está nos campos esperados da Entrada — é o
 *       contrato que o fluxo já declara, e a forma que funciona com qualquer entrada;</li>
 *   <li>TEXT_OUTPUT_FIELD — {@code ${X.campo}} em que X é um LLM Chat: a saída dele é o texto da resposta;</li>
 *   <li>NO_REFERENCE — switch sem nenhum {@code ${…}}: o valor é sempre o mesmo.</li>
 * </ul>
 */

export type ExpressionProblem = 'MALFORMED' | 'BARE_OPERAND' | 'UNKNOWN_STEP' | 'TEXT_OUTPUT_FIELD' | 'NO_REFERENCE'

export interface ExpressionFinding {
  problem:  ExpressionProblem
  /** O operando, o caminho ou a expressão inteira, conforme o problema. */
  fragment: string
}

/**
 * O que o fluxo oferece às referências: os ids dos passos, quais deles têm saída de texto e as chaves da entrada do
 * run declaradas nos nós Entrada (ioFields).
 */
export interface FlowRefs {
  stepIds:           ReadonlySet<string>
  textOutputStepIds: ReadonlySet<string>
  inputFields?:      ReadonlySet<string>
}

/** As chaves de expressão por tipo de nó de controle. */
export const CONTROL_EXPRESSION_FIELD: Readonly<Record<string, 'conditionExpression' | 'switchExpression'>> = {
  condition: 'conditionExpression',
  switch:    'switchExpression',
}

/**
 * Raízes que o servidor grava fora dos ids dos passos (RunInputSeeding, DefaultFlowExecutor, ExecutionKeys): também o
 * approvalPayload da aprovação e, no invoke do VendaX (AgentFlowRunner), o payload como objeto e as chaves vendax.*.
 */
export const ENGINE_ROOTS: ReadonlySet<string> = new Set([
  'input', 'step', 'llm', 'archflow', '__archflow', 'approvalPayload', 'payload', 'vendax',
])

/** Os nós de chat: a saída é a String da resposta (AdapterNodeTypes, ChatTurn). */
export const TEXT_OUTPUT_NODE_TYPES: ReadonlySet<string> = new Set(['llm-chat', 'llm-streaming', 'chat'])

// A gramática do ConditionEvaluator, linha a linha.
const COMPARISON = /^(.+?)\s*(==|!=|>=|<=|>|<)\s*(.+)$/
const CONTAINS = /^(.+?)\s+contains\s+(.+)$/i
const PLACEHOLDER = /^\$\{(.+)}$/
const NUMBER = /^[+-]?(\d+\.?\d*|\.\d+)([eE][+-]?\d+)?$/
const VAR = /\$\{([^}]+)}/g

/** splitTopLevel do avaliador: divide pelo operador fora de aspas; operador solto (parte vazia) lança. */
function splitTopLevel(expr: string, operator: string): string[] {
  const parts: string[] = []
  let start = 0
  let quote = ''
  for (let i = 0; i < expr.length; i++) {
    const c = expr[i]
    if (quote) {
      if (c === quote) quote = ''
      continue
    }
    if (c === "'" || c === '"') {
      quote = c
      continue
    }
    if (expr.startsWith(operator, i)) {
      parts.push(expr.substring(start, i))
      i += operator.length - 1
      start = i + 1
    }
  }
  parts.push(expr.substring(start))
  if (parts.length > 1 && parts.some(p => p.trim() === '')) throw new Error(`malformed around ${operator}`)
  return parts
}

/** ConditionEvaluator.isWellFormed. */
export function isWellFormed(condition: string): boolean {
  const expr = condition.trim()
  if (expr === '') return true
  try {
    let parts = splitTopLevel(expr, '||')
    if (parts.length === 1) parts = splitTopLevel(expr, '&&')
    if (parts.length > 1) return parts.every(p => isWellFormed(p.trim()))
  } catch {
    return false
  }
  const contains = CONTAINS.exec(expr)
  if (contains) return contains[1].trim() !== '' && contains[2].trim() !== ''
  const m = COMPARISON.exec(expr)
  if (m) return m[1].trim() !== '' && m[3].trim() !== ''
  return !/(==|!=|>=|<=|>|<)/.test(expr) && !/\bcontains\b/i.test(expr)
}

function checkPath(path: string, refs: FlowRefs, out: ExpressionFinding[]) {
  if (path === '') {
    out.push({ problem: 'BARE_OPERAND', fragment: '${}' })
    return
  }
  if (!path.includes('.')) return
  // O prefixo mais longo que é id de passo decide: o avaliador procura a chave inteira antes de descer.
  let prefix = path
  for (;;) {
    if (refs.stepIds.has(prefix)) {
      if (prefix !== path && refs.textOutputStepIds.has(prefix)) {
        out.push({ problem: 'TEXT_OUTPUT_FIELD', fragment: path })
      }
      return
    }
    if (refs.inputFields?.has(prefix)) return
    const dot = prefix.lastIndexOf('.')
    if (dot <= 0) break
    prefix = prefix.substring(0, dot)
  }
  if (!ENGINE_ROOTS.has(path.substring(0, path.indexOf('.')))) out.push({ problem: 'UNKNOWN_STEP', fragment: path })
}

function checkOperand(token: string, refs: FlowRefs, out: ExpressionFinding[]) {
  const t = token.trim()
  const placeholder = PLACEHOLDER.exec(t)
  if (placeholder) {
    const path = placeholder[1].trim()
    // "${a}x${b}" casa com o placeholder, mas o avaliador procuraria a chave "a}x${b".
    if (/[{}$]/.test(path)) out.push({ problem: 'BARE_OPERAND', fragment: t })
    else checkPath(path, refs, out)
    return
  }
  const quoted = t.length >= 2 && ((t.startsWith("'") && t.endsWith("'")) || (t.startsWith('"') && t.endsWith('"')))
  if (quoted || /^(true|false)$/i.test(t) || NUMBER.test(t)) return
  out.push({ problem: 'BARE_OPERAND', fragment: t })
}

function checkConditionPart(expr: string, refs: FlowRefs, out: ExpressionFinding[]) {
  const alternatives = splitTopLevel(expr, '||')
  if (alternatives.length > 1) {
    alternatives.forEach(a => checkConditionPart(a.trim(), refs, out))
    return
  }
  const conjuncts = splitTopLevel(expr, '&&')
  if (conjuncts.length > 1) {
    conjuncts.forEach(c => checkConditionPart(c.trim(), refs, out))
    return
  }
  const contains = CONTAINS.exec(expr)
  if (contains) {
    checkOperand(contains[1], refs, out)
    checkOperand(contains[2], refs, out)
    return
  }
  const m = COMPARISON.exec(expr)
  if (m) {
    checkOperand(m[1], refs, out)
    checkOperand(m[3], refs, out)
    return
  }
  checkOperand(expr, refs, out)
}

/** As recusas de uma conditionExpression; em branco não é aqui (é campo obrigatório). */
export function checkCondition(expression: string, refs: FlowRefs): ExpressionFinding[] {
  const expr = expression.trim()
  if (expr === '') return []
  if (!isWellFormed(expr)) return [{ problem: 'MALFORMED', fragment: expr }]
  const out: ExpressionFinding[] = []
  checkConditionPart(expr, refs, out)
  return out
}

/** As recusas de uma switchExpression: um template com ao menos um ${…}. */
export function checkSwitch(expression: string, refs: FlowRefs): ExpressionFinding[] {
  if (expression.trim() === '') return []
  const out: ExpressionFinding[] = []
  const found = [...expression.matchAll(VAR)]
  for (const m of found) checkPath(m[1].trim(), refs, out)
  if (found.length === 0) out.push({ problem: 'NO_REFERENCE', fragment: expression.trim() })
  return out
}

/** Um passo do canvas como os validadores o leem (o mesmo recorte do runBlockers). */
export interface ControlCandidateStep {
  id:           string
  label?:       string
  componentId?: string
  type?:        string
  config?:      Record<string, unknown>
}

const kindsOf = (step: ControlCandidateStep) =>
  [step.componentId, step.type].filter((k): k is string => typeof k === 'string').map(k => k.trim().toLowerCase())

/** O tipo que decide, como no motor (PortableFlowStepFactory.kindOf): o componentId, senão o type. */
const kindOf = (step: ControlCandidateStep) => kindsOf(step)[0] ?? ''

/** Os campos esperados de um nó Entrada (ioFields), aparados; vazio para outro tipo de nó. */
export function declaredInputFields(step: ControlCandidateStep): string[] {
  if (!kindsOf(step).includes('input')) return []
  const raw = step.config?.ioFields
  if (!Array.isArray(raw)) return []
  return raw.filter((f): f is string => typeof f === 'string').map(f => f.trim()).filter(f => f !== '')
}

/** Os ids, os nós de saída de texto e os campos de entrada declarados de um conjunto de passos. */
export function flowRefsOf(steps: readonly ControlCandidateStep[]): FlowRefs {
  return {
    stepIds: new Set(steps.map(s => s.id)),
    textOutputStepIds: new Set(steps.filter(s => kindsOf(s).some(k => TEXT_OUTPUT_NODE_TYPES.has(k))).map(s => s.id)),
    inputFields: new Set(steps.flatMap(declaredInputFields)),
  }
}

/** As recusas da expressão de um nó (vazio para nó que não é de controle ou sem expressão). */
export function controlExpressionFindings(
  nodeType: string, config: Record<string, unknown> | undefined, refs: FlowRefs,
): ExpressionFinding[] {
  const field = CONTROL_EXPRESSION_FIELD[nodeType]
  const raw = field ? config?.[field] : undefined
  if (typeof raw !== 'string') return []
  return nodeType === 'condition' ? checkCondition(raw, refs) : checkSwitch(raw, refs)
}

export interface InvalidControlExpression {
  id:       string
  label:    string
  field:    'conditionExpression' | 'switchExpression'
  findings: ExpressionFinding[]
}

/**
 * Os nós de controle com expressão recusada, na ordem do canvas. As anotações ficam de fora pelo chamador (não são
 * passos), e os ids delas não contam como referência.
 */
export function invalidControlExpressions(steps: readonly ControlCandidateStep[]): InvalidControlExpression[] {
  const refs = flowRefsOf(steps)
  const out: InvalidControlExpression[] = []
  for (const step of steps) {
    const kind = kindOf(step)
    const findings = controlExpressionFindings(kind, step.config, refs)
    if (findings.length > 0) {
      out.push({ id: step.id, label: step.label?.trim() || step.id, field: CONTROL_EXPRESSION_FIELD[kind], findings })
    }
  }
  return out
}

/**
 * O motivo em inglês, para o agente do copiloto: ele lê a recusa e corrige na rodada seguinte. Diz a forma certa,
 * e não só o que está errado — sem isso o modelo repete a mesma expressão com outra grafia.
 */
export function describeFindingForAgent(f: ExpressionFinding): string {
  const root = f.fragment.split('.')[0]
  switch (f.problem) {
    case 'MALFORMED':
      return `"${f.fragment}" is not in the evaluator grammar: <operand> <op> <operand> with op one of `
        + '==, !=, >, >=, <, <=, contains, joined by && or || (no parentheses)'
    case 'BARE_OPERAND':
      return `"${f.fragment}" is not a value: reference data as \${<nodeId>.<field>} or \${input}, quote text `
        + `('…'), or use a number/true/false — without \${} the evaluator compares the literal text and never branches`
    case 'UNKNOWN_STEP':
      return `\${${f.fragment}}: no node in this flow has the id "${root}" — a node's output lives under its node id `
        + `(from addNode), never its label; if "${root}" is a key of the run input JSON, declare it in the input `
        + `node's ioFields (configureNode on the input node: {"ioFields": ["${root}"]})`
    case 'TEXT_OUTPUT_FIELD':
      return `\${${f.fragment}}: "${root}" is an llm-chat node, whose output is the reply TEXT with no fields — `
        + `compare \${${root}} (e.g. \${${root}} contains 'URGENT'), or classify with a decision node and read `
        + `\${<decisionId>.decision} / branch on its route`
    case 'NO_REFERENCE':
      return `switch expression "${f.fragment}" reads no \${…}: its value never changes — use e.g. \${<decisionId>.decision}`
  }
}
