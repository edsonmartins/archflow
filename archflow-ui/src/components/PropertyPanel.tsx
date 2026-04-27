import { useEffect, useState } from 'react'
import { useTranslation } from 'react-i18next'
import {
  Select, TextInput, Textarea, NumberInput, Divider, ScrollArea, Tabs,
  Accordion, Switch, TagsInput, Badge, Text,
} from '@mantine/core'
import { useFlowStore }     from './FlowCanvas/store/useFlowStore'
import type { FlowNodeData } from './FlowCanvas/types'
import { NODE_CATEGORIES }  from './FlowCanvas/constants'
import { FIELD_STYLES, MONO_INPUT } from './PropertyPanel/fieldStyles'
import { useWorkflowConfig } from './PropertyPanel/useWorkflowConfig'
import { useCatalog } from './PropertyPanel/useCatalog'
import type { ProviderInfo } from '../services/workflow-config-api'
import { mcpApi }      from '../services/mcp-api'
import { skillsApi }   from '../services/skills-api'
import { workflowApi } from '../services/api'

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

function getConfig<T>(data: FlowNodeData, key: string, fallback: T): T {
  return (data.config?.[key] as T) ?? fallback
}

function findProvider(providers: ProviderInfo[], providerId: string) {
  return providers.find(p => p.id === providerId)
}

function findModel(providers: ProviderInfo[], providerId: string, modelId: string) {
  return findProvider(providers, providerId)?.models.find(m => m.id === modelId)
}

