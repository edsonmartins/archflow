import { Select, TextInput, Textarea, NumberInput, Divider, ScrollArea, Tabs } from '@mantine/core'
import { useFlowStore }     from './FlowCanvas/store/useFlowStore'
import type { FlowNodeData } from './FlowCanvas/types'
import { NODE_CATEGORIES }  from './FlowCanvas/constants'

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
        display:    'flex',
        flexDirection: 'column',
        height:     '100%',
        borderLeft: '0.5px solid var(--color-border-tertiary)',
        background: 'var(--color-background-primary)',
        overflow:   'hidden',
      }}
    >
      {nodeData ? (
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
                <NodeFields nodeData={nodeData} />
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

// ── Cabeçalho do nó ─────────────────────────────────────────────
function NodeHeader({ nodeData }: { nodeData: FlowNodeData }) {
  const cat = NODE_CATEGORIES[nodeData.nodeType as keyof typeof NODE_CATEGORIES]
    ?? NODE_CATEGORIES.io

  return (
    <div
      style={{
        padding:       '12px 16px',
        borderBottom:  '0.5px solid var(--color-border-tertiary)',
        display:       'flex',
        alignItems:    'center',
        gap:           10,
      }}
    >
      <div
        style={{
          width:          32,
          height:         32,
          borderRadius:   8,
          background:     cat.colorLight,
          border:         `1px solid ${cat.border}`,
          display:        'flex',
          alignItems:     'center',
          justifyContent: 'center',
          fontSize:       14,
          flexShrink:     0,
        }}
      >
        {(nodeData.config?.icon as string) ?? '●'}
      </div>
      <div>
        <div style={{ fontSize: 13, fontWeight: 500, color: 'var(--color-text-primary)' }}>
          {nodeData.label}
        </div>
        <div style={{ fontSize: 11, color: 'var(--color-text-tertiary)', fontFamily: 'var(--font-mono)' }}>
          {nodeData.nodeType} · {cat.label}
        </div>
      </div>
    </div>
  )
}

// ── Campos de propriedade por tipo de nó ────────────────────────
function NodeFields({ nodeData }: { nodeData: FlowNodeData }) {
  const isAgent = ['agent', 'assistant', 'llm-chat', 'llm-streaming'].includes(nodeData.nodeType)
  const isTool  = ['tool', 'function', 'custom'].includes(nodeData.nodeType)

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 14 }}>
      <TextInput
        label="Node name"
        defaultValue={nodeData.label}
        size="xs"
        styles={{ input: { fontSize: 12 }, label: { fontSize: 11, fontWeight: 500 } }}
      />

      {isAgent && (
        <>
          <Select
            label="Model"
            defaultValue="claude-sonnet-4-6"
            size="xs"
            data={[
              { value: 'claude-sonnet-4-6',  label: 'Claude Sonnet 4.6' },
              { value: 'claude-opus-4-6',    label: 'Claude Opus 4.6'   },
              { value: 'gpt-4o',             label: 'GPT-4o'            },
              { value: 'gemma-3-27b-local',  label: 'Gemma 3 27B (local)' },
            ]}
            styles={{ input: { fontSize: 12 }, label: { fontSize: 11, fontWeight: 500 } }}
          />

          <div>
            <div style={{ fontSize: 11, fontWeight: 500, color: 'var(--color-text-secondary)', marginBottom: 5 }}>
              System prompt
            </div>
            <Textarea
              defaultValue={(nodeData.config?.systemPrompt as string) ?? ''}
              placeholder="You are a helpful assistant..."
              minRows={3}
              maxRows={6}
              autosize
              size="xs"
              styles={{ input: { fontSize: 12, fontFamily: 'var(--font-mono)' } }}
            />
          </div>

          <NumberInput
            label="Max iterations"
            defaultValue={10}
            min={1}
            max={50}
            size="xs"
            styles={{ input: { fontSize: 12 }, label: { fontSize: 11, fontWeight: 500 } }}
          />

          <NumberInput
            label="Temperature"
            defaultValue={0.7}
            min={0}
            max={2}
            step={0.1}
            decimalScale={1}
            size="xs"
            styles={{ input: { fontSize: 12 }, label: { fontSize: 11, fontWeight: 500 } }}
          />
        </>
      )}

      {isTool && (
        <>
          <TextInput
            label="Tool ID"
            defaultValue={(nodeData.config?.toolId as string) ?? ''}
            size="xs"
            styles={{ input: { fontSize: 12, fontFamily: 'var(--font-mono)' }, label: { fontSize: 11 } }}
          />
          <Select
            label="On error"
            defaultValue="stop"
            size="xs"
            data={[
              { value: 'stop',     label: 'Stop execution'  },
              { value: 'continue', label: 'Continue'        },
              { value: 'retry',    label: 'Retry (3x)'      },
            ]}
            styles={{ input: { fontSize: 12 }, label: { fontSize: 11 } }}
          />
        </>
      )}
    </div>
  )
}

