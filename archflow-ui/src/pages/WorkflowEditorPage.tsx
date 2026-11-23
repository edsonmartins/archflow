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
import {
    newCopilotGenerateNotificationId,
    requestCopilotGeneration,
} from '../components/copilot/CopilotGenerateBridge'
import { PublishModal } from '../components/PublishModal'
import { RunInputModal } from '../components/RunInputModal'
import { toAgUiRunInput } from '../lib/runInput'
import { stepOutputsFromExecution } from '../lib/stepOutput'
import { runOutcomeOf } from '../lib/runOutcome'
import { executionApi }      from '../services/api'
import { FlowCanvas }        from '../components/FlowCanvas/FlowCanvas'
import { NodePalette }       from '../components/NodePalette'
import { PropertyPanel }     from '../components/PropertyPanel'
import YamlEditor            from '../components/CodeEditor/YamlEditor'
import { useFlowStore }      from '../components/FlowCanvas/store/useFlowStore'
import { useWorkflowStore }  from '../stores/workflow-store'
import { workflowYamlApi }   from '../services/workflow-yaml-api'
import type { FlowNodeData, WorkflowData } from '../components/FlowCanvas/types'
import { NODE_TYPE_TO_CATEGORY } from '../components/FlowCanvas/constants'
import { connectionsForStep, connectionsFromSteps } from '../components/FlowCanvas/workflowSerde'

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

  // Condition and branch survive the round trip — dropping them made a flow opened and saved in the
  // designer follow every branch (see workflowSerde).
  const connections = connectionsFromSteps(detail.steps)

  return { steps, connections }
}

type EditorView = 'canvas' | 'yaml'

/** Chave comparável do snapshot do canvas — o que o Salvar gravaria (posições, config, arestas). */
function canvasSnapshotKey(snapshot: WorkflowData): string {
  return JSON.stringify(snapshot)
}

