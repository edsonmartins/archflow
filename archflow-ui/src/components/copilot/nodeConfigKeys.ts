/**
 * Chaves de config que o copiloto pode gravar em cada tipo de nó (ferramenta configureNode e config do
 * addNode), com o tipo do valor.
 *
 * <p>Por que uma lista fechada, e não "qualquer chave": o painel de propriedades e o motor só leem
 * chaves conhecidas. Uma chave inventada pelo modelo ({@code prompt} no lugar de {@code systemPrompt},
 * {@code expression} no lugar de {@code conditionExpression}) seria gravada sem erro, o agente
 * resumiria "nó configurado" e o fluxo seguiria falhando com config vazia — o defeito do RCHFLW-78
 * escondido atrás de um ok. Recusar com a lista certa deixa o agente corrigir na rodada seguinte.</p>
 *
 * <p>As chaves são as que o PropertyPanel grava ({@code update('chave', …)}) para cada tipo — e o
 * teste {@code nodeConfigKeys.test.ts} confere que nenhuma daqui deixou de existir no painel. A
 * seleção de provedor/modelo continua com o painel; aqui {@code provider}/{@code model} são só
 * valores (o id do provedor e o do modelo).</p>
 */
import { NODE_TYPE_TO_CATEGORY, PALETTE_NODES } from '../FlowCanvas/constants'

/**
 * Tipo do valor. {@code json} é um objeto/array cuja FORMA é conferida por {@link JSON_SHAPES} — o motor lê
 * {@code questions} como mapa id → pergunta e {@code thresholds} como {auto, review}; aceitar "qualquer objeto"
 * devolvia ok ao agente para uma config que só quebra ao executar.
 */
export type ConfigValueType = 'string' | 'number' | 'boolean' | 'string[]' | 'json'
export type ConfigKeys = Readonly<Record<string, ConfigValueType>>

// llm-chat / llm-streaming são uma chamada de chat: só os campos de chat (ver AgentFields.isChat).
const CHAT: ConfigKeys = {
  provider: 'string',
  model: 'string',
  systemPrompt: 'string',
  temperature: 'number',
  maxTokens: 'number',
  personaId: 'string',
}

// agent / assistant: o chat mais o laço do agente, memória, governança de ferramentas e servidores MCP.
const AGENT_LOOP: ConfigKeys = {
  ...CHAT,
  agentPattern: 'string',
  maxIterations: 'number',
  timeout: 'number',
  memoryBackend: 'string',
  redisUrl: 'string',
  jdbcDatasource: 'string',
  governanceProfileId: 'string',
  enabledTools: 'string[]',
  disabledTools: 'string[]',
  escalationThreshold: 'number',
  maxToolExecutions: 'number',
  humanEscalation: 'boolean',
  customInstructions: 'string',
  mcpServers: 'string[]',
  mcpTransport: 'string',
  mcpCommand: 'string',
  mcpUrl: 'string',
}

const TOOL: ConfigKeys = {
  toolId: 'string',
  onError: 'string',
  retryMaxAttempts: 'number',
  retryDelay: 'number',
  retryMultiplier: 'number',
  timeout: 'number',
}

// Sem pineconeApiKey: segredo não entra no documento do fluxo pela mão do modelo (nem sai do canvas para o
// contexto dele — ver visibleConfig). O painel segue aceitando a chave digitada pelo usuário.
const VECTOR: ConfigKeys = {
  vectorBackend: 'string',
  pineconeEnvironment: 'string',
  pineconeIndex: 'string',
  pgDatasource: 'string',
  pgTableName: 'string',
  pgDimensions: 'number',
  vectorRedisUrl: 'string',
  vectorRedisPrefix: 'string',
  topK: 'number',
}

const MEMORY: ConfigKeys = { memoryBackend: 'string', memoryMaxMessages: 'number' }

const TRANSFORM: ConfigKeys = {
  transformInput: 'string',
  transformExpression: 'string',
  transformOutputVar: 'string',
}

const IO: ConfigKeys = { ioFields: 'string[]', ioSchema: 'string' }

const LINKTOR_CONVERSATION: ConfigKeys = { linktorConversationExpr: 'string' }

