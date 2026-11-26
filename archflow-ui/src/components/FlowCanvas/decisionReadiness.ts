/**
 * O que o nó {@code decision} recusa antes de decidir qualquer coisa (RCHFLW-116), lido do código do motor e não
 * inventado: {@code DecisionComponent.initialize}/{@code parseQuestions}, os records de {@code Question} e
 * {@code RoutePolicy}, e as recusas determinísticas dos provedores ({@code HttpDecisionProvider} e
 * {@code LangChain4jDecisionProvider} exigem o {@code model} do nó; {@code RulesDecisionProvider} exige as regras
 * de cada pergunta).
 *
 * <p>Por que importa: com {@code onError: ESCALATE} (o padrão) uma recusa do provedor não falha o passo — vira a
 * rota ESCALATE com o motivo no {@code error}. O "Gerar com IA" montou um decision sem provider nem model; o
 * padrão é o {@code http-decisions}, que exige o model, e TODO pedido, urgente ou comum, saiu pelo ramo de
 * escalada. O fluxo terminava COMPLETED e parecia funcionar. O editor recusa o nó antes do Executar, como faz com
 * os outros campos obrigatórios (runReadiness).</p>
 *
 * <p>As recusas do provedor só travam quando o nó escalaria sempre: se um degrau de {@code cascade} ou de
 * {@code fallbacks} pode responder, o motor decide com ele e o editor deixa executar.</p>
 *
 * <p>Fica de fora o que só o servidor sabe: se o provedor está registrado ali (SPI) e se há chave para ele.</p>
 */

/** O provedor que o motor usa quando o nó não diz ({@code HttpDecisionProvider.ID}). */
export const DEFAULT_DECISION_PROVIDER = 'http-decisions'

/** Os provedores que recusam a chamada sem o {@code model} do nó ("o provedor … exige o 'model' do nó"). */
export const DECISION_PROVIDERS_REQUIRING_MODEL: ReadonlySet<string> = new Set(['http-decisions', 'langchain4j-decisions'])

/** O teto de confiança do provedor {@code llm} (LlmDecisionProvider.DEFAULT_MAX_CONFIDENCE). */
export const LLM_DECISION_MAX_CONFIDENCE = 0.89

/** Os limiares padrão da rota (RoutePolicy.DEFAULT). */
const DEFAULT_AUTO = 0.9
const DEFAULT_REVIEW = 0.5

export type DecisionProblem =
  | 'QUESTIONS_NOT_OBJECT' | 'QUESTION_ID' | 'QUESTION_NOT_OBJECT' | 'QUESTION_TYPE' | 'CHOICE_CRITERIA'
  | 'SCORE_CRITERIA' | 'UNKNOWN_PRIMARY' | 'UNKNOWN_GATE' | 'ON_ERROR' | 'THRESHOLDS' | 'ACCEPT_AT'
  | 'ON_DISAGREE' | 'RULES_MISSING'

export interface DecisionFinding {
  problem: DecisionProblem
  /** A pergunta, o id ou o valor recusado; vazio quando o problema é do nó inteiro. */
  subject: string
  /** A chave da config onde está o problema (o campo do painel que o usuário corrige). */
  key:     string
}

const isObject = (v: unknown): v is Record<string, unknown> =>
  typeof v === 'object' && v !== null && !Array.isArray(v)

/** O {@code str(v, padrao)} do componente: só texto não vazio vale; o resto é o padrão. */
const str = (v: unknown, padrao: string | null): string | null =>
  (typeof v === 'string' && v.trim() !== '' ? v : padrao)

/** O {@code num(v, padrao)} do componente: só número vale. */
const num = (v: unknown, padrao: number): number => (typeof v === 'number' ? v : padrao)

/** O provedor do nó como o motor o resolve: o {@code provider} gravado ou o padrão. */
export function decisionProviderOf(config: Record<string, unknown> | undefined): string {
  return str(config?.provider, DEFAULT_DECISION_PROVIDER) as string
}

/** Um degrau da execução: o provedor, o model e as opções próprias dele (só cascata e reserva têm). */
interface DecisionCandidate {
  provider: string | null
  model:    string | null
  options:  Record<string, unknown> | undefined
}

/** O {@code tiers(raw)} do componente: da lista, só os objetos. */
const tiers = (raw: unknown): Record<string, unknown>[] => (Array.isArray(raw) ? raw.filter(isObject) : [])

/**
 * Os provedores que o motor tenta, na ordem do {@code DecisionComponent.execute}: o do nó, os degraus de
 * {@code cascade} e, quando um degrau FALHA, os {@code fallbacks} ({@code comReserva}). O do nó sem provider é o
 * padrão; um degrau sem provider é {@code null}, que {@code DecisionProviders.find} não acha.
 */
function decisionCandidates(cfg: Record<string, unknown>): DecisionCandidate[] {
  const degrau = (t: Record<string, unknown>): DecisionCandidate => ({
    provider: str(t.provider, null), model: str(t.model, null), options: isObject(t.options) ? t.options : undefined,
  })
  return [
    { provider: decisionProviderOf(cfg), model: str(cfg.model, null), options: undefined },
    ...tiers(cfg.cascade).map(degrau),
    ...tiers(cfg.fallbacks).map(degrau),
  ]
}

