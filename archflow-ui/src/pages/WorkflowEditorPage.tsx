import { useEffect, useCallback, useMemo, useState } from 'react'
import { useParams }         from 'react-router-dom'
import { notifications }     from '@mantine/notifications'
import { FlowCanvas }        from '../components/FlowCanvas/FlowCanvas'
import { NodePalette }       from '../components/NodePalette'
import { PropertyPanel }     from '../components/PropertyPanel'
import YamlEditor            from '../components/CodeEditor/YamlEditor'
import { useFlowStore }      from '../components/FlowCanvas/store/useFlowStore'
import { useWorkflowStore }  from '../stores/workflow-store'
import { workflowYamlApi }   from '../services/workflow-yaml-api'
import type { FlowNodeData, WorkflowData } from '../components/FlowCanvas/types'
import { NODE_TYPE_TO_CATEGORY } from '../components/FlowCanvas/constants'

function toWorkflowData(detail: any): WorkflowData {
  const steps = (detail.steps ?? []).map((step: any, i: number) => ({
    id:          step.id ?? `step-${i}`,
    type:        step.type?.toLowerCase() ?? 'custom',
    componentId: step.componentId ?? step.type?.toLowerCase() ?? 'custom',
    label:       step.operation ?? step.componentId ?? step.type ?? 'Node',
    category:    NODE_TYPE_TO_CATEGORY[step.type?.toLowerCase()] ?? 'tool' as const,
    position:    step.position ?? { x: 200 + i * 250, y: 150 },
    config:      step.configuration ?? {},
  }))

  const connections = (detail.steps ?? []).flatMap((step: any) =>
    (step.connections ?? []).map((conn: any, j: number) => ({
      id:          `conn-${step.id}-${j}`,
      sourceId:    conn.sourceId ?? step.id,
      targetId:    conn.targetId,
      isErrorPath: conn.isErrorPath ?? false,
    }))
  )

  return { steps, connections }
}

type EditorView = 'canvas' | 'yaml'

