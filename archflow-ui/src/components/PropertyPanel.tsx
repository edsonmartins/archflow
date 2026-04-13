import {
  Select, TextInput, Textarea, NumberInput, Divider, ScrollArea, Tabs,
  Accordion, Switch, MultiSelect, Badge, Text,
} from '@mantine/core'
import { useFlowStore }     from './FlowCanvas/store/useFlowStore'
import type { FlowNodeData } from './FlowCanvas/types'
import { NODE_CATEGORIES }  from './FlowCanvas/constants'
import { FIELD_STYLES, MONO_INPUT } from './PropertyPanel/fieldStyles'

// ── Provider / model data (will be API-driven in production) ─────
const PROVIDERS = [
  {
    id: 'openai', displayName: 'OpenAI', group: 'Cloud',
    models: [
      { id: 'gpt-4o',       name: 'GPT-4o',       contextWindow: 128000, maxTemperature: 2.0 },
      { id: 'gpt-4o-mini',  name: 'GPT-4o Mini',  contextWindow: 128000, maxTemperature: 2.0 },
      { id: 'gpt-4-turbo',  name: 'GPT-4 Turbo',  contextWindow: 128000, maxTemperature: 2.0 },
      { id: 'o1',           name: 'o1',            contextWindow: 200000, maxTemperature: 1.0 },
      { id: 'o3-mini',      name: 'o3 Mini',       contextWindow: 200000, maxTemperature: 1.0 },
    ],
  },
  {
    id: 'anthropic', displayName: 'Anthropic', group: 'Cloud',
    models: [
      { id: 'claude-sonnet-4-6',        name: 'Claude Sonnet 4.6',    contextWindow: 200000, maxTemperature: 1.0 },
      { id: 'claude-opus-4-6',          name: 'Claude Opus 4.6',      contextWindow: 200000, maxTemperature: 1.0 },
      { id: 'claude-3-5-sonnet-latest', name: 'Claude 3.5 Sonnet',    contextWindow: 200000, maxTemperature: 1.0 },
      { id: 'claude-3-haiku-20240307',  name: 'Claude 3 Haiku',       contextWindow: 200000, maxTemperature: 1.0 },
    ],
  },
  {
    id: 'azure-openai', displayName: 'Azure OpenAI', group: 'Cloud',
    models: [
      { id: 'azure-gpt-4o',      name: 'GPT-4o (Azure)',      contextWindow: 128000, maxTemperature: 2.0 },
      { id: 'azure-gpt-4o-mini', name: 'GPT-4o Mini (Azure)', contextWindow: 128000, maxTemperature: 2.0 },
    ],
  },
  {
    id: 'gemini', displayName: 'Gemini', group: 'Cloud',
    models: [
      { id: 'gemini-2.0-flash-exp', name: 'Gemini 2.0 Flash',  contextWindow: 1048576, maxTemperature: 2.0 },
      { id: 'gemini-1.5-pro',       name: 'Gemini 1.5 Pro',    contextWindow: 2097152, maxTemperature: 2.0 },
      { id: 'gemini-1.5-flash',     name: 'Gemini 1.5 Flash',  contextWindow: 1048576, maxTemperature: 2.0 },
    ],
  },
  {
    id: 'mistral', displayName: 'Mistral', group: 'Cloud',
    models: [
      { id: 'mistral-large-latest', name: 'Mistral Large',   contextWindow: 128000, maxTemperature: 1.5 },
      { id: 'mistral-medium',       name: 'Mistral Medium',  contextWindow: 32000,  maxTemperature: 1.5 },
      { id: 'codestral-latest',     name: 'Codestral',       contextWindow: 32000,  maxTemperature: 1.0 },
    ],
  },
  {
    id: 'cohere', displayName: 'Cohere', group: 'Cloud',
    models: [
      { id: 'command-r-plus', name: 'Command R+', contextWindow: 128000, maxTemperature: 1.0 },
      { id: 'command-r',      name: 'Command R',  contextWindow: 128000, maxTemperature: 1.0 },
    ],
  },
  {
    id: 'deepseek', displayName: 'DeepSeek', group: 'Cloud',
    models: [
      { id: 'deepseek-chat',     name: 'DeepSeek Chat',     contextWindow: 64000, maxTemperature: 2.0 },
      { id: 'deepseek-coder',    name: 'DeepSeek Coder',    contextWindow: 64000, maxTemperature: 2.0 },
      { id: 'deepseek-reasoner', name: 'DeepSeek Reasoner', contextWindow: 64000, maxTemperature: 1.0 },
    ],
  },
  {
    id: 'openrouter', displayName: 'OpenRouter', group: 'Cloud',
    models: [
      { id: 'openrouter/auto',            name: 'Auto (best available)', contextWindow: 128000, maxTemperature: 2.0 },
      { id: 'meta-llama/llama-3.3-70b',   name: 'Llama 3.3 70B',        contextWindow: 128000, maxTemperature: 2.0 },
      { id: 'google/gemma-2-27b-it',      name: 'Gemma 2 27B',          contextWindow: 8192,   maxTemperature: 2.0 },
    ],
  },
  {
    id: 'bedrock', displayName: 'AWS Bedrock', group: 'Cloud',
    models: [
      { id: 'bedrock-claude-3-sonnet', name: 'Claude 3 Sonnet (Bedrock)', contextWindow: 200000, maxTemperature: 1.0 },
      { id: 'bedrock-llama-3-70b',     name: 'Llama 3 70B (Bedrock)',     contextWindow: 8192,   maxTemperature: 1.0 },
    ],
  },
  {
    id: 'ollama', displayName: 'Ollama (local)', group: 'Local',
    models: [
      { id: 'llama3.3',       name: 'Llama 3.3',       contextWindow: 128000, maxTemperature: 2.0 },
      { id: 'mistral',        name: 'Mistral 7B',      contextWindow: 32000,  maxTemperature: 1.5 },
      { id: 'qwen2.5',        name: 'Qwen 2.5',        contextWindow: 128000, maxTemperature: 2.0 },
      { id: 'deepseek-coder', name: 'DeepSeek Coder',  contextWindow: 16000,  maxTemperature: 2.0 },
      { id: 'gemma-3-27b',    name: 'Gemma 3 27B',     contextWindow: 8192,   maxTemperature: 2.0 },
      { id: 'phi3',           name: 'Phi-3',           contextWindow: 128000, maxTemperature: 2.0 },
    ],
  },
]

