import type { TFunction } from 'i18next'
import { isAnnotationStep, isNotExecutableNode } from './constants'
import { CONNECTION_FIELD_BY_BACKEND, NODE_TYPES_WITHOUT_DEFAULT_OPERATION, knowledgeBaseOf } from './dataNodeKeys'
import { invalidControlExpressions, type ExpressionProblem } from './controlExpressions'
import { decisionConfigFindings, decisionModelMissing, type DecisionProblem } from './decisionReadiness'

/**
 * O que impede o Executar do editor de começar (RCHFLW-11, RCHFLW-92): nós de tipo sem execução e campos que
 * o motor exige e o nó não tem.
 *
 * <p>Sem isto o editor salvava, disparava o run e o usuário só descobria pelo erro da execução — e, no caso
 * dos campos, depois de o painel ter mostrado um valor de exemplo como se estivesse gravado (a Condição
 * aparecia com {@code ${agent.confidence} > 0.8} e o motor recebia {@code {}}).</p>
 */

/**
 * Campos que o motor exige, por tipo de nó, com a chave i18n do rótulo do campo no painel. Cada um é uma
 * recusa real do backend: ControlStep ("'conditionExpression' é obrigatória", "'switchExpression' é
 * obrigatória") e DecisionComponent ("'questions' exige ao menos uma pergunta"). Os casos do Switch não são
 * recusados pelo motor — sem eles todo valor cai no ramo "default" —, mas um Switch sem casos não decide
 * nada, e o painel os trata como parte obrigatória do nó.
 */
export const REQUIRED_NODE_FIELDS: Readonly<Record<string, readonly RequiredField[]>> = {
  condition: [{ key: 'conditionExpression', labelKey: 'editor.properties.fields.expression' }],
  switch: [
    { key: 'switchExpression', labelKey: 'editor.properties.fields.switchExpression' },
    { key: 'switchCases', labelKey: 'editor.properties.fields.switchCases' },
  ],
  decision: [{ key: 'questions', labelKey: 'editor.properties.fields.decisionQuestions' }],
}

/**
 * Um campo que o motor exige. {@code alternatives} são as chaves nativas do adapter que o motor também aceita no
 * lugar da do designer (um nó escrito em YAML/API com {@code pgvector.dimension}, {@code provider}...): o editor
 * não pode recusar o que o servidor roda (DesignerAdapterKeys.providerOf/translate).
 */
type RequiredField = { key: string; labelKey: string; alternatives?: readonly string[] }
const field = (key: string, label: string, alternatives?: readonly string[]): RequiredField =>
  ({ key, labelKey: `editor.properties.fields.${label}`, alternatives })

const text = (v: unknown): string | null => (typeof v === 'string' && v.trim() !== '' ? v.trim() : null)

/**
 * O backend de um nó de dados como o motor o resolve (DesignerAdapterKeys.providerOf): o {@code provider} explícito
 * vence o campo do designer.
 */
const backendOf = (config: Record<string, unknown>, designerKey: string) =>
  text(config.provider) ?? text(config[designerKey])

/** Chave nativa da dimensão, por backend (PgVectorStoreAdapter/RedisVectorStoreAdapter/PineconeVectorStoreAdapter). */
const DIMENSION_KEY_BY_BACKEND: Readonly<Record<string, string>> = {
  pgvector: 'pgvector.dimension', redis: 'redis.dimension', pinecone: 'pinecone.dimension',
}

/**
 * O vector store de um nó de dados (RCHFLW-56): o backend, a conexão do servidor que ele exige
 * (LangChainAdapterComponent recusa sem ela, dizendo a propriedade) e a dimensão, que os três adapters exigem
 * (PgVectorStoreAdapter/RedisVectorStoreAdapter/PineconeVectorStoreAdapter.validate).
 */
function vectorStoreFields(backend: string | null, backendField: RequiredField): RequiredField[] {
  if (backend == null) return [backendField]
  const out: RequiredField[] = []
  const conn = CONNECTION_FIELD_BY_BACKEND[backend]
  if (conn) out.push(field(conn, 'serverConnection', [`${backend}.connection`]))
  const dimension = DIMENSION_KEY_BY_BACKEND[backend]
  if (dimension) out.push(field(backend === 'pgvector' ? 'pgDimensions' : 'vectorDimensions', 'dimensions', [dimension]))
  return out
}

/**
 * O embedding local exige os caminhos do modelo e do vocabulário e a dimensão (LocalEmbeddingAdapter.validate). O
 * painel não tem os caminhos: sem eles escritos à mão no nó, a execução falha sempre.
 */
