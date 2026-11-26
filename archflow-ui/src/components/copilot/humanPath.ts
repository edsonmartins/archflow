/**
 * A decisão que falha chega ao humano? (RCHFLW-155)
 *
 * <p>Uma decisão que falha sai com {@code decision} nulo, {@code confidence} 0, {@code route} ESCALATE e
 * {@code answers} vazio (DecisionComponent.degradada, onError ESCALATE). No switch sobre {@code ${D.decision}} o nulo
 * cai no {@code default} (ControlStep); numa condition sobre a resposta, o nulo não é igual a nada — a não ser que ela
 * tenha o {@code || ${D.route} == 'ESCALATE'}. O copiloto ligava o {@code default} à Resposta Automática quando várias
 * categorias tinham esse destino, e um pedido urgente cuja classificação falhou recebia resposta automática, sem
 * aprovação. As regras do contexto já diziam "default para o humano"; isto confere o canvas que ficou.</p>
 *
 * <p>O caminho da falha é SIMULADO com os valores que a decisão que falhou grava, e não presumido por forma ("o
 * default do switch", "o false da condition"): a forma fixa avisava o portão ESCALATE (a falha vai direto à aprovação
 * e o switch nem roda), avisava {@code ${D.confidence} < 0.7} (0 é menor, vai ao true) e deixava passar
 * {@code ${D.decision} != 'alta'} (nulo é diferente de 'alta'). E a falha tem de chegar SÓ ao humano: o motor segue
 * toda aresta ativa (DefaultFlowExecutor.resolveOutgoing), então um {@code default} que vai à aprovação E à resposta
 * automática ainda responde ao cliente sozinho.</p>
 *
 * <p>Só vale para o nó que JÁ manda algum ramo ao humano (uma aprovação alcançável por um dos ramos): um fluxo sem
 * caminho humano não tem para onde mandar a falha, e não é este aviso que vai inventar um. E só se afirma o que se
 * sabe: o ramo que depende de outro dado (a entrada, outro nó) é "não se sabe", e o nó não é cobrado. A avaliação é a
 * do modo estrito do ControlStep (o padrão); com {@code archflow.flow.lenient-conditions} a referência sem valor
 * seguiria o ramo padrão em vez de falhar o passo.</p>
 */

export interface HumanPathStep {
  id: string
  label?: string
  componentId?: string
  type?: string
  config?: Record<string, unknown>
}

export interface HumanPathConnection {
  sourceId: string
  targetId: string
  branch?: string
  condition?: string
  isErrorPath?: boolean
}

const HUMAN_NODE_TYPES: ReadonlySet<string> = new Set(['approval'])

const kindOf = (s: HumanPathStep) => String(s.componentId || s.type || '').trim().toLowerCase()

// ── A expressão avaliada com a decisão que falhou ───────────────────────────────────────────────

/**
 * {@code fails}: o passo falha (referência sem valor, ordem contra nulo — o modo estrito do ControlStep), e só o
 * caminho de erro segue. {@code unknown}: depende de um valor que não é o da decisão que falhou.
 */
export type FailedOutcome = boolean | 'fails' | 'unknown'
type Operand = { value: unknown } | 'missing' | 'unknown'

const COMPARISON = /^(.+?)\s*(==|!=|>=|<=|>|<)\s*(.+)$/
const CONTAINS = /^(.+?)\s+contains\s+(.+)$/i
const PLACEHOLDER = /^\$\{\s*(.+?)\s*}$/
const NUMBER = /^[+-]?(\d+\.?\d*|\.\d+)([eE][+-]?\d+)?$/

/** O que {@code ${path}} vale depois que a decisão {@code d} falhou. */
function failedValue(path: string, d: string): Operand {
  if (!path.startsWith(`${d}.`)) return 'unknown'
  const field = path.slice(d.length + 1)
  if (field === 'decision') return { value: null }
  if (field === 'route') return { value: 'ESCALATE' }
  if (field === 'confidence') return { value: 0 }
  // O texto do erro varia e answers é um mapa vazio: comparar um ou outro não tem resposta conhecida aqui.
  if (field === 'error' || field === 'answers') return 'unknown'
  // answers.<pergunta>..., usage... não existem na saída da falha: no modo estrito o passo falha.
  return 'missing'
}

