import { useTranslation } from 'react-i18next'
import {
  Select, TextInput, Textarea, NumberInput, ScrollArea, Tabs,
  Switch, TagsInput, Badge, Text,
} from '@mantine/core'
import { useFlowStore }     from './FlowCanvas/store/useFlowStore'
import type { FlowNodeData } from './FlowCanvas/types'
import { NODE_CATEGORIES }  from './FlowCanvas/constants'
import { FIELD_STYLES, MONO_INPUT } from './PropertyPanel/fieldStyles'
import { useWorkflowConfig } from './PropertyPanel/useWorkflowConfig'
import { FlowDefaultsPanel } from './PropertyPanel/FlowDefaultsPanel'
import { useCatalog } from './PropertyPanel/useCatalog'
import { AgentFields } from './PropertyPanel/fields/AgentFields'
import { getConfig } from './PropertyPanel/fields/helpers'
import { McpToolFields } from './PropertyPanel/fields/McpToolFields'
import { SubflowFields } from './PropertyPanel/fields/SubflowFields'
import { SkillsFields } from './PropertyPanel/fields/SkillsFields'
import { ExecutionLogs, OutputPreview } from './PropertyPanel/fields/ExecutionPanels'

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
}

export function PropertyPanel({ nodeId, nodeData }: PropertyPanelProps) {
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
                <NodeFields nodeId={nodeId} nodeData={nodeData} />
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
        <FlowDefaultsPanel />
      )}
    </div>
  )
}

// ── Node Header ─────────────────────────────────────────────────
function NodeHeader({ nodeData }: { nodeData: FlowNodeData }) {
  const { t } = useTranslation()
  const catKey = nodeData.nodeType as keyof typeof NODE_CATEGORIES
  const cat = NODE_CATEGORIES[catKey] ?? NODE_CATEGORIES.io
  const catLabel = t(`categories.${catKey}`, { defaultValue: cat.label })

  return (
    <div style={{ padding: '12px 16px', borderBottom: '0.5px solid var(--color-border-tertiary)', display: 'flex', alignItems: 'center', gap: 10 }}>
      <div style={{ width: 32, height: 32, borderRadius: 8, background: cat.colorLight, border: `1px solid ${cat.border}`, display: 'flex', alignItems: 'center', justifyContent: 'center', fontSize: 14, flexShrink: 0 }}>
        {(nodeData.config?.icon as string) ?? '●'}
      </div>
      <div>
        <div style={{ fontSize: 13, fontWeight: 500, color: 'var(--color-text-primary)' }}>{nodeData.label}</div>
        <div style={{ fontSize: 11, color: 'var(--color-text-tertiary)', fontFamily: 'var(--font-mono)' }}>
          {nodeData.nodeType} · {catLabel}
        </div>
      </div>
    </div>
  )
}