export function WorkflowEditor() {
  const { id } = useParams<{ id: string }>()
  const { t, i18n } = useTranslation()

  const {
    currentWorkflow: current, fetchWorkflow: loadWorkflow, updateWorkflow, renameCurrent, loading: isSaving,
  } = useWorkflowStore()
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

  // Revisão do canvas: bumpada para reconstruí-lo a partir do documento recarregado (ver o memo
  // workflowData abaixo).
  const [canvasRevision, setCanvasRevision] = useState(0)

  // ── Coerência canvas ↔ backend ────────────────────────────────
  // A aba Código mostra o YAML que o BACKEND gera do documento salvo, e o Salvar YAML reconstrói o
  // canvas a partir dele. Para nenhum dos dois lados descartar edições do outro em silêncio, o editor
  // precisa saber (a) se o canvas tem edições não salvas e (b) se o canvas ficou atrás do backend.
  //
  // (a) canvasBaselineRef: a chave do snapshot do canvas quando ele foi (re)construído ou salvo pela
  //     última vez. Diferente do snapshot atual = há edições não salvas.
  // (b) canvasBehindRef: o YAML foi gravado mas o reload do documento falhou — o canvas ainda mostra o
  //     estado anterior ao YAML e um Salvar do canvas desfaria o YAML recém-gravado.
  const canvasBaselineRef = useRef<string | null>(null)
  const canvasBehindRef = useRef(false)
  // (c) pendingRenameRef: o título foi renomeado no cabeçalho e ainda não foi gravado. O nome não faz
  //     parte do snapshot do canvas, então sem isso a ida para a aba Código não gravaria antes — e o
  //     Salvar YAML reconstruiria tudo a partir do backend, com o nome antigo.
  const pendingRenameRef = useRef(false)

  // Recarrega o documento do backend e reconstrói o canvas a partir dele. fetchWorkflow engole o erro:
  // reconstruir a partir do currentWorkflow anterior reapresentaria justamente o estado velho.
  const resyncCanvas = useCallback(async () => {
    if (!id) return
    await loadWorkflow(id)
    const reloadError = useWorkflowStore.getState().error
    if (reloadError) throw new Error(reloadError)
    canvasBehindRef.current = false
    // A cópia do backend substituiu o currentWorkflow (e com ela um nome não salvo).
    pendingRenameRef.current = false
    setCanvasRevision(v => v + 1)
  }, [id, loadWorkflow])

  const saveWorkflow = useCallback(async () => {
    if (!current) return
    if (canvasBehindRef.current) {
      // O canvas mostra o estado de antes do último Salvar YAML; gravá-lo desfaria o YAML. Tenta
      // reconstruí-lo e aborta este salvamento: o usuário revisa o canvas reconstruído e salva de novo.
      await resyncCanvas()
      throw new Error('Canvas desatualizado em relação ao YAML salvo; recarregado a partir do backend')
    }
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
        connections:   connectionsForStep(s.id, snapshot.connections),
      })),
    }
    await updateWorkflow(current.id, merged)
    canvasBaselineRef.current = canvasSnapshotKey(snapshot)
    pendingRenameRef.current = false
    setLastSavedAt(Date.now())
  }, [current, updateWorkflow, resyncCanvas])
  // The canvas must only be rebuilt on a genuine (re)load — keying this memo
  // on the workflow OBJECT identity made every save reset the canvas (and
  // wipe undo history + selection), because updateWorkflow replaces
  // `currentWorkflow` with the server response. Key on the id plus an
  // explicit revision that the YAML-save path bumps after reloading.
  // Depois de um Salvar do canvas este memo fica propositalmente velho: só é
  // seguro porque o FlowCanvas nunca remonta numa troca de aba (fica montado e
  // oculto). Qualquer novo caminho que remonte o canvas precisa bumpar a revisão.
  // eslint-disable-next-line react-hooks/exhaustive-deps
  const workflowData = useMemo(() => current ? toWorkflowData(current) : null, [current?.id, canvasRevision])
  // O FlowCanvas (filho) aplica o initialWorkflow no efeito de montagem, que roda ANTES deste efeito do
  // pai no mesmo commit — então aqui o store já tem o canvas reconstruído e o snapshot é o "limpo".
  // Edições aplicadas depois (inclusive a fila do copiloto, drenada num setTimeout) contam como sujas.
  useEffect(() => {
    canvasBaselineRef.current = workflowData
      ? canvasSnapshotKey(useFlowStore.getState().getCanvasSnapshot())
      : null
  }, [workflowData])

  const isCanvasDirty = useCallback(() => {
    if (pendingRenameRef.current) return true
    if (canvasBaselineRef.current == null) return false
    return canvasSnapshotKey(useFlowStore.getState().getCanvasSnapshot()) !== canvasBaselineRef.current
  }, [])

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
  // Incrementado quando o documento muda no backend por um caminho que não passa pelo YAML (Executar
  // na aba Código grava o canvas): força recarregar o texto para a aba não mostrar uma versão velha.
  const [yamlReload, setYamlReload] = useState(0)

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
  }, [view, id, yamlReload])

  // Grava o YAML e reconstrói o canvas a partir do documento salvo. O canvas continua montado (oculto)
  // durante a aba Código, então sem o bump de revisão ele seguiria com o estado de antes do YAML — e o
  // próximo Salvar/Executar do canvas desfaria a edição feita no YAML.
  // Se o PUT passa mas o reload falha, o YAML JÁ está gravado (yamlDirty fica falso, com razão) e o
  // canvas fica atrás do backend: canvasBehindRef impede que um Salvar/Executar do canvas o regrave por
  // cima, e a volta para a aba Canvas tenta reconstruí-lo de novo.
  const persistYaml = useCallback(async () => {
    if (!id) return
    const dto = await workflowYamlApi.update(id, yamlText)
    setYamlText(dto.yaml ?? yamlText)
    setYamlDirty(false)
    setLastSavedAt(Date.now())
    canvasBehindRef.current = true
    await resyncCanvas()
  }, [id, yamlText, resyncCanvas])

  const handleSaveYaml = useCallback(async () => {
    if (!id) return
    setYamlError(null)
    try {
      await persistYaml()
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
  }, [id, persistYaml, t])

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

  // Os eventos STEP_* não carregam a saída do passo; ela fica no registro da execução
  // (StepRecordingListener). Ao fim do run, lê o registro e preenche a aba "Saída" de cada nó —
  // por merge, para não apagar status/duração/erro que os eventos já gravaram.
  const fillNodeOutputs = useCallback(async () => {
    const executionId = useFlowStore.getState().executionId
    if (!executionId) return
    try {
      const record = await executionApi.get(executionId)
      const store = useFlowStore.getState()
      // Outro run começou enquanto a leitura voltava: as saídas antigas não entram no estado novo.
      if (store.executionId !== executionId) return
      for (const [nodeId, output] of Object.entries(stepOutputsFromExecution(record))) {
        store.mergeNodeState(nodeId, { output })
      }
    } catch {
      // Sem registro (id provisório, execução apagada, rede): a aba "Saída" só fica vazia.
    }
  }, [])

  const handleRunEvent = useCallback((ev: AgUiEvent) => {
    const stepId = typeof ev.stepId === 'string' ? ev.stepId
      : typeof ev.stepName === 'string' ? ev.stepName : null
    switch (ev.type) {
      case 'RUN_STARTED':
        // Adopt the real execution id minted by the backend (exec-…) without
        // resetting executionState — a STEP_STARTED that raced ahead of
        // RUN_STARTED must not be wiped.
        if (typeof ev.runId === 'string') {
          adoptExecutionId(ev.runId)
          // O toast sai aqui, e não no clique: só o RUN_STARTED traz o id da execução (exec-…). Antes ele
          // saía no clique com o id do WORKFLOW rotulado como "ID da execução", e quem copiava aquele id
          // para procurar a execução não achava nada.
          notifications.show({
            title:   t('editor.notif.execStarted'),
            message: t('editor.notif.execStartedMsg', { id: ev.runId }),
            color:   'blue',
          })
        }
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
      case 'RUN_FINISHED': {
        finishExecution()
        abortRunRef.current = null
        void fillNodeOutputs()
        // RUN_FINISHED só diz que o run acabou, não que deu certo (ver runOutcomeOf): um passo sem chave
        // de API dá FAILED e uma aprovação humana suspende com PAUSED. Antes o editor anunciava "terminou
        // com sucesso" nos dois casos, com o nó vermelho (ou o portão esperando) no canvas.
        const outcome = runOutcomeOf(ev)
        const toast = {
          completed: { title: 'execFinished', message: 'execFinishedMsg', color: 'teal' },
          failed:    { title: 'execFailed',   message: 'execFailedMsg',   color: 'red' },
          cancelled: { title: 'execStopped',  message: 'execStoppedMsg',  color: 'gray' },
          paused:    { title: 'execPaused',   message: 'execPausedMsg',   color: 'yellow' },
          unknown:   { title: 'execUnknown',  message: 'execUnknownMsg',  color: 'gray' },
        }[outcome]
        const status = (ev.result as { status?: unknown } | undefined)?.status
        notifications.show({
          title:   t(`editor.notif.${toast.title}`),
          message: t(`editor.notif.${toast.message}`, { status: typeof status === 'string' ? status : '?' }),
          color:   toast.color,
        })
        break
      }
      case 'RUN_ERROR':
        abortExecution()
        abortRunRef.current = null
        // Os passos que concluíram antes da falha também têm saída a mostrar.
        void fillNodeOutputs()
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
  }, [adoptExecutionId, updateNodeStatus, finishExecution, abortExecution, fillNodeOutputs, t])

  // Executar pede a entrada antes (RunInputModal); o Parar continua imediato. Sem o modal o fluxo
  // rodava sempre sem entrada e um nó que lê ${input} não tinha como ser testado pelo editor.
  const [runInputOpen, setRunInputOpen] = useState(false)

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
    setRunInputOpen(true)
  }, [current, isExecuting, abortExecution, t])

  const startRun = useCallback(async (inputText: string) => {
    if (!current) return
    setRunInputOpen(false)
    // The backend executes the persisted workflow, so unsaved edits
    // must land first. Na aba Código com YAML não salvo, o texto é a
    // edição mais recente: gravar o snapshot do canvas aqui descartaria
    // o YAML em silêncio e executaria a versão anterior.
    const viaYaml = view === 'yaml' && yamlDirty
    if (viaYaml) setYamlError(null)
    try {
      if (viaYaml) {
        await persistYaml()
      } else {
        await saveWorkflow()
        if (view === 'yaml') setYamlReload(v => v + 1)
      }
    } catch (e) {
      // Mesma mensagem que o Salvar YAML mostraria no editor: o erro de validação do backend é o que
      // diz ao usuário o que corrigir.
      if (viaYaml) setYamlError(e instanceof Error ? e.message : t('editor.notif.yamlErrorMsg'))
      notifications.show({
        title:   t('editor.notif.execStartError'),
        message: t('editor.notif.saveErrorMsg'),
        color:   'red',
      })
      return
    }
    startExecution(current.id)
    abortRunRef.current = runAgUiWorkflow(current.id, toAgUiRunInput(inputText), handleRunEvent)
  }, [current, startExecution, handleRunEvent, saveWorkflow, persistYaml, view, yamlDirty, t])

  // ── Troca de aba ──────────────────────────────────────────────
  // O YAML da aba Código vem do backend, então não inclui edições do canvas ainda não salvas — e um
  // Salvar YAML (ou Executar com YAML sujo) gravaria esse documento e reconstruiria o canvas a partir
  // dele, descartando-as em silêncio. Por isso, com o canvas sujo, a ida para Código grava o canvas
  // antes (como o Executar já faz). Se a gravação falha, o usuário fica no Canvas: abrir um YAML que
  // não reflete o que ele vê é exatamente o caminho de perda de dados.
  const [switchingView, setSwitchingView] = useState(false)
  const switchView = useCallback(async (next: EditorView) => {
    if (next === view || switchingView) return
    // Com o canvas atrás do backend (reload pós-YAML falhou) não há o que gravar: o backend já tem a
    // versão mais nova, que é a que a aba Código vai mostrar.
    if (next === 'yaml' && current && !canvasBehindRef.current && isCanvasDirty()) {
      setSwitchingView(true)
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
        return
      } finally {
        setSwitchingView(false)
      }
    }
    if (next === 'canvas' && canvasBehindRef.current) {
      resyncCanvas().catch(() => {
        notifications.show({
          title:   t('editor.notif.saveError'),
          message: t('editor.notif.saveErrorMsg'),
          color:   'red',
        })
      })
    }
    setView(next)
  }, [view, switchingView, current, isCanvasDirty, saveWorkflow, resyncCanvas, t])

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
    setAiOpen(false)
    setAiGoal('')
    // Um id por geração, entregue à ponte do copilot: é ele que ela fecha se a execução falhar.
    // O toast vem antes do pedido para que exista quando um erro imediato tentar fechá-lo.
    const notificationId = newCopilotGenerateNotificationId()
    notifications.show({
      id:      notificationId,
      title:   t('editor.ai.started'),
      message: t('editor.ai.startedMsg'),
      color:   'grape',
    })
    requestCopilotGeneration(t('editor.ai.promptTemplate', { goal }), notificationId)
  }, [aiGoal, t])

  // ── Auto-save (debounced 3s) ───────────────────────────────────
  // useEffect(() => {
  //   const timer = setTimeout(handleSave, 3000)
  //   return () => clearTimeout(timer)
  // }, [nodes, edges])   ← ativar quando quiser auto-save

  // ── Renomear o fluxo pelo título do cabeçalho ──────────────────
  // Clique no título abre um campo; Enter/blur confirma, Esc cancela. O nome vai só para o
  // currentWorkflow em memória e é gravado pelo próximo Salvar (ou pela ida à aba Código), como o resto
  // das edições do editor. Nome vazio é ignorado: o backend rejeitaria um fluxo sem nome.
  //
  // Na aba Código o título é só texto: lá o documento que vale é o YAML da tela (que ainda traz o nome
  // antigo), o Salvar YAML grava esse texto e recarrega o currentWorkflow do backend — um nome trocado no
  // cabeçalho seria descartado logo abaixo do toast "YAML salvo". Quem quer renomear ali edita
  // metadata.name no próprio YAML.
  const titleEditable = !!current && view !== 'yaml'
  const [editingTitle, setEditingTitle] = useState(false)
  const [titleDraft, setTitleDraft] = useState('')
  // Esc fecha o campo; se o navegador disparar blur ao desmontá-lo, o rascunho não pode ser confirmado.
  const titleCancelledRef = useRef(false)
  const startTitleEdit = useCallback(() => {
    if (!current || !titleEditable) return
    titleCancelledRef.current = false
    setTitleDraft(current.metadata?.name ?? '')
    setEditingTitle(true)
  }, [current, titleEditable])
  const commitTitleEdit = useCallback(() => {
    setEditingTitle(false)
    if (titleCancelledRef.current) return
    const name = titleDraft.trim()
    if (!current || !name || name === current.metadata?.name) return
    renameCurrent(name)
    pendingRenameRef.current = true
  }, [current, titleDraft, renameCurrent])

  const formattedSavedAt = lastSavedAt
    ? new Intl.RelativeTimeFormat(i18n.resolvedLanguage ?? i18n.language, { numeric: 'auto' }).format(
        Math.round((lastSavedAt - Date.now()) / 1000),
        'second'
      )
    : null

  return (
    <div
      className="af-editor-frame"
      style={{ display: 'flex', flexDirection: 'column', height: 'calc(100vh - 52px - 32px)', overflow: 'hidden' }}
    >
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
          {!titleEditable ? (
            <span
              data-testid="editor-title"
              style={{ fontSize: 14, fontWeight: 500, color: 'var(--color-text-primary)', padding: '1px 0' }}
            >
              {current?.metadata?.name || t('editor.untitled')}
            </span>
          ) : editingTitle ? (
            <input
              data-testid="editor-title-input"
              aria-label={t('editor.renameHint')}
              autoFocus
              value={titleDraft}
              onChange={(e) => setTitleDraft(e.target.value)}
              onBlur={commitTitleEdit}
              onKeyDown={(e) => {
                if (e.key === 'Enter') {
                  e.preventDefault()
                  commitTitleEdit()
                } else if (e.key === 'Escape') {
                  e.preventDefault()
                  titleCancelledRef.current = true
                  setEditingTitle(false)
                }
              }}
              style={{
                fontSize:     14,
                fontWeight:   500,
                color:        'var(--color-text-primary)',
                background:   'var(--color-background-primary)',
                border:       '0.5px solid var(--color-border-secondary, var(--color-border-tertiary))',
                borderRadius: 5,
                padding:      '1px 6px',
                minWidth:     200,
                fontFamily:   'inherit',
              }}
            />
          ) : (
            <button
              type="button"
              data-testid="editor-title"
              title={t('editor.renameHint')}
              onClick={startTitleEdit}
              style={{
                fontSize:   14,
                fontWeight: 500,
                color:      'var(--color-text-primary)',
                background: 'transparent',
                border:     'none',
                padding:    '1px 0',
                cursor:     'text',
                fontFamily: 'inherit',
              }}
            >
              {current?.metadata?.name || t('editor.untitled')}
            </button>
          )}
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
                void switchView(view === 'canvas' ? 'yaml' : 'canvas')
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
              onClick={() => void switchView('canvas')}
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
              onClick={() => void switchView('yaml')}
              disabled={switchingView}
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

      {current && (
        <RunInputModal
          workflowId={current.id}
          opened={runInputOpen}
          onClose={() => setRunInputOpen(false)}
          onConfirm={(text) => { void startRun(text) }}
        />
      )}

      {/* ── Editor body ──────────────────────────────────────── */}
      {/* O canvas fica SEMPRE montado; a aba Código só o oculta. Desmontá-lo fazia o remount
          restaurar o workflowData memoizado no último load (não no último save) e zerar o undo:
          o nó recém-adicionado sumia e um Salvar seguinte gravava o snapshot velho. visibility
          (e não display:none) preserva as dimensões, para o React Flow medir os nós quando o
          canvas é reconstruído oculto após um Salvar YAML. */}
      {/* isolation: os z-index de dentro do corpo do editor (o painel YAML, os toggles do canvas)
          competem só entre si. Sem isso eles entravam no contexto raiz e cobriam os Modals (200) e as
          Notifications (400) do Mantine, que são centralizados/empilhados por cima desta área. */}
      <div style={{ display: 'flex', flex: 1, overflow: 'hidden', position: 'relative', isolation: 'isolate' }}>
        <div
          data-testid="editor-canvas-pane"
          aria-hidden={view !== 'canvas'}
          // inert tira o canvas oculto do foco por Tab e dos cliques — os nós do React Flow têm
          // tabIndex e pointer-events próprios, que visibility sozinho não neutraliza.
          inert={view !== 'canvas'}
          style={{
            display:    'flex',
            flex:       1,
            overflow:   'hidden',
            position:   'relative',
            // Contexto de empilhamento próprio: o React Flow (z 4-5) e os PanelToggle (1000) ficam
            // contidos aqui, e o painel YAML só precisa de um z-index local para cobrir tudo.
            isolation:  'isolate',
            visibility: view === 'canvas' ? 'visible' : 'hidden',
          }}
        >
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
                keyboardActive={view === 'canvas'}
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
        {view === 'yaml' && (
          <div
            data-testid="editor-yaml-pane"
            style={{
              position:   'absolute',
              inset:      0,
              overflow:   'hidden',
              padding:    12,
              background: 'var(--color-background-primary)',
              // Cobre o painel do canvas, que é isolado (ver acima): os nós do React Flow fixam
              // visibility:visible e atravessariam o visibility:hidden do pai. Basta um valor local —
              // o corpo do editor também é isolado, então isto não compete com Modals/Notifications.
              zIndex:     1,
            }}
          >
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
