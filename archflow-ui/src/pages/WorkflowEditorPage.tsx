import { useEffect, useCallback, useMemo, useRef, useState } from 'react'
import { useParams, useSearchParams } from 'react-router-dom'
import { useTranslation }    from 'react-i18next'
import { notifications }     from '@mantine/notifications'
import { Button, Modal, Stack, Textarea } from '@mantine/core'
import {
    IconChevronLeft,
    IconChevronRight,
    IconPlayerPlay,
    IconPlayerStop,
    IconSparkles,
    IconWorldUpload,
} from '@tabler/icons-react'
import { runAgUiWorkflow, type AgUiEvent } from '../services/agui-client'
import { requestCopilotGeneration } from '../components/copilot/CopilotGenerateBridge'
import { PublishModal } from '../components/PublishModal'
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
  const steps = (detail.steps ?? []).map((step: any, i: number) => {
    // A YAML/API-authored execution operation lives at the node level; fold it
    // into `config.operation` so a plain canvas save round-trips it instead of
    // dropping it (the backend's DefaultFlowStepFactory reads config.operation
    // first). Only do this when a distinct `label` is present: pre-schema saves
    // stored the DISPLAY NAME in `operation`, so folding a label-less step's
    // `operation` would make the factory run a bogus operation.
    const config = { ...(step.configuration ?? {}) }
    if (step.operation && step.label && config.operation == null) config.operation = step.operation
    return {
      id:          step.id ?? `step-${i}`,
      type:        step.type?.toLowerCase() ?? 'custom',
      componentId: step.componentId ?? step.type?.toLowerCase() ?? 'custom',
      label:       step.label ?? step.operation ?? step.componentId ?? step.type ?? 'Node',
      category:    NODE_TYPE_TO_CATEGORY[step.type?.toLowerCase()] ?? 'tool' as const,
      position:    step.position ?? { x: 200 + i * 250, y: 150 },
      config,
    }
  })

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
  const { t, i18n } = useTranslation()

  const { currentWorkflow: current, fetchWorkflow: loadWorkflow, updateWorkflow, loading: isSaving } = useWorkflowStore()
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

  const saveWorkflow = useCallback(async () => {
    if (!current) return
    const snapshot = useFlowStore.getState().getCanvasSnapshot()
    const merged = {
      ...current,
      steps: snapshot.steps.map(s => ({
        id:            s.id,
        type:          s.type?.toUpperCase(),
        componentId:   s.componentId,
        // Display name goes into `label`; `operation` is reserved for the
        // execution operation (backend: DefaultFlowStepFactory) and comes
        // from the node config when the user sets one.
        label:         s.label,
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
  }, [current, updateWorkflow])
  // The canvas must only be rebuilt on a genuine (re)load — keying this memo
  // on the workflow OBJECT identity made every save reset the canvas (and
  // wipe undo history + selection), because updateWorkflow replaces
  // `currentWorkflow` with the server response. Key on the id plus an
  // explicit revision that the YAML-save path bumps after reloading.
  const [canvasRevision, setCanvasRevision] = useState(0)
  // eslint-disable-next-line react-hooks/exhaustive-deps
  const workflowData = useMemo(() => current ? toWorkflowData(current) : null, [current?.id, canvasRevision])
  const {
    selectedNodeId, selectedNodeData, selectNode,
    startExecution, adoptExecutionId, updateNodeStatus, finishExecution, abortExecution, isExecuting,
  } = useFlowStore()

  // ── YAML view state ───────────────────────────────────────────
  const [view, setView] = useState<EditorView>('canvas')
  const [yamlText, setYamlText] = useState<string>('')
  const [yamlLoading, setYamlLoading] = useState(false)
  const [yamlError, setYamlError] = useState<string | null>(null)
  const [yamlDirty, setYamlDirty] = useState(false)

  // Carregar workflow ao montar. When we're replacing an ALREADY-loaded copy of
  // the same id (the store persists currentWorkflow across unmount), bump the
  // canvas revision once the fetch resolves so the memo rebuilds with the fresh
  // server copy — otherwise reopening a workflow that changed server-side keeps
  // the stale copy and a Save would overwrite the newer version. A first load or
  // an id change already rebuilds via the memo's id key, so we must NOT bump
  // there: it would double-mount the canvas and reset the undo history.
  useEffect(() => {
    if (!id) return
    let cancelled = false
    const hadSameId = useWorkflowStore.getState().currentWorkflow?.id === id
    loadWorkflow(id).then(() => {
      if (!cancelled && hadSameId) setCanvasRevision(v => v + 1)
    })
    return () => { cancelled = true }
  }, [id, loadWorkflow])

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
      setCanvasRevision(v => v + 1)
      notifications.show({
        title: t('editor.notif.yamlSaved'),
        message: t('editor.notif.yamlSavedMsg'),
        color: 'teal',
      })
    } catch (e) {
      setYamlError(e instanceof Error ? e.message : t('editor.notif.yamlErrorMsg'))
      notifications.show({
        title: t('editor.notif.yamlError'),
        message: t('editor.notif.yamlErrorMsg'),
        color: 'red',
      })
    }
  }, [id, yamlText, loadWorkflow, t])

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
        title:   t('editor.notif.saved'),
        message: t('editor.notif.savedMsg'),
        color:   'teal',
      })
    } catch {
      notifications.show({
        title:   t('editor.notif.saveError'),
        message: t('editor.notif.saveErrorMsg'),
        color:   'red',
      })
    }
  }, [current, saveWorkflow, t])

  // Live run over AG-UI SSE: STEP_* events drive the per-node status
  // pills / animated edges via useFlowStore.executionState (keyed by
  // step id, which equals the canvas node id — getCanvasSnapshot
  // persists steps with the node's id).
  const abortRunRef = useRef<(() => void) | null>(null)
  useEffect(() => () => { abortRunRef.current?.() }, [])

  const handleRunEvent = useCallback((ev: AgUiEvent) => {
    const stepId = typeof ev.stepId === 'string' ? ev.stepId
      : typeof ev.stepName === 'string' ? ev.stepName : null
    switch (ev.type) {
      case 'RUN_STARTED':
        // Adopt the real execution id minted by the backend (exec-…) without
        // resetting executionState — a STEP_STARTED that raced ahead of
        // RUN_STARTED must not be wiped.
        if (typeof ev.runId === 'string') adoptExecutionId(ev.runId)
        break
      case 'STEP_STARTED':
        if (stepId) updateNodeStatus(stepId, { status: 'running', startedAt: Date.now() })
        break
      case 'STEP_FINISHED': {
        if (!stepId) break
        const status = ev.status === 'STEP_FAILED' ? 'error'
          : ev.status === 'STEP_SKIPPED' ? 'skipped'
          : 'success'
        updateNodeStatus(stepId, {
          status,
          durationMs: typeof ev.durationMs === 'number' ? ev.durationMs : undefined,
          error: typeof ev.error === 'string' ? ev.error : undefined,
        })
        break
      }
      case 'RUN_FINISHED':
        finishExecution()
        abortRunRef.current = null
        notifications.show({
          title:   t('editor.notif.execFinished'),
          message: t('editor.notif.execFinishedMsg'),
          color:   'teal',
        })
        break
      case 'RUN_ERROR':
        abortExecution()
        abortRunRef.current = null
        notifications.show({
          title:   t('editor.notif.execFailed'),
          message: typeof ev.message === 'string' && ev.message
            ? ev.message
            : t('editor.notif.execFailedMsg'),
          color:   'red',
        })
        break
      default:
        break
    }
  }, [adoptExecutionId, updateNodeStatus, finishExecution, abortExecution, t])

  const handleExecute = useCallback(async () => {
    if (!current) return
    if (isExecuting) {
      // Second click while running = stop: abort the SSE stream (the
      // backend cancels the run on disconnect) and mark running nodes.
      abortRunRef.current?.()
      abortRunRef.current = null
      abortExecution()
      notifications.show({
        title:   t('editor.notif.execStopped'),
        message: t('editor.notif.execStoppedMsg'),
        color:   'yellow',
      })
      return
    }
    try {
      // The backend executes the persisted workflow, so unsaved canvas
      // edits must land first.
      await saveWorkflow()
    } catch {
      notifications.show({
        title:   t('editor.notif.execStartError'),
        message: t('editor.notif.saveErrorMsg'),
        color:   'red',
      })
      return
    }
    startExecution(current.id)
    abortRunRef.current = runAgUiWorkflow(current.id, {}, handleRunEvent)
    notifications.show({
      title:   t('editor.notif.execStarted'),
      message: t('editor.notif.execStartedMsg', { id: current.id }),
      color:   'blue',
    })
  }, [current, isExecuting, startExecution, abortExecution, handleRunEvent, saveWorkflow, t])

  // ── "Gerar com IA" — hands the goal to the app-wide copilot, whose
  // frontend tools (addNode/connectNodes) assemble the flow on this canvas.
  const [searchParams, setSearchParams] = useSearchParams()
  const [aiOpen, setAiOpen] = useState(false)
  const [aiGoal, setAiGoal] = useState('')
  const [publishOpen, setPublishOpen] = useState(false)

  useEffect(() => {
    // /editor/:id?ai=1 (from the create-workflow modal) opens the AI
    // prompt straight away; strip the flag so refreshes don't re-open it.
    if (searchParams.get('ai') === '1') {
      setAiOpen(true)
      const next = new URLSearchParams(searchParams)
      next.delete('ai')
      setSearchParams(next, { replace: true })
    }
  }, [searchParams, setSearchParams])

  const handleGenerate = useCallback(() => {
    const goal = aiGoal.trim()
    if (!goal) return
    requestCopilotGeneration(t('editor.ai.promptTemplate', { goal }))
    setAiOpen(false)
    setAiGoal('')
    notifications.show({
      title:   t('editor.ai.started'),
      message: t('editor.ai.startedMsg'),
      color:   'grape',
    })
  }, [aiGoal, t])

  // ── Auto-save (debounced 3s) ───────────────────────────────────
  // useEffect(() => {
  //   const timer = setTimeout(handleSave, 3000)
  //   return () => clearTimeout(timer)
  // }, [nodes, edges])   ← ativar quando quiser auto-save

  const formattedSavedAt = lastSavedAt
    ? new Intl.RelativeTimeFormat(i18n.resolvedLanguage ?? i18n.language, { numeric: 'auto' }).format(
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
            {current?.metadata?.name ?? t('editor.untitled')}
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
              {t('editor.savedRelative', { time: formattedSavedAt })}
            </span>
          )}

          {/* Canvas / Code toggle */}
          <div
            role="tablist"
            onKeyDown={(e) => {
              if (e.key === 'ArrowRight' || e.key === 'ArrowLeft') {
                e.preventDefault()
                setView(view === 'canvas' ? 'yaml' : 'canvas')
              }
            }}
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
              tabIndex={view === 'canvas' ? 0 : -1}
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
              tabIndex={view === 'yaml' ? 0 : -1}
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

          <button
            onClick={view === 'yaml' ? handleSaveYaml : handleSave}
            disabled={isSaving || (view === 'yaml' && !yamlDirty)}
            style={{ ...btnStyle, opacity: isSaving ? 0.6 : 1 }}
            data-testid="editor-save"
          >
            {isSaving ? t('common.saving') : view === 'yaml' ? t('editor.saveYaml') : t('editor.save')}
          </button>

          <Button
            onClick={() => setPublishOpen(true)}
            variant="default"
            leftSection={<IconWorldUpload size={14} />}
            size="xs"
            disabled={!current}
            data-testid="editor-publish"
          >
            {t('editor.publish.button')}
          </Button>

          <Button
            onClick={() => setAiOpen(true)}
            variant="light"
            color="grape"
            leftSection={<IconSparkles size={14} />}
            size="xs"
            disabled={!current}
            data-testid="editor-ai"
          >
            {t('editor.ai.button')}
          </Button>

          <Button
            onClick={handleExecute}
            color={isExecuting ? 'red' : undefined}
            variant={isExecuting ? 'light' : 'filled'}
            leftSection={isExecuting ? <IconPlayerStop size={14} /> : <IconPlayerPlay size={14} />}
            size="xs"
            disabled={!current}
            data-testid="editor-run"
          >
            {isExecuting ? t('editor.stop') : t('editor.run')}
          </Button>
        </div>
      </div>

      <Modal
        opened={aiOpen}
        onClose={() => setAiOpen(false)}
        title={t('editor.ai.title')}
        centered
      >
        <Stack gap="sm">
          <Textarea
            label={t('editor.ai.goalLabel')}
            placeholder={t('editor.ai.goalPlaceholder')}
            minRows={3}
            autosize
            autoFocus
            value={aiGoal}
            onChange={(e) => setAiGoal(e.currentTarget.value)}
            data-testid="editor-ai-goal"
            onKeyDown={(e) => {
              if (e.key === 'Enter' && (e.metaKey || e.ctrlKey)) handleGenerate()
            }}
          />
          <Button
            leftSection={<IconSparkles size={14} />}
            color="grape"
            disabled={!aiGoal.trim()}
            onClick={handleGenerate}
            data-testid="editor-ai-generate"
          >
            {t('editor.ai.generate')}
          </Button>
        </Stack>
      </Modal>

      {current && (
        <PublishModal
          workflowId={current.id}
          opened={publishOpen}
          onClose={() => setPublishOpen(false)}
        />
      )}

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
                // Remount on a genuine (re)load so React Flow re-runs its mount
                // fit-view + node measurement — mutating an already-mounted
                // instance via props leaves freshly-rebuilt nodes stuck at
                // visibility:hidden and off-viewport. The key changes only on
                // id change or an explicit reload (canvasRevision), never on a
                // plain save, so editing state is preserved.
                key={`${current?.id ?? 'none'}:${canvasRevision}`}
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
              {t('editor.loadingYaml')}
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
  const { t } = useTranslation()
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
        {t('editor.emptyTitle')}
      </div>
      <div style={{ fontSize: 13, color: 'var(--color-text-tertiary)', maxWidth: 260, textAlign: 'center' }}>
        {t('editor.emptyHint')}
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

const tabBtnStyle: React.CSSProperties = {
  padding:     '4px 14px',
  border:      'none',
  cursor:      'pointer',
  fontSize:    12,
  fontFamily:  'var(--font-sans)',
  color:       'var(--color-text-primary)',
  background:  'transparent',
}
