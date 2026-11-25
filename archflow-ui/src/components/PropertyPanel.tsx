import { useTranslation } from 'react-i18next'
import {
  Select, TextInput, Textarea, NumberInput, ScrollArea, Tabs,
  Switch, TagsInput, Badge, Text, Alert,
} from '@mantine/core'
import { useFlowStore }     from './FlowCanvas/store/useFlowStore'
import type { FlowNodeData } from './FlowCanvas/types'
import { NODE_CATEGORIES, NODE_TYPE_TO_CATEGORY, isNotExecutableNode } from './FlowCanvas/constants'
import { NodeIcon } from './FlowCanvas/nodeIcons'
import { FIELD_STYLES, MONO_INPUT } from './PropertyPanel/fieldStyles'
import { useWorkflowConfig } from './PropertyPanel/useWorkflowConfig'
import { FlowDefaultsPanel } from './PropertyPanel/FlowDefaultsPanel'
import { ReadOnlyFieldset } from './PropertyPanel/ReadOnlyFieldset'
import { useCatalog } from './PropertyPanel/useCatalog'
import { AgentFields } from './PropertyPanel/fields/AgentFields'
import {
  getConfig, numberUpdater, storedChoice, storedFlag, storedNumber, storedText,
} from './PropertyPanel/fields/helpers'
import { McpToolFields } from './PropertyPanel/fields/McpToolFields'
import { SubflowFields } from './PropertyPanel/fields/SubflowFields'
import { SkillsFields } from './PropertyPanel/fields/SkillsFields'
import { DecisionFields } from './PropertyPanel/fields/DecisionFields'
import { KnowledgeBaseField } from './PropertyPanel/fields/KnowledgeBaseField'
import { MEMORY_OPERATIONS, MEMORY_ROLES, knowledgeBaseOf } from './FlowCanvas/dataNodeKeys'
import { ServerConnectionField, VectorStoreFields } from './PropertyPanel/fields/VectorStoreFields'
import { ApprovalFields } from './PropertyPanel/fields/ApprovalFields'
import { ExecutionLogs, OutputPreview } from './PropertyPanel/fields/ExecutionPanels'
import { NodeIdField, UpstreamRefs } from './PropertyPanel/fields/NodeReferences'

// ── Embedding model catalog (tiny, kept local) ──────────────────
const EMBEDDING_MODELS = [
  { provider: 'openai', models: [
    { id: 'text-embedding-3-large', name: 'text-embedding-3-large', dimensions: 3072 },
    { id: 'text-embedding-3-small', name: 'text-embedding-3-small', dimensions: 1536 },
    { id: 'text-embedding-ada-002', name: 'text-embedding-ada-002', dimensions: 1536 },
  ]},
  { provider: 'local', models: [
    { id: 'minilm-l6-v2', name: 'MiniLM-L6-v2 (ONNX)', dimensions: 384 },
  ]},
]

// ── Helpers ──────────────────────────────────────────────────────

/**
 * Drops options with a repeated `value`, keeping the first. Mantine 9 throws
 * "Duplicate options are not supported" when a Select receives two options with
 * the same value — which the backend catalog can produce (e.g. the same model
 * id offered by more than one provider).
 */





// ── Main Component ──────────────────────────────────────────────

interface PropertyPanelProps {
  nodeId:   string | null
  nodeData: FlowNodeData | null
  /**
   * Sem workflow:update (RCHFLW-74) o painel só mostra: o canvas só leitura ainda seleciona o nó (o
   * React Flow chama onNodeClick mesmo com elementsSelectable=false) e, sem isto, os campos mudavam o
   * canvas sem nada que gravasse — e o Executar do EXECUTOR rodava a versão gravada, descartando-os.
   */
  readOnly?: boolean
}

export function PropertyPanel({ nodeId, nodeData, readOnly = false }: PropertyPanelProps) {
  const { t } = useTranslation()
  const { executionState } = useFlowStore()
  const execState = nodeId ? executionState[nodeId] : null

  return (
    <div
      style={{
        display:       'flex',
        flexDirection: 'column',
        height:        '100%',
        borderLeft:    '0.5px solid var(--color-border-tertiary)',
        background:    'var(--color-background-primary)',
        overflow:      'hidden',
      }}
    >
      {nodeData && nodeId ? (
        <>
          <NodeHeader nodeData={nodeData} />
          <Tabs defaultValue="properties" style={{ flex: 1, display: 'flex', flexDirection: 'column', overflow: 'hidden' }}>
            <Tabs.List style={{ padding: '0 12px', borderBottom: '0.5px solid var(--color-border-tertiary)' }}>
              <Tabs.Tab value="properties" style={{ fontSize: 12 }}>{t('editor.properties.tabs.properties')}</Tabs.Tab>
              <Tabs.Tab value="logs"       style={{ fontSize: 12 }}>{t('editor.properties.tabs.logs')}</Tabs.Tab>
              <Tabs.Tab value="output"     style={{ fontSize: 12 }}>{t('editor.properties.tabs.output')}</Tabs.Tab>
            </Tabs.List>

            <Tabs.Panel value="properties" style={{ flex: 1, overflow: 'hidden' }}>
              <ScrollArea style={{ height: '100%' }} p="md">
                <NodeFields nodeId={nodeId} nodeData={nodeData} readOnly={readOnly} />
              </ScrollArea>
            </Tabs.Panel>

            <Tabs.Panel value="logs" style={{ flex: 1, overflow: 'hidden' }}>
              <ScrollArea style={{ height: '100%' }} p="md">
                <ExecutionLogs execState={execState} />
              </ScrollArea>
            </Tabs.Panel>

            <Tabs.Panel value="output" style={{ flex: 1, overflow: 'hidden' }}>
              <ScrollArea style={{ height: '100%' }} p="md">
                <OutputPreview execState={execState} />
              </ScrollArea>
            </Tabs.Panel>
          </Tabs>
        </>
      ) : (
        <FlowDefaultsPanel readOnly={readOnly} />
      )}
    </div>
  )
}