export function WorkflowEditor() {
  const { id } = useParams<{ id: string }>()

  const { currentWorkflow: current, fetchWorkflow: loadWorkflow, updateWorkflow, loading: isSaving } = useWorkflowStore()
  const lastSavedAt: number | null = null // TODO: track save timestamp
  const saveWorkflow = async () => { if (current) await updateWorkflow(current.id, current) }
  const workflowData = useMemo(() => current ? toWorkflowData(current) : null, [current])
  const { selectedNodeId, selectedNodeData, selectNode, startExecution, isExecuting } = useFlowStore()

  // ── YAML view state ───────────────────────────────────────────
  const [view, setView] = useState<EditorView>('canvas')
  const [yamlText, setYamlText] = useState<string>('')
  const [yamlLoading, setYamlLoading] = useState(false)
  const [yamlError, setYamlError] = useState<string | null>(null)
  const [yamlDirty, setYamlDirty] = useState(false)

  // Carregar workflow ao montar
  useEffect(() => {
    if (id) loadWorkflow(id)
  }, [id])

  // Carrega o YAML do backend quando o usuário abre a aba Code
  useEffect(() => {
    if (view !== 'yaml' || !id) return
    let cancelled = false
    setYamlLoading(true)
    setYamlError(null)
    workflowYamlApi
      .get(id)
      .then((dto) => {
        if (!cancelled) {
          setYamlText(dto.yaml ?? '')
          setYamlDirty(false)
        }
      })
      .catch((e: unknown) => {
        if (!cancelled) {
          setYamlError(e instanceof Error ? e.message : 'Failed to load YAML')
        }
      })
      .finally(() => {
        if (!cancelled) setYamlLoading(false)
      })
    return () => {
      cancelled = true
    }
  }, [view, id])

  const handleSaveYaml = useCallback(async () => {
    if (!id) return
    setYamlError(null)
    try {
      const dto = await workflowYamlApi.update(id, yamlText)
      setYamlText(dto.yaml ?? yamlText)
      setYamlDirty(false)
      // Reload the JSON-backed canvas so the next Canvas view picks up the edits
      await loadWorkflow(id)
      notifications.show({
        title: 'YAML saved',
        message: 'Workflow updated from YAML',
        color: 'teal',
      })
    } catch (e) {
      setYamlError(e instanceof Error ? e.message : 'Failed to save YAML')
      notifications.show({
        title: 'YAML error',
        message: 'Failed to save YAML',
        color: 'red',
      })
    }
  }, [id, yamlText, loadWorkflow])

  // ── Handlers ──────────────────────────────────────────────────
  const handleNodeSelect = useCallback(
    (nodeId: string | null, data: FlowNodeData | null) => {
      selectNode(nodeId, data as any)
    },
    [selectNode]
  )

  const handleSave = useCallback(async () => {
    if (!current) return
    try {
      await saveWorkflow()
      notifications.show({
        title:   'Saved',
        message: 'Workflow saved successfully',
        color:   'teal',
      })
    } catch {
      notifications.show({
        title:   'Error',
        message: 'Failed to save workflow',
        color:   'red',
      })
    }
  }, [current, saveWorkflow])

  const handleExecute = useCallback(() => {
    // Em produção: chamar API, receber executionId, ouvir SSE
    const execId = `exec-${Date.now()}`
    startExecution(execId)
    notifications.show({
      title:   'Execution started',
      message: `Execution ID: ${execId}`,
      color:   'blue',
    })
  }, [startExecution])

  // ── Auto-save (debounced 3s) ───────────────────────────────────
  // useEffect(() => {
  //   const timer = setTimeout(handleSave, 3000)
  //   return () => clearTimeout(timer)
  // }, [nodes, edges])   ← ativar quando quiser auto-save

  const formattedSavedAt = lastSavedAt
    ? new Intl.RelativeTimeFormat('en', { numeric: 'auto' }).format(
        Math.round((lastSavedAt - Date.now()) / 1000),
        'second'
      )
    : null

  return (
    <div style={{ display: 'flex', flexDirection: 'column', height: 'calc(100vh - 52px - 32px)', overflow: 'hidden' }}>
      {/* ── Top bar ─────────────────────────────────────────── */}
      <div
        style={{
          display:       'flex',
          alignItems:    'center',
          gap:           8,
          borderBottom:  '0.5px solid var(--color-border-tertiary)',
          background:    'var(--color-background-primary)',
          padding:       '8px 12px',
          flexShrink:    0,
        }}
      >
        <div style={{ display: 'flex', alignItems: 'center', gap: 8, flex: 1 }}>
          <span style={{ fontSize: 14, fontWeight: 500, color: 'var(--color-text-primary)' }}>
            {current?.metadata?.name ?? 'Untitled workflow'}
          </span>
          <span
            style={{
              fontSize:   11,
              color:      'var(--color-text-tertiary)',
              background: 'var(--color-background-tertiary)',
              border:     '0.5px solid var(--color-border-tertiary)',
              padding:    '1px 7px',
              borderRadius: 20,
              fontFamily: 'var(--font-mono)',
            }}
          >
            {current?.metadata?.version ?? 'v1.0.0'}
          </span>
        </div>

        <div style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
          {formattedSavedAt && (
            <span style={{ fontSize: 11, color: 'var(--color-text-tertiary)', marginRight: 8 }}>
              Saved {formattedSavedAt}
            </span>
          )}

          {/* Canvas / Code toggle */}
          <div
            role="tablist"
            style={{
              display: 'inline-flex',
              border: '0.5px solid var(--color-border-tertiary)',
              borderRadius: 7,
              overflow: 'hidden',
              marginRight: 6,
            }}
          >
            <button
              role="tab"
              aria-selected={view === 'canvas'}
              data-testid="editor-tab-canvas"
              onClick={() => setView('canvas')}
              style={{
                ...tabBtnStyle,
                background:
                  view === 'canvas' ? 'var(--color-background-secondary)' : 'transparent',
                fontWeight: view === 'canvas' ? 600 : 400,
              }}
            >
              Canvas
            </button>
            <button
              role="tab"
              aria-selected={view === 'yaml'}
              data-testid="editor-tab-code"
              onClick={() => setView('yaml')}
              style={{
                ...tabBtnStyle,
                background:
                  view === 'yaml' ? 'var(--color-background-secondary)' : 'transparent',
                fontWeight: view === 'yaml' ? 600 : 400,
              }}
            >
              Code {yamlDirty && '●'}
            </button>
          </div>

          <button aria-label="Undo" style={iconBtnStyle} title="Undo (⌘Z)">↩</button>
          <button aria-label="Redo" style={iconBtnStyle} title="Redo (⌘⇧Z)">↪</button>

          <button
            onClick={view === 'yaml' ? handleSaveYaml : handleSave}
            disabled={isSaving || (view === 'yaml' && !yamlDirty)}
            style={{ ...btnStyle, opacity: isSaving ? 0.6 : 1 }}
            data-testid="editor-save"
          >
            {isSaving ? 'Saving...' : view === 'yaml' ? 'Save YAML' : 'Save'}
          </button>

          <button
            onClick={handleExecute}
            disabled={isExecuting}
            style={{
              ...btnStyle,
              background: isExecuting ? '#85B7EB' : '#378ADD',
              border: '0.5px solid #185FA5',
              color: '#fff',
            }}
          >
            {isExecuting ? '◌ Running...' : '▶ Execute'}
          </button>
        </div>
      </div>

      {/* ── Editor body ──────────────────────────────────────── */}
      {view === 'canvas' ? (
        <div style={{ display: 'flex', flex: 1, overflow: 'hidden' }}>
          {/* Palette */}
          <div style={{ width: 240, flexShrink: 0, overflow: 'hidden' }}>
            <NodePalette />
          </div>

          {/* Canvas */}
          <div style={{ flex: 1, position: 'relative', overflow: 'hidden' }}>
            {workflowData ? (
              <FlowCanvas
                initialWorkflow={workflowData}
                onNodeSelect={handleNodeSelect}
                onExecutionRequest={handleExecute}
              />
            ) : (
              <EmptyCanvas />
            )}
          </div>

          {/* Property panel */}
          <div style={{ width: 320, flexShrink: 0, overflow: 'hidden' }}>
            <PropertyPanel
              nodeId={selectedNodeId}
              nodeData={selectedNodeData as FlowNodeData | null}
            />
          </div>
        </div>
      ) : (
        <div style={{ flex: 1, overflow: 'hidden', padding: 12 }}>
          {yamlLoading ? (
            <div style={{ padding: 40, textAlign: 'center', color: 'var(--color-text-tertiary)' }}>
              Loading YAML…
            </div>
          ) : (
            <YamlEditor
              value={yamlText}
              onChange={(v) => {
                setYamlText(v)
                setYamlDirty(true)
              }}
              error={yamlError}
            />
          )}
        </div>
      )}
    </div>
  )
}