const localEmbeddingFields = (): RequiredField[] => [
  field('local.model.path', 'localModelPath'),
  field('local.vocab.path', 'localVocabPath'),
  field('embeddingDimensions', 'dimensions', ['local.dimension']),
]

/**
 * A operação, que o painel ainda não oferece: os tipos de NODE_TYPES_WITHOUT_DEFAULT_OPERATION recusam sem ela no
 * motor — um padrão inventado faria o "Armazena embeddings" buscar, ou a memória adivinhar entre guardar e ler.
 */
const operationField = field('operation', 'operationNotInPanel')

/**
 * Obrigatórios que dependem do que o nó já escolheu. Com Base de conhecimento (RCHFLW-55) a busca não usa o vector
 * store, e o chat do RAG herda o provider como um LLM Chat; sem ela, o RagChainAdapter exige os três providers.
 */
export const CONDITIONAL_REQUIRED_FIELDS: Readonly<Record<string, (config: Record<string, unknown>) => RequiredField[]>> = {
  'vector-search': config => (knowledgeBaseOf(config) ? [] : vectorStoreFields(
    backendOf(config, 'vectorBackend'), field('vectorBackend', 'vectorBackend', ['provider']))),
  'vector-store': config => [...vectorStoreFields(
    backendOf(config, 'vectorBackend'), field('vectorBackend', 'vectorBackend', ['provider']))],
  rag: config => {
    if (knowledgeBaseOf(config)) return []
    // No RAG o campo do designer vence a chave nativa (DesignerAdapterKeys.translate: vectorBackend →
    // vectorstore.provider), ao contrário do provider dos outros nós.
    const embedding = text(config.embeddingProvider) ?? text(config['embedding.provider'])
    return [
      field('embeddingProvider', 'embeddingProvider', ['embedding.provider']),
      ...(embedding === 'local' ? localEmbeddingFields() : []),
      ...vectorStoreFields(text(config.vectorBackend) ?? text(config['vectorstore.provider']),
        field('vectorBackend', 'vectorStore', ['vectorstore.provider'])),
      field('llmProvider', 'llmProvider', ['languagemodel.provider']),
    ]
  },
  embedding: config => [
    field('embeddingProvider', 'embeddingProvider', ['provider']),
    ...(backendOf(config, 'embeddingProvider') === 'local' ? localEmbeddingFields() : []),
  ],
  memory: config => [
    field('memoryBackend', 'memoryBackend', ['provider']),
    ...(backendOf(config, 'memoryBackend') === 'redis'
      ? [field('redisConnection', 'serverConnection', ['redis.connection'])] : []),
  ],
  // RCHFLW-116: sem provider o motor usa o http-decisions, que exige o model — sem ele a decisão nem é pedida e,
  // com onError ESCALATE, todo pedido sai pela escalada (decisionReadiness).
  decision: config => (decisionModelMissing(config) ? [field('model', 'decisionModel')] : []),
}

export interface RunCandidateStep {
  id:           string
  label?:       string
  componentId?: string
  type?:        string
  /** A operação no nível do nó — o motor lê config.operation e, sem ela, esta (DefaultFlowStepFactory). */
  operation?:   string
  config?:      Record<string, unknown>
}

export interface RunBlockers {
  /** Nós de tipo sem execução, na ordem do canvas. */
  notExecutable: { id: string; label: string; type: string }[]
  /** Campos obrigatórios vazios: o nó e a chave i18n do rótulo do campo. */
  missing: { id: string; label: string; labelKey: string }[]
  /**
   * Expressões de condition/switch que o motor não avalia ou que leem um passo inexistente (RCHFLW-116) — o
   * servidor recusa o mesmo antes do primeiro passo. Opcional: quem monta um RunBlockers à mão não precisa dele.
   */
  invalidExpressions?: { id: string; label: string; labelKey: string; problem: ExpressionProblem; fragment: string }[]
  /**
   * Configuração de um nó decision que o DecisionComponent recusa (RCHFLW-116): pergunta sem tipo ou sem critérios,
   * primary/gate que não são perguntas, limiares fora de ordem, provider rules sem a regra de uma pergunta.
   * Opcional, como invalidExpressions.
   */
  invalidDecisions?: { id: string; label: string; labelKey: string; problem: DecisionProblem; subject: string }[]
}

/** O campo do painel de cada chave do decision, para a mensagem nomear o que o usuário corrige. */
const DECISION_FIELD_LABEL: Readonly<Record<string, string>> = {
  questions: 'decisionQuestions', primary: 'decisionPrimary', gate: 'decisionGate', onError: 'decisionOnError',
  thresholds: 'thresholdAuto', acceptAt: 'decisionAcceptAt', consensus: 'decisionOnDisagree',
  providerOptions: 'decisionRules',
}

