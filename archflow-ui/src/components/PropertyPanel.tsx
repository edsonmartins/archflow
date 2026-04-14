import {
  Select, TextInput, Textarea, NumberInput, Divider, ScrollArea, Tabs,
  Accordion, Switch, TagsInput, Badge, Text,
} from '@mantine/core'
import { useFlowStore }     from './FlowCanvas/store/useFlowStore'
import type { FlowNodeData } from './FlowCanvas/types'
import { NODE_CATEGORIES }  from './FlowCanvas/constants'
import { FIELD_STYLES, MONO_INPUT } from './PropertyPanel/fieldStyles'
import { useWorkflowConfig } from './PropertyPanel/useWorkflowConfig'
import type { ProviderInfo } from '../services/workflow-config-api'

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
              <Tabs.Tab value="properties" style={{ fontSize: 12 }}>Properties</Tabs.Tab>
              <Tabs.Tab value="logs"       style={{ fontSize: 12 }}>Logs</Tabs.Tab>
              <Tabs.Tab value="output"     style={{ fontSize: 12 }}>Output</Tabs.Tab>
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
  const cat = NODE_CATEGORIES[nodeData.nodeType as keyof typeof NODE_CATEGORIES]
    ?? NODE_CATEGORIES.io

  return (
    <div style={{ padding: '12px 16px', borderBottom: '0.5px solid var(--color-border-tertiary)', display: 'flex', alignItems: 'center', gap: 10 }}>
      <div style={{ width: 32, height: 32, borderRadius: 8, background: cat.colorLight, border: `1px solid ${cat.border}`, display: 'flex', alignItems: 'center', justifyContent: 'center', fontSize: 14, flexShrink: 0 }}>
        {(nodeData.config?.icon as string) ?? '●'}
      </div>
      <div>
        <div style={{ fontSize: 13, fontWeight: 500, color: 'var(--color-text-primary)' }}>{nodeData.label}</div>
        <div style={{ fontSize: 11, color: 'var(--color-text-tertiary)', fontFamily: 'var(--font-mono)' }}>
          {nodeData.nodeType} · {cat.label}
        </div>
      </div>
    </div>
  )
}

