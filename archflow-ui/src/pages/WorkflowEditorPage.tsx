import { useEffect, useCallback, useMemo, useRef, useState } from 'react'
import { useBlocker, useNavigate, useParams, useSearchParams, type BlockerFunction } from 'react-router-dom'
import { useTranslation }    from 'react-i18next'
import { notifications }     from '@mantine/notifications'
import { Badge, Button, Group, Modal, Paper, Stack, Text, Textarea } from '@mantine/core'
import {
    IconAlertCircle,
    IconChevronLeft,
    IconChevronRight,
    IconFileOff,
    IconPlayerPlay,
    IconPlayerStop,
    IconSparkles,
    IconWorldUpload,
} from '@tabler/icons-react'
import { runAgUiWorkflow, type AgUiEvent, type StopAgUiRun } from '../services/agui-client'
import {
    newCopilotGenerateNotificationId,
    requestCopilotGeneration,
} from '../components/copilot/CopilotGenerateBridge'
import { PublishModal } from '../components/PublishModal'
import { RunInputModal } from '../components/RunInputModal'
import { toAgUiRunInput } from '../lib/runInput'
import { stepOutputsFromExecution } from '../lib/stepOutput'
import { nodeStatusOfStepFinished, runOutcomeOf } from '../lib/runOutcome'
import { mensagemDeYamlInvalido } from '../lib/errorMessage'
import { executionApi, workflowApi } from '../services/api'
import { FlowCanvas }        from '../components/FlowCanvas/FlowCanvas'
import { NodePalette }       from '../components/NodePalette'
import { PropertyPanel }     from '../components/PropertyPanel'
import YamlEditor            from '../components/CodeEditor/YamlEditor'
import { useFlowStore }      from '../components/FlowCanvas/store/useFlowStore'
import { useWorkflowStore }  from '../stores/workflow-store'
import { workflowYamlApi }   from '../services/workflow-yaml-api'
import type { FlowNodeData, WorkflowData } from '../components/FlowCanvas/types'
import { canvasToDocumentSteps, workflowDataFromDocument } from '../components/FlowCanvas/workflowSerde'
import { hasRunBlockers, runBlockers, runBlockersMessage } from '../components/FlowCanvas/runReadiness'
import { declaredInputFields } from '../components/FlowCanvas/controlExpressions'
import { usePermissions }    from '../lib/permissions'
import { unknownNodeTypeSteps } from '../lib/unknownNodeTypes'
import type { WorkflowDetail } from '../services/api'
import EmptyState            from '../components/EmptyState'

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

type EditorView = 'canvas' | 'yaml'
type BlockerArgs = Parameters<BlockerFunction>[0]

/** Chave comparável do snapshot do canvas — o que o Salvar gravaria (posições, config, arestas). */
function canvasSnapshotKey(snapshot: WorkflowData): string {
  return JSON.stringify(snapshot)
}

/**
 * Chave comparável do que o Salvar grava fora do canvas: o nome (metadata) e os padrões do fluxo
 * (configuration.llmConfig, editados no painel). Sem ela, trocar o modelo padrão do fluxo não contava como
 * alteração — nem para o indicador de não salvo, nem para a ida à aba Código gravar antes.
 */
function documentKey(workflow: WorkflowDetail | null | undefined): string | null {
  return workflow ? JSON.stringify({ metadata: workflow.metadata, configuration: workflow.configuration ?? {} }) : null
}

/**
 * Rascunho do "Novo Fluxo" (RCHFLW-131): só em memória, sem id. Antes o modal de criação gravava um fluxo
 * vazio antes de abrir o editor, e quem fechava sem fazer nada deixava um "Fluxo sem título" com 0 passos — o
 * filtro de Execuções acumulava vários, indistinguíveis. O fluxo nasce no primeiro Salvar (ou quando o Gerar
 * com IA monta o primeiro nó).
 */
function newDraft(name: string): WorkflowDetail {
  return {
    id: '',
    metadata: { name, description: '', version: '1.0.0', category: '', tags: [] },
    steps: [],
    configuration: {},
  }
}

/** O PUT foi recusado porque o fluxo mudou no servidor desde que o editor o leu (RCHFLW-125). */
function isSaveConflict(e: unknown): boolean {
  return (e as { status?: unknown } | null)?.status === 409
}