/** Vazio para o motor: ausente, texto em branco, lista ou objeto sem nada. */
function isEmpty(value: unknown): boolean {
  if (value == null) return true
  if (typeof value === 'string') return value.trim() === ''
  if (Array.isArray(value)) return value.length === 0
  if (typeof value === 'object') return Object.keys(value as object).length === 0
  return false
}

function kindOf(step: RunCandidateStep): string {
  const raw = [step.componentId, step.type].find(k => typeof k === 'string' && k.trim() !== '')
  return (raw ?? '').trim().toLowerCase()
}

export function runBlockers(steps: RunCandidateStep[]): RunBlockers {
  const result: RunBlockers = { notExecutable: [], missing: [], invalidExpressions: [], invalidDecisions: [] }
  const runnable = steps.filter(step => !isAnnotationStep(step))
  for (const inv of invalidControlExpressions(runnable)) {
    const labelKey = REQUIRED_NODE_FIELDS[inv.field === 'conditionExpression' ? 'condition' : 'switch'][0].labelKey
    for (const f of inv.findings) result.invalidExpressions!.push({ id: inv.id, label: inv.label, labelKey, ...f })
  }
  for (const step of steps) {
    if (isAnnotationStep(step)) continue
    const label = step.label?.trim() || step.id
    const kind = kindOf(step)
    if (isNotExecutableNode(step)) {
      // O nó inteiro já impede o run; listar os campos dele seria pedir para preencher o que não roda.
      result.notExecutable.push({ id: step.id, label, type: kind })
      continue
    }
    const required = [
      ...(REQUIRED_NODE_FIELDS[kind] ?? []),
      ...(NODE_TYPES_WITHOUT_DEFAULT_OPERATION.has(kind) ? [operationField] : []),
      ...(CONDITIONAL_REQUIRED_FIELDS[kind]?.(step.config ?? {}) ?? []),
    ]
    const config: Record<string, unknown> = { ...(step.config ?? {}) }
    if (isEmpty(config.operation) && step.operation != null) config.operation = step.operation
    for (const f of required) {
      if ([f.key, ...(f.alternatives ?? [])].every(k => isEmpty(config[k]))) {
        result.missing.push({ id: step.id, label, labelKey: f.labelKey })
      }
    }
    if (kind === 'decision') {
      for (const f of decisionConfigFindings(step.config)) {
        result.invalidDecisions!.push({
          id: step.id, label, labelKey: `editor.properties.fields.${DECISION_FIELD_LABEL[f.key]}`,
          problem: f.problem, subject: f.subject,
        })
      }
    }
  }
  return result
}

export function hasRunBlockers(blockers: RunBlockers): boolean {
  return blockers.notExecutable.length > 0 || blockers.missing.length > 0
    || (blockers.invalidExpressions?.length ?? 0) > 0 || (blockers.invalidDecisions?.length ?? 0) > 0
}

/** A mensagem do aviso, nomeando cada nó (e o campo): é o que o usuário precisa para corrigir. */
export function runBlockersMessage(t: TFunction, blockers: RunBlockers): string {
  const parts: string[] = []
  if (blockers.notExecutable.length > 0) {
    parts.push(t('editor.notif.runBlockedNotExecutable', {
      nodes: blockers.notExecutable.map(n => `"${n.label}" (${n.type})`).join(', '),
    }))
  }
  if (blockers.missing.length > 0) {
    parts.push(t('editor.notif.runBlockedMissing', {
      fields: blockers.missing.map(m => `"${m.label}" → ${t(m.labelKey)}`).join(', '),
    }))
  }
  if (blockers.invalidExpressions?.length) {
    parts.push(t('editor.notif.runBlockedInvalidExpression', {
      fields: blockers.invalidExpressions.map(m => `"${m.label}" → ${t(m.labelKey)}: `
        + t(`editor.notif.expressionProblem.${m.problem}`, { fragment: m.fragment, root: m.fragment.split('.')[0] }))
        .join('; '),
    }))
  }
  if (blockers.invalidDecisions?.length) {
    parts.push(t('editor.notif.runBlockedInvalidDecision', {
      fields: blockers.invalidDecisions.map(m => `"${m.label}" → ${t(m.labelKey)}: `
        + t(`editor.notif.decisionProblem.${m.problem}`, { subject: m.subject })).join('; '),
    }))
  }
  return parts.join(' ')
}