// ── Empty state do canvas ────────────────────────────────────────
function EmptyCanvas() {
  return (
    <div
      style={{
        height:         '100%',
        display:        'flex',
        flexDirection:  'column',
        alignItems:     'center',
        justifyContent: 'center',
        gap:            12,
        background:     'var(--color-background-tertiary)',
        color:          'var(--color-text-tertiary)',
      }}
    >
      <svg width="48" height="48" viewBox="0 0 48 48" fill="none">
        <circle cx="12" cy="24" r="5" stroke="currentColor" strokeWidth="1.5" strokeDasharray="3 2"/>
        <circle cx="36" cy="14" r="5" stroke="currentColor" strokeWidth="1.5" strokeDasharray="3 2"/>
        <circle cx="36" cy="34" r="5" stroke="currentColor" strokeWidth="1.5" strokeDasharray="3 2"/>
        <line x1="17" y1="22.5" x2="31" y2="15.5" stroke="currentColor" strokeWidth="1.5" strokeDasharray="3 2"/>
        <line x1="17" y1="25.5" x2="31" y2="32.5" stroke="currentColor" strokeWidth="1.5" strokeDasharray="3 2"/>
      </svg>
      <div style={{ fontSize: 16, fontWeight: 500, color: 'var(--color-text-secondary)' }}>
        No workflow loaded
      </div>
      <div style={{ fontSize: 13, color: 'var(--color-text-tertiary)', maxWidth: 260, textAlign: 'center' }}>
        Create a new workflow or load an existing one to get started
      </div>
    </div>
  )
}

// ── Estilos utilitários ──────────────────────────────────────────
const btnStyle: React.CSSProperties = {
  padding:      '5px 14px',
  borderRadius: 7,
  fontSize:     12,
  fontWeight:   500,
  cursor:       'pointer',
  border:       '0.5px solid var(--color-border-secondary)',
  background:   'var(--color-background-primary)',
  color:        'var(--color-text-primary)',
  fontFamily:   'var(--font-sans)',
}

const iconBtnStyle: React.CSSProperties = {
  width:        30,
  height:       30,
  borderRadius: 7,
  border:       '0.5px solid var(--color-border-tertiary)',
  background:   'var(--color-background-primary)',
  display:      'flex',
  alignItems:   'center',
  justifyContent: 'center',
  cursor:       'pointer',
  color:        'var(--color-text-secondary)',
  fontSize:     14,
  fontFamily:   'var(--font-sans)',
}

const tabBtnStyle: React.CSSProperties = {
  padding:     '4px 14px',
  border:      'none',
  cursor:      'pointer',
  fontSize:    12,
  fontFamily:  'var(--font-sans)',
  color:       'var(--color-text-primary)',
  background:  'transparent',
}