/**
 * As regras que o {@code RulesDecisionProvider} recebe num degrau: o {@code tentar} junta
 * {@code providerOptions.rules} com as {@code options} do degrau, chave a chave — uma chave {@code rules} no degrau
 * substitui a do nó inteira, mesmo que não seja um objeto.
 */
function rulesOf(cfg: Record<string, unknown>, c: DecisionCandidate): Record<string, unknown> {
  const doNo = isObject(cfg.providerOptions) && isObject(cfg.providerOptions.rules) ? cfg.providerOptions.rules : {}
  const raw = c.options && 'rules' in c.options ? c.options.rules : doNo.rules
  return isObject(raw) ? raw : {}
}

/**
 * O degrau é recusado com certeza, sem chamar ninguém: provedor ausente, provedor que exige {@code model} sem ele, ou
 * {@code rules} sem a regra de alguma pergunta. Um provedor com outro nome pode existir no servidor (SPI): não é
 * recusado aqui.
 */
function candidateRefused(cfg: Record<string, unknown>, c: DecisionCandidate, ids: string[]): boolean {
  if (c.provider == null) return true
  if (DECISION_PROVIDERS_REQUIRING_MODEL.has(c.provider) && c.model == null) return true
  if (c.provider === 'rules') {
    const rules = rulesOf(cfg, c)
    return ids.some(id => !isObject(rules[id]))
  }
  return false
}

/**
 * Algum degrau além do principal pode responder. Quando pode, o motor marca o principal como FAILED e decide com o
 * seguinte (a cascata típica é {@code rules → llm}): o defeito do principal é config morta, não um nó que escala
 * sempre, e o editor não pode travar o que o motor executa.
 */
function otherCandidateMayAnswer(cfg: Record<string, unknown>, ids: string[]): boolean {
  return decisionCandidates(cfg).slice(1).some(c => !candidateRefused(cfg, c, ids))
}

/** Os ids das perguntas, como o componente as lê (a chave do objeto {@code questions}). */
const questionIdsOf = (cfg: Record<string, unknown>): string[] =>
  (isObject(cfg.questions) ? Object.keys(cfg.questions) : [])

/**
 * O provedor do nó exige {@code model}, o nó não tem, e nenhum degrau de {@code cascade}/{@code fallbacks} responde
 * no lugar dele: a chamada nem sai, e o fluxo escala sempre.
 */
export function decisionModelMissing(config: Record<string, unknown> | undefined): boolean {
  const cfg = config ?? {}
  return DECISION_PROVIDERS_REQUIRING_MODEL.has(decisionProviderOf(cfg)) && str(cfg.model, null) == null
    && !otherCandidateMayAnswer(cfg, questionIdsOf(cfg))
}

const QUESTION_TYPES = new Set(['choice', 'score', 'noul', 'boolean', 'yesno'])

/**
 * As recusas do nó, na ordem em que o componente as encontra. As que dependem das perguntas (primary, gate, regras)
 * só são conferidas quando as perguntas são um objeto — senão o componente para antes, e repetir o problema com
 * outro nome só confundiria. Perguntas ausentes ou vazias são campo obrigatório (REQUIRED_NODE_FIELDS), não daqui.
 */