export const NODE_CONFIG_KEYS: Readonly<Record<string, ConfigKeys>> = {
  agent: { ...AGENT_LOOP, agentTypeId: 'string' },
  assistant: { ...AGENT_LOOP, assistantTypeId: 'string' },
  'llm-chat': CHAT,
  'llm-streaming': CHAT,
  orchestrate: {
    goal: 'string',
    decomposePrompt: 'string',
    maxSubtasks: 'number',
    voters: 'number',
    maxRounds: 'number',
    concurrency: 'number',
    budgetTokens: 'number',
  },
  condition: { conditionExpression: 'string' },
  switch: { switchExpression: 'string', switchCases: 'string' },
  loop: { loopCollection: 'string', loopItemVar: 'string', loopMaxIterations: 'number', loopParallel: 'boolean' },
  parallel: { parallelStrategy: 'string', parallelTimeoutSeconds: 'number', parallelFailFast: 'boolean' },
  transform: TRANSFORM,
  map: TRANSFORM,
  filter: TRANSFORM,
  reduce: { ...TRANSFORM, reduceInitial: 'string' },
  merge: { mergeStrategy: 'string', mergeOutputVar: 'string' },
  tool: TOOL,
  function: TOOL,
  custom: TOOL,
  'vector-search': VECTOR,
  'vector-store': VECTOR,
  embedding: { embeddingProvider: 'string', embeddingModel: 'string', embeddingDimensions: 'number' },
  rag: {
    embeddingProvider: 'string',
    vectorBackend: 'string',
    llmProvider: 'string',
    retrieverMaxResults: 'number',
    retrieverMinScore: 'number',
  },
  memory: MEMORY,
  'prompt-template': { promptText: 'string', promptVariables: 'string[]', promptOutputVar: 'string' },
  'prompt-chunk': { promptText: 'string', promptChunkOrder: 'number', promptOutputVar: 'string' },
  input: { ...IO, inputRequired: 'boolean' },
  output: IO,
  skills: { skillsOperation: 'string', skillName: 'string', skillResource: 'string', skillsOutputVar: 'string' },
  approval: {
    approvalTitle: 'string',
    approvalDescription: 'string',
    approvalProposal: 'string',
    approvalTimeoutMinutes: 'number',
    approvalOnTimeout: 'string',
  },
  decision: {
    provider: 'string',
    model: 'string',
    questions: 'json',
    state: 'json',
    primary: 'string',
    gate: 'string[]',
    thresholds: 'json',
    onError: 'string',
    acceptAt: 'number',
    cascade: 'json',
    fallbacks: 'json',
    consensus: 'json',
    providerOptions: 'json',
  },
  subflow: {
    subflowId: 'string',
    subflowInput: 'string',
    subflowOutputVar: 'string',
    subflowTimeoutSeconds: 'number',
    subflowAsync: 'boolean',
  },
  'mcp-tool': { mcpServer: 'string', mcpTool: 'string', mcpArguments: 'string', mcpOutputVar: 'string' },
  'linktor-send': { ...LINKTOR_CONVERSATION, linktorMessageText: 'string', linktorSendOnError: 'string' },
  'linktor-escalate': { ...LINKTOR_CONVERSATION, linktorTargetUser: 'string', linktorReason: 'string' },
  // Anotações: o texto e a cor que o próprio nó edita (StickyNote/GroupFrame/SectionDivider).
  'sticky-note': { text: 'string', color: 'string' },
  'group-frame': { label: 'string', color: 'string' },
  'section-divider': { label: 'string', color: 'string' },
}

/** Os tipos de nó que o copiloto pode criar: os da paleta. */
export const COPILOT_NODE_TYPES: readonly string[] = PALETTE_NODES.map(n => n.componentId)

export function isAnnotationType(nodeType: string): boolean {
  return NODE_TYPE_TO_CATEGORY[nodeType] === 'annotation'
}

/**
 * O mapa tipo → "chave:tipo; …" (com a forma esperada das chaves json), compacto, para o contexto do agente:
 * é enviado a cada rodada, e a forma de objeto aninhado custaria várias vezes mais tokens.
 */
