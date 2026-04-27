import { useEffect, useCallback, useMemo, useState } from 'react'
import { useParams }         from 'react-router-dom'
import { useTranslation }    from 'react-i18next'
import { notifications }     from '@mantine/notifications'
import {
    IconChevronLeft,
    IconChevronRight,
} from '@tabler/icons-react'
import { FlowCanvas }        from '../components/FlowCanvas/FlowCanvas'
import { NodePalette }       from '../components/NodePalette'
import { PropertyPanel }     from '../components/PropertyPanel'
import YamlEditor            from '../components/CodeEditor/YamlEditor'
import { useFlowStore }      from '../components/FlowCanvas/store/useFlowStore'
import { useWorkflowStore }  from '../stores/workflow-store'
import { workflowYamlApi }   from '../services/workflow-yaml-api'
import type { FlowNodeData, WorkflowData } from '../components/FlowCanvas/types'
import { NODE_TYPE_TO_CATEGORY } from '../components/FlowCanvas/constants'

/**
 * Floating chevron button that sits flush to the canvas edge and
 * opens / closes one of the side panels. Rendered absolutely so it
 * stays visible when its panel is 0-width. The chevron direction
 * flips to show which way the panel will move on click.
 *
 * <p>Implemented as a native {@code <button>} instead of Mantine's
 * {@code ActionIcon} because React Flow captures pointer events
 * aggressively on its root element — stopping propagation on a raw
 * button guarantees the click is handled before reaching the canvas,
 * no matter the z-index stacking context React Flow creates.</p>
 */
function PanelToggle({
    side, open, onToggle, label, testId,
}: {
    side:     'left' | 'right'
    open:     boolean
    onToggle: () => void
    label:    string
    testId?:  string
}) {
    const pointLeft = (side === 'left') === open
    const Icon = pointLeft ? IconChevronLeft : IconChevronRight
    return (
        <button
            type="button"
            title={label}
            aria-label={label}
            data-testid={testId}
            onMouseDown={e => e.stopPropagation()}
            onClick={e => {
                e.stopPropagation()
                e.preventDefault()
                onToggle()
            }}
            style={{
                position:        'absolute',
                top:             12,
                [side]:          8,
                zIndex:          1000,
                width:           26,
                height:          26,
                borderRadius:    6,
                border:          '1px solid var(--mantine-color-default-border, #E2E8F0)',
                background:      'var(--mantine-color-body, #FFFFFF)',
                color:           'var(--mantine-color-text, #0F172A)',
                display:         'flex',
                alignItems:      'center',
                justifyContent:  'center',
                boxShadow:       '0 2px 6px rgba(15,23,42,0.14)',
                cursor:          'pointer',
                padding:         0,
                pointerEvents:   'auto',
            }}
        >
            <Icon size={14} stroke={2.5} />
        </button>
    )
}

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
  const { t } = useTranslation()

  const { currentWorkflow: current, fetchWorkflow: loadWorkflow, updateWorkflow, loading: isSaving } = useWorkflowStore()
  const { getCanvasSnapshot } = useFlowStore()
  // Timestamp (epoch millis) of the most recent successful save. Used
  // by the "saved N seconds ago" indicator in the toolbar.
  const [lastSavedAt, setLastSavedAt] = useState<number | null>(null)
  // Side panel visibility — persisted so the user's preference
  // survives navigating away from the editor. Plain useState (reading
  // once from localStorage) avoids the async-hydration quirk that was
  // preventing the toggle buttons from firing on first click.
  const [paletteVisible, _setPaletteVisible] = useState<boolean>(() => {
    try { return localStorage.getItem('archflow-editor-palette-visible') !== 'false' }
    catch { return true }
  })
  const [propertiesVisible, _setPropertiesVisible] = useState<boolean>(() => {
    try { return localStorage.getItem('archflow-editor-properties-visible') !== 'false' }
    catch { return true }
  })
  const setPaletteVisible = (updater: boolean | ((v: boolean) => boolean)) => {
    _setPaletteVisible(prev => {
      const next = typeof updater === 'function' ? updater(prev) : updater
      try { localStorage.setItem('archflow-editor-palette-visible', String(next)) } catch { /* ignore */ }
      return next
    })
  }
  const setPropertiesVisible = (updater: boolean | ((v: boolean) => boolean)) => {
    _setPropertiesVisible(prev => {
      const next = typeof updater === 'function' ? updater(prev) : updater
      try { localStorage.setItem('archflow-editor-properties-visible', String(next)) } catch { /* ignore */ }
      return next
    })
  }

  const saveWorkflow = async () => {
    if (!current) return
    const snapshot = getCanvasSnapshot()
    const merged = {
      ...current,
      steps: snapshot.steps.map(s => ({
        id:            s.id,
        type:          s.type?.toUpperCase(),
        componentId:   s.componentId,
        operation:     s.label,
        position:      s.position,
        configuration: s.config ?? {},
        connections:   snapshot.connections
          .filter(c => c.sourceId === s.id)
          .map(c => ({
            sourceId:    c.sourceId,
            targetId:    c.targetId,
            isErrorPath: c.isErrorPath,
          })),
      })),
    }
    await updateWorkflow(current.id, merged)
    setLastSavedAt(Date.now())
  }
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
      setLastSavedAt(Date.now())
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
              {t('editor.canvas')}
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
              {t('editor.code')} {yamlDirty && '●'}
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
            {isSaving ? t('common.saving') : view === 'yaml' ? t('editor.saveYaml') : t('editor.save')}
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
            {isExecuting ? `◌ ${t('common.loading')}` : `▶ ${t('editor.run')}`}
          </button>
        </div>
      </div>

      {/* ── Editor body ──────────────────────────────────────── */}
      {view === 'canvas' ? (
        <div style={{ display: 'flex', flex: 1, overflow: 'hidden', position: 'relative' }}>
          {/* Palette — animates width between 240 and 0. A thin edge
              button stays visible at all times so the user can bring
              it back without hunting a menu. */}
          <div
            style={{
              width:      paletteVisible ? 240 : 0,
              flexShrink: 0,
              overflow:   'hidden',
              transition: 'width 0.18s ease',
            }}
          >
            <NodePalette />
          </div>

          {/* Canvas — gains the reclaimed space automatically via
              flex:1. Absolute toggles float over the canvas edges. */}
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

            <PanelToggle
              side="left"
              open={paletteVisible}
              onToggle={() => setPaletteVisible(v => !v)}
              label={t('editor.properties.togglePalette')}
              testId="toggle-palette"
            />
            <PanelToggle
              side="right"
              open={propertiesVisible}
              onToggle={() => setPropertiesVisible(v => !v)}
              label={t('editor.properties.toggleProperties')}
              testId="toggle-properties"
            />
          </div>

          {/* Property panel — same collapse animation. */}
          <div
            style={{
              width:      propertiesVisible ? 320 : 0,
              flexShrink: 0,
              overflow:   'hidden',
              transition: 'width 0.18s ease',
            }}
          >
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