// ── Log de execução ──────────────────────────────────────────────
function ExecutionLogs({ execState }: { execState: any }) {
  if (!execState) {
    return (
      <div style={{ fontSize: 12, color: 'var(--color-text-tertiary)', textAlign: 'center', paddingTop: 24 }}>
        No execution data yet
      </div>
    )
  }

  const logs = execState.logs ?? []
  const ts   = new Date(execState.startedAt ?? Date.now()).toLocaleTimeString()

  return (
    <div
      style={{
        background:  'var(--color-background-secondary)',
        borderRadius: 7,
        padding:     '10px 12px',
        fontFamily:  'var(--font-mono)',
        fontSize:    11,
        lineHeight:  1.7,
      }}
    >
      <div style={{ color: 'var(--color-text-tertiary)' }}>[{ts}] Node started</div>
      {execState.status === 'success' && (
        <>
          <div style={{ color: 'var(--color-text-success)' }}>[{ts}] ✓ Completed in {execState.durationMs?.toFixed(0)}ms</div>
        </>
      )}
      {execState.status === 'error' && (
        <div style={{ color: 'var(--color-text-danger)' }}>[{ts}] ✕ {execState.error}</div>
      )}
      {execState.status === 'running' && (
        <div style={{ color: 'var(--color-text-warning)' }}>[{ts}] ◌ Running...</div>
      )}
    </div>
  )
}

// ── Preview de output ────────────────────────────────────────────
function OutputPreview({ execState }: { execState: any }) {
  if (!execState?.output) {
    return (
      <div style={{ fontSize: 12, color: 'var(--color-text-tertiary)', textAlign: 'center', paddingTop: 24 }}>
        No output available
      </div>
    )
  }

  return (
    <pre
      style={{
        background:   'var(--color-background-secondary)',
        borderRadius: 7,
        padding:      '10px 12px',
        fontFamily:   'var(--font-mono)',
        fontSize:     11,
        color:        'var(--color-text-primary)',
        whiteSpace:   'pre-wrap',
        wordBreak:    'break-all',
        margin:       0,
      }}
    >
      {JSON.stringify(execState.output, null, 2)}
    </pre>
  )
}

// ── Estado vazio do painel ───────────────────────────────────────
function EmptyPanel() {
  return (
    <div
      style={{
        flex:           1,
        display:        'flex',
        flexDirection:  'column',
        alignItems:     'center',
        justifyContent: 'center',
        gap:            8,
        color:          'var(--color-text-tertiary)',
        padding:        24,
        textAlign:      'center',
      }}
    >
      <div style={{ fontSize: 20 }}>◎</div>
      <div style={{ fontSize: 13, color: 'var(--color-text-secondary)' }}>No node selected</div>
      <div style={{ fontSize: 12 }}>Click a node on the canvas to view its properties</div>
    </div>
  )
}