function operandOf(token: string, d: string): Operand {
  const t = token.trim()
  const ref = PLACEHOLDER.exec(t)
  if (ref) return failedValue(ref[1], d)
  if (t.length >= 2 && ((t.startsWith("'") && t.endsWith("'")) || (t.startsWith('"') && t.endsWith('"')))) {
    return { value: t.slice(1, -1) }
  }
  if (/^(true|false)$/i.test(t)) return { value: t.toLowerCase() === 'true' }
  if (NUMBER.test(t)) return { value: Number(t) }
  return 'unknown'
}

const asNumber = (v: unknown): number | undefined => {
  if (typeof v === 'number') return v
  if (typeof v === 'string' && NUMBER.test(v.trim())) return Number(v.trim())
  return undefined
}

/** ConditionEvaluator.looseEquals. */
function looseEquals(a: unknown, b: unknown): boolean {
  if (a === null || b === null) return a === b
  const an = asNumber(a)
  const bn = asNumber(b)
  if (an !== undefined && bn !== undefined) return an === bn
  return String(a) === String(b)
}

function comparison(left: Operand, op: string, right: Operand): FailedOutcome {
  if (left === 'missing' || right === 'missing') return 'fails'
  if (left === 'unknown' || right === 'unknown') return 'unknown'
  const a = left.value
  const b = right.value
  if (op === '==') return looseEquals(a, b)
  if (op === '!=') return !looseEquals(a, b)
  // Ordem contra nulo, ou entre número e texto, deixa o passo indeciso: ele falha (evaluateRequiringValues).
  if (a === null || b === null) return 'fails'
  const an = asNumber(a)
  const bn = asNumber(b)
  if ((an === undefined) !== (bn === undefined)) return 'fails'
  const cmp = an !== undefined && bn !== undefined
    ? an - bn
    : String(a) < String(b) ? -1 : String(a) > String(b) ? 1 : 0
  return op === '>' ? cmp > 0 : op === '>=' ? cmp >= 0 : op === '<' ? cmp < 0 : cmp <= 0
}

/** splitTopLevel do avaliador: divide pelo operador fora de aspas. */
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
  return parts
}

/** A lógica de três valores do evaluateKnown: o que não decide só pesa quando o resultado depende dele. */
function combine(values: FailedOutcome[], absorbing: boolean): FailedOutcome {
  if (values.includes(absorbing)) return absorbing
  if (values.includes('unknown')) return 'unknown'
  if (values.includes('fails')) return 'fails'
  return !absorbing
}

/** A condição com a decisão {@code d} que falhou (a gramática do ConditionEvaluator, sem parênteses). */
export function failedConditionOutcome(expression: string, d: string): FailedOutcome {
  const expr = expression.trim()
  if (!expr) return true
  const alternatives = splitTopLevel(expr, '||')
  if (alternatives.length > 1) return combine(alternatives.map(p => failedConditionOutcome(p, d)), true)
  const conjuncts = splitTopLevel(expr, '&&')
  if (conjuncts.length > 1) return combine(conjuncts.map(p => failedConditionOutcome(p, d)), false)
  const contains = CONTAINS.exec(expr)
  if (contains) {
    const left = operandOf(contains[1], d)
    const right = operandOf(contains[2], d)
    if (left === 'missing' || right === 'missing') return 'fails'
    if (left === 'unknown' || right === 'unknown') return 'unknown'
    if (left.value === null || right.value === null) return false
    return String(left.value).includes(String(right.value))
  }
  const m = COMPARISON.exec(expr)
  if (m) return comparison(operandOf(m[1], d), m[2], operandOf(m[3], d))
  const single = operandOf(expr, d)
  if (single === 'missing') return 'fails'
  if (single === 'unknown') return 'unknown'
  // ConditionEvaluator.truthy.
  const v = single.value
  if (v === null) return false
  if (typeof v === 'boolean') return v
  if (typeof v === 'number') return v !== 0
  return String(v).trim() !== '' && String(v).trim().toLowerCase() !== 'false'
}