function ctxLabel(ctx: number) {
  return ctx >= 1_000_000 ? `${(ctx / 1_000_000).toFixed(1)}M ctx` : `${Math.round(ctx / 1000)}K ctx`
}

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
        <EmptyPanel />
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
  const { providers, patterns, personas, governance } = useWorkflowConfig()
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

  const update = (key: string, value: unknown) => updateNodeConfig(nodeId, key, value)
  // Shortcut for field translations; keeps JSX free of the long prefix.
  const f = (key: string, opts?: Record<string, unknown>) => t(`editor.properties.fields.${key}`, opts)

  // Derived state for agent nodes
  const providerId = getConfig(nodeData, 'provider', providers[0]?.id ?? 'anthropic')
  const modelId    = getConfig(nodeData, 'model', providers[0]?.models[0]?.id ?? 'claude-sonnet-4-6')
  const provider   = findProvider(providers, providerId)
  const model      = findModel(providers, providerId, modelId)
  const maxTemp    = model?.maxTemperature ?? 2.0
  const ctxWindow  = model?.contextWindow ?? 128000

  // Persona selector options ("custom" = free-text system prompt)
  const personaOptions = [
    { value: '__custom__', label: t('common.customFreeText') },
    ...personas.map(p => ({ value: p.id, label: p.label })),
  ]
  const personaId = getConfig(nodeData, 'personaId', '__custom__')

  // Governance profile selector
  const governanceOptions = [
    { value: '__custom__', label: t('common.custom') },
    ...governance.map(g => ({ value: g.id, label: g.name })),
  ]

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 14 }}>
      {/* ── Common: Node name ─────────────────────────────────── */}
      <TextInput
        label={f('nodeName')}
        value={nodeData.label}
        onChange={e => updateNodeLabel(nodeId, e.currentTarget.value)}
        size="xs"
        styles={FIELD_STYLES}
      />

      {/* ════════════════════════════════════════════════════════ */}
      {/*  AGENT FIELDS                                          */}
      {/* ════════════════════════════════════════════════════════ */}
      {isAgent && (
        <>
          {/* Concrete agent / assistant type from the catalog */}
          {(() => {
            const typedItems = isAssistant ? catalog.assistants : catalog.agents
            const configKey = isAssistant ? 'assistantTypeId' : 'agentTypeId'
            if (typedItems.length === 0) return null
            return (
              <div>
                <Select
                  label={isAssistant ? f('assistantType') : f('agentType')}
                  value={getConfig<string | null>(nodeData, configKey, null) ?? typedItems[0].id}
                  onChange={v => update(configKey, v)}
                  size="xs"
                  data={typedItems.map(a => ({ value: a.id, label: a.displayName }))}
                  styles={FIELD_STYLES}
                  searchable
                />
                <Text size="xs" c="dimmed" mt={4} lh={1.4}>
                  {typedItems.find(a => a.id === getConfig<string | null>(nodeData, configKey, typedItems[0].id))?.description}
                </Text>
              </div>
            )
          })()}

          {/* Gap 1: Agent pattern */}
          <div>
            <Select
              label={f('executionStrategy')}
              value={getConfig(nodeData, 'agentPattern', 'react')}
              onChange={v => update('agentPattern', v)}
              size="xs"
              data={patterns.map(p => ({ value: p.id, label: p.label }))}
              styles={FIELD_STYLES}
            />
            <Text size="xs" c="dimmed" mt={4} lh={1.4}>
              {patterns.find(p => p.id === getConfig(nodeData, 'agentPattern', 'react'))?.description}
            </Text>
          </div>

          {/* Gap 2: Provider */}
          <Select
            label={f('provider')}
            value={providerId}
            onChange={v => {
              update('provider', v)
              const firstModel = providers.find(p => p.id === v)?.models[0]
              if (firstModel) update('model', firstModel.id)
            }}
            size="xs"
            data={(() => {
              const groups = new Map<string, { value: string; label: string }[]>()
              providers.forEach(p => {
                if (!groups.has(p.group)) groups.set(p.group, [])
                groups.get(p.group)!.push({ value: p.id, label: p.displayName })
              })
              return Array.from(groups.entries()).map(([group, items]) => ({ group, items }))
            })()}
            styles={FIELD_STYLES}
          />

          {/* Gap 2: Model (filtered by provider) */}
          <div>
            <Select
              label={f('model')}
              value={modelId}
              onChange={v => update('model', v)}
              size="xs"
              data={(provider?.models ?? []).map(m => ({
                value: m.id,
                label: `${m.name}`,
              }))}
              styles={FIELD_STYLES}
            />
            {model && (
              <Badge size="xs" variant="light" color="gray" mt={4}>{ctxLabel(model.contextWindow)}</Badge>
            )}
          </div>

          {/* Gap 10: Persona selector */}
          {personas.length > 0 && (
            <Select
              label={f('persona')}
              value={personaId}
              onChange={v => {
                update('personaId', v)
                if (v && v !== '__custom__') {
                  const p = personas.find(pp => pp.id === v)
                  if (p) update('systemPrompt', p.description)
                }
              }}
              size="xs"
              data={personaOptions}
              styles={FIELD_STYLES}
            />
          )}

          {/* System prompt */}
          <div>
            <div style={{ fontSize: 11, fontWeight: 500, color: 'var(--color-text-secondary)', marginBottom: 5 }}>
              {f('systemPrompt')}
            </div>
            <Textarea
              value={getConfig(nodeData, 'systemPrompt', '')}
              onChange={e => update('systemPrompt', e.currentTarget.value)}
              placeholder={f('systemPromptPlaceholder')}
              minRows={3}
              maxRows={6}
              autosize
              disabled={personas.length > 0 && personaId !== '__custom__'}
              size="xs"
              styles={{ input: { fontSize: 12, fontFamily: 'var(--font-mono)' } }}
            />
          </div>

          {/* Temperature */}
          <NumberInput
            label={f('temperature')}
            value={getConfig(nodeData, 'temperature', 0.7)}
            onChange={v => update('temperature', v)}
            min={0}
            max={maxTemp}
            step={0.1}
            decimalScale={1}
            size="xs"
            styles={FIELD_STYLES}
          />

          {/* Gap 3: Max tokens */}
          <NumberInput
            label={f('maxTokens')}
            value={getConfig(nodeData, 'maxTokens', 4096)}
            onChange={v => update('maxTokens', v)}
            min={1}
            max={ctxWindow}
            step={256}
            size="xs"
            styles={FIELD_STYLES}
          />

          {/* Max iterations */}
          <NumberInput
            label={f('maxIterations')}
            value={getConfig(nodeData, 'maxIterations', 10)}
            onChange={v => update('maxIterations', v)}
            min={1}
            max={50}
            size="xs"
            styles={FIELD_STYLES}
          />

          <Divider my={4} />

          {/* ── Advanced accordion ───────────────────────────── */}
          <Accordion variant="contained" radius="sm" styles={{ control: { fontSize: 12, padding: '6px 10px' }, content: { padding: '8px 10px' } }}>
            <Accordion.Item value="advanced">
              <Accordion.Control>{f('advanced')}</Accordion.Control>
              <Accordion.Panel>
                <div style={{ display: 'flex', flexDirection: 'column', gap: 12 }}>
                  {/* Gap 4: Timeout */}
                  <NumberInput
                    label={f('timeoutSec')}
                    value={getConfig(nodeData, 'timeout', 120)}
                    onChange={v => update('timeout', v)}
                    min={1}
                    max={3600}
                    size="xs"
                    styles={FIELD_STYLES}
                  />

                  {/* Gap 5: Memory backend */}
                  <Select
                    label={f('memoryBackend')}
                    value={getConfig(nodeData, 'memoryBackend', 'in-memory')}
                    onChange={v => update('memoryBackend', v)}
                    size="xs"
                    data={[
                      { value: 'in-memory', label: f('memoryOptions.in-memory') },
                      { value: 'redis',     label: f('memoryOptions.redis') },
                      { value: 'jdbc',      label: f('memoryOptions.jdbc') },
                    ]}
                    styles={FIELD_STYLES}
                  />

                  {getConfig(nodeData, 'memoryBackend', 'in-memory') === 'redis' && (
                    <TextInput
                      label={f('redisUrl')}
                      value={getConfig(nodeData, 'redisUrl', 'redis://localhost:6379')}
                      onChange={e => update('redisUrl', e.currentTarget.value)}
                      size="xs"
                      styles={MONO_INPUT}
                    />
                  )}

                  {getConfig(nodeData, 'memoryBackend', 'in-memory') === 'jdbc' && (
                    <TextInput
                      label={f('jdbcDatasource')}
                      value={getConfig(nodeData, 'jdbcDatasource', '')}
                      onChange={e => update('jdbcDatasource', e.currentTarget.value)}
                      placeholder={f('datasourceNamePlaceholder')}
                      size="xs"
                      styles={MONO_INPUT}
                    />
                  )}
                </div>
              </Accordion.Panel>
            </Accordion.Item>

            {/* ── Gap 9 + 12: Governance + Confidence ──────── */}
            <Accordion.Item value="governance">
              <Accordion.Control>{f('governance')}</Accordion.Control>
              <Accordion.Panel>
                <div style={{ display: 'flex', flexDirection: 'column', gap: 12 }}>
                  {governance.length > 0 && (
                    <Select
                      label={f('profile')}
                      value={getConfig(nodeData, 'governanceProfileId', '__custom__')}
                      onChange={v => {
                        update('governanceProfileId', v)
                        if (v && v !== '__custom__') {
                          const g = governance.find(gg => gg.id === v)
                          if (g) {
                            update('enabledTools', g.enabledTools)
                            update('disabledTools', g.disabledTools)
                            update('escalationThreshold', g.escalationThreshold)
                            update('maxToolExecutions', g.maxToolExecutions)
                            update('customInstructions', g.customInstructions)
                          }
                        }
                      }}
                      size="xs"
                      data={governanceOptions}
                      styles={FIELD_STYLES}
                    />
                  )}
                  <TagsInput
                    label={f('enabledTools')}
                    value={getConfig<string[]>(nodeData, 'enabledTools', [])}
                    onChange={v => update('enabledTools', v)}
                    placeholder={f('addTool')}
                    size="xs"
                    styles={FIELD_STYLES}
                  />
                  <TagsInput
                    label={f('disabledTools')}
                    value={getConfig<string[]>(nodeData, 'disabledTools', [])}
                    onChange={v => update('disabledTools', v)}
                    placeholder={f('addTool')}
                    size="xs"
                    styles={FIELD_STYLES}
                  />
                  <NumberInput
                    label={f('escalationThreshold')}
                    description={f('escalationHint')}
                    value={getConfig(nodeData, 'escalationThreshold', 0.5)}
                    onChange={v => update('escalationThreshold', v)}
                    min={0}
                    max={1}
                    step={0.05}
                    decimalScale={2}
                    size="xs"
                    styles={FIELD_STYLES}
                  />
                  <NumberInput
                    label={f('maxToolExecutions')}
                    value={getConfig(nodeData, 'maxToolExecutions', 10)}
                    onChange={v => update('maxToolExecutions', v)}
                    min={1}
                    max={100}
                    size="xs"
                    styles={FIELD_STYLES}
                  />
                  <Switch
                    label={f('humanEscalation')}
                    checked={getConfig(nodeData, 'humanEscalation', false)}
                    onChange={e => update('humanEscalation', e.currentTarget.checked)}
                    size="xs"
                    styles={{ label: { fontSize: 12 } }}
                  />
                  <Textarea
                    label={f('customInstructions')}
                    value={getConfig(nodeData, 'customInstructions', '')}
                    onChange={e => update('customInstructions', e.currentTarget.value)}
                    placeholder={f('customInstructionsPlaceholder')}
                    minRows={2}
                    maxRows={4}
                    autosize
                    size="xs"
                    styles={{ input: { fontSize: 12 }, label: { fontSize: 11, fontWeight: 500 } }}
                  />
                </div>
              </Accordion.Panel>
            </Accordion.Item>

            {/* ── Gap 7: MCP Servers ──────────────────────── */}
            <Accordion.Item value="mcp">
              <Accordion.Control>{f('mcpServers')}</Accordion.Control>
              <Accordion.Panel>
                <div style={{ display: 'flex', flexDirection: 'column', gap: 12 }}>
                  <TagsInput
                    label={f('connectedServers')}
                    value={getConfig<string[]>(nodeData, 'mcpServers', [])}
                    onChange={v => update('mcpServers', v)}
                    placeholder={f('addServer')}
                    size="xs"
                    styles={FIELD_STYLES}
                  />
                  <Select
                    label={f('transport')}
                    value={getConfig(nodeData, 'mcpTransport', 'stdio')}
                    onChange={v => update('mcpTransport', v)}
                    size="xs"
                    data={[
                      { value: 'stdio', label: f('transportOptions.stdio') },
                      { value: 'sse',   label: f('transportOptions.sse') },
                    ]}
                    styles={FIELD_STYLES}
                  />
                  {getConfig(nodeData, 'mcpTransport', 'stdio') === 'stdio' && (
                    <TextInput
                      label={f('command')}
                      value={getConfig(nodeData, 'mcpCommand', '')}
                      onChange={e => update('mcpCommand', e.currentTarget.value)}
                      placeholder={f('commandPlaceholder')}
                      size="xs"
                      styles={MONO_INPUT}
                    />
                  )}
                  {getConfig(nodeData, 'mcpTransport', 'stdio') === 'sse' && (
                    <TextInput
                      label={f('serverUrl')}
                      value={getConfig(nodeData, 'mcpUrl', '')}
                      onChange={e => update('mcpUrl', e.currentTarget.value)}
                      placeholder={f('serverUrlPlaceholder')}
                      size="xs"
                      styles={MONO_INPUT}
                    />
                  )}
                </div>
              </Accordion.Panel>
            </Accordion.Item>
          </Accordion>
        </>
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
type FieldProps = { nodeData: FlowNodeData; update: (key: string, value: unknown) => void }

function McpToolFields({ nodeData, update }: FieldProps) {
  const { t } = useTranslation()
  const f = (key: string) => t(`editor.properties.fields.${key}`)
  const [servers, setServers] = useState<string[]>([])
  const [tools, setTools]     = useState<{ name: string; description: string }[]>([])
  const [loading, setLoading] = useState(false)

  const selectedServer = getConfig(nodeData, 'mcpServer', '') as string
  const selectedTool   = getConfig(nodeData, 'mcpTool',   '') as string

  useEffect(() => {
    // Pull the list of registered MCP servers; default bean ships with
    // Linktor when enabled.
    mcpApi.listServers().then(setServers).catch(() => setServers([]))
  }, [])

  useEffect(() => {
    if (!selectedServer) { setTools([]); return }
    setLoading(true)
    mcpApi.introspect(selectedServer)
        .then(x => setTools(x.tools.map(t => ({ name: t.name, description: t.description }))))
        .catch(() => setTools([]))
        .finally(() => setLoading(false))
  }, [selectedServer])

  return (
    <>
      <Select
        label={f('mcpServer')}
        value={selectedServer || null}
        onChange={v => { update('mcpServer', v ?? ''); update('mcpTool', '') }}
        size="xs"
        data={servers.map(s => ({ value: s, label: s }))}
        placeholder={servers.length ? f('pickServer') : f('noMcpServers')}
        styles={FIELD_STYLES}
      />
      <Select
        label={f('tool')}
        value={selectedTool || null}
        onChange={v => update('mcpTool', v ?? '')}
        size="xs"
        data={tools.map(tool => ({ value: tool.name, label: tool.name }))}
        searchable
        disabled={!selectedServer || loading}
        placeholder={selectedServer ? (loading ? f('loadingEllipsis') : f('pickTool')) : f('pickServerFirst')}
        styles={FIELD_STYLES}
      />
      {selectedTool && (
        <Text size="xs" c="dimmed" lh={1.4}>
          {tools.find(tool => tool.name === selectedTool)?.description}
        </Text>
      )}
      <Textarea
        label={f('argumentsJson')}
        value={getConfig(nodeData, 'mcpArguments', '{}')}
        onChange={e => update('mcpArguments', e.currentTarget.value)}
        autosize minRows={3}
        size="xs"
        styles={MONO_INPUT}
        description={f('argumentsHint')}
      />
      <TextInput
        label={f('outputVariable')}
        value={getConfig(nodeData, 'mcpOutputVar', 'toolResult')}
        onChange={e => update('mcpOutputVar', e.currentTarget.value)}
        size="xs"
        styles={FIELD_STYLES}
      />
    </>
  )
}

// ── Subflow fields ──────────────────────────────────────────────
function SubflowFields({ nodeData, update }: FieldProps) {
  const { t } = useTranslation()
  const f = (key: string) => t(`editor.properties.fields.${key}`)
  const [workflows, setWorkflows] = useState<{ id: string; name: string }[]>([])
  const [loading, setLoading]     = useState(true)

  useEffect(() => {
    workflowApi.list()
        .then(items => setWorkflows(items.map(w => ({ id: w.id, name: w.name ?? w.id }))))
        .catch(() => setWorkflows([]))
        .finally(() => setLoading(false))
  }, [])

  return (
    <>
      <Select
        label={f('workflowToInvoke')}
        value={(getConfig(nodeData, 'subflowId', '') as string) || null}
        onChange={v => update('subflowId', v ?? '')}
        size="xs"
        data={workflows.map(w => ({ value: w.id, label: `${w.name} (${w.id})` }))}
        searchable
        disabled={loading}
        placeholder={loading ? f('loadingWorkflows') : f('pickWorkflow')}
        styles={FIELD_STYLES}
      />
      <Textarea
        label={f('inputMapping')}
        value={getConfig(nodeData, 'subflowInput', '{}')}
        onChange={e => update('subflowInput', e.currentTarget.value)}
        autosize minRows={3}
        size="xs"
        styles={MONO_INPUT}
        description={f('inputMappingHint')}
      />
      <TextInput
        label={f('outputVariable')}
        value={getConfig(nodeData, 'subflowOutputVar', 'subflowResult')}
        onChange={e => update('subflowOutputVar', e.currentTarget.value)}
        size="xs"
        styles={FIELD_STYLES}
      />
      <NumberInput
        label={f('timeoutSec')}
        value={getConfig(nodeData, 'subflowTimeoutSeconds', 120)}
        onChange={v => update('subflowTimeoutSeconds', v)}
        min={1} max={3600}
        size="xs"
        styles={FIELD_STYLES}
      />
      <Switch
        checked={getConfig(nodeData, 'subflowAsync', false)}
        onChange={e => update('subflowAsync', e.currentTarget.checked)}
        label={f('fireAndForget')}
        size="xs"
      />
    </>
  )
}

// ── Skills fields ───────────────────────────────────────────────
function SkillsFields({ nodeData, update }: FieldProps) {
  const { t } = useTranslation()
  const f = (key: string, opts?: Record<string, unknown>) => t(`editor.properties.fields.${key}`, opts)
  const [skills, setSkills] = useState<{ name: string; description: string; active: boolean }[]>([])

  useEffect(() => {
    skillsApi.list()
        .then(xs => setSkills(xs.map(s => ({ name: s.name, description: s.description, active: s.active }))))
        .catch(() => setSkills([]))
  }, [])

  const operation = getConfig(nodeData, 'skillsOperation', 'activate') as string

  return (
    <>
      <Select
        label={f('operation')}
        value={operation}
        onChange={v => update('skillsOperation', v)}
        size="xs"
        data={[
          { value: 'list',        label: f('skillsOps.list') },
          { value: 'activate',    label: f('skillsOps.activate') },
          { value: 'deactivate',  label: f('skillsOps.deactivate') },
          { value: 'read',        label: f('skillsOps.read') },
          { value: 'active',      label: f('skillsOps.active') },
        ]}
        styles={FIELD_STYLES}
      />
      {(operation === 'activate' || operation === 'deactivate' || operation === 'read') && (
        <Select
          label={f('skillLabel')}
          value={(getConfig(nodeData, 'skillName', '') as string) || null}
          onChange={v => update('skillName', v ?? '')}
          size="xs"
          data={skills.map(s => ({ value: s.name, label: `${s.name}${s.active ? f('skillActiveSuffix') : ''}` }))}
          searchable
          placeholder={skills.length ? f('skillPick') : f('noSkills')}
          styles={FIELD_STYLES}
        />
      )}
      {operation === 'read' && (
        <TextInput
          label={f('resourceName')}
          value={getConfig(nodeData, 'skillResource', '')}
          onChange={e => update('skillResource', e.currentTarget.value)}
          size="xs"
          styles={FIELD_STYLES}
        />
      )}
      <TextInput
        label={f('outputVariable')}
        value={getConfig(nodeData, 'skillsOutputVar', 'skillsResult')}
        onChange={e => update('skillsOutputVar', e.currentTarget.value)}
        size="xs"
        styles={FIELD_STYLES}
      />
    </>
  )
}

// ── Execution Logs ──────────────────────────────────────────────
function ExecutionLogs({ execState }: { execState: any }) {
  const { t, i18n } = useTranslation()
  const locale = i18n.resolvedLanguage ?? i18n.language
  if (!execState) {
    return (
      <div style={{ fontSize: 12, color: 'var(--color-text-tertiary)', textAlign: 'center', paddingTop: 24 }}>
        {t('editor.properties.fields.logs.noData')}
      </div>
    )
  }

  const ts = new Date(execState.startedAt ?? Date.now()).toLocaleTimeString(locale)

  return (
    <div style={{ background: 'var(--color-background-secondary)', borderRadius: 7, padding: '10px 12px', fontFamily: 'var(--font-mono)', fontSize: 11, lineHeight: 1.7 }}>
      <div style={{ color: 'var(--color-text-tertiary)' }}>{t('editor.properties.fields.logs.nodeStarted', { ts })}</div>
      {execState.status === 'success' && (
        <div style={{ color: 'var(--color-text-success)' }}>{t('editor.properties.fields.logs.completedIn', { ts, ms: execState.durationMs?.toFixed(0) })}</div>
      )}
      {execState.status === 'error' && (
        <div style={{ color: 'var(--color-text-danger)' }}>[{ts}] {execState.error}</div>
      )}
      {execState.status === 'running' && (
        <div style={{ color: 'var(--color-text-warning)' }}>{t('editor.properties.fields.logs.running', { ts })}</div>
      )}
    </div>
  )
}

// ── Output Preview ──────────────────────────────────────────────
function OutputPreview({ execState }: { execState: any }) {
  const { t } = useTranslation()
  if (!execState?.output) {
    return (
      <div style={{ fontSize: 12, color: 'var(--color-text-tertiary)', textAlign: 'center', paddingTop: 24 }}>
        {t('editor.properties.fields.logs.noOutput')}
      </div>
    )
  }

  return (
    <pre style={{ background: 'var(--color-background-secondary)', borderRadius: 7, padding: '10px 12px', fontFamily: 'var(--font-mono)', fontSize: 11, color: 'var(--color-text-primary)', whiteSpace: 'pre-wrap', wordBreak: 'break-all', margin: 0 }}>
      {JSON.stringify(execState.output, null, 2)}
    </pre>
  )
}

// ── Empty Panel ─────────────────────────────────────────────────
function EmptyPanel() {
  const { t } = useTranslation()
  return (
    <div style={{ flex: 1, display: 'flex', flexDirection: 'column', alignItems: 'center', justifyContent: 'center', gap: 8, color: 'var(--color-text-tertiary)', padding: 24, textAlign: 'center' }}>
      <div style={{ fontSize: 20 }}>{'\u25CE'}</div>
      <div style={{ fontSize: 13, color: 'var(--color-text-secondary)' }}>{t('editor.properties.noNodeSelected')}</div>
      <div style={{ fontSize: 12 }}>{t('editor.properties.emptyHint')}</div>
    </div>
  )
}