// ── Node Header ─────────────────────────────────────────────────
function NodeHeader({ nodeData }: { nodeData: FlowNodeData }) {
  const { t } = useTranslation()
  const catKey = NODE_TYPE_TO_CATEGORY[nodeData.nodeType] ?? 'io'
  const cat = NODE_CATEGORIES[catKey]
  const catLabel = t(`categories.${catKey}`, { defaultValue: cat.label })

  return (
    <div style={{ padding: '12px 16px', borderBottom: '0.5px solid var(--color-border-tertiary)', display: 'flex', alignItems: 'center', gap: 10 }}>
      <div style={{ width: 32, height: 32, borderRadius: 8, background: cat.colorLight, border: `1px solid ${cat.border}`, color: cat.colorDark, display: 'flex', alignItems: 'center', justifyContent: 'center', flexShrink: 0 }}>
        <NodeIcon componentId={nodeData.nodeType} size={16} imageSize={28} />
      </div>
      <div>
        <div data-testid="property-panel-node-title" style={{ fontSize: 13, fontWeight: 500, color: 'var(--color-text-primary)' }}>{nodeData.label}</div>
        <div style={{ fontSize: 11, color: 'var(--color-text-tertiary)', fontFamily: 'var(--font-mono)' }}>
          {nodeData.nodeType} · {catLabel}
        </div>
      </div>
    </div>
  )
}