const switchCasesOf = (config: Record<string, unknown> | undefined): string[] => {
  const raw = config?.switchCases
  const cases = typeof raw === 'string' ? raw.split(/\r?\n/) : Array.isArray(raw) ? raw.map(String) : []
  return [...new Set(cases.map(c => c.trim()).filter(Boolean))]
}

/** O ramo seguido; num objeto, para um caso chamado "unknown" não se confundir com o "não se sabe". */
type Branch = { name: string } | 'fails' | 'unknown'

/** O ramo que o switch grava com a decisão {@code d} que falhou (ControlStep: valor nulo → "", caso sem caixa). */
function failedSwitchBranch(expression: string, cases: readonly string[], d: string): Branch {
  const ref = PLACEHOLDER.exec(expression.trim())
  if (!ref) return 'unknown'
  const operand = failedValue(ref[1], d)
  if (operand === 'missing') return 'fails'
  if (operand === 'unknown') return 'unknown'
  const v = operand.value
  // Double.toString do Java: o 0 da confiança vira "0.0".
  const text = v === null ? '' : typeof v === 'number' && Number.isInteger(v) ? `${v}.0` : String(v).trim()
  return { name: cases.find(c => c.toLowerCase() === text.toLowerCase()) ?? 'default' }
}

/** Os ids de decision citados num texto com {@code ${id...}}. */
function decisionRefs(expression: unknown, decisions: ReadonlySet<string>): string[] {
  if (typeof expression !== 'string') return []
  const out = new Set<string>()
  for (const m of expression.matchAll(/\$\{\s*([^}.\s]+)[^}]*\}/g)) {
    if (decisions.has(m[1])) out.add(m[1])
  }
  return [...out]
}

// ── O caminho ───────────────────────────────────────────────────────────────────────────────────

/**
 * Os nós de controle (switch/condition sobre uma decisão) que a decisão que falhou alcança e de onde o caminho da
 * falha não vai SÓ a uma aprovação, embora outro ramo deles chegue a ela. Na ordem dos passos.
 */