const AGENT_PATTERNS = [
  { value: 'react',           label: 'ReAct (Reason + Act)',              desc: 'Iterative thought-action-observation loop. Best for multi-step tool use.' },
  { value: 'plan-execute',    label: 'Plan and Execute',                  desc: 'Separates planning from execution. Cost-efficient for complex tasks.' },
  { value: 'rewoo',           label: 'ReWOO (Reasoning Without Observation)', desc: 'Plans all tool calls upfront. 82% fewer tokens than ReAct.' },
  { value: 'chain-of-thought', label: 'Chain of Thought',                 desc: 'Multiple reasoning paths with majority vote. Best for analytical tasks.' },
]

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

function findProvider(providerId: string) {
  return PROVIDERS.find(p => p.id === providerId)
}

function findModel(providerId: string, modelId: string) {
  return findProvider(providerId)?.models.find(m => m.id === modelId)
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
        {(nodeData.config?.icon as string) ?? '\u25CF'}
      </div>
      <div>
        <div style={{ fontSize: 13, fontWeight: 500, color: 'var(--color-text-primary)' }}>{nodeData.label}</div>
        <div style={{ fontSize: 11, color: 'var(--color-text-tertiary)', fontFamily: 'var(--font-mono)' }}>
          {nodeData.nodeType} \u00b7 {cat.label}
        </div>
      </div>
    </div>
  )
}

// ── Node Fields (all gaps implemented) ──────────────────────────
function NodeFields({ nodeId, nodeData }: { nodeId: string; nodeData: FlowNodeData }) {
  const { updateNodeConfig, updateNodeLabel } = useFlowStore()

  const isAgent     = ['agent', 'assistant', 'llm-chat', 'llm-streaming'].includes(nodeData.nodeType)
  const isTool      = ['tool', 'function', 'custom'].includes(nodeData.nodeType)
  const isVector    = ['vector-search', 'vector-store'].includes(nodeData.nodeType)
  const isEmbedding = nodeData.nodeType === 'embedding'

  const update = (key: string, value: unknown) => updateNodeConfig(nodeId, key, value)

  // Derived state for agent nodes
  const providerId = getConfig(nodeData, 'provider', 'anthropic')
  const modelId    = getConfig(nodeData, 'model', 'claude-sonnet-4-6')
  const provider   = findProvider(providerId)
  const model      = findModel(providerId, modelId)
  const maxTemp    = model?.maxTemperature ?? 2.0
  const ctxWindow  = model?.contextWindow ?? 128000

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
              data={AGENT_PATTERNS.map(p => ({ value: p.value, label: p.label }))}
              styles={FIELD_STYLES}
            />
            <Text size="xs" c="dimmed" mt={4} lh={1.4}>
              {AGENT_PATTERNS.find(p => p.value === getConfig(nodeData, 'agentPattern', 'react'))?.desc}
            </Text>
          </div>

          {/* Gap 2: Provider */}
          <Select
            label="Provider"
            value={providerId}
            onChange={v => {
              update('provider', v)
              const firstModel = PROVIDERS.find(p => p.id === v)?.models[0]
              if (firstModel) update('model', firstModel.id)
            }}
            size="xs"
            data={PROVIDERS.map(p => ({ value: p.id, label: p.displayName, group: p.group }))}
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
                  <MultiSelect
                    label="Enabled tools (whitelist)"
                    value={getConfig<string[]>(nodeData, 'enabledTools', [])}
                    onChange={v => update('enabledTools', v)}
                    data={getConfig<string[]>(nodeData, 'enabledTools', [])}
                    searchable
                    creatable
                    getCreateLabel={q => `+ Add "${q}"`}
                    size="xs"
                    styles={FIELD_STYLES}
                  />
                  <MultiSelect
                    label="Disabled tools (blacklist)"
                    value={getConfig<string[]>(nodeData, 'disabledTools', [])}
                    onChange={v => update('disabledTools', v)}
                    data={getConfig<string[]>(nodeData, 'disabledTools', [])}
                    searchable
                    creatable
                    getCreateLabel={q => `+ Add "${q}"`}
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
                  <MultiSelect
                    label="Connected servers"
                    value={getConfig<string[]>(nodeData, 'mcpServers', [])}
                    onChange={v => update('mcpServers', v)}
                    data={getConfig<string[]>(nodeData, 'mcpServers', [])}
                    searchable
                    creatable
                    getCreateLabel={q => `+ Add "${q}"`}
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