// ── Node Fields (all gaps implemented) ──────────────────────────
function NodeFields({ nodeId, nodeData }: { nodeId: string; nodeData: FlowNodeData }) {
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
  // ── New node families backed by existing backend surface ──────
  const isMcpTool         = nodeData.nodeType === 'mcp-tool'
  const isApproval        = nodeData.nodeType === 'approval'
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
  // Shortcut for field translations; keeps JSX free of the long prefix.
  const f = (key: string, opts?: Record<string, unknown>) => t(`editor.properties.fields.${key}`, opts)


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
      <TextInput
        label={f('nodeName')}
        value={nodeData.label}
        onChange={e => updateNodeLabel(nodeId, e.currentTarget.value)}
        size="xs"
        styles={FIELD_STYLES}
      />

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
              value={getConfig(nodeData, 'toolId', catalog.tools[0].id)}
              onChange={v => update('toolId', v)}
              size="xs"
              data={catalog.tools.map(x => ({ value: x.id, label: x.displayName }))}
              styles={FIELD_STYLES}
              searchable
            />
          ) : (
            <TextInput
              label={f('toolId')}
              value={getConfig(nodeData, 'toolId', '')}
              onChange={e => update('toolId', e.currentTarget.value)}
              size="xs"
              styles={MONO_INPUT}
            />
          )}

          {/* Gap 8: Retry policy */}
          <Select
            label={f('onError')}
            value={getConfig(nodeData, 'onError', 'stop')}
            onChange={v => update('onError', v)}
            size="xs"
            data={[
              { value: 'stop',     label: f('onErrorOptions.stop') },
              { value: 'continue', label: f('onErrorOptions.continue') },
              { value: 'retry',    label: f('onErrorOptions.retry') },
            ]}
            styles={FIELD_STYLES}
          />

          {getConfig(nodeData, 'onError', 'stop') === 'retry' && (
            <div style={{ display: 'flex', flexDirection: 'column', gap: 10, padding: '8px 0' }}>
              <NumberInput
                label={f('retryMaxAttempts')}
                value={getConfig(nodeData, 'retryMaxAttempts', 3)}
                onChange={v => update('retryMaxAttempts', v)}
                min={1}
                max={10}
                size="xs"
                styles={FIELD_STYLES}
              />
              <NumberInput
                label={f('retryInitialDelay')}
                value={getConfig(nodeData, 'retryDelay', 1000)}
                onChange={v => update('retryDelay', v)}
                min={100}
                max={60000}
                step={100}
                size="xs"
                styles={FIELD_STYLES}
              />
              <NumberInput
                label={f('retryBackoff')}
                value={getConfig(nodeData, 'retryMultiplier', 2.0)}
                onChange={v => update('retryMultiplier', v)}
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
            value={getConfig(nodeData, 'timeout', 30)}
            onChange={v => update('timeout', v)}
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
          <Select
            label={f('vectorBackend')}
            value={getConfig(nodeData, 'vectorBackend', catalog.vectorstores[0]?.id ?? 'pgvector')}
            onChange={v => update('vectorBackend', v)}
            size="xs"
            data={catalog.vectorstores.length > 0
                ? catalog.vectorstores.map(v => ({ value: v.id, label: v.displayName }))
                : [
                    { value: 'pgvector',  label: f('vectorBackendOptions.pgvector') },
                    { value: 'pinecone',  label: f('vectorBackendOptions.pinecone') },
                    { value: 'redis',     label: f('vectorBackendOptions.redis') },
                    { value: 'in-memory', label: f('vectorBackendOptions.in-memory') },
                  ]}
            styles={FIELD_STYLES}
          />

          {getConfig(nodeData, 'vectorBackend', 'pgvector') === 'pinecone' && (
            <>
              <TextInput
                label={f('pineconeApiKey')}
                value={getConfig(nodeData, 'pineconeApiKey', '')}
                onChange={e => update('pineconeApiKey', e.currentTarget.value)}
                type="password"
                size="xs"
                styles={FIELD_STYLES}
              />
              <TextInput
                label={f('environment')}
                value={getConfig(nodeData, 'pineconeEnvironment', '')}
                onChange={e => update('pineconeEnvironment', e.currentTarget.value)}
                placeholder={f('environmentPlaceholder')}
                size="xs"
                styles={MONO_INPUT}
              />
              <TextInput
                label={f('indexName')}
                value={getConfig(nodeData, 'pineconeIndex', '')}
                onChange={e => update('pineconeIndex', e.currentTarget.value)}
                size="xs"
                styles={MONO_INPUT}
              />
            </>
          )}

          {getConfig(nodeData, 'vectorBackend', 'pgvector') === 'pgvector' && (
            <>
              <TextInput
                label={f('datasource')}
                value={getConfig(nodeData, 'pgDatasource', '')}
                onChange={e => update('pgDatasource', e.currentTarget.value)}
                placeholder={f('datasourceNamePlaceholder')}
                size="xs"
                styles={MONO_INPUT}
              />
              <TextInput
                label={f('tableName')}
                value={getConfig(nodeData, 'pgTableName', 'embeddings')}
                onChange={e => update('pgTableName', e.currentTarget.value)}
                size="xs"
                styles={MONO_INPUT}
              />
              <NumberInput
                label={f('dimensions')}
                value={getConfig(nodeData, 'pgDimensions', 1536)}
                onChange={v => update('pgDimensions', v)}
                min={1}
                max={4096}
                size="xs"
                styles={FIELD_STYLES}
              />
            </>
          )}

          {getConfig(nodeData, 'vectorBackend', 'pgvector') === 'redis' && (
            <>
              <TextInput
                label={f('redisUrl')}
                value={getConfig(nodeData, 'vectorRedisUrl', 'redis://localhost:6379')}
                onChange={e => update('vectorRedisUrl', e.currentTarget.value)}
                size="xs"
                styles={MONO_INPUT}
              />
              <TextInput
                label={f('keyPrefix')}
                value={getConfig(nodeData, 'vectorRedisPrefix', 'vec:')}
                onChange={e => update('vectorRedisPrefix', e.currentTarget.value)}
                size="xs"
                styles={MONO_INPUT}
              />
            </>
          )}

          {nodeData.nodeType === 'vector-search' && (
            <NumberInput
              label={f('topK')}
              value={getConfig(nodeData, 'topK', 5)}
              onChange={v => update('topK', v)}
              min={1}
              max={100}
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
              value={getConfig(nodeData, 'embeddingProvider', catalog.embeddings[0].id)}
              onChange={v => update('embeddingProvider', v)}
              size="xs"
              data={catalog.embeddings.map(e => ({ value: e.id, label: e.displayName }))}
              styles={FIELD_STYLES}
            />
          ) : (
            <>
              <Select
                label={f('embeddingProvider')}
                value={getConfig(nodeData, 'embeddingProvider', 'openai')}
                onChange={v => {
                  update('embeddingProvider', v)
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
                value={getConfig(nodeData, 'embeddingModel', 'text-embedding-3-small')}
                onChange={v => {
                  update('embeddingModel', v)
                  const entry = EMBEDDING_MODELS.find(e => e.provider === getConfig(nodeData, 'embeddingProvider', 'openai'))
                  const m = entry?.models.find(mm => mm.id === v)
                  if (m) update('embeddingDimensions', m.dimensions)
                }}
                size="xs"
                data={(EMBEDDING_MODELS.find(e => e.provider === getConfig(nodeData, 'embeddingProvider', 'openai'))?.models ?? [])
                  .map(m => ({ value: m.id, label: m.name }))}
                styles={FIELD_STYLES}
              />
            </>
          )}
          <NumberInput
            label={f('dimensions')}
            value={getConfig(nodeData, 'embeddingDimensions', 1536)}
            onChange={v => update('embeddingDimensions', v)}
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
      {isMemory && catalog.memories.length > 0 && (
        <>
          <Select
            label={f('memoryBackend')}
            value={getConfig(nodeData, 'memoryBackend', catalog.memories[0].id)}
            onChange={v => update('memoryBackend', v)}
            size="xs"
            data={catalog.memories.map(m => ({ value: m.id, label: m.displayName }))}
            styles={FIELD_STYLES}
          />
          <NumberInput
            label={f('maxMessages')}
            value={getConfig(nodeData, 'memoryMaxMessages', 100)}
            onChange={v => update('memoryMaxMessages', v)}
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
          <Select
            label={f('embeddingProvider')}
            value={getConfig(nodeData, 'embeddingProvider', catalog.embeddings[0]?.id ?? 'openai')}
            onChange={v => update('embeddingProvider', v)}
            size="xs"
            data={catalog.embeddings.length > 0
                ? catalog.embeddings.map(e => ({ value: e.id, label: e.displayName }))
                : [{ value: 'openai', label: f('embeddingProviderOptions.openai') }, { value: 'local', label: f('embeddingProviderOptions.local') }]}
            styles={FIELD_STYLES}
          />
          <Select
            label={f('vectorStore')}
            value={getConfig(nodeData, 'vectorBackend', catalog.vectorstores[0]?.id ?? 'pgvector')}
            onChange={v => update('vectorBackend', v)}
            size="xs"
            data={catalog.vectorstores.length > 0
                ? catalog.vectorstores.map(v => ({ value: v.id, label: v.displayName }))
                : [{ value: 'pgvector', label: 'pgvector' }]}
            styles={FIELD_STYLES}
          />
          <Select
            label={f('llmProvider')}
            value={getConfig(nodeData, 'llmProvider', providers[0]?.id ?? 'openai')}
            onChange={v => update('llmProvider', v)}
            size="xs"
            data={providers.map(p => ({ value: p.id, label: p.displayName }))}
            styles={FIELD_STYLES}
          />
          <NumberInput
            label={f('topKRetrieval')}
            value={getConfig(nodeData, 'retrieverMaxResults', 5)}
            onChange={v => update('retrieverMaxResults', v)}
            min={1}
            max={50}
            size="xs"
            styles={FIELD_STYLES}
          />
          <NumberInput
            label={f('minScore')}
            value={getConfig(nodeData, 'retrieverMinScore', 0.7)}
            onChange={v => update('retrieverMinScore', v)}
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
            value={getConfig(nodeData, 'goal', '')}
            onChange={e => update('goal', e.currentTarget.value)}
            autosize minRows={2}
            size="xs"
            styles={FIELD_STYLES}
          />
          <Textarea
            label={t('dynamicWorkflow.decomposePrompt')}
            placeholder={t('dynamicWorkflow.decomposePromptPlaceholder')}
            value={getConfig(nodeData, 'decomposePrompt', '')}
            onChange={e => update('decomposePrompt', e.currentTarget.value)}
            autosize minRows={1}
            size="xs"
            styles={FIELD_STYLES}
          />
          <NumberInput
            label={t('dynamicWorkflow.maxSubtasks')}
            value={getConfig(nodeData, 'maxSubtasks', 8)}
            onChange={v => update('maxSubtasks', v)}
            min={1}
            size="xs"
            styles={FIELD_STYLES}
          />
          <NumberInput
            label={t('dynamicWorkflow.voters')}
            value={getConfig(nodeData, 'voters', 1)}
            onChange={v => update('voters', v)}
            min={1}
            size="xs"
            styles={FIELD_STYLES}
          />
          <NumberInput
            label={t('dynamicWorkflow.maxRounds')}
            value={getConfig(nodeData, 'maxRounds', 5)}
            onChange={v => update('maxRounds', v)}
            min={1}
            size="xs"
            styles={FIELD_STYLES}
          />
          <NumberInput
            label={t('dynamicWorkflow.concurrency')}
            value={getConfig(nodeData, 'concurrency', 4)}
            onChange={v => update('concurrency', v)}
            min={1}
            size="xs"
            styles={FIELD_STYLES}
          />
          <NumberInput
            label={t('dynamicWorkflow.budgetTokens')}
            value={getConfig(nodeData, 'budgetTokens', '')}
            onChange={v => update('budgetTokens', v)}
            min={0}
            step={1000}
            placeholder="∞"
            size="xs"
            styles={FIELD_STYLES}
          />
        </>
      )}

      {isApproval && (
        <>
          <TextInput
            label={f('approvalTitle')}
            value={getConfig(nodeData, 'approvalTitle', 'Please review')}
            onChange={e => update('approvalTitle', e.currentTarget.value)}
            size="xs"
            styles={FIELD_STYLES}
          />
          <Textarea
            label={f('approvalDescription')}
            value={getConfig(nodeData, 'approvalDescription', '')}
            onChange={e => update('approvalDescription', e.currentTarget.value)}
            autosize minRows={2}
            size="xs"
            styles={FIELD_STYLES}
            description={f('approvalDescriptionHint')}
          />
          <Textarea
            label={f('proposalPayload')}
            value={getConfig(nodeData, 'approvalProposal', '{}')}
            onChange={e => update('approvalProposal', e.currentTarget.value)}
            autosize minRows={2}
            size="xs"
            styles={MONO_INPUT}
            description={f('proposalPayloadHint')}
          />
          <NumberInput
            label={f('timeoutMin')}
            value={getConfig(nodeData, 'approvalTimeoutMinutes', 30)}
            onChange={v => update('approvalTimeoutMinutes', v)}
            min={1} max={24 * 60}
            size="xs"
            styles={FIELD_STYLES}
          />
          <Select
            label={f('onTimeout')}
            value={getConfig(nodeData, 'approvalOnTimeout', 'reject')}
            onChange={v => update('approvalOnTimeout', v)}
            size="xs"
            data={[
              { value: 'reject',   label: f('onTimeoutOptions.reject') },
              { value: 'approve',  label: f('onTimeoutOptions.approve') },
              { value: 'escalate', label: f('onTimeoutOptions.escalate') },
            ]}
            styles={FIELD_STYLES}
          />
        </>
      )}

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
            value={getConfig(nodeData, 'linktorConversationExpr', '${context.conversationId}')}
            onChange={e => update('linktorConversationExpr', e.currentTarget.value)}
            size="xs"
            styles={MONO_INPUT}
            description={f('conversationIdHint')}
          />
          <Textarea
            label={f('messageText')}
            value={getConfig(nodeData, 'linktorMessageText', '')}
            onChange={e => update('linktorMessageText', e.currentTarget.value)}
            autosize minRows={2}
            size="xs"
            styles={FIELD_STYLES}
            description={f('messageTextHint')}
          />
          <Select
            label={f('failurePolicy')}
            value={getConfig(nodeData, 'linktorSendOnError', 'warn')}
            onChange={v => update('linktorSendOnError', v)}
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
            value={getConfig(nodeData, 'linktorConversationExpr', '${context.conversationId}')}
            onChange={e => update('linktorConversationExpr', e.currentTarget.value)}
            size="xs"
            styles={MONO_INPUT}
          />
          <TextInput
            label={f('targetUser')}
            value={getConfig(nodeData, 'linktorTargetUser', '')}
            onChange={e => update('linktorTargetUser', e.currentTarget.value)}
            size="xs"
            styles={FIELD_STYLES}
            description={f('targetUserHint')}
          />
          <Textarea
            label={f('reason')}
            value={getConfig(nodeData, 'linktorReason', 'Agent escalated')}
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
            value={getConfig(nodeData, 'conditionExpression', '${agent.confidence} > 0.8')}
            onChange={e => update('conditionExpression', e.currentTarget.value)}
            autosize minRows={2}
            size="xs"
            styles={MONO_INPUT}
            description={f('expressionHint')}
          />
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
            value={getConfig(nodeData, 'switchExpression', '${agent.intent}')}
            onChange={e => update('switchExpression', e.currentTarget.value)}
            size="xs"
            styles={MONO_INPUT}
            description={f('switchExpressionHint')}
          />
          <Textarea
            label={f('switchCases')}
            value={getConfig(nodeData, 'switchCases', 'order_tracking\ncomplaint\nsales\ndefault')}
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
            value={getConfig(nodeData, 'loopCollection', '${input.items}')}
            onChange={e => update('loopCollection', e.currentTarget.value)}
            size="xs"
            styles={MONO_INPUT}
          />
          <TextInput
            label={f('itemVariable')}
            value={getConfig(nodeData, 'loopItemVar', 'item')}
            onChange={e => update('loopItemVar', e.currentTarget.value)}
            size="xs"
            styles={FIELD_STYLES}
            description={f('itemVariableHint')}
          />
          <NumberInput
            label={f('maxIterations')}
            value={getConfig(nodeData, 'loopMaxIterations', 100)}
            onChange={v => update('loopMaxIterations', v)}
            min={1} max={10_000}
            size="xs"
            styles={FIELD_STYLES}
          />
          <Switch
            checked={getConfig(nodeData, 'loopParallel', false)}
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
            value={getConfig(nodeData, 'parallelStrategy', 'all')}
            onChange={v => update('parallelStrategy', v)}
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
            value={getConfig(nodeData, 'parallelTimeoutSeconds', 60)}
            onChange={v => update('parallelTimeoutSeconds', v)}
            min={1} max={3600}
            size="xs"
            styles={FIELD_STYLES}
          />
          <Switch
            checked={getConfig(nodeData, 'parallelFailFast', true)}
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
            value={getConfig(nodeData, 'mergeStrategy', 'concat')}
            onChange={v => update('mergeStrategy', v)}
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
            value={getConfig(nodeData, 'mergeOutputVar', 'merged')}
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
            value={getConfig(nodeData, 'transformInput', '${input}')}
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
            value={getConfig(nodeData, 'transformExpression', '')}
            onChange={e => update('transformExpression', e.currentTarget.value)}
            autosize minRows={3}
            size="xs"
            styles={MONO_INPUT}
            description={f('transformHint')}
          />
          {nodeData.nodeType === 'reduce' && (
            <TextInput
              label={f('reduceInitial')}
              value={getConfig(nodeData, 'reduceInitial', '0')}
              onChange={e => update('reduceInitial', e.currentTarget.value)}
              size="xs"
              styles={MONO_INPUT}
            />
          )}
          <TextInput
            label={f('outputVariable')}
            value={getConfig(nodeData, 'transformOutputVar', 'output')}
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
            value={getConfig(nodeData, 'promptText', '')}
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
              value={getConfig(nodeData, 'promptChunkOrder', 0)}
              onChange={v => update('promptChunkOrder', v)}
              min={0} max={100}
              size="xs"
              styles={FIELD_STYLES}
            />
          )}
          <TextInput
            label={f('outputVariable')}
            value={getConfig(nodeData, 'promptOutputVar', 'prompt')}
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
            value={getConfig(nodeData, 'ioSchema', '')}
            onChange={e => update('ioSchema', e.currentTarget.value)}
            autosize minRows={3}
            size="xs"
            styles={MONO_INPUT}
            description={f('jsonSchemaHint')}
          />
          {isInput && (
            <Switch
              checked={getConfig(nodeData, 'inputRequired', true)}
              onChange={e => update('inputRequired', e.currentTarget.checked)}
              label={f('rejectIfMissing')}
              size="xs"
            />
          )}
        </>
      )}
    </div>
  )
}

// ── MCP tool fields ─────────────────────────────────────────────