export function configKeysSummary(): Record<string, string> {
  return Object.fromEntries(Object.entries(NODE_CONFIG_KEYS).map(([type, keys]) => [
    type, Object.entries(keys).map(([k, v]) => {
      const shape = JSON_SHAPES[type]?.[k]
      return shape ? `${k}:${v} ${shape.hint}` : `${k}:${v}`
    }).join('; '),
  ]))
}

// ── O que sai do canvas para o provedor do LLM ──

/**
 * Marca do que foi escondido do agente. Um texto que a contenha não pode voltar ao nó: o agente que reescreve
 * um redisUrl copiando o que viu gravaria a marca no lugar da senha.
 */
export const REDACTED = '<redacted>'

/** Nome de campo que guarda segredo (pineconeApiKey, clientSecret, accessToken, headers.Authorization…). */
const SECRET_FIELD =
  /(password|passwd|pwd|secret|token|api[-_]?key|access[-_]?key|private[-_]?key|authorization|credentials?)$/i
/** Parâmetro de URL/DSN que costuma levar credencial (?token=, ?key=, ;password=, &sig=…). */
const SECRET_PARAM = /pass|pwd|secret|token|key|auth|sig|credential/i

/**
 * Esconde as credenciais de um texto: o userinfo de uma URL/DSN ({@code redis://:s3cret@cache} →
 * {@code redis://<redacted>@cache}) e o valor dos parâmetros com nome de segredo. Vale para todo texto, não só
 * para as chaves "de URL": um mcpUrl, um jdbcDatasource ou um endpoint dentro de um JSON levam o mesmo risco.
 */