// ── Node Fields (all gaps implemented) ──────────────────────────
function NodeFields({ nodeId, nodeData }: { nodeId: string; nodeData: FlowNodeData }) {
  const { updateNodeConfig, updateNodeLabel } = useFlowStore()
  const { providers, patterns, personas, governance } = useWorkflowConfig()

  const isAgent     = ['agent', 'assistant', 'llm-chat', 'llm-streaming'].includes(nodeData.nodeType)
  const isTool      = ['tool', 'function', 'custom'].includes(nodeData.nodeType)
  const isVector    = ['vector-search', 'vector-store'].includes(nodeData.nodeType)
  const isEmbedding = nodeData.nodeType === 'embedding'

  const update = (key: string, value: unknown) => updateNodeConfig(nodeId, key, value)

  // Derived state for agent nodes
  const providerId = getConfig(nodeData, 'provider', providers[0]?.id ?? 'anthropic')
  const modelId    = getConfig(nodeData, 'model', providers[0]?.models[0]?.id ?? 'claude-sonnet-4-6')
  const provider   = findProvider(providers, providerId)
  const model      = findModel(providers, providerId, modelId)
  const maxTemp    = model?.maxTemperature ?? 2.0
  const ctxWindow  = model?.contextWindow ?? 128000

  // Persona selector options ("custom" = free-text system prompt)
  const personaOptions = [
    { value: '__custom__', label: 'Custom (free text)' },
    ...personas.map(p => ({ value: p.id, label: p.label })),
  ]
  const personaId = getConfig(nodeData, 'personaId', '__custom__')

  // Governance profile selector
  const governanceOptions = [
    { value: '__custom__', label: 'Custom' },
    ...governance.map(g => ({ value: g.id, label: g.name })),
  ]

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 14 }}>
      {/* ── Common: Node name ─────────────────────────────────── */}
      <TextInput
        label="Node name"
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
          {/* Gap 1: Agent pattern */}
          <div>
            <Select
              label="Execution strategy"
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
            label="Provider"
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
              label="Model"
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
              label="Persona"
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
              System prompt
            </div>
            <Textarea
              value={getConfig(nodeData, 'systemPrompt', '')}
              onChange={e => update('systemPrompt', e.currentTarget.value)}
              placeholder="You are a helpful assistant..."
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
            label="Temperature"
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
            label="Max tokens"
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
            label="Max iterations"
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
              <Accordion.Control>Advanced</Accordion.Control>
              <Accordion.Panel>
                <div style={{ display: 'flex', flexDirection: 'column', gap: 12 }}>
                  {/* Gap 4: Timeout */}
                  <NumberInput
                    label="Timeout (seconds)"
                    value={getConfig(nodeData, 'timeout', 120)}
                    onChange={v => update('timeout', v)}
                    min={1}
                    max={3600}
                    size="xs"
                    styles={FIELD_STYLES}
                  />

                  {/* Gap 5: Memory backend */}
                  <Select
                    label="Memory backend"
                    value={getConfig(nodeData, 'memoryBackend', 'in-memory')}
                    onChange={v => update('memoryBackend', v)}
                    size="xs"
                    data={[
                      { value: 'in-memory', label: 'In-Memory (default)' },
                      { value: 'redis',     label: 'Redis' },
                      { value: 'jdbc',      label: 'JDBC (PostgreSQL)' },
                    ]}
                    styles={FIELD_STYLES}
                  />

                  {getConfig(nodeData, 'memoryBackend', 'in-memory') === 'redis' && (
                    <TextInput
                      label="Redis URL"
                      value={getConfig(nodeData, 'redisUrl', 'redis://localhost:6379')}
                      onChange={e => update('redisUrl', e.currentTarget.value)}
                      size="xs"
                      styles={MONO_INPUT}
                    />
                  )}

                  {getConfig(nodeData, 'memoryBackend', 'in-memory') === 'jdbc' && (
                    <TextInput
                      label="JDBC datasource"
                      value={getConfig(nodeData, 'jdbcDatasource', '')}
                      onChange={e => update('jdbcDatasource', e.currentTarget.value)}
                      placeholder="datasource-name"
                      size="xs"
                      styles={MONO_INPUT}
                    />
                  )}
                </div>
              </Accordion.Panel>
            </Accordion.Item>

            {/* ── Gap 9 + 12: Governance + Confidence ──────── */}
            <Accordion.Item value="governance">
              <Accordion.Control>Governance</Accordion.Control>
              <Accordion.Panel>
                <div style={{ display: 'flex', flexDirection: 'column', gap: 12 }}>
                  {governance.length > 0 && (
                    <Select
                      label="Profile"
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
                    label="Enabled tools (whitelist)"
                    value={getConfig<string[]>(nodeData, 'enabledTools', [])}
                    onChange={v => update('enabledTools', v)}
                    placeholder="Add tool..."
                    size="xs"
                    styles={FIELD_STYLES}
                  />
                  <TagsInput
                    label="Disabled tools (blacklist)"
                    value={getConfig<string[]>(nodeData, 'disabledTools', [])}
                    onChange={v => update('disabledTools', v)}
                    placeholder="Add tool..."
                    size="xs"
                    styles={FIELD_STYLES}
                  />
                  <NumberInput
                    label="Escalation threshold"
                    description="Confidence below this triggers escalation (0.0-1.0)"
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
                    label="Max tool executions"
                    value={getConfig(nodeData, 'maxToolExecutions', 10)}
                    onChange={v => update('maxToolExecutions', v)}
                    min={1}
                    max={100}
                    size="xs"
                    styles={FIELD_STYLES}
                  />
                  <Switch
                    label="Human escalation"
                    checked={getConfig(nodeData, 'humanEscalation', false)}
                    onChange={e => update('humanEscalation', e.currentTarget.checked)}
                    size="xs"
                    styles={{ label: { fontSize: 12 } }}
                  />
                  <Textarea
                    label="Custom instructions"
                    value={getConfig(nodeData, 'customInstructions', '')}
                    onChange={e => update('customInstructions', e.currentTarget.value)}
                    placeholder="Additional governance rules..."
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
              <Accordion.Control>MCP Servers</Accordion.Control>
              <Accordion.Panel>
                <div style={{ display: 'flex', flexDirection: 'column', gap: 12 }}>
                  <TagsInput
                    label="Connected servers"
                    value={getConfig<string[]>(nodeData, 'mcpServers', [])}
                    onChange={v => update('mcpServers', v)}
                    placeholder="Add server name..."
                    size="xs"
                    styles={FIELD_STYLES}
                  />
                  <Select
                    label="Transport"
                    value={getConfig(nodeData, 'mcpTransport', 'stdio')}
                    onChange={v => update('mcpTransport', v)}
                    size="xs"
                    data={[
                      { value: 'stdio', label: 'STDIO (subprocess)' },
                      { value: 'sse',   label: 'SSE (HTTP)' },
                    ]}
                    styles={FIELD_STYLES}
                  />
                  {getConfig(nodeData, 'mcpTransport', 'stdio') === 'stdio' && (
                    <TextInput
                      label="Command"
                      value={getConfig(nodeData, 'mcpCommand', '')}
                      onChange={e => update('mcpCommand', e.currentTarget.value)}
                      placeholder="python server.py"
                      size="xs"
                      styles={MONO_INPUT}
                    />
                  )}
                  {getConfig(nodeData, 'mcpTransport', 'stdio') === 'sse' && (
                    <TextInput
                      label="Server URL"
                      value={getConfig(nodeData, 'mcpUrl', '')}
                      onChange={e => update('mcpUrl', e.currentTarget.value)}
                      placeholder="http://localhost:8080/mcp"
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
          <TextInput
            label="Tool ID"
            value={getConfig(nodeData, 'toolId', '')}
            onChange={e => update('toolId', e.currentTarget.value)}
            size="xs"
            styles={MONO_INPUT}
          />

          {/* Gap 8: Retry policy */}
          <Select
            label="On error"
            value={getConfig(nodeData, 'onError', 'stop')}
            onChange={v => update('onError', v)}
            size="xs"
            data={[
              { value: 'stop',     label: 'Stop execution' },
              { value: 'continue', label: 'Continue (ignore)' },
              { value: 'retry',    label: 'Retry with policy' },
            ]}
            styles={FIELD_STYLES}
          />

          {getConfig(nodeData, 'onError', 'stop') === 'retry' && (
            <div style={{ display: 'flex', flexDirection: 'column', gap: 10, padding: '8px 0' }}>
              <NumberInput
                label="Max attempts"
                value={getConfig(nodeData, 'retryMaxAttempts', 3)}
                onChange={v => update('retryMaxAttempts', v)}
                min={1}
                max={10}
                size="xs"
                styles={FIELD_STYLES}
              />
              <NumberInput
                label="Initial delay (ms)"
                value={getConfig(nodeData, 'retryDelay', 1000)}
                onChange={v => update('retryDelay', v)}
                min={100}
                max={60000}
                step={100}
                size="xs"
                styles={FIELD_STYLES}
              />
              <NumberInput
                label="Backoff multiplier"
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
            label="Timeout (seconds)"
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
            label="Vector backend"
            value={getConfig(nodeData, 'vectorBackend', 'pgvector')}
            onChange={v => update('vectorBackend', v)}
            size="xs"
            data={[
              { value: 'pgvector',  label: 'PGVector (PostgreSQL)' },
              { value: 'pinecone',  label: 'Pinecone' },
              { value: 'redis',     label: 'Redis Vector' },
              { value: 'in-memory', label: 'In-Memory' },
            ]}
            styles={FIELD_STYLES}
          />

          {getConfig(nodeData, 'vectorBackend', 'pgvector') === 'pinecone' && (
            <>
              <TextInput
                label="Pinecone API key"
                value={getConfig(nodeData, 'pineconeApiKey', '')}
                onChange={e => update('pineconeApiKey', e.currentTarget.value)}
                type="password"
                size="xs"
                styles={FIELD_STYLES}
              />
              <TextInput
                label="Environment"
                value={getConfig(nodeData, 'pineconeEnvironment', '')}
                onChange={e => update('pineconeEnvironment', e.currentTarget.value)}
                placeholder="us-east-1-aws"
                size="xs"
                styles={MONO_INPUT}
              />
              <TextInput
                label="Index name"
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
                label="Datasource"
                value={getConfig(nodeData, 'pgDatasource', '')}
                onChange={e => update('pgDatasource', e.currentTarget.value)}
                placeholder="datasource-name"
                size="xs"
                styles={MONO_INPUT}
              />
              <TextInput
                label="Table name"
                value={getConfig(nodeData, 'pgTableName', 'embeddings')}
                onChange={e => update('pgTableName', e.currentTarget.value)}
                size="xs"
                styles={MONO_INPUT}
              />
              <NumberInput
                label="Dimensions"
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
                label="Redis URL"
                value={getConfig(nodeData, 'vectorRedisUrl', 'redis://localhost:6379')}
                onChange={e => update('vectorRedisUrl', e.currentTarget.value)}
                size="xs"
                styles={MONO_INPUT}
              />
              <TextInput
                label="Key prefix"
                value={getConfig(nodeData, 'vectorRedisPrefix', 'vec:')}
                onChange={e => update('vectorRedisPrefix', e.currentTarget.value)}
                size="xs"
                styles={MONO_INPUT}
              />
            </>
          )}

          {nodeData.nodeType === 'vector-search' && (
            <NumberInput
              label="Top K results"
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
          <Select
            label="Embedding provider"
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
              { value: 'openai', label: 'OpenAI' },
              { value: 'local',  label: 'Local (ONNX)' },
            ]}
            styles={FIELD_STYLES}
          />
          <Select
            label="Model"
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
          <NumberInput
            label="Dimensions"
            value={getConfig(nodeData, 'embeddingDimensions', 1536)}
            onChange={v => update('embeddingDimensions', v)}
            min={1}
            max={4096}
            size="xs"
            styles={FIELD_STYLES}
          />
        </>
      )}
    </div>
  )
}

// ── Execution Logs ──────────────────────────────────────────────
function ExecutionLogs({ execState }: { execState: any }) {
  if (!execState) {
    return (
      <div style={{ fontSize: 12, color: 'var(--color-text-tertiary)', textAlign: 'center', paddingTop: 24 }}>
        No execution data yet
      </div>
    )
  }

  const ts = new Date(execState.startedAt ?? Date.now()).toLocaleTimeString()

  return (
    <div style={{ background: 'var(--color-background-secondary)', borderRadius: 7, padding: '10px 12px', fontFamily: 'var(--font-mono)', fontSize: 11, lineHeight: 1.7 }}>
      <div style={{ color: 'var(--color-text-tertiary)' }}>[{ts}] Node started</div>
      {execState.status === 'success' && (
        <div style={{ color: 'var(--color-text-success)' }}>[{ts}] Completed in {execState.durationMs?.toFixed(0)}ms</div>
      )}
      {execState.status === 'error' && (
        <div style={{ color: 'var(--color-text-danger)' }}>[{ts}] {execState.error}</div>
      )}
      {execState.status === 'running' && (
        <div style={{ color: 'var(--color-text-warning)' }}>[{ts}] Running...</div>
      )}
    </div>
  )
}

// ── Output Preview ──────────────────────────────────────────────
function OutputPreview({ execState }: { execState: any }) {
  if (!execState?.output) {
    return (
      <div style={{ fontSize: 12, color: 'var(--color-text-tertiary)', textAlign: 'center', paddingTop: 24 }}>
        No output available
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
  return (
    <div style={{ flex: 1, display: 'flex', flexDirection: 'column', alignItems: 'center', justifyContent: 'center', gap: 8, color: 'var(--color-text-tertiary)', padding: 24, textAlign: 'center' }}>
      <div style={{ fontSize: 20 }}>{'\u25CE'}</div>
      <div style={{ fontSize: 13, color: 'var(--color-text-secondary)' }}>No node selected</div>
      <div style={{ fontSize: 12 }}>Click a node on the canvas to view its properties</div>
    </div>
  )
}