export function decisionConfigFindings(config: Record<string, unknown> | undefined): DecisionFinding[] {
  const cfg = config ?? {}
  const out: DecisionFinding[] = []
  const add = (problem: DecisionProblem, key: string, subject = '') => out.push({ problem, key, subject })

  const questions = cfg.questions
  const ids: string[] = []
  if (questions != null && !isObject(questions)) {
    // Uma lista (o erro mais comum de quem escreve à mão) ou um texto: "'questions' exige ao menos uma pergunta".
    if (!(Array.isArray(questions) && questions.length === 0) && !(typeof questions === 'string' && !questions.trim())) {
      add('QUESTIONS_NOT_OBJECT', 'questions')
    }
  } else if (isObject(questions)) {
    for (const [id, q] of Object.entries(questions)) {
      ids.push(id)
      if (!isObject(q)) { add('QUESTION_NOT_OBJECT', 'questions', id); continue }
      // Como o Java: str(type, "").toLowerCase(), sem trim — " choice" não é choice.
      const type = (str(q.type, '') as string).toLowerCase()
      if (!QUESTION_TYPES.has(type)) { add('QUESTION_TYPE', 'questions', id); continue }
      const criteria = q.criteria
      // Question.Choice exige ao menos uma opção; Question.Score, ao menos dois níveis.
      if (type === 'choice' && !(isObject(criteria) && Object.keys(criteria).length > 0)) {
        add('CHOICE_CRITERIA', 'questions', id)
        continue
      }
      if (type === 'score' && !(Array.isArray(criteria) && criteria.length >= 2)) {
        add('SCORE_CRITERIA', 'questions', id)
        continue
      }
      // Question.requireId: o id é a chave do JSON, e uma chave em branco é recusada.
      if (id.trim() === '') add('QUESTION_ID', 'questions', id)
    }
  }
  const questionsOk = ids.length > 0 && out.length === 0

  if (questionsOk) {
    if (Array.isArray(cfg.gate)) {
      for (const g of cfg.gate.map(String)) {
        if (!ids.includes(g)) add('UNKNOWN_GATE', 'gate', g)
      }
    }
    const primary = str(cfg.primary, ids[0]) as string
    if (!ids.includes(primary)) add('UNKNOWN_PRIMARY', 'primary', primary)
  }

  const onError = (str(cfg.onError, 'ESCALATE') as string).toUpperCase()
  if (onError !== 'ESCALATE' && onError !== 'FAIL') add('ON_ERROR', 'onError', String(cfg.onError))

  // RoutePolicy: 0 <= review <= auto <= 1.
  const th = isObject(cfg.thresholds) ? cfg.thresholds : {}
  const auto = num(th.auto, DEFAULT_AUTO)
  const review = num(th.review, DEFAULT_REVIEW)
  if (review < 0 || auto > 1 || review > auto) add('THRESHOLDS', 'thresholds', `auto=${auto}, review=${review}`)

  const acceptAt = num(cfg.acceptAt, auto)
  if (acceptAt < 0 || acceptAt > 1) add('ACCEPT_AT', 'acceptAt', String(acceptAt))

  if (isObject(cfg.consensus)) {
    const onDisagree = (str(cfg.consensus.onDisagree, 'REVIEW') as string).toUpperCase()
    if (onDisagree !== 'REVIEW' && onDisagree !== 'ESCALATE') {
      add('ON_DISAGREE', 'consensus', String(cfg.consensus.onDisagree))
    }
  }

  // RulesDecisionProvider: "sem regras para a pergunta" — o motor entrega providerOptions.rules ao provedor do
  // nó. Só trava quando nenhum degrau de cascade/fallbacks responde no lugar (otherCandidateMayAnswer).
  if (questionsOk && decisionProviderOf(cfg) === 'rules' && !otherCandidateMayAnswer(cfg, ids)) {
    const rules = rulesOf(cfg, decisionCandidates(cfg)[0])
    for (const id of ids) {
      if (!isObject(rules[id])) add('RULES_MISSING', 'providerOptions', id)
    }
  }
  return out
}

/**
 * A rota AUTO nunca sai deste nó: o provedor é o {@code llm}, cuja confiança é limitada a {@code maxConfidence}
 * (0,89 por padrão, LlmDecisionProvider) e fica abaixo do limiar {@code thresholds.auto} (0,9 por padrão). Uma
 * aresta AUTO como único caminho do caso comum nunca seria seguida. Sem cascata nem reserva: outro provedor ali
 * poderia chegar a AUTO, e então a regra não se aplica.
 */
export function decisionAutoUnreachable(config: Record<string, unknown> | undefined): boolean {
  const cfg = config ?? {}
  if (decisionProviderOf(cfg) !== 'llm') return false
  const hasTiers = (v: unknown) => Array.isArray(v) && v.length > 0
  if (hasTiers(cfg.cascade) || hasTiers(cfg.fallbacks)) return false
  const th = isObject(cfg.thresholds) ? cfg.thresholds : {}
  const llmOptions = isObject(cfg.providerOptions) && isObject(cfg.providerOptions.llm) ? cfg.providerOptions.llm : {}
  const teto = typeof llmOptions.maxConfidence === 'number'
    ? Math.max(0, Math.min(1, llmOptions.maxConfidence)) : LLM_DECISION_MAX_CONFIDENCE
  return teto < num(th.auto, DEFAULT_AUTO)
}

/** O problema em inglês, para o agente do copiloto (as recusas das ferramentas são em inglês). */
export function describeDecisionFindingForAgent(f: DecisionFinding): string {
  switch (f.problem) {
    case 'QUESTIONS_NOT_OBJECT': return 'questions must be an object mapping each question id to {type, instructions, criteria}'
    case 'QUESTION_ID': return 'a question id cannot be blank'
    case 'QUESTION_NOT_OBJECT': return `question "${f.subject}" must be an object {type, instructions, criteria}`
    case 'QUESTION_TYPE': return `question "${f.subject}" needs type choice, score or noul`
    case 'CHOICE_CRITERIA': return `choice question "${f.subject}" needs criteria mapping each option to its description`
    case 'SCORE_CRITERIA': return `score question "${f.subject}" needs criteria with at least 2 ordered levels`
    case 'UNKNOWN_PRIMARY': return `primary "${f.subject}" is not one of the question ids`
    case 'UNKNOWN_GATE': return `gate "${f.subject}" is not one of the question ids`
    case 'ON_ERROR': return 'onError must be ESCALATE or FAIL'
    case 'THRESHOLDS': return `thresholds need 0 <= review <= auto <= 1 (${f.subject})`
    case 'ACCEPT_AT': return 'acceptAt must be between 0 and 1'
    case 'ON_DISAGREE': return 'consensus.onDisagree must be REVIEW or ESCALATE'
    case 'RULES_MISSING': return `provider rules has no rule for question "${f.subject}" `
      + '(providerOptions.rules.rules.<question>)'
  }
}
