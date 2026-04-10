import { useEffect, useCallback, useMemo } from 'react'
import { useParams }         from 'react-router-dom'
import { notifications }     from '@mantine/notifications'
import { FlowCanvas }        from '../components/FlowCanvas/FlowCanvas'
import { NodePalette }       from '../components/NodePalette'
import { PropertyPanel }     from '../components/PropertyPanel'
import { useFlowStore }      from '../components/FlowCanvas/store/useFlowStore'
import { useWorkflowStore }  from '../stores/workflow-store'
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

export function WorkflowEditor() {
  const { id } = useParams<{ id: string }>()

  const { currentWorkflow: current, fetchWorkflow: loadWorkflow, updateWorkflow, loading: isSaving } = useWorkflowStore()
  const lastSavedAt: number | null = null // TODO: track save timestamp
  const saveWorkflow = async () => { if (current) await updateWorkflow(current.id, current) }
  const workflowData = useMemo(() => current ? toWorkflowData(current) : null, [current])
  const { selectedNodeId, selectedNodeData, selectNode, startExecution, isExecuting } = useFlowStore()

  // Carregar workflow ao montar
  useEffect(() => {
    if (id) loadWorkflow(id)
  }, [id])

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

          <button aria-label="Undo" style={iconBtnStyle} title="Undo (⌘Z)">↩</button>
          <button aria-label="Redo" style={iconBtnStyle} title="Redo (⌘⇧Z)">↪</button>

          <button
            onClick={handleSave}
            disabled={isSaving}
            style={{ ...btnStyle, opacity: isSaving ? 0.6 : 1 }}
          >
            {isSaving ? 'Saving...' : 'Save'}
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

      {/* ── Editor body: palette + canvas + property panel ──── */}
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