export function WorkflowEditor() {
  const { id } = useParams<{ id: string }>()
  const { t, i18n } = useTranslation()
  const [searchParams, setSearchParams] = useSearchParams()

  const {
    currentWorkflow: storedWorkflow, fetchWorkflow: loadWorkflow, updateWorkflow, createWorkflow, renameCurrent,
    loading: isSaving,
  } = useWorkflowStore()
  // ── Rascunho do Novo Fluxo (RCHFLW-131) ───────────────────────
  // /editor?new=1 abre um fluxo só em memória (newDraft). draftSeq identifica o rascunho do canvas: cada
  // "Novo Fluxo" é um canvas novo, mesmo com o mesmo id vazio.
  const draftRoute = !id && searchParams.get('new') === '1'
  const [draftSeq, setDraftSeq] = useState(0)
  // Id do fluxo que nasceu de um rascunho nesta montagem do editor. O canvas continua o MESMO depois do
  // primeiro Salvar (sem remontar: o undo, a seleção e o Gerar com IA em andamento seguem), e a rota troca
  // para /editor/:id sem um fetch, porque o store já tem a resposta do POST.
  const promotedRef = useRef<string | null>(null)
  const skipFetchRef = useRef<string | null>(null)
  // Navegação que o próprio editor faz e o bloqueio de não salvo não pode segurar (rascunho → /editor/:id).
  const allowedPathRef = useRef<string | null>(null)
  // Rota cujo fetch já respondeu. O store guarda o currentWorkflow depois que o editor desmonta: ao abrir
  // /editor/B com A na memória, A apareceria (editável) até B chegar — a fila do copiloto (createFlow →
  // addNode) era drenada nesse canvas de A e sumia quando B carregava, e um Salvar nessa janela gravava
  // em A. Mesmo com o id certo a cópia guardada pode ser velha (é recarregada e o canvas remonta). Então,
  // com id na rota, o editor só trata como aberto o fluxo da rota, e depois do fetch. Sem id (/editor, o
  // item de menu) continua mostrando o último fluxo aberto.
  const [loadedId, setLoadedId] = useState<string | null>(null)
  // RCHFLW-117: o fetch da rota falhou. Sem isto o editor abria um canvas em branco, "Fluxo sem título",
  // com o id na URL — para quem seguiu um link antigo ou de outro tenant parecia um fluxo novo. 404 e 403
  // viram "não encontrado" (o backend responde 404 a fluxo de outro tenant justamente para não dizer que
  // ele existe); qualquer outra falha mostra o erro de carregamento, com Atualizar. Guarda o id: a falha
  // de uma rota anterior não pode tapar o fluxo da rota atual.
  const [loadFailure, setLoadFailure] = useState<{ id: string; notFound: boolean; message: string } | null>(null)
  // Incrementado pelo Atualizar do erro de carregamento: refaz o fetch da mesma rota.
  const [loadAttempt, setLoadAttempt] = useState(0)
  // RCHFLW-74: sem workflow:update o editor é só leitura (canvas, YAML, título, Salvar, Publicar, IA);
  // sem workflow:execute não há Executar. O backend responde 403 a quem tentar mesmo assim.
  const can = usePermissions()
  const canUpdate = can('workflow:update')
  const canExecute = can('workflow:execute')
  const current = draftRoute
    // Na rota do rascunho só vale o rascunho (ou o fluxo que acabou de nascer dele): o último fluxo aberto, que
    // o store ainda guarda, apareceria editável até o rascunho entrar — e um Salvar ali gravaria nele.
    ? (storedWorkflow && (!storedWorkflow.id || storedWorkflow.id === promotedRef.current) ? storedWorkflow : null)
    : !id || (storedWorkflow?.id === id && loadedId === id) ? storedWorkflow : null
  const isDraft = !!current && !current.id
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
  // A mesma linha de base para o nome e os padrões do fluxo (documentKey).
  const documentBaselineRef = useRef<string | null>(null)
  // Bumpado quando as linhas de base mudam fora de um render (efeito de reconstrução do canvas): o
  // indicador de não salvo é calculado no render e precisa de um render depois delas.
  const [, setBaselineTick] = useState(0)
  const canvasBehindRef = useRef(false)
  // (c) pendingRenameRef: o título foi renomeado no cabeçalho e ainda não foi gravado. O nome não faz
  //     parte do snapshot do canvas, então sem isso a ida para a aba Código não gravaria antes — e o
  //     Salvar YAML reconstruiria tudo a partir do backend, com o nome antigo.
  const pendingRenameRef = useRef(false)

  // Recarrega o documento do backend e reconstrói o canvas a partir dele. fetchWorkflow engole o erro:
  // reconstruir a partir do currentWorkflow anterior reapresentaria justamente o estado velho.
  const resyncCanvas = useCallback(async () => {
    if (!id) return
    // O desfecho é o deste fetch: o `error` do store pode vir de outra chamada (uma listagem que falhou).
    const reload = await loadWorkflow(id)
    if (!reload.ok) throw new Error(reload.message)
    canvasBehindRef.current = false
    // A cópia do backend substituiu o currentWorkflow (e com ela um nome não salvo).
    pendingRenameRef.current = false
    setCanvasRevision(v => v + 1)
  }, [id, loadWorkflow])

  const navigate = useNavigate()

  // Sessão de gravação: muda quando o usuário sai do editor (desmontar, Descartar e sair). Um Salvar que ainda
  // estava no ar — o POST do rascunho disparado pelo Gerar com IA — não pode, ao voltar, adotar o fluxo nem
  // levar o usuário de volta para /editor/:id, longe da página que ele escolheu; nem um Salvar que esperava a
  // vez na fila pode gravar o que ele acabou de descartar.
  const saveSessionRef = useRef(0)
  // Fila dos Salvar: um por vez. Dois em paralelo (Salvar e logo a aba Código, ou o Executar) mandavam o mesmo
  // updatedAt antigo, e o segundo levava 409 contra o primeiro Salvar do próprio usuário — o aviso de "alterado
  // em outro lugar" sem conflito nenhum (RCHFLW-125). Na fila, o segundo lê a versão que o primeiro gravou.
  const saveQueueRef = useRef<Promise<unknown>>(Promise.resolve())
  // O aviso de saída está aberto (espelho do blocker, declarado mais abaixo). A troca de rota do rascunho
  // promovido espera ele fechar: uma navegação nesse meio desfaz o bloqueio e fecha o aviso sem a escolha.
  const blockedRef = useRef(false)
  const pendingRouteRef = useRef<string | null>(null)
  const goToPromoted = useCallback((promotedId: string) => {
    skipFetchRef.current = promotedId
    allowedPathRef.current = `/editor/${promotedId}`
    navigate(`/editor/${promotedId}`, { replace: true })
  }, [navigate])
  // O documento como foi carregado ou salvo pela última vez: o que o Descartar e sair devolve ao store.
  const savedDocRef = useRef<WorkflowDetail | null>(null)

  /**
   * Grava o canvas e devolve o id do fluxo gravado. No rascunho (RCHFLW-131) o primeiro Salvar CRIA o fluxo
   * (POST) e a rota passa a ser /editor/:id — salvo com `leaving`, quando o editor está saindo (Salvar e sair
   * do aviso de não salvo): aí a navegação pendente é a do usuário, não a troca de rota.
   *
   * <p>O PUT leva o updatedAt que o editor leu (RCHFLW-125): se outra sessão gravou no meio, o servidor
   * responde 409 e quem chamou abre o aviso de conflito. `force` grava por cima, sem a versão — é o
   * "Sobrescrever" desse aviso.</p>
   */
  const persistCanvas = useCallback(async (
    opts: { force?: boolean; leaving?: boolean }, session: number,
  ): Promise<string | undefined> => {
    if (!current || session !== saveSessionRef.current) return undefined
    if (canvasBehindRef.current) {
      // O canvas mostra o estado de antes do último Salvar YAML; gravá-lo desfaria o YAML. Tenta
      // reconstruí-lo e aborta este salvamento: o usuário revisa o canvas reconstruído e salva de novo.
      await resyncCanvas()
      throw new Error('Canvas desatualizado em relação ao YAML salvo; recarregado a partir do backend')
    }
    const snapshot = useFlowStore.getState().getCanvasSnapshot()
    const merged = {
      // O documento do store, não o `current` deste render: um nome trocado logo antes do Salvar já está lá.
      ...(useWorkflowStore.getState().currentWorkflow ?? current),
      // O mesmo mapeamento do clone de template (RCHFLW-45), para os dois não divergirem. Display name
      // vai em `label`; `operation` é a operação de execução (DefaultFlowStepFactory) e vem da config.
      steps: canvasToDocumentSteps(snapshot.steps, snapshot.connections)
        .map(s => ({ ...s, type: s.type?.toUpperCase() })),
    }
    // O rascunho que esperava na fila pode ter sido promovido pelo Salvar da frente: aí este já é um PUT.
    const targetId = current.id || promotedRef.current || ''
    let savedId: string
    if (!targetId) {
      // O id vazio do rascunho não vai no POST: quem dá o id é o servidor.
      const body: Partial<WorkflowDetail> = { ...merged }
      delete body.id
      const created = await createWorkflow(body)
      // Saiu do editor com o POST no ar: o fluxo existe (o pedido saiu antes da escolha), mas este editor não o
      // adota nem leva o usuário de volta a ele.
      if (session !== saveSessionRef.current) return created.id
      savedId = created.id
      promotedRef.current = created.id
      useWorkflowStore.setState({ currentWorkflow: created })
      setLoadedId(created.id)
      if (!opts.leaving) {
        if (blockedRef.current) pendingRouteRef.current = created.id
        else goToPromoted(created.id)
      }
    } else {
      await updateWorkflow(targetId, merged, opts.force ? undefined : merged.updatedAt)
      savedId = targetId
    }
    canvasBaselineRef.current = canvasSnapshotKey(snapshot)
    savedDocRef.current = useWorkflowStore.getState().currentWorkflow
    documentBaselineRef.current = documentKey(savedDocRef.current)
    pendingRenameRef.current = false
    setLastSavedAt(Date.now())
    return savedId
  }, [current, updateWorkflow, createWorkflow, resyncCanvas, goToPromoted])

  const saveWorkflow = useCallback((
    opts: { force?: boolean; leaving?: boolean } = {},
  ): Promise<string | undefined> => {
    const session = saveSessionRef.current
    const run = saveQueueRef.current.catch(() => undefined).then(() => persistCanvas(opts, session))
    saveQueueRef.current = run
    return run
  }, [persistCanvas])
  // The canvas must only be rebuilt on a genuine (re)load — keying this memo
  // on the workflow OBJECT identity made every save reset the canvas (and
  // wipe undo history + selection), because updateWorkflow replaces
  // `currentWorkflow` with the server response. Key on the id plus an
  // explicit revision that the YAML-save path bumps after reloading.
  // Depois de um Salvar do canvas este memo fica propositalmente velho: só é
  // seguro porque o FlowCanvas nunca remonta numa troca de aba (fica montado e
  // oculto). Qualquer novo caminho que remonte o canvas precisa bumpar a revisão.
  // A identidade do canvas é o id do fluxo — e, para o rascunho e o fluxo que nasceu dele nesta montagem, o
  // número do rascunho: o primeiro Salvar dá um id ao documento sem reconstruir o canvas (RCHFLW-131).
  const canvasIdentity = !current ? null
    : !current.id || current.id === promotedRef.current ? `draft:${draftSeq}` : current.id
  // eslint-disable-next-line react-hooks/exhaustive-deps
  const workflowData = useMemo(() => current ? workflowDataFromDocument(current) : null, [canvasIdentity, canvasRevision])
  // O FlowCanvas (filho) aplica o initialWorkflow no efeito de montagem, que roda ANTES deste efeito do
  // pai no mesmo commit — então aqui o store já tem o canvas reconstruído e o snapshot é o "limpo".
  // Edições aplicadas depois (inclusive a fila do copiloto, drenada num setTimeout) contam como sujas.
  useEffect(() => {
    canvasBaselineRef.current = workflowData
      ? canvasSnapshotKey(useFlowStore.getState().getCanvasSnapshot())
      : null
    savedDocRef.current = workflowData ? useWorkflowStore.getState().currentWorkflow : null
    documentBaselineRef.current = documentKey(savedDocRef.current)
    setBaselineTick(v => v + 1)
  }, [workflowData])

  const isCanvasDirty = useCallback(() => {
    if (pendingRenameRef.current) return true
    if (canvasBaselineRef.current == null) return false
    if (documentKey(useWorkflowStore.getState().currentWorkflow) !== documentBaselineRef.current) return true
    return canvasSnapshotKey(useFlowStore.getState().getCanvasSnapshot()) !== canvasBaselineRef.current
  }, [])

  const {
    selectedNodeId, selectedNodeData, selectNode, canvasApi,
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
    if (skipFetchRef.current === id && useWorkflowStore.getState().currentWorkflow?.id === id) {
      // Rota trocada pelo primeiro Salvar do rascunho: o store já tem o fluxo que o POST devolveu, e o canvas
      // é o mesmo. Um fetch aqui reconstruiria o canvas (e descartaria o que o Gerar com IA montou depois).
      skipFetchRef.current = null
      setLoadFailure(null)
      setLoadedId(id)
      return
    }
    let cancelled = false
    const hadSameId = useWorkflowStore.getState().currentWorkflow?.id === id
    loadWorkflow(id).then((result) => {
      if (cancelled) return
      // Julga pelo desfecho deste fetch, não pelo `error` do store: esse é compartilhado, e uma listagem de
      // fluxos que falhe em paralelo (busca global, copiloto) trocaria um editor válido pelo erro dela.
      if (!result.ok) {
        // Não marca a rota como carregada: o store ainda guarda o fluxo anterior (até uma cópia velha
        // deste mesmo id) e ele não pode aparecer como se fosse o da rota.
        setLoadFailure({ id, notFound: result.status === 404 || result.status === 403, message: result.message })
        return
      }
      setLoadFailure(null)
      if (hadSameId) setCanvasRevision(v => v + 1)
      setLoadedId(id)
    })
    return () => { cancelled = true }
  }, [id, loadWorkflow, loadAttempt])

  // Entrar em /editor?new=1 abre um rascunho novo (RCHFLW-131): cada "Novo Fluxo" começa do zero.
  useEffect(() => {
    if (!draftRoute) return
    promotedRef.current = null
    setDraftSeq(v => v + 1)
    useWorkflowStore.setState({ currentWorkflow: newDraft(t('workflows.untitledName')) })
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [draftRoute])

  // Saiu do editor com o rascunho ainda sem id: ele não vira registro e não fica como "último fluxo aberto".
  useEffect(() => () => {
    saveSessionRef.current += 1
    if (useWorkflowStore.getState().currentWorkflow?.id === '') useWorkflowStore.getState().clearCurrent()
  }, [])

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

  // Frase traduzida com linha/coluna do backend (RCHFLW-94); o toast repete o problema, não um genérico.
  const reportYamlSaveError = useCallback((e: unknown) => {
    const mensagem = mensagemDeYamlInvalido(e, t, t('editor.notif.yamlErrorMsg'))
    setYamlError(mensagem)
    notifications.show({
      title: t('editor.notif.yamlError'),
      message: mensagem,
      color: 'red',
    })
  }, [t])

  // O YAML gravado tem nó que não executa — tipo da paleta sem execução ou tipo que nem o designer nem o
  // catálogo do servidor conhecem (`type: foo-bar`) — e o Salvar YAML aceitava calado: o fluxo rodava os passos
  // anteriores, gastando o LLM, e só falhava no fim (RCHFLW-134). Mesmo aviso que o Executar dá aos nós sem
  // execução. O YAML continua salvo, como antes: o aviso só diz, antes de alguém executar, o que vai falhar.
  const warnNotExecutableAfterYaml = useCallback(async () => {
    const saved = useWorkflowStore.getState().currentWorkflow
    if (!saved) return
    const steps = workflowDataFromDocument(saved).steps.map(s => ({
      id: s.id, label: s.label, componentId: s.componentId, type: s.type, config: s.config,
    }))
    const notExecutable = runBlockers(steps).notExecutable
    const unknown = (await unknownNodeTypeSteps(steps)) ?? []
    const blockers = { notExecutable: [...notExecutable, ...unknown], missing: [] }
    if (!hasRunBlockers(blockers)) return
    notifications.show({
      title:   t('editor.notif.yamlNotExecutable'),
      message: runBlockersMessage(t, blockers),
      color:   'orange',
      autoClose: false,
    })
  }, [t])

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
      reportYamlSaveError(e)
      return
    }
    void warnNotExecutableAfterYaml()
  }, [id, persistYaml, reportYamlSaveError, warnNotExecutableAfterYaml, t])

  // ── Conflito no Salvar (RCHFLW-125) ───────────────────────────
  // O fluxo mudou no servidor desde que o editor o leu: o aviso pergunta se recarrega (descarta o que está na
  // tela) ou sobrescreve (grava por cima da outra sessão). Antes o Salvar sobrescrevia calado.
  const [conflictOpen, setConflictOpen] = useState(false)
  const [conflictBusy, setConflictBusy] = useState(false)
  const reportSaveFailure = useCallback((e: unknown) => {
    if (isSaveConflict(e)) {
      setConflictOpen(true)
      return
    }
    notifications.show({
      title:   t('editor.notif.saveError'),
      message: t('editor.notif.saveErrorMsg'),
      color:   'red',
    })
  }, [t])

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
    } catch (e) {
      reportSaveFailure(e)
    }
  }, [current, saveWorkflow, reportSaveFailure, t])

  const reloadAfterConflict = useCallback(async () => {
    setConflictBusy(true)
    try {
      await resyncCanvas()
      setYamlReload(v => v + 1)
      setConflictOpen(false)
    } catch {
      // Falhou o recarregar, não um salvar: o aviso diz isso e que a tela segue como estava (o modal fica aberto).
      notifications.show({ title: t('editor.loadFailed'), message: t('editor.conflict.reloadFailedMsg'), color: 'red' })
    } finally {
      setConflictBusy(false)
    }
  }, [resyncCanvas, t])

  const overwriteAfterConflict = useCallback(async () => {
    setConflictBusy(true)
    try {
      await saveWorkflow({ force: true })
      setConflictOpen(false)
      notifications.show({ title: t('editor.notif.saved'), message: t('editor.notif.savedMsg'), color: 'teal' })
    } catch (e) {
      setConflictOpen(false)
      reportSaveFailure(e)
    } finally {
      setConflictBusy(false)
    }
  }, [saveWorkflow, reportSaveFailure, t])

  // Live run over AG-UI SSE: STEP_* events drive the per-node status
  // pills / animated edges via useFlowStore.executionState (keyed by
  // step id, which equals the canvas node id — getCanvasSnapshot
  // persists steps with the node's id).
  const abortRunRef = useRef<StopAgUiRun | null>(null)
  useEffect(() => () => { void abortRunRef.current?.() }, [])

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
        // O portão de aprovação à espera chega como STEP_SUSPENDED: 'awaiting', não ✓ (RCHFLW-32).
        // Sem duração — ele não terminou, e o tempo até suspender não diz nada a quem olha.
        const status = nodeStatusOfStepFinished(ev.status)
        updateNodeStatus(stepId, {
          status,
          durationMs: status !== 'awaiting' && typeof ev.durationMs === 'number' ? ev.durationMs : undefined,
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
  // Os campos que as Entradas declaram (ioFields), lidos do canvas ao abrir o modal: ele avisa quando a entrada
  // digitada não os traz (RCHFLW-123).
  const [runExpectedFields, setRunExpectedFields] = useState<string[]>([])

  const handleExecute = useCallback(async () => {
    if (!current) return
    if (isExecuting) {
      // Second click while running = stop: fecha o stream E cancela a execução no servidor pelo id
      // do RUN_STARTED. Só fechar não bastava: durante um passo longo o servidor não escreve nada,
      // não nota a desconexão, e o passo ia até o fim com a execução COMPLETED (RCHFLW-54).
      const stop = abortRunRef.current
      abortRunRef.current = null
      abortExecution()
      void Promise.resolve(stop?.() ?? 'stopped').then((desfecho) => {
        // O "interrompida" só sai quando o servidor aceitou; se recusou, a execução pode seguir
        // rodando, e dizer "cancelada" seria repetir o defeito. Recusa por permissão (403) não é
        // isso: a desconexão cancela, só um pouco depois (ver StopOutcome no agui-client).
        notifications.show(desfecho === 'failed'
          ? { title: t('editor.notif.execStopError'), message: t('editor.notif.execStopErrorMsg'), color: 'red' }
          : desfecho === 'byDisconnect'
            ? { title: t('editor.notif.execStopping'), message: t('editor.notif.execStoppingMsg'), color: 'yellow' }
            : { title: t('editor.notif.execStopped'), message: t('editor.notif.execStoppedMsg'), color: 'yellow' })
      })
      return
    }
    // Nó sem execução ou campo obrigatório vazio: o run nem começa, e o aviso diz qual nó (e qual campo)
    // corrigir (RCHFLW-11, RCHFLW-92). Com YAML não salvo na aba Código, o canvas não é o que vai rodar —
    // aí quem recusa é o servidor, no início da execução.
    if (!(view === 'yaml' && yamlDirty)) {
      const blockers = runBlockers(useFlowStore.getState().getCanvasSnapshot().steps.map(s => ({
        id: s.id, label: s.label, componentId: s.componentId, type: s.type, config: s.config,
      })))
      if (hasRunBlockers(blockers)) {
        notifications.show({
          title:   t('editor.notif.runBlocked'),
          message: runBlockersMessage(t, blockers),
          color:   'red',
          autoClose: false,
        })
        return
      }
    }
    setRunExpectedFields(useFlowStore.getState().getCanvasSnapshot().steps.flatMap(s => declaredInputFields({
      id: s.id, label: s.label, componentId: s.componentId, type: s.type, config: s.config,
    })))
    setRunInputOpen(true)
  }, [current, isExecuting, abortExecution, view, yamlDirty, t])

  const startRun = useCallback(async (inputText: string) => {
    if (!current) return
    setRunInputOpen(false)
    // The backend executes the persisted workflow, so unsaved edits
    // must land first. Na aba Código com YAML não salvo, o texto é a
    // edição mais recente: gravar o snapshot do canvas aqui descartaria
    // o YAML em silêncio e executaria a versão anterior.
    const viaYaml = view === 'yaml' && yamlDirty
    if (viaYaml) setYamlError(null)
    // Quem só executa (EXECUTOR) não grava: o canvas e o YAML dele são só leitura, então não há edição
    // a perder, e o PUT responderia 403 e impediria a execução da versão gravada.
    if (!canUpdate) {
      startExecution(current.id)
      abortRunRef.current = runAgUiWorkflow(current.id, toAgUiRunInput(inputText), handleRunEvent)
      return
    }
    // O rascunho ganha o id no Salvar que precede o run (RCHFLW-131).
    let workflowId = current.id
    try {
      if (viaYaml) {
        await persistYaml()
      } else {
        workflowId = (await saveWorkflow()) ?? workflowId
        if (view === 'yaml') setYamlReload(v => v + 1)
      }
    } catch (e) {
      // O fluxo mudou em outro lugar: quem decide é o usuário, pelo aviso de conflito — não roda nenhuma versão.
      if (!viaYaml && isSaveConflict(e)) {
        setConflictOpen(true)
        return
      }
      // Mesma mensagem que o Salvar YAML mostraria no editor: o erro de validação do backend é o que
      // diz ao usuário o que corrigir.
      if (viaYaml) setYamlError(mensagemDeYamlInvalido(e, t, t('editor.notif.yamlErrorMsg')))
      notifications.show({
        title:   t('editor.notif.execStartError'),
        message: t('editor.notif.saveErrorMsg'),
        color:   'red',
      })
      return
    }
    startExecution(workflowId)
    abortRunRef.current = runAgUiWorkflow(workflowId, toAgUiRunInput(inputText), handleRunEvent)
  }, [current, startExecution, handleRunEvent, saveWorkflow, persistYaml, view, yamlDirty, canUpdate, t])

  // ── Troca de aba ──────────────────────────────────────────────
  // O YAML da aba Código vem do backend, então não inclui edições do canvas ainda não salvas — e um
  // Salvar YAML (ou Executar com YAML sujo) gravaria esse documento e reconstruiria o canvas a partir
  // dele, descartando-as em silêncio. Por isso, com o canvas sujo, a ida para Código grava o canvas
  // antes (como o Executar já faz). Se a gravação falha, o usuário fica no Canvas: abrir um YAML que
  // não reflete o que ele vê é exatamente o caminho de perda de dados.
  const [switchingView, setSwitchingView] = useState(false)
  // YAML editado e não salvo (válido ou não): voltar ao Canvas o descarta — a aba Código recarrega o YAML do
  // backend na próxima abertura. Antes isso acontecia sem perguntar (RCHFLW-134); agora o modal confirma.
  const [discardYamlOpen, setDiscardYamlOpen] = useState(false)
  const switchView = useCallback(async (next: EditorView, opts: { discardYaml?: boolean } = {}) => {
    if (next === view || switchingView) return
    if (next === 'canvas' && yamlDirty && !opts.discardYaml) {
      setDiscardYamlOpen(true)
      return
    }
    if (next === 'canvas' && opts.discardYaml) {
      setYamlDirty(false)
      setYamlError(null)
    }
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
      } catch (e) {
        reportSaveFailure(e)
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
  }, [view, switchingView, yamlDirty, current, isCanvasDirty, saveWorkflow, resyncCanvas, reportSaveFailure, t])

  // ── "Gerar com IA" — hands the goal to the app-wide copilot, whose
  // frontend tools (addNode/connectNodes) assemble the flow on this canvas.
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

  // Gerar com IA num rascunho (RCHFLW-131): o fluxo nasce quando o copiloto monta o primeiro nó — se a geração
  // falhar antes disso, nada é gravado. O que ele monta depois fica como alteração não salva, como no fluxo
  // já existente.
  const aiDraftRef = useRef(false)
  const handleGenerate = useCallback(() => {
    const goal = aiGoal.trim()
    if (!goal) return
    setAiOpen(false)
    setAiGoal('')
    aiDraftRef.current = isDraft
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
  }, [aiGoal, isDraft, t])

  useEffect(() => {
    if (!isDraft) return
    return useFlowStore.subscribe((state) => {
      if (!aiDraftRef.current || state.nodes.length === 0) return
      aiDraftRef.current = false
      // Depois do lote do canvas (o addNode aplica a config logo em seguida), não no meio dele.
      setTimeout(() => {
        saveWorkflow().catch(reportSaveFailure)
      }, 0)
    })
  }, [isDraft, saveWorkflow, reportSaveFailure])

  // ── Auto-save (debounced 3s) ───────────────────────────────────
  // useEffect(() => {
  //   const timer = setTimeout(handleSave, 3000)
  //   return () => clearTimeout(timer)
  // }, [nodes, edges])   ← ativar quando quiser auto-save

  // ── Renomear o fluxo pelo título do cabeçalho ──────────────────
  // Clique no título abre um campo; Enter/blur confirma, Esc cancela. O nome vai só para o
  // currentWorkflow em memória e é gravado pelo próximo Salvar (ou pela ida à aba Código), como o resto
  // das edições do editor. Nome vazio é recusado com um aviso (antes era ignorado calado) e o nome fica o
  // anterior; nome igual ao de outro fluxo é aceito, com um aviso — os dois passam a se confundir na lista e
  // no filtro de Execuções (RCHFLW-134).
  //
  // Na aba Código o título é só texto: lá o documento que vale é o YAML da tela (que ainda traz o nome
  // antigo), o Salvar YAML grava esse texto e recarrega o currentWorkflow do backend — um nome trocado no
  // cabeçalho seria descartado logo abaixo do toast "YAML salvo". Quem quer renomear ali edita
  // metadata.name no próprio YAML.
  const titleEditable = !!current && view !== 'yaml' && canUpdate
  const warnIfDuplicateName = useCallback(async (name: string, ownId: string) => {
    // A lista costuma já estar no store (a busca do layout a carrega). Sem ela, lê da API direto, e não pelo
    // fetchWorkflows do store: ele liga o `loading` compartilhado, que aqui é o "salvando" — o Salvar ficava
    // "Salvando…" e desabilitado sem nada sendo gravado —, e a falha dele preencheria o `error` do store. O aviso
    // é só um conselho: sem a lista, ele não sai.
    let workflows = useWorkflowStore.getState().workflows
    if (workflows.length === 0) {
      try {
        workflows = await workflowApi.list()
      } catch {
        return
      }
    }
    const normalized = name.toLocaleLowerCase()
    const duplicate = workflows
      .some(w => w.id !== ownId && (w.name ?? '').trim().toLocaleLowerCase() === normalized)
    if (duplicate) {
      notifications.show({
        title:   t('editor.renameDuplicate'),
        message: t('editor.renameDuplicateMsg', { name }),
        color:   'yellow',
      })
    }
  }, [t])
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
    if (!current) return
    if (!name) {
      notifications.show({ title: t('editor.renameEmpty'), message: t('editor.renameEmptyMsg'), color: 'red' })
      return
    }
    if (name === current.metadata?.name) return
    renameCurrent(name)
    pendingRenameRef.current = true
    void warnIfDuplicateName(name, current.id)
  }, [current, titleDraft, renameCurrent, warnIfDuplicateName, t])

  // ── Alterações não salvas (RCHFLW-124) ────────────────────────
  // Calculado no render: o editor já re-renderiza a cada mudança do canvas e do store do fluxo. Quem não
  // edita (só leitura) não tem o que perder.
  const hasUnsavedChanges = canUpdate && !!current && (yamlDirty || isCanvasDirty())
  const hasUnsavedRef = useRef(hasUnsavedChanges)
  hasUnsavedRef.current = hasUnsavedChanges
  // Sair do editor com alterações (menu, link, voltar do navegador) pede confirmação no modal do app. Troca
  // só de query (?ai=1 removido) não sai do editor; a troca de rota do primeiro Salvar do rascunho também não.
  const blocker = useBlocker(useCallback(({ currentLocation, nextLocation }: BlockerArgs) => {
    if (currentLocation.pathname === nextLocation.pathname) return false
    if (nextLocation.pathname === allowedPathRef.current) return false
    return hasUnsavedRef.current
  }, []))
  blockedRef.current = blocker.state === 'blocked'
  // Fica no editor: a troca de rota do rascunho que nasceu com o aviso aberto (ver persistCanvas) sai agora.
  const stayInEditor = useCallback(() => {
    blocker.reset?.()
    const promotedId = pendingRouteRef.current
    pendingRouteRef.current = null
    if (promotedId) goToPromoted(promotedId)
  }, [blocker, goToPromoted])
  // Descartar devolve o documento salvo ao store e reconstrói o canvas antes de sair. Sem isso, ir para o item
  // "Editor" do menu (/editor, sem id: o mesmo elemento, o editor não remonta) mantinha na tela, como "não
  // salvo", o nome e o canvas "descartados" — e o próximo Salvar os gravava. Na saída para outra página, o
  // /editor também não reabre depois o nome descartado como último fluxo aberto.
  const discardAndLeave = useCallback(() => {
    saveSessionRef.current += 1
    pendingRouteRef.current = null
    const saved = savedDocRef.current
    if (saved && useWorkflowStore.getState().currentWorkflow?.id === saved.id) {
      useWorkflowStore.setState({ currentWorkflow: saved })
    }
    pendingRenameRef.current = false
    setYamlDirty(false)
    setYamlError(null)
    setView('canvas')
    setCanvasRevision(v => v + 1)
    blocker.proceed?.()
  }, [blocker])
  const [leaving, setLeaving] = useState(false)
  const saveAndLeave = useCallback(async () => {
    const viaYaml = view === 'yaml' && yamlDirty
    setLeaving(true)
    try {
      if (viaYaml) await persistYaml()
      else await saveWorkflow({ leaving: true })
    } catch (e) {
      stayInEditor()
      if (viaYaml) reportYamlSaveError(e)
      else reportSaveFailure(e)
      return
    } finally {
      setLeaving(false)
    }
    pendingRouteRef.current = null
    blocker.proceed?.()
  }, [blocker, view, yamlDirty, persistYaml, saveWorkflow, stayInEditor, reportYamlSaveError, reportSaveFailure])

  const formattedSavedAt = lastSavedAt
    ? new Intl.RelativeTimeFormat(i18n.resolvedLanguage ?? i18n.language, { numeric: 'auto' }).format(
        Math.round((lastSavedAt - Date.now()) / 1000),
        'second'
      )
    : null

  // Fluxo da rota inacessível: no lugar do editor inteiro — sem canvas, sem Salvar, Executar ou IA, que
  // agiriam sobre um documento que não existe aqui (RCHFLW-117). /editor sem id nunca chega aqui.
  const failure = id && loadFailure?.id === id ? loadFailure : null
  if (failure) {
    return (
      <Stack gap="lg" p="md" data-testid={failure.notFound ? 'editor-not-found' : 'editor-load-error'}>
        <Paper withBorder>
          {failure.notFound ? (
            <EmptyState
              icon={<IconFileOff />}
              accent="amber"
              title={t('editor.notFoundTitle')}
              description={t('editor.notFoundHint')}
              actionLabel={t('editor.backToList')}
              onAction={() => navigate('/workflows')}
            />
          ) : (
            <EmptyState
              icon={<IconAlertCircle />}
              accent="rose"
              title={t('editor.loadFailed')}
              description={failure.message}
              actionLabel={t('common.refresh')}
              onAction={() => setLoadAttempt(v => v + 1)}
            />
          )}
        </Paper>
      </Stack>
    )
  }

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
        {/* minWidth 0: sem ele o flex item não encolhe e um nome de 512 caracteres ocupava ~6 linhas do topo do
            editor (RCHFLW-134); o título corta com reticências e o nome inteiro fica no title (tooltip). */}
        <div style={{ display: 'flex', alignItems: 'center', gap: 8, flex: 1, minWidth: 0 }}>
          {!titleEditable ? (
            <span
              data-testid="editor-title"
              title={current?.metadata?.name || undefined}
              style={{ ...titleTextStyle, fontSize: 14, fontWeight: 500, color: 'var(--color-text-primary)', padding: '1px 0' }}
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
              title={current?.metadata?.name ? `${current.metadata.name} — ${t('editor.renameHint')}` : t('editor.renameHint')}
              onClick={startTitleEdit}
              style={{
                ...titleTextStyle,
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
          {hasUnsavedChanges && (
            <Badge variant="light" color="orange" size="sm" data-testid="editor-unsaved" style={{ flexShrink: 0 }}>
              {t('editor.unsaved.badge')}
            </Badge>
          )}
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
              // O YAML é o que o servidor gera do documento salvo: o rascunho ainda não tem um (RCHFLW-131).
              disabled={switchingView || isDraft}
              title={isDraft ? t('editor.codeAfterSave') : undefined}
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

          {!canUpdate && (
            <Badge variant="light" color="gray" size="sm" data-testid="editor-read-only">
              {t('editor.readOnly')}
            </Badge>
          )}

          {canUpdate && (
            <button
              onClick={view === 'yaml' ? handleSaveYaml : handleSave}
              disabled={isSaving || (view === 'yaml' && !yamlDirty)}
              style={{ ...btnStyle, opacity: isSaving ? 0.6 : 1 }}
              data-testid="editor-save"
            >
              {isSaving ? t('common.saving') : view === 'yaml' ? t('editor.saveYaml') : t('editor.save')}
            </button>
          )}

          {canUpdate && (
            <Button
              onClick={() => setPublishOpen(true)}
              variant="default"
              leftSection={<IconWorldUpload size={14} />}
              size="xs"
              disabled={!current || isDraft}
              data-testid="editor-publish"
            >
              {t('editor.publish.button')}
            </Button>
          )}

          {canUpdate && (
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
          )}

          {canExecute && <Button
            onClick={handleExecute}
            color={isExecuting ? 'red' : undefined}
            variant={isExecuting ? 'light' : 'filled'}
            leftSection={isExecuting ? <IconPlayerStop size={14} /> : <IconPlayerPlay size={14} />}
            size="xs"
            disabled={!current}
            data-testid="editor-run"
          >
            {/* O editor roda o rascunho; a lista e a API, a versão publicada (RCHFLW-89). */}
            {isExecuting ? t('editor.stop') : t('editor.runDraft')}
          </Button>}
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
          {/* Só com o canvas de pé: o ?ai=1 abre este modal antes de o fluxo carregar, e um Gerar nessa janela
              (logo após o login, com o fetch mais lento) mandava o copiloto a um editor sem canvas — ele navegava
              para /editor sem id e montava ali (RCHFLW-131). */}
          <Button
            leftSection={<IconSparkles size={14} />}
            color="grape"
            disabled={!aiGoal.trim() || !current || !canvasApi}
            onClick={handleGenerate}
            data-testid="editor-ai-generate"
          >
            {t('editor.ai.generate')}
          </Button>
        </Stack>
      </Modal>

      {/* Saída com alterações não salvas (RCHFLW-124): o bloqueio do router segura a navegação até a escolha. */}
      <Modal
        opened={blocker.state === 'blocked'}
        onClose={stayInEditor}
        title={t('editor.unsaved.title')}
        centered
      >
        <Stack gap="md" data-testid="editor-unsaved-modal">
          <Text size="sm">{t('editor.unsaved.message')}</Text>
          <Group justify="flex-end" gap="xs">
            <Button variant="default" onClick={stayInEditor} disabled={leaving}
                    data-testid="editor-unsaved-stay">
              {t('editor.unsaved.stay')}
            </Button>
            <Button color="red" variant="light" onClick={discardAndLeave} disabled={leaving}
                    data-testid="editor-unsaved-discard">
              {t('editor.unsaved.discard')}
            </Button>
            <Button onClick={() => void saveAndLeave()} loading={leaving} data-testid="editor-unsaved-save">
              {t('editor.unsaved.save')}
            </Button>
          </Group>
        </Stack>
      </Modal>

      {/* Conflito no Salvar (RCHFLW-125). */}
      <Modal
        opened={conflictOpen}
        onClose={() => { if (!conflictBusy) setConflictOpen(false) }}
        title={t('editor.conflict.title')}
        centered
      >
        <Stack gap="md" data-testid="editor-conflict-modal">
          <Text size="sm">{t('editor.conflict.message')}</Text>
          <Group justify="flex-end" gap="xs">
            <Button variant="default" onClick={() => void reloadAfterConflict()} disabled={conflictBusy}
                    data-testid="editor-conflict-reload">
              {t('editor.conflict.reload')}
            </Button>
            <Button color="red" onClick={() => void overwriteAfterConflict()} disabled={conflictBusy}
                    data-testid="editor-conflict-overwrite">
              {t('editor.conflict.overwrite')}
            </Button>
          </Group>
        </Stack>
      </Modal>

      {/* Voltar ao Canvas com YAML não salvo (RCHFLW-134). */}
      <Modal
        opened={discardYamlOpen}
        onClose={() => setDiscardYamlOpen(false)}
        title={t('editor.discardYaml.title')}
        centered
      >
        <Stack gap="md" data-testid="editor-discard-yaml-modal">
          <Text size="sm">{t('editor.discardYaml.message')}</Text>
          <Group justify="flex-end" gap="xs">
            <Button variant="default" onClick={() => setDiscardYamlOpen(false)} data-testid="editor-discard-yaml-stay">
              {t('editor.discardYaml.stay')}
            </Button>
            <Button
              color="red"
              onClick={() => { setDiscardYamlOpen(false); void switchView('canvas', { discardYaml: true }) }}
              data-testid="editor-discard-yaml-confirm"
            >
              {t('editor.discardYaml.confirm')}
            </Button>
          </Group>
        </Stack>
      </Modal>

      {current && current.id && (
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
          runs="draft"
          expectedFields={runExpectedFields}
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
              it back without hunting a menu. Sem workflow:update (RCHFLW-74)
              não há paleta nem o botão dela: o canvas só leitura não registra
              a API de inserir nem aceita o drop, então os itens não fariam nada. */}
          {canUpdate && (
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
          )}

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
                key={`${canvasIdentity ?? 'none'}:${canvasRevision}`}
                initialWorkflow={workflowData}
                readonly={!canUpdate}
                onNodeSelect={handleNodeSelect}
                onExecutionRequest={handleExecute}
                keyboardActive={view === 'canvas'}
                workflowId={current?.id || undefined}
              />
            ) : (
              <EmptyCanvas />
            )}

            {canUpdate && (
              <PanelToggle
                side="left"
                open={paletteVisible}
                onToggle={() => setPaletteVisible(v => !v)}
                label={t('editor.properties.togglePalette')}
                testId="toggle-palette"
              />
            )}
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
              readOnly={!canUpdate}
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
                readOnly={!canUpdate}
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

/** Título do fluxo numa linha só, com reticências (RCHFLW-134). */
const titleTextStyle: React.CSSProperties = {
  minWidth:     0,
  maxWidth:     '100%',
  overflow:     'hidden',
  textOverflow: 'ellipsis',
  whiteSpace:   'nowrap',
  textAlign:    'left',
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