export function redactText(text: string): string {
  return text
    .replace(/\/\/[^/?#@\s]+@/g, `//${REDACTED}@`)
    .replace(/([?&;])([^=&;#\s]+)=([^&;#\s]*)/g,
      (all, sep: string, name: string) => (SECRET_PARAM.test(name) ? `${sep}${name}=${REDACTED}` : all))
}

function redactValue(value: unknown): unknown {
  if (typeof value === 'string') return redactText(value)
  if (Array.isArray(value)) return value.map(redactValue)
  if (typeof value === 'object' && value !== null) {
    return Object.fromEntries(Object.entries(value)
      .map(([k, v]) => [k, SECRET_FIELD.test(k) ? REDACTED : redactValue(v)]))
  }
  return value
}

function containsRedacted(value: unknown): boolean {
  if (typeof value === 'string') return value.includes(REDACTED)
  if (Array.isArray(value)) return value.some(containsRedacted)
  if (typeof value === 'object' && value !== null) return Object.values(value).some(containsRedacted)
  return false
}

/**
 * A config de um nó como o agente a vê no contexto (e no resultado do configureNode): só as chaves conhecidas
 * do tipo, e sem credenciais. O contexto vai ao provedor do LLM a cada rodada — e, antes do RCHFLW-78, a config
 * nem saía do canvas; filtrar só pelo nome da chave deixava passar a senha de um {@code redis://:pass@host}.
 */
export function visibleConfig(nodeType: string, config: Record<string, unknown> | undefined) {
  const keys = NODE_CONFIG_KEYS[nodeType] ?? {}
  return Object.fromEntries(Object.entries(config ?? {})
    .filter(([k]) => Object.prototype.hasOwnProperty.call(keys, k))
    .map(([k, v]) => [k, SECRET_FIELD.test(k) ? REDACTED : redactValue(v)]))
}

// ── Forma das chaves json (nó decision) ──

/** Confere a forma de um valor json; devolve o problema, em texto para o agente, ou undefined. */
interface JsonShape {
  /** A forma esperada, compacta: vai no contexto do agente ao lado da chave. */
  hint: string
  check: (value: unknown) => string | undefined
}

const isObject = (v: unknown): v is Record<string, unknown> =>
  typeof v === 'object' && v !== null && !Array.isArray(v)
const extraKeys = (o: Record<string, unknown>, allowed: readonly string[]) =>
  Object.keys(o).filter(k => !allowed.includes(k))
const firstProblem = (problems: (string | undefined)[]) => problems.find(p => p !== undefined)

/**
 * Opções de provedor que o copiloto pode gravar: só as regras do provedor {@code rules} (o que o painel grava).
 * {@code endpoint}/{@code baseUrl} dizem para ONDE vai a chave da plataforma resolvida por {@code keyRef}, e
 * {@code headers}/{@code maxConfidence} mexem em credencial e governança — um texto injetado no objetivo do
 * "Gerar com IA" não pode desviá-los com um toast "Configured" que o usuário nem lê.
 */
const COPILOT_OPTION_KEYS = ['rules'] as const

function checkOptions(value: unknown, where: string): string | undefined {
  if (!isObject(value)) return `${where} must be an object`
  const extra = extraKeys(value, COPILOT_OPTION_KEYS)
  if (extra.length) {
    return `${where} may only set ${COPILOT_OPTION_KEYS.join(', ')}; ${extra.join(', ')} (endpoint, baseUrl, `
      + 'keyRef, headers, maxConfidence…) can only be set by the user in the properties panel'
  }
  if ('rules' in value && !isObject(value.rules)) return `${where}.rules must be an object`
  return undefined
}

const TIER_KEYS = ['provider', 'model', 'options'] as const

/** Um degrau de cascata/reserva/consenso: {provider, model, options} (DecisionComponent.tentar). */
function checkTier(value: unknown, where: string): string | undefined {
  if (!isObject(value)) return `${where} must be an object {provider, model, options}`
  const extra = extraKeys(value, TIER_KEYS)
  if (extra.length) return `${where} has unknown keys ${extra.join(', ')} (allowed: ${TIER_KEYS.join(', ')})`
  for (const k of ['provider', 'model'] as const) {
    if (k in value && typeof value[k] !== 'string') return `${where}.${k} must be a string`
  }
  return 'options' in value ? checkOptions(value.options, `${where}.options`) : undefined
}

function checkTiers(value: unknown, where: string): string | undefined {
  if (!Array.isArray(value)) return `${where} must be a list of {provider, model, options}`
  return firstProblem(value.map((t, i) => checkTier(t, `${where}[${i}]`)))
}

const QUESTION_TYPES = ['choice', 'score', 'noul', 'boolean', 'yesno']

/** Uma pergunta, como DecisionComponent.parseQuestions a lê — o que ele recusaria, recusamos antes. */
function checkQuestion(id: string, q: unknown): string | undefined {
  const where = `questions.${id}`
  if (!isObject(q)) return `${where} must be an object {type, instructions, criteria}`
  const extra = extraKeys(q, ['type', 'instructions', 'criteria'])
  if (extra.length) return `${where} has unknown keys ${extra.join(', ')} (allowed: type, instructions, criteria)`
  const type = typeof q.type === 'string' ? q.type.toLowerCase() : ''
  if (!QUESTION_TYPES.includes(type)) return `${where}.type must be choice, score or noul`
  if ('instructions' in q && typeof q.instructions !== 'string') return `${where}.instructions must be a string`
  const { criteria } = q
  if (type === 'choice' && !(isObject(criteria) && Object.keys(criteria).length > 0)) {
    return `${where}.criteria must map each option to its description, e.g. {"billing":"payment issues"}`
  }
  if (type === 'score' && !(Array.isArray(criteria) && criteria.length >= 2)) {
    return `${where}.criteria must be the ordered list of at least 2 levels, lowest first`
  }
  if ((type === 'noul' || type === 'boolean' || type === 'yesno') && criteria !== undefined) {
    if (!isObject(criteria) || extraKeys(criteria, ['true', 'false']).length) {
      return `${where}.criteria must be {"true":"...","false":"..."}`
    }
  }
  return undefined
}

const unitInterval = (v: unknown) => typeof v === 'number' && Number.isFinite(v) && v >= 0 && v <= 1

export const JSON_SHAPES: Readonly<Record<string, Readonly<Record<string, JsonShape>>>> = {
  decision: {
    questions: {
      hint: '{"<id>":{type:choice|score|noul, instructions, criteria}} (choice criteria {"<option>":"<description>"}'
        + ' / score criteria ["<lowest>",…,"<highest>"] / noul criteria {"true":"…","false":"…"})',
      check: v => {
        if (!isObject(v) || Object.keys(v).length === 0) {
          return 'questions must be an object mapping each question id to {type, instructions, criteria} '
            + '(not a list), with at least one question'
        }
        return firstProblem(Object.entries(v).map(([id, q]) => checkQuestion(id, q)))
      },
    },
    state: {
      hint: '{"<name>":"${template}"}',
      check: v => (isObject(v) ? undefined : 'state must be an object, e.g. {"ticket":"${input}"}'),
    },
    thresholds: {
      hint: '{auto:0..1, review:0..1, review<=auto}',
      check: v => {
        if (!isObject(v)) return 'thresholds must be an object {auto, review}'
        const extra = extraKeys(v, ['auto', 'review'])
        if (extra.length) return `thresholds has unknown keys ${extra.join(', ')} (allowed: auto, review)`
        for (const k of ['auto', 'review'] as const) {
          if (k in v && !unitInterval(v[k])) return `thresholds.${k} must be a number between 0 and 1`
        }
        const auto = 'auto' in v ? (v.auto as number) : 0.9
        const review = 'review' in v ? (v.review as number) : 0.5
        return review > auto ? 'thresholds.review must be <= thresholds.auto' : undefined
      },
    },
    cascade: { hint: '[{provider, model, options?:{rules}}]', check: v => checkTiers(v, 'cascade') },
    fallbacks: { hint: '[{provider, model, options?:{rules}}]', check: v => checkTiers(v, 'fallbacks') },
    consensus: {
      hint: '{models:[{provider, model}], onDisagree:REVIEW|ESCALATE}',
      check: v => {
        if (!isObject(v)) return 'consensus must be an object {models, onDisagree}'
        const extra = extraKeys(v, ['models', 'onDisagree'])
        if (extra.length) return `consensus has unknown keys ${extra.join(', ')} (allowed: models, onDisagree)`
        if ('onDisagree' in v && !['REVIEW', 'ESCALATE'].includes(String(v.onDisagree).toUpperCase())) {
          return 'consensus.onDisagree must be REVIEW or ESCALATE'
        }
        return 'models' in v ? checkTiers(v.models, 'consensus.models') : undefined
      },
    },
    providerOptions: {
      hint: '{"rules":{"rules":{…}}} (only the rules of the rules provider)',
      check: v => {
        if (!isObject(v)) return 'providerOptions must be an object keyed by provider id'
        return firstProblem(Object.entries(v).map(([id, o]) => checkOptions(o, `providerOptions.${id}`)))
      },
    },
  },
}

/**
 * O patch validado: {@code set} são os valores já convertidos para o tipo da chave; {@code unset} as
 * chaves que voltam ao padrão (o agente mandou {@code null}).
 */
export interface ValidatedConfig {
  set: Record<string, unknown>
  unset: string[]
}

export type ConfigValidation =
  | { ok: true; config: ValidatedConfig }
  | { ok: false; error: string; allowedKeys: Record<string, ConfigValueType> }

/**
 * A config pode chegar como objeto ou como texto JSON: o AgUiAgentController anuncia todo parâmetro de
 * ferramenta como string (toSchema), então um modelo obediente ao schema manda o objeto serializado.
 */
export function parseConfigArg(raw: unknown): { ok: true; value: Record<string, unknown> | undefined }
  | { ok: false; error: string } {
  if (raw === undefined || raw === null || raw === '') return { ok: true, value: undefined }
  let value = raw
  if (typeof raw === 'string') {
    try {
      value = JSON.parse(raw)
    } catch {
      return { ok: false, error: 'config must be a JSON object, e.g. {"systemPrompt":"..."}' }
    }
  }
  if (typeof value !== 'object' || value === null || Array.isArray(value)) {
    return { ok: false, error: 'config must be a JSON object, e.g. {"systemPrompt":"..."}' }
  }
  return { ok: true, value: value as Record<string, unknown> }
}

/** Converte um valor para o tipo da chave; undefined quando não dá (o chamador monta o erro). */
function coerce(value: unknown, type: ConfigValueType): { ok: true; value: unknown } | { ok: false } {
  switch (type) {
    case 'string':
      // Número/booleano viram texto (um id de modelo "4" ou uma cor), mas objeto não: seria "[object Object]".
      if (typeof value === 'string') return { ok: true, value }
      if (typeof value === 'number' || typeof value === 'boolean') return { ok: true, value: String(value) }
      return { ok: false }
    case 'number': {
      // O painel grava número (NumberInput); "0.7" gravado como texto quebraria a comparação do motor.
      const n = typeof value === 'string' && value.trim() !== '' ? Number(value) : value
      return typeof n === 'number' && Number.isFinite(n) ? { ok: true, value: n } : { ok: false }
    }
    case 'boolean':
      if (typeof value === 'boolean') return { ok: true, value }
      if (value === 'true' || value === 'false') return { ok: true, value: value === 'true' }
      return { ok: false }
    case 'string[]':
      if (Array.isArray(value) && value.every(v => typeof v === 'string' || typeof v === 'number')) {
        return { ok: true, value: value.map(String) }
      }
      // Como o campo "gate" do painel: uma lista separada por vírgulas.
      if (typeof value === 'string') {
        return { ok: true, value: value.split(',').map(s => s.trim()).filter(Boolean) }
      }
      return { ok: false }
    case 'json':
      if (typeof value === 'object' && value !== null) return { ok: true, value }
      if (typeof value === 'string') {
        try {
          const parsed: unknown = JSON.parse(value)
          if (typeof parsed === 'object' && parsed !== null) return { ok: true, value: parsed }
        } catch { /* cai no erro abaixo */ }
      }
      return { ok: false }
  }
}

/**
 * Valida o patch de config de um nó do tipo {@code nodeType}. Tudo ou nada: se uma chave for
 * desconhecida ou um valor não servir, nada é aplicado — um patch pela metade deixaria o agente sem
 * saber o que ficou gravado.
 */
export function validateNodeConfig(nodeType: string, patch: Record<string, unknown>): ConfigValidation {
  const keys = NODE_CONFIG_KEYS[nodeType]
  if (!keys) {
    return {
      ok: false,
      error: `node type "${nodeType}" has no config keys the copilot can set; `
        + 'ask the user to configure it in the properties panel',
      allowedKeys: {},
    }
  }
  const errors: string[] = []
  const set: Record<string, unknown> = {}
  const unset: string[] = []
  for (const [key, value] of Object.entries(patch)) {
    const type = Object.prototype.hasOwnProperty.call(keys, key) ? keys[key] : undefined
    if (!type) {
      errors.push(`unknown config key "${key}" for ${nodeType}`)
      continue
    }
    if (value === null) {
      unset.push(key)
      continue
    }
    if (containsRedacted(value)) {
      errors.push(`"${key}" contains ${REDACTED}: that part of the current value is hidden from you — leave `
        + `"${key}" out of the patch to keep it, or ask the user to change it in the properties panel`)
      continue
    }
    const coerced = coerce(value, type)
    if (!coerced.ok) {
      errors.push(`"${key}" must be ${type}`)
      continue
    }
    if (type === 'json') {
      // Sem forma conhecida, uma chave json não é gravável pelo copiloto: "qualquer objeto" é o ok enganoso.
      const shape = JSON_SHAPES[nodeType]?.[key]
      const problem = shape ? shape.check(coerced.value) : `"${key}" cannot be set by the copilot`
      if (problem) {
        errors.push(problem)
        continue
      }
    }
    set[key] = coerced.value
  }
  if (errors.length) return { ok: false, error: errors.join('; '), allowedKeys: { ...keys } }
  return { ok: true, config: { set, unset } }
}

/** Aplica um patch validado sobre a config atual, sem mutá-la. */
export function applyConfig(current: Record<string, unknown> | undefined, patch: ValidatedConfig) {
  const next: Record<string, unknown> = { ...(current ?? {}), ...patch.set }
  for (const key of patch.unset) delete next[key]
  return next
}