// ── Node Fields (all gaps implemented) ──────────────────────────
function NodeFields({ nodeId, nodeData, readOnly }: { nodeId: string; nodeData: FlowNodeData; readOnly: boolean }) {
  const { t } = useTranslation()
  const { updateNodeConfig, updateNodeLabel } = useFlowStore()
  const { providers } = useWorkflowConfig()
  const catalog = useCatalog()

  const isAgent     = ['agent', 'assistant', 'llm-chat', 'llm-streaming'].includes(nodeData.nodeType)
  const isAssistant = nodeData.nodeType === 'assistant'
  const isTool      = ['tool', 'function', 'custom'].includes(nodeData.nodeType)
  const isVector    = ['vector-search', 'vector-store'].includes(nodeData.nodeType)
  const isEmbedding = nodeData.nodeType === 'embedding'
  const isRag       = nodeData.nodeType === 'rag'
  const isMemory    = ['memory', 'memory-store'].includes(nodeData.nodeType)
  // Memória e Vector store não têm operação padrão no motor (LangChainAdapterComponent). A Memória tem o campo
  // Operação (RCHFLW-115); o Vector store ainda não: sem 'operation' escrita no nó, ele sempre falha — o painel diz,
  // e o Executar bloqueia.
  const hasOperation = storedChoice(nodeData, 'operation') != null
  // O embedding local exige caminhos que o painel não tem (LocalEmbeddingAdapter.validate).
  const localEmbeddingIncomplete = storedChoice(nodeData, 'embeddingProvider') === 'local'
    && (storedChoice(nodeData, 'local.model.path') == null || storedChoice(nodeData, 'local.vocab.path') == null)
  // ── New node families backed by existing backend surface ──────
  const isMcpTool         = nodeData.nodeType === 'mcp-tool'
  const isApproval        = nodeData.nodeType === 'approval'
  const isDecision        = nodeData.nodeType === 'decision'
  const isSubflow         = nodeData.nodeType === 'subflow'
  const isSkills          = nodeData.nodeType === 'skills'
  const isLinktorSend     = nodeData.nodeType === 'linktor-send'
  const isLinktorEscalate = nodeData.nodeType === 'linktor-escalate'
  // Control flow with previously-empty configs
  const isCondition       = nodeData.nodeType === 'condition'
  const isSwitch          = nodeData.nodeType === 'switch'
  const isLoop            = nodeData.nodeType === 'loop'
  const isParallel        = nodeData.nodeType === 'parallel'
  const isMerge           = nodeData.nodeType === 'merge'
  const isTransform       = ['transform', 'map', 'filter', 'reduce'].includes(nodeData.nodeType)
  const isPromptTemplate  = nodeData.nodeType === 'prompt-template'
  const isPromptChunk     = nodeData.nodeType === 'prompt-chunk'
  const isInput           = nodeData.nodeType === 'input'
  const isOutput          = nodeData.nodeType === 'output'
  const isOrchestrate     = nodeData.nodeType === 'orchestrate'

  const update = (key: string, value: unknown) => updateNodeConfig(nodeId, key, value)
  // A referência entra no fim da expressão já escrita; se o campo ainda mostra só o exemplo
  // (nada gravado), substitui — colar ao lado de `${agent.confidence} > 0.8` daria lixo.
  const insertRef = (key: string, snippet: string, replace = false) => {
    const atual = nodeData.config?.[key]
    const texto = typeof atual === 'string' ? atual : ''
    update(key, replace || texto.trim() === '' ? snippet : `${texto.replace(/\s+$/, '')} ${snippet}`)
  }
  // Shortcut for field translations; keeps JSX free of the long prefix.
  const f = (key: string, opts?: Record<string, unknown>) => t(`editor.properties.fields.${key}`, opts)
  const localEmbeddingWarning = (
    <Alert color="orange" variant="light" p="xs" data-testid="local-embedding-not-configurable">
      <Text size="xs">{f('localEmbeddingNotConfigurable')}</Text>
    </Alert>
  )
  // O painel só mostra o que está gravado (RCHFLW-92). Exemplo vai no placeholder como "ex.: X"; "padrão: X" só
  // quando o motor usa X com o campo vazio — conferido no Java (ex.: DynamicWorkflowService para o Orquestrar).
  const ex = (value: unknown) => f('exampleValue', { value: String(value) })
  const dflt = (value: unknown) => f('defaultValue', { value: String(value) })
  const num = (key: string) => numberUpdater(update, key)
  // Select desmarcado tira a chave, em vez de gravar null.
  const choose = (key: string) => (v: string | null) => update(key, v ?? undefined)
  // vector-search/rag com Base de conhecimento (RCHFLW-55): o backend de vetores não é usado.
  const withKnowledgeBase = (nodeData.nodeType === 'vector-search' || isRag) && knowledgeBaseOf(nodeData.config) != null
  const catalogStores = catalog.vectorstores ?? []
  const vectorBackends = catalogStores.length > 0
    ? catalogStores.map(v => ({ value: v.id, label: v.displayName }))
    : [
        { value: 'pgvector',  label: f('vectorBackendOptions.pgvector') },
        { value: 'pinecone',  label: f('vectorBackendOptions.pinecone') },
        { value: 'redis',     label: f('vectorBackendOptions.redis') },
        // Sem 'in-memory': não há adapter de vector store em memória — o nó caía em "component not found".
      ]


  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 14 }}>
      {/* Catálogo em modo fallback: o usuário precisa saber que as opções
          exibidas são a lista estática local, não o catálogo do servidor */}
      {catalog.usingFallback && (
        <Badge color="yellow" variant="light" size="sm" fullWidth radius="sm">
          {f('catalogOffline')}
        </Badge>
      )}

      {/* ── Common: Node name ─────────────────────────────────── */}
      <ReadOnlyFieldset readOnly={readOnly}>
        <TextInput
          label={f('nodeName')}
          value={nodeData.label}
          onChange={e => updateNodeLabel(nodeId, e.currentTarget.value)}
          size="xs"
          styles={FIELD_STYLES}
        />
      </ReadOnlyFieldset>

      {/* Id só leitura: é por ele (e não pelo nome) que as expressões referenciam a saída. Fica fora do
          fieldset de propósito: copiar o id não edita nada e serve a quem só lê. */}
      <NodeIdField nodeId={nodeId} />

      {/* Tipo sem execução no servidor (RCHFLW-11): o nó salva, mas o Executar é impedido enquanto ele estiver no fluxo */}
      {isNotExecutableNode(nodeData) && (
        <Alert color="orange" variant="light" p="xs" data-testid="panel-not-executable">
          <Text size="xs">{f('notExecutableHint')}</Text>
        </Alert>
      )}

      {/* llm-streaming saiu da paleta: roda como o llm-chat, sem streaming (RCHFLW-57) */}
      {nodeData.nodeType === 'llm-streaming' && (
        <Alert color="blue" variant="light" p="xs" data-testid="panel-llm-streaming-alias">
          <Text size="xs">{f('llmStreamingAlias')}</Text>
        </Alert>
      )}

      {/* Daqui ao fim, todos os campos do tipo do nó: travados sem workflow:update (RCHFLW-74). */}
      <ReadOnlyFieldset readOnly={readOnly}>

      {/* ════════════════════════════════════════════════════════ */}
      {/*  AGENT FIELDS (extraído para fields/AgentFields)        */}
      {/* ════════════════════════════════════════════════════════ */}
      {isAgent && (
        <AgentFields nodeData={nodeData} update={update} isAssistant={isAssistant} />
      )}

      {/* ════════════════════════════════════════════════════════ */}
      {/*  TOOL FIELDS                                           */}
      {/* ════════════════════════════════════════════════════════ */}
      {isTool && (
        <>
          {catalog.tools.length > 0 ? (
            <Select
              label={f('tool')}
              value={storedChoice(nodeData, 'toolId')}
              onChange={choose('toolId')}
              size="xs"
              data={catalog.tools.map(x => ({ value: x.id, label: x.displayName }))}
              styles={FIELD_STYLES}
              searchable
            />
          ) : (
            <TextInput
              label={f('toolId')}
              value={storedText(nodeData, 'toolId')}
              onChange={e => update('toolId', e.currentTarget.value)}
              size="xs"
              styles={MONO_INPUT}
            />
          )}

          {/* Gap 8: Retry policy */}
          <Select
            label={f('onError')}
            value={storedChoice(nodeData, 'onError')}
            onChange={choose('onError')}
            size="xs"
            data={[
              { value: 'stop',     label: f('onErrorOptions.stop') },
              { value: 'continue', label: f('onErrorOptions.continue') },
              { value: 'retry',    label: f('onErrorOptions.retry') },
            ]}
            styles={FIELD_STYLES}
          />

          {storedChoice(nodeData, 'onError') === 'retry' && (
            <div style={{ display: 'flex', flexDirection: 'column', gap: 10, padding: '8px 0' }}>
              <NumberInput
                label={f('retryMaxAttempts')}
                value={storedNumber(nodeData, 'retryMaxAttempts')}
                placeholder={ex(3)}
                onChange={num('retryMaxAttempts')}
                min={1}
                max={10}
                size="xs"
                styles={FIELD_STYLES}
              />
              <NumberInput
                label={f('retryInitialDelay')}
                value={storedNumber(nodeData, 'retryDelay')}
                placeholder={ex(1000)}
                onChange={num('retryDelay')}
                min={100}
                max={60000}
                step={100}
                size="xs"
                styles={FIELD_STYLES}
              />
              <NumberInput
                label={f('retryBackoff')}
                value={storedNumber(nodeData, 'retryMultiplier')}
                placeholder={ex(2.0)}
                onChange={num('retryMultiplier')}
                min={1.0}
                max={5.0}
                step={0.5}
                decimalScale={1}
                size="xs"
                styles={FIELD_STYLES}
              />
            </div>
          )}

          {/* Gap 4: Timeout for tools */}
          <NumberInput
            label={f('timeoutSec')}
            value={storedNumber(nodeData, 'timeout')}
            placeholder={ex(30)}
            onChange={num('timeout')}
            min={1}
            max={600}
            size="xs"
            styles={FIELD_STYLES}
          />
        </>
      )}

      {/* ════════════════════════════════════════════════════════ */}
      {/*  VECTOR STORE FIELDS (Gap 6)                           */}
      {/* ════════════════════════════════════════════════════════ */}
      {isVector && (
        <>
          {/* Base de conhecimento (RCHFLW-55): com ela a busca é a da tela de Conhecimento, e o backend não é usado */}
          {nodeData.nodeType === 'vector-search' && <KnowledgeBaseField nodeData={nodeData} update={update} />}

          {nodeData.nodeType === 'vector-store' && !hasOperation && (
            <Alert color="orange" variant="light" p="xs" data-testid="vector-store-does-not-write">
              <Text size="xs">{f('vectorStoreDoesNotWrite')}</Text>
            </Alert>
          )}

          {!withKnowledgeBase && (
            <VectorStoreFields nodeData={nodeData} update={update} backends={vectorBackends} />
          )}

          {nodeData.nodeType === 'vector-search' && (
            <NumberInput
              label={f('topK')}
              value={storedNumber(nodeData, 'topK')}
              // Com base: o padrão da busca do Conhecimento (KnowledgeNodeComponent.DEFAULT_LIMIT). Sem base, o
              // adapter recebe o pedido sem maxResults e vale o do LangChain4j — exemplo, não padrão do motor.
              placeholder={withKnowledgeBase ? dflt(5) : ex(5)}
              onChange={num('topK')}
              min={1}
              max={withKnowledgeBase ? 50 : 100}
              size="xs"
              styles={FIELD_STYLES}
            />
          )}
        </>
      )}

      {/* ════════════════════════════════════════════════════════ */}
      {/*  EMBEDDING FIELDS (Gap 11)                             */}
      {/* ════════════════════════════════════════════════════════ */}
      {isEmbedding && (
        <>
          {catalog.embeddings.length > 0 ? (
            <Select
              label={f('embeddingProvider')}
              value={storedChoice(nodeData, 'embeddingProvider')}
              onChange={choose('embeddingProvider')}
              size="xs"
              data={catalog.embeddings.map(e => ({ value: e.id, label: e.displayName }))}
              styles={FIELD_STYLES}
            />
          ) : (
            <>
              <Select
                label={f('embeddingProvider')}
                value={storedChoice(nodeData, 'embeddingProvider')}
                onChange={v => {
                  update('embeddingProvider', v ?? undefined)
                  const first = EMBEDDING_MODELS.find(e => e.provider === v)?.models[0]
                  if (first) {
                    update('embeddingModel', first.id)
                    update('embeddingDimensions', first.dimensions)
                  }
                }}
                size="xs"
                data={[
                  { value: 'openai', label: f('embeddingProviderOptions.openai') },
                  { value: 'local',  label: f('embeddingProviderOptions.local') },
                ]}
                styles={FIELD_STYLES}
              />
              <Select
                label={f('model')}
                value={storedChoice(nodeData, 'embeddingModel')}
                onChange={v => {
                  update('embeddingModel', v ?? undefined)
                  const entry = EMBEDDING_MODELS.find(e => e.provider === storedChoice(nodeData, 'embeddingProvider'))
                  const m = entry?.models.find(mm => mm.id === v)
                  if (m) update('embeddingDimensions', m.dimensions)
                }}
                size="xs"
                data={(EMBEDDING_MODELS.find(e => e.provider === storedChoice(nodeData, 'embeddingProvider'))?.models ?? [])
                  .map(m => ({ value: m.id, label: m.name }))}
                styles={FIELD_STYLES}
              />
            </>
          )}
          {localEmbeddingIncomplete && localEmbeddingWarning}
          <NumberInput
            label={f('dimensions')}
            value={storedNumber(nodeData, 'embeddingDimensions')}
            placeholder={ex(1536)}
            onChange={num('embeddingDimensions')}
            min={1}
            max={4096}
            size="xs"
            styles={FIELD_STYLES}
          />
        </>
      )}

      {/* ════════════════════════════════════════════════════════ */}
      {/*  MEMORY FIELDS                                         */}
      {/* ════════════════════════════════════════════════════════ */}
      {/* A operação é escolha do autor — o motor não tem padrão (guardar, ler ou limpar) e o Executar a exige.
          As opções são as que os adapters de memória aceitam (LangChainAdapterComponent.MEMORY_OPERATIONS). */}
      {isMemory && (
        <Select
          label={f('memoryOperation')}
          withAsterisk
          value={storedChoice(nodeData, 'operation')}
          onChange={choose('operation')}
          size="xs"
          data={MEMORY_OPERATIONS.map(op => ({ value: op, label: f(`memoryOperationOptions.${op}`) }))}
          description={f('memoryOperationHint')}
          styles={FIELD_STYLES}
        />
      )}
      {/* O add guarda a entrada como uma mensagem; os adapters exigem saber de quem ela é. */}
      {isMemory && storedChoice(nodeData, 'operation') === 'add' && (
        <Select
          label={f('memoryRole')}
          withAsterisk
          value={storedChoice(nodeData, 'memoryRole')}
          onChange={choose('memoryRole')}
          size="xs"
          data={MEMORY_ROLES.map(role => ({ value: role, label: f(`memoryRoleOptions.${role}`) }))}
          styles={FIELD_STYLES}
        />
      )}
      {isMemory && catalog.memories.length > 0 && (
        <>
          <Select
            label={f('memoryBackend')}
            value={storedChoice(nodeData, 'memoryBackend')}
            onChange={choose('memoryBackend')}
            size="xs"
            data={catalog.memories.map(m => ({ value: m.id, label: m.displayName }))}
            styles={FIELD_STYLES}
          />
          {storedChoice(nodeData, 'memoryBackend') === 'redis' && (
            <ServerConnectionField nodeData={nodeData} update={update} backend="redis" />
          )}
          {storedChoice(nodeData, 'memoryBackend') === 'jdbc' && (
            <Alert color="orange" variant="light" p="xs" data-testid="memory-jdbc-not-connected">
              <Text size="xs">{f('jdbcMemoryNotConnected')}</Text>
            </Alert>
          )}
          <NumberInput
            label={f('maxMessages')}
            value={storedNumber(nodeData, 'memoryMaxMessages')}
            // O padrão dos dois adapters de memória (RedisMemoryAdapter, JdbcMemoryAdapter).
            placeholder={dflt(100)}
            onChange={num('memoryMaxMessages')}
            min={1}
            max={5000}
            size="xs"
            styles={FIELD_STYLES}
          />
        </>
      )}

      {/* ════════════════════════════════════════════════════════ */}
      {/*  RAG CHAIN FIELDS                                      */}
      {/* ════════════════════════════════════════════════════════ */}
      {isRag && (
        <>
          {/* Com base (RCHFLW-55): busca no Conhecimento e responde pelo chat do nó — embedding e vector store
              são os da base. Sem base: o RagChainAdapter, com os três providers e o vector store dele. */}
          <KnowledgeBaseField nodeData={nodeData} update={update} />
          {!withKnowledgeBase && (
            <>
              <Select
                label={f('embeddingProvider')}
                withAsterisk
                value={storedChoice(nodeData, 'embeddingProvider')}
                onChange={choose('embeddingProvider')}
                size="xs"
                data={catalog.embeddings.length > 0
                    ? catalog.embeddings.map(e => ({ value: e.id, label: e.displayName }))
                    : [{ value: 'openai', label: f('embeddingProviderOptions.openai') }, { value: 'local', label: f('embeddingProviderOptions.local') }]}
                styles={FIELD_STYLES}
              />
              {localEmbeddingIncomplete && localEmbeddingWarning}
              <VectorStoreFields nodeData={nodeData} update={update} backends={vectorBackends} label={f('vectorStore')} />
            </>
          )}
          <Select
            label={f('llmProvider')}
            // Com base, o chat herda o provider do fluxo/plataforma como um LLM Chat; o RagChainAdapter não herda.
            withAsterisk={!withKnowledgeBase}
            value={storedChoice(nodeData, 'llmProvider')}
            onChange={choose('llmProvider')}
            size="xs"
            data={providers.map(p => ({ value: p.id, label: p.displayName }))}
            styles={FIELD_STYLES}
          />
          <NumberInput
            label={f('topKRetrieval')}
            value={storedNumber(nodeData, 'retrieverMaxResults')}
            // Padrões reais de cada caminho: a busca do Conhecimento (5, KnowledgeNodeComponent) e o
            // RagChainAdapter (2).
            placeholder={dflt(withKnowledgeBase ? 5 : 2)}
            onChange={num('retrieverMaxResults')}
            min={1}
            max={50}
            size="xs"
            styles={FIELD_STYLES}
          />
          <NumberInput
            label={f('minScore')}
            value={storedNumber(nodeData, 'retrieverMinScore')}
            placeholder={dflt(withKnowledgeBase ? 0 : 0.7)}
            onChange={num('retrieverMinScore')}
            min={0}
            max={1}
            step={0.05}
            decimalScale={2}
            size="xs"
            styles={FIELD_STYLES}
          />
        </>
      )}

      {/* ════════════════════════════════════════════════════════ */}
      {/*  MCP TOOL CALL                                        */}
      {/* ════════════════════════════════════════════════════════ */}
      {isMcpTool && (
        <McpToolFields nodeData={nodeData} update={update} />
      )}

      {/* ════════════════════════════════════════════════════════ */}
      {/*  HUMAN APPROVAL                                       */}
      {/* ════════════════════════════════════════════════════════ */}
      {/* ════════════════════════════════════════════════════════ */}
      {/*  ORCHESTRATE (dynamic multi-agent workflow)             */}
      {/* ════════════════════════════════════════════════════════ */}
      {isOrchestrate && (
        <>
          <Textarea
            label={t('dynamicWorkflow.goal')}
            placeholder={t('dynamicWorkflow.goalPlaceholder')}
            value={storedText(nodeData, 'goal')}
            onChange={e => update('goal', e.currentTarget.value)}
            autosize minRows={2}
            size="xs"
            styles={FIELD_STYLES}
          />
          <Textarea
            label={t('dynamicWorkflow.decomposePrompt')}
            placeholder={t('dynamicWorkflow.decomposePromptPlaceholder')}
            value={storedText(nodeData, 'decomposePrompt')}
            onChange={e => update('decomposePrompt', e.currentTarget.value)}
            autosize minRows={1}
            size="xs"
            styles={FIELD_STYLES}
          />
          <NumberInput
            label={t('dynamicWorkflow.maxSubtasks')}
            value={storedNumber(nodeData, 'maxSubtasks')}
            placeholder={dflt(8)}
            onChange={num('maxSubtasks')}
            min={1}
            size="xs"
            styles={FIELD_STYLES}
          />
          <NumberInput
            label={t('dynamicWorkflow.voters')}
            value={storedNumber(nodeData, 'voters')}
            placeholder={dflt(1)}
            onChange={num('voters')}
            min={1}
            size="xs"
            styles={FIELD_STYLES}
          />
          <NumberInput
            label={t('dynamicWorkflow.maxRounds')}
            value={storedNumber(nodeData, 'maxRounds')}
            placeholder={dflt(5)}
            onChange={num('maxRounds')}
            min={1}
            size="xs"
            styles={FIELD_STYLES}
          />
          <NumberInput
            label={t('dynamicWorkflow.concurrency')}
            value={storedNumber(nodeData, 'concurrency')}
            placeholder={dflt(4)}
            onChange={num('concurrency')}
            min={1}
            size="xs"
            styles={FIELD_STYLES}
          />
          <NumberInput
            label={t('dynamicWorkflow.budgetTokens')}
            value={storedNumber(nodeData, 'budgetTokens')}
            onChange={num('budgetTokens')}
            min={0}
            step={1000}
            placeholder="∞"
            size="xs"
            styles={FIELD_STYLES}
          />
        </>
      )}

      {isDecision && <DecisionFields nodeData={nodeData} update={update} />}

      {isApproval && <ApprovalFields nodeData={nodeData} update={update} />}

      {/* ════════════════════════════════════════════════════════ */}
      {/*  SUBFLOW                                               */}
      {/* ════════════════════════════════════════════════════════ */}
      {isSubflow && (
        <SubflowFields nodeData={nodeData} update={update} />
      )}

      {/* ════════════════════════════════════════════════════════ */}
      {/*  SKILLS                                                */}
      {/* ════════════════════════════════════════════════════════ */}
      {isSkills && (
        <SkillsFields nodeData={nodeData} update={update} />
      )}

      {/* ════════════════════════════════════════════════════════ */}
      {/*  LINKTOR — SEND MESSAGE                               */}
      {/* ════════════════════════════════════════════════════════ */}
      {isLinktorSend && (
        <>
          <TextInput
            label={f('conversationIdSource')}
            value={storedText(nodeData, 'linktorConversationExpr')}
            placeholder={ex('${context.conversationId}')}
            onChange={e => update('linktorConversationExpr', e.currentTarget.value)}
            size="xs"
            styles={MONO_INPUT}
            description={f('conversationIdHint')}
          />
          <Textarea
            label={f('messageText')}
            value={storedText(nodeData, 'linktorMessageText')}
            onChange={e => update('linktorMessageText', e.currentTarget.value)}
            autosize minRows={2}
            size="xs"
            styles={FIELD_STYLES}
            description={f('messageTextHint')}
          />
          <Select
            label={f('failurePolicy')}
            value={storedChoice(nodeData, 'linktorSendOnError')}
            onChange={choose('linktorSendOnError')}
            size="xs"
            data={[
              { value: 'warn',  label: f('failurePolicyOptions.warn') },
              { value: 'stop',  label: f('failurePolicyOptions.stop') },
              { value: 'retry', label: f('failurePolicyOptions.retry') },
            ]}
            styles={FIELD_STYLES}
          />
        </>
      )}

      {/* ════════════════════════════════════════════════════════ */}
      {/*  LINKTOR — ESCALATE (handoff)                         */}
      {/* ════════════════════════════════════════════════════════ */}
      {isLinktorEscalate && (
        <>
          <TextInput
            label={f('conversationIdSource')}
            value={storedText(nodeData, 'linktorConversationExpr')}
            placeholder={ex('${context.conversationId}')}
            onChange={e => update('linktorConversationExpr', e.currentTarget.value)}
            size="xs"
            styles={MONO_INPUT}
          />
          <TextInput
            label={f('targetUser')}
            value={storedText(nodeData, 'linktorTargetUser')}
            onChange={e => update('linktorTargetUser', e.currentTarget.value)}
            size="xs"
            styles={FIELD_STYLES}
            description={f('targetUserHint')}
          />
          <Textarea
            label={f('reason')}
            value={storedText(nodeData, 'linktorReason')}
            placeholder={ex('Agent escalated')}
            onChange={e => update('linktorReason', e.currentTarget.value)}
            autosize minRows={2}
            size="xs"
            styles={FIELD_STYLES}
          />
        </>
      )}

      {/* ════════════════════════════════════════════════════════ */}
      {/*  CONDITION                                            */}
      {/* ════════════════════════════════════════════════════════ */}
      {isCondition && (
        <>
          <Textarea
            label={f('expression')}
            withAsterisk
            value={storedText(nodeData, 'conditionExpression')}
            placeholder={ex('${agent.confidence} > 0.8')}
            onChange={e => update('conditionExpression', e.currentTarget.value)}
            autosize minRows={2}
            size="xs"
            styles={MONO_INPUT}
            description={f('expressionHint')}
          />
          <UpstreamRefs nodeId={nodeId} onInsert={s => insertRef('conditionExpression', s)} />
          <Text size="xs" c="dimmed" mt={2}>
            {f('errorBranchHint_pre')} <Badge size="xs" color="red" variant="outline">{f('errorBranchLabel')}</Badge> {f('errorBranchHint_post')}
          </Text>
        </>
      )}

      {/* ════════════════════════════════════════════════════════ */}
      {/*  SWITCH                                               */}
      {/* ════════════════════════════════════════════════════════ */}
      {isSwitch && (
        <>
          <TextInput
            label={f('switchExpression')}
            withAsterisk
            value={storedText(nodeData, 'switchExpression')}
            placeholder={ex('${agent.intent}')}
            onChange={e => update('switchExpression', e.currentTarget.value)}
            size="xs"
            styles={MONO_INPUT}
            description={f('switchExpressionHint')}
          />
          {/* O switch compara um valor só: a referência substitui a expressão */}
          <UpstreamRefs nodeId={nodeId} onInsert={s => insertRef('switchExpression', s, true)} />
          <Textarea
            label={f('switchCases')}
            withAsterisk
            value={storedText(nodeData, 'switchCases')}
            placeholder={ex('order_tracking\ncomplaint\nsales\ndefault')}
            onChange={e => update('switchCases', e.currentTarget.value)}
            autosize minRows={3}
            size="xs"
            styles={MONO_INPUT}
            description={f('switchCasesHint')}
          />
        </>
      )}

      {/* ════════════════════════════════════════════════════════ */}
      {/*  LOOP                                                 */}
      {/* ════════════════════════════════════════════════════════ */}
      {isLoop && (
        <>
          <TextInput
            label={f('collectionExpression')}
            value={storedText(nodeData, 'loopCollection')}
            placeholder={ex('${input.items}')}
            onChange={e => update('loopCollection', e.currentTarget.value)}
            size="xs"
            styles={MONO_INPUT}
          />
          <TextInput
            label={f('itemVariable')}
            value={storedText(nodeData, 'loopItemVar')}
            placeholder={ex('item')}
            onChange={e => update('loopItemVar', e.currentTarget.value)}
            size="xs"
            styles={FIELD_STYLES}
            description={f('itemVariableHint')}
          />
          <NumberInput
            label={f('maxIterations')}
            value={storedNumber(nodeData, 'loopMaxIterations')}
            placeholder={ex(100)}
            onChange={num('loopMaxIterations')}
            min={1} max={10_000}
            size="xs"
            styles={FIELD_STYLES}
          />
          <Switch
            checked={storedFlag(nodeData, 'loopParallel')}
            onChange={e => update('loopParallel', e.currentTarget.checked)}
            label={f('runInParallel')}
            size="xs"
          />
        </>
      )}

      {/* ════════════════════════════════════════════════════════ */}
      {/*  PARALLEL                                             */}
      {/* ════════════════════════════════════════════════════════ */}
      {isParallel && (
        <>
          <Select
            label={f('waitStrategy')}
            value={storedChoice(nodeData, 'parallelStrategy')}
            onChange={choose('parallelStrategy')}
            size="xs"
            data={[
              { value: 'all',     label: f('waitStrategyOptions.all') },
              { value: 'any',     label: f('waitStrategyOptions.any') },
              { value: 'majority', label: f('waitStrategyOptions.majority') },
            ]}
            styles={FIELD_STYLES}
          />
          <NumberInput
            label={f('timeoutSec')}
            value={storedNumber(nodeData, 'parallelTimeoutSeconds')}
            placeholder={ex(60)}
            onChange={num('parallelTimeoutSeconds')}
            min={1} max={3600}
            size="xs"
            styles={FIELD_STYLES}
          />
          <Switch
            checked={storedFlag(nodeData, 'parallelFailFast')}
            onChange={e => update('parallelFailFast', e.currentTarget.checked)}
            label={f('failFast')}
            size="xs"
          />
        </>
      )}

      {/* ════════════════════════════════════════════════════════ */}
      {/*  MERGE                                                */}
      {/* ════════════════════════════════════════════════════════ */}
      {isMerge && (
        <>
          <Select
            label={f('mergeStrategy')}
            value={storedChoice(nodeData, 'mergeStrategy')}
            onChange={choose('mergeStrategy')}
            size="xs"
            data={[
              { value: 'concat',      label: f('mergeStrategyOptions.concat') },
              { value: 'deepMerge',   label: f('mergeStrategyOptions.deepMerge') },
              { value: 'firstNonNull', label: f('mergeStrategyOptions.firstNonNull') },
              { value: 'last',        label: f('mergeStrategyOptions.last') },
            ]}
            styles={FIELD_STYLES}
          />
          <TextInput
            label={f('outputVariable')}
            value={storedText(nodeData, 'mergeOutputVar')}
            placeholder={ex('merged')}
            onChange={e => update('mergeOutputVar', e.currentTarget.value)}
            size="xs"
            styles={FIELD_STYLES}
            description={f('outputVariableHint')}
          />
        </>
      )}

      {/* ════════════════════════════════════════════════════════ */}
      {/*  DATA TRANSFORM (transform/map/filter/reduce)          */}
      {/* ════════════════════════════════════════════════════════ */}
      {isTransform && (
        <>
          <TextInput
            label={f('inputExpression')}
            value={storedText(nodeData, 'transformInput')}
            placeholder={ex('${input}')}
            onChange={e => update('transformInput', e.currentTarget.value)}
            size="xs"
            styles={MONO_INPUT}
          />
          <Textarea
            label={
              nodeData.nodeType === 'filter'   ? f('transformFilter') :
              nodeData.nodeType === 'reduce'   ? f('transformReduce') :
              nodeData.nodeType === 'map'      ? f('transformMap') :
              f('transformExpression')
            }
            value={storedText(nodeData, 'transformExpression')}
            onChange={e => update('transformExpression', e.currentTarget.value)}
            autosize minRows={3}
            size="xs"
            styles={MONO_INPUT}
            description={f('transformHint')}
          />
          {nodeData.nodeType === 'reduce' && (
            <TextInput
              label={f('reduceInitial')}
              value={storedText(nodeData, 'reduceInitial')}
              placeholder={ex('0')}
              onChange={e => update('reduceInitial', e.currentTarget.value)}
              size="xs"
              styles={MONO_INPUT}
            />
          )}
          <TextInput
            label={f('outputVariable')}
            value={storedText(nodeData, 'transformOutputVar')}
            placeholder={ex('output')}
            onChange={e => update('transformOutputVar', e.currentTarget.value)}
            size="xs"
            styles={FIELD_STYLES}
          />
        </>
      )}

      {/* ════════════════════════════════════════════════════════ */}
      {/*  PROMPT TEMPLATE / CHUNK                              */}
      {/* ════════════════════════════════════════════════════════ */}
      {(isPromptTemplate || isPromptChunk) && (
        <>
          <Textarea
            label={isPromptChunk ? f('promptChunk') : f('promptTemplate')}
            value={storedText(nodeData, 'promptText')}
            onChange={e => update('promptText', e.currentTarget.value)}
            autosize minRows={4}
            size="xs"
            styles={MONO_INPUT}
            description={isPromptChunk ? f('promptChunkHint') : f('promptTemplateHint')}
          />
          {isPromptTemplate && (
            <TagsInput
              label={f('requiredVariables')}
              value={getConfig<string[]>(nodeData, 'promptVariables', [])}
              onChange={v => update('promptVariables', v)}
              placeholder={f('addVariable')}
              size="xs"
              styles={FIELD_STYLES}
              description={f('requiredVariablesHint')}
            />
          )}
          {isPromptChunk && (
            <NumberInput
              label={f('chunkOrder')}
              value={storedNumber(nodeData, 'promptChunkOrder')}
              placeholder={ex(0)}
              onChange={num('promptChunkOrder')}
              min={0} max={100}
              size="xs"
              styles={FIELD_STYLES}
            />
          )}
          <TextInput
            label={f('outputVariable')}
            value={storedText(nodeData, 'promptOutputVar')}
            placeholder={ex('prompt')}
            onChange={e => update('promptOutputVar', e.currentTarget.value)}
            size="xs"
            styles={FIELD_STYLES}
          />
        </>
      )}

      {/* ════════════════════════════════════════════════════════ */}
      {/*  INPUT / OUTPUT                                       */}
      {/* ════════════════════════════════════════════════════════ */}
      {(isInput || isOutput) && (
        <>
          <Text size="xs" c="dimmed" lh={1.4} data-testid="io-pass-through-hint">
            {isInput ? f('inputPassThroughHint') : f('outputPassThroughHint')}
          </Text>
          <TagsInput
            label={isInput ? f('expectedInputs') : f('exportedOutputs')}
            value={getConfig<string[]>(nodeData, 'ioFields', [])}
            onChange={v => update('ioFields', v)}
            placeholder={f('fieldNamePlaceholder')}
            size="xs"
            styles={FIELD_STYLES}
          />
          <Textarea
            label={f('jsonSchema')}
            value={storedText(nodeData, 'ioSchema')}
            onChange={e => update('ioSchema', e.currentTarget.value)}
            autosize minRows={3}
            size="xs"
            styles={MONO_INPUT}
            description={f('jsonSchemaHint')}
          />
          {isInput && (
            <Switch
              checked={storedFlag(nodeData, 'inputRequired')}
              onChange={e => update('inputRequired', e.currentTarget.checked)}
              label={f('rejectIfMissing')}
              size="xs"
            />
          )}
        </>
      )}
      </ReadOnlyFieldset>
    </div>
  )
}

// ── MCP tool fields ─────────────────────────────────────────────