export function failedDecisionMissesHuman(
  steps: readonly HumanPathStep[], connections: readonly HumanPathConnection[],
): HumanPathStep[] {
  const decisions = new Set(steps.filter(s => kindOf(s) === 'decision').map(s => s.id))
  if (decisions.size === 0) return []
  const humans = new Set(steps.filter(s => HUMAN_NODE_TYPES.has(kindOf(s))).map(s => s.id))
  if (humans.size === 0) return []
  const byId = new Map(steps.map(s => [s.id, s]))
  const out = new Map<string, HumanPathConnection[]>()
  for (const c of connections) out.set(c.sourceId, [...(out.get(c.sourceId) ?? []), c])
  const normalOut = (id: string) => (out.get(id) ?? []).filter(c => !c.isErrorPath)

  const reachesHumanSomehow = (start: string): boolean => {
    const seen = new Set<string>()
    const stack = [start]
    while (stack.length) {
      const id = stack.pop()!
      if (humans.has(id)) return true
      if (seen.has(id)) continue
      seen.add(id)
      for (const c of normalOut(id)) stack.push(c.targetId)
    }
    return false
  }

  const missing = new Set<string>()
  for (const d of decisions) {
    /**
     * As arestas que podem seguir de {@code id} depois que {@code d} falhou. {@code decided}: o ramo saiu da
     * decisão que falhou (e não de um dado que não se conhece aqui).
     */
    const activeOut = (id: string): { edges: HumanPathConnection[]; decided: boolean } => {
      const all = out.get(id) ?? []
      const normal = all.filter(c => !c.isErrorPath)
      const errorPaths = all.filter(c => c.isErrorPath)
      // A condição escrita à mão vence o ramo (BranchCondition); a que não se decide pode seguir.
      const byCondition = (c: HumanPathConnection) => failedConditionOutcome(c.condition!, d) !== false
      const step = byId.get(id)
      const kind = step ? kindOf(step) : ''
      let branch: Branch = 'unknown'
      if (id === d) {
        // As arestas da própria decisão são o portão: AUTO/REVIEW não seguem, ESCALATE e a sem ramo seguem.
        branch = { name: 'ESCALATE' }
      } else if (step && (kind === 'switch' || kind === 'condition')) {
        const field = kind === 'switch' ? 'switchExpression' : 'conditionExpression'
        const expression = step.config?.[field]
        if (decisionRefs(expression, new Set([d])).length > 0) {
          if (kind === 'switch') {
            branch = failedSwitchBranch(String(expression), switchCasesOf(step.config), d)
          } else {
            const outcome = failedConditionOutcome(String(expression), d)
            branch = typeof outcome === 'boolean' ? { name: String(outcome) } : outcome
          }
        }
      }
      if (branch === 'unknown') {
        return { edges: normal.filter(c => !c.condition || byCondition(c)), decided: false }
      }
      if (branch === 'fails') return { edges: errorPaths, decided: true }
      const name = branch.name
      const taken = normal.filter(c => (c.condition ? byCondition(c) : !c.branch || c.branch === name))
      // Switch no default sem aresta para ele, tendo outras: o passo falha (RCHFLW-135) e só o erro segue.
      if (taken.length === 0 && kind === 'switch' && name === 'default' && normal.length > 0) {
        return { edges: errorPaths, decided: true }
      }
      return { edges: taken, decided: true }
    }

    // Os nós que a falha alcança, parando no humano: depois da aprovação, quem decidiu foi uma pessoa.
    const reached = new Set<string>()
    const stack = [d]
    while (stack.length) {
      const id = stack.pop()!
      if (reached.has(id) || humans.has(id)) continue
      reached.add(id)
      for (const c of activeOut(id).edges) stack.push(c.targetId)
    }

    // Todo caminho da falha a partir do nó termina numa aprovação? Ciclo em aberto não reprova.
    const memo = new Map<string, boolean>()
    const onlyHuman = (id: string): boolean => {
      if (humans.has(id)) return true
      const known = memo.get(id)
      if (known !== undefined) return known
      memo.set(id, true)
      const edges = activeOut(id).edges
      const result = edges.length > 0 && edges.every(c => onlyHuman(c.targetId))
      memo.set(id, result)
      return result
    }

    for (const id of reached) {
      if (id === d) continue
      const step = byId.get(id)
      const kind = step ? kindOf(step) : ''
      if (kind !== 'switch' && kind !== 'condition') continue
      if (!activeOut(id).decided) continue
      if (!normalOut(id).some(c => reachesHumanSomehow(c.targetId))) continue
      if (!onlyHuman(id)) missing.add(id)
    }
  }
  return steps.filter(s => missing.has(s.id))
}

/**
 * Os casos declarados de cada switch que nenhuma aresta leva a lugar algum (RCHFLW-149). O ControlStep trata o caso
 * casado sem aresta como fim deliberado: o fluxo termina COMPLETED sem resposta — foi o exec-3ca41eed, com os ramos
 * media e baixa perdidos. O motor roda; quem não pode dizer "montado" sem ressalva é o copiloto, que tinha resumido
 * que esses casos iam para a resposta.
 */
export function switchCasesWithoutEdge(
  steps: readonly HumanPathStep[], connections: readonly HumanPathConnection[],
): { step: HumanPathStep; cases: string[] }[] {
  const result: { step: HumanPathStep; cases: string[] }[] = []
  for (const step of steps) {
    if (kindOf(step) !== 'switch') continue
    const edges = connections.filter(c => c.sourceId === step.id && !c.isErrorPath)
    // Um switch sem nenhuma aresta já é o nó solto que a checagem de conectividade aponta.
    if (edges.length === 0) continue
    // Uma aresta sem ramo (ou com condição à mão) pode seguir para qualquer valor: não se afirma nada.
    if (edges.some(c => !c.branch || c.condition)) continue
    const used = new Set(edges.map(c => c.branch))
    const cases = switchCasesOf(step.config).filter(c => c !== 'default' && !used.has(c))
    if (cases.length) result.push({ step, cases })
  }
  return result
}
