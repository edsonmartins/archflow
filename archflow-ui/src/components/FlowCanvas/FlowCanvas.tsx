import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { useTranslation } from 'react-i18next'
import {
  ReactFlow,
  Background,
  Controls,
  MiniMap,
  Panel,
  BackgroundVariant,
  ConnectionLineType,
  addEdge,
  useNodesState,
  useEdgesState,
  type Node,
  type Edge,
  type Connection,
  type OnConnect,
  type OnConnectEnd,
  type NodeTypes,
  type ReactFlowInstance,
  type XYPosition,
} from '@xyflow/react'
import '@xyflow/react/dist/style.css'
import dagre from '@dagrejs/dagre'
import { ActionIcon, Group, Tooltip } from '@mantine/core'
import {
  IconArrowBackUp, IconArrowForwardUp, IconLayoutDistributeHorizontal,
} from '@tabler/icons-react'

import {
  AgentNode, ControlNode, DataNode, ToolNode, VectorNode, IONode,
  KnowledgeNode, IntegrationNode,
  StickyNoteNode, GroupFrameNode, SectionDividerNode,
} from './nodes/index'
import { FlowEdge }      from './edges/FlowEdge'
import { useFlowStore, type CanvasApi }  from './store/useFlowStore'
import { NODE_CATEGORIES, NODE_TYPE_TO_CATEGORY } from './constants'
import type { FlowNodeData, WorkflowData } from './types'
import { CanvasOutline } from './CanvasOutline'
import { AddNodeMenu } from './AddNodeMenu'

// ── Registro de tipos ────────────────────────────────────────────
const nodeTypes: NodeTypes = {
  agent:       AgentNode,
  control:     ControlNode,
  data:        DataNode,
  tool:        ToolNode,
  vector:      VectorNode,
  io:          IONode,
  knowledge:   KnowledgeNode,
  integration: IntegrationNode,
  // Auxiliary shapes — render under their componentId so the drop
  // handler in `onDrop` resolves to the right component (we use the
  // componentId as the React Flow `type`, not the category).
  'sticky-note':     StickyNoteNode,
  'group-frame':     GroupFrameNode,
  'section-divider': SectionDividerNode,
}

const edgeTypes = {
  flow: FlowEdge,
}

// Annotation shapes are excluded from autolayout and from the
// on-connection-drop menu — they are canvas furniture, not steps.
const ANNOTATION_TYPES = new Set(['sticky-note', 'group-frame', 'section-divider'])

const ANNOTATION_DEFAULT_SIZE: Record<string, { width: number; height: number }> = {
  'sticky-note':     { width: 220, height: 140 },
  'group-frame':     { width: 360, height: 220 },
  'section-divider': { width: 480, height: 36  },
}

interface CanvasSnapshot {
  nodes: Node<FlowNodeData>[]
  edges: Edge[]
}

/** Builds a canvas node for a palette entry at the given flow position. */
function buildPaletteNode(
  info: { componentId: string; label: string; nodeType?: string },
  position: XYPosition,
  id: string,
): Node<FlowNodeData> {
  const category = NODE_TYPE_TO_CATEGORY[info.componentId] ?? 'tool'
  const isAnnotation = category === 'annotation'
  return {
    id,
    type: isAnnotation ? info.componentId : category,
    position,
    ...(isAnnotation ? { ...ANNOTATION_DEFAULT_SIZE[info.componentId] } : {}),
    data: {
      label:       info.label,
      componentId: info.componentId,
      nodeType:    info.nodeType ?? info.componentId,
      status:      'idle',
      config:      {},
    },
  }
}

// ── Props públicas do FlowCanvas ─────────────────────────────────
export interface FlowCanvasProps {
  initialWorkflow?: WorkflowData | null
  readonly?: boolean
  showMinimap?: boolean
  showGrid?: boolean
  onNodeSelect?: (nodeId: string | null, data: FlowNodeData | null) => void
  onWorkflowChange?: (workflow: WorkflowData) => void
  onExecutionRequest?: () => void
}

// ── Componente ───────────────────────────────────────────────────
export function FlowCanvas({
  initialWorkflow,
  readonly = false,
  showMinimap = true,
  showGrid = true,
  onNodeSelect,
  onWorkflowChange,
  onExecutionRequest,
}: FlowCanvasProps) {
  const rfInstanceRef = useRef<ReactFlowInstance<Node<FlowNodeData>, Edge> | null>(null)

  const initialNodes = useMemo(
    () => initialWorkflow ? workflowToNodes(initialWorkflow) : [],
    [initialWorkflow]
  )
  const initialEdges = useMemo(
    () => initialWorkflow ? workflowToEdges(initialWorkflow) : [],
    [initialWorkflow]
  )

  const { t } = useTranslation()
  const [outlineOpen, setOutlineOpen] = useState(false)
  const [nodes, setNodes, onNodesChange] = useNodesState(initialNodes)
  const [edges, setEdges, onEdgesChange] = useEdgesState(initialEdges)

  const {
    executionState,
    setNodes: syncNodes,
    setEdges: syncEdges,
    nodes: storeNodes,
    setCanvasApi,
    replaceCanvas,
    selectNode,
    clearSelection,
  } = useFlowStore()

  // Track whether an update came from the store (PropertyPanel edit)
  // or from React Flow (drag/drop, connect, delete). Prevents ping-pong
  // between the two sync effects during remount.
  const fromStoreRef = useRef(false)
  const localNodesRef = useRef(nodes)
  localNodesRef.current = nodes
  const localEdgesRef = useRef(edges)
  localEdgesRef.current = edges
  const wrapperRef = useRef<HTMLDivElement | null>(null)

  // ── Undo/redo history ──────────────────────────────────────────
  // Snapshots of the local React Flow state, captured BEFORE each
  // discrete mutation (add / connect / delete / move / config edit).
  // Node data objects are updated immutably everywhere, so snapshots
  // can hold references without deep-cloning.
  const historyRef = useRef<{ past: CanvasSnapshot[]; future: CanvasSnapshot[] }>({ past: [], future: [] })
  const lastPushRef = useRef<{ reason: string; at: number }>({ reason: '', at: 0 })
  const [historyState, setHistoryState] = useState({ canUndo: false, canRedo: false })

  const syncHistoryState = useCallback(() => {
    const h = historyRef.current
    setHistoryState({ canUndo: h.past.length > 0, canRedo: h.future.length > 0 })
  }, [])

  const pushHistory = useCallback((reason: string, coalesce = false) => {
    const now = Date.now()
    // Coalesce bursts of the same edit (e.g. typing in the PropertyPanel)
    // into a single undo step.
    if (coalesce && reason === lastPushRef.current.reason && now - lastPushRef.current.at < 800) {
      lastPushRef.current.at = now
      return
    }
    lastPushRef.current = { reason, at: now }
    const h = historyRef.current
    h.past.push({ nodes: localNodesRef.current, edges: localEdgesRef.current })
    if (h.past.length > 100) h.past.shift()
    h.future = []
    syncHistoryState()
  }, [syncHistoryState])

  const applySnapshot = useCallback((s: CanvasSnapshot) => {
    setNodes(s.nodes)
    setEdges(s.edges)
    // Restore the store wholesale too: the RF→store merge keeps the
    // store's `data` for existing ids, so config undo needs this.
    replaceCanvas(s.nodes, s.edges)
    onNodeSelect?.(null, null)
  }, [setNodes, setEdges, replaceCanvas, onNodeSelect])

  const undo = useCallback(() => {
    const h = historyRef.current
    const prev = h.past.pop()
    if (!prev) return
    h.future.push({ nodes: localNodesRef.current, edges: localEdgesRef.current })
    applySnapshot(prev)
    lastPushRef.current = { reason: '', at: 0 }
    syncHistoryState()
  }, [applySnapshot, syncHistoryState])

  const redo = useCallback(() => {
    const h = historyRef.current
    const next = h.future.pop()
    if (!next) return
    h.past.push({ nodes: localNodesRef.current, edges: localEdgesRef.current })
    applySnapshot(next)
    lastPushRef.current = { reason: '', at: 0 }
    syncHistoryState()
  }, [applySnapshot, syncHistoryState])

  useEffect(() => {
    setNodes(initialNodes)
    setEdges(initialEdges)
    replaceCanvas(initialNodes, initialEdges)
    // A (re)loaded workflow starts a fresh history timeline.
    historyRef.current = { past: [], future: [] }
    lastPushRef.current = { reason: '', at: 0 }
    syncHistoryState()
  }, [initialEdges, initialNodes, replaceCanvas, setEdges, setNodes, syncHistoryState])

  // React Flow → Zustand: publish local node/edge changes so PropertyPanel
  // can read the latest config via `selectedNodeData`, and so
  // `getCanvasSnapshot()` returns current state for the save flow.
  useEffect(() => {
    if (fromStoreRef.current) {
      fromStoreRef.current = false
      return
    }
    syncNodes(nodes)
  }, [nodes, syncNodes])

  useEffect(() => {
    syncEdges(edges)
  }, [edges, syncEdges])

  // Zustand → React Flow: apply `updateNodeConfig`/`updateNodeLabel`
  // mutations from PropertyPanel back into React Flow's state. We only
  // touch nodes whose id exists on the canvas and whose data reference
  // differs, so the no-op path is free.
  useEffect(() => {
    if (storeNodes.length === 0) return
    const current = localNodesRef.current
    // Guard against stale store data from a previous FlowCanvas mount:
    // if the store's node ids don't match the canvas, skip.
    const canvasIds = new Set(current.map(n => n.id))
    const matching = storeNodes.filter(s => canvasIds.has(s.id))
    if (matching.length === 0) return

    let changed = false
    const next = current.map(n => {
      const storeNode = matching.find(s => s.id === n.id)
      if (!storeNode || storeNode.data === n.data) return n
      changed = true
      return { ...n, data: storeNode.data }
    })
    if (changed) {
      // PropertyPanel edits land here — one (coalesced) undo step.
      pushHistory('config', true)
      fromStoreRef.current = true
      setNodes(next)
    }
  }, [storeNodes, setNodes, pushHistory])

  // ── API imperativa p/ o copilot global e o palette (click-to-add) ──
  useEffect(() => {
    if (readonly) return
    const api: CanvasApi = {
      addNode: ({ nodeType, label, position }) => {
        const id = `node-${Date.now()}`
        // Default placement: current viewport centre, nudged per node so
        // consecutive adds don't stack exactly on top of each other.
        let pos = position
        if (!pos) {
          const rect = wrapperRef.current?.getBoundingClientRect()
          const count = localNodesRef.current.length
          pos = rect && rfInstanceRef.current
            ? rfInstanceRef.current.screenToFlowPosition({
                x: rect.x + rect.width / 2 + (count % 3) * 28,
                y: rect.y + rect.height / 2 + (count % 3) * 28,
              })
            : { x: 120 + (count % 5) * 220, y: 120 + Math.floor(count / 5) * 130 }
        }
        pushHistory('add')
        setNodes(nds => [...nds, buildPaletteNode({ componentId: nodeType, label: label ?? nodeType }, pos, id)])
        return id
      },
      connectNodes: (sourceId, targetId) => {
        const ids = new Set(localNodesRef.current.map(n => n.id))
        if (!ids.has(sourceId) || !ids.has(targetId)) return false
        pushHistory('connect')
        setEdges(eds => addEdge(
          { id: `e-${sourceId}-${targetId}-${Date.now()}`, source: sourceId, target: targetId, type: 'flow', animated: false, data: { isErrorPath: false } },
          eds))
        return true
      },
      listNodes: () => localNodesRef.current.map(n => ({
        id: n.id,
        label: String(n.data?.label ?? n.id),
        nodeType: String(n.data?.nodeType ?? ''),
      })),
    }
    setCanvasApi(api)
    // Apply edits queued before this canvas existed (copilot: create flow -> add nodes).
    const drain = setTimeout(() => {
      for (const op of useFlowStore.getState().takePendingCanvasOps()) {
        if (op.kind === 'add') api.addNode(op)
        else api.connectNodes(op.sourceId, op.targetId)
      }
    }, 0)
    return () => { clearTimeout(drain); setCanvasApi(null) }
  }, [readonly, setCanvasApi, setNodes, setEdges, pushHistory])

  // ── Conexão entre nós ──────────────────────────────────────────
  const onConnect: OnConnect = useCallback(
    (params: Connection) => {
      pushHistory('connect')
      setEdges(eds =>
        addEdge(
          {
            ...params,
            type: 'flow',
            animated: false,
            data: { isErrorPath: false },
          },
          eds
        )
      )
    },
    [setEdges, pushHistory]
  )

  // ── Menu "solte para adicionar" ao largar uma conexão no vazio ──
  // n8n-style: dragging a connection and releasing it on empty canvas
  // opens a searchable node menu; picking an entry creates the node at
  // the drop point and connects it to the origin.
  const [connectMenu, setConnectMenu] = useState<null | {
    left: number
    top: number
    flowPos: XYPosition
    fromNodeId: string
    fromHandleType: 'source' | 'target'
  }>(null)

  const onConnectEnd: OnConnectEnd = useCallback((event, connectionState) => {
    if (readonly || connectionState.isValid || !connectionState.fromNode) return
    if (!rfInstanceRef.current || !wrapperRef.current) return
    const { clientX, clientY } =
      'changedTouches' in event ? event.changedTouches[0] : event
    const rect = wrapperRef.current.getBoundingClientRect()
    setConnectMenu({
      left: Math.min(clientX - rect.left, rect.width - 260),
      top: Math.min(clientY - rect.top, rect.height - 320),
      flowPos: rfInstanceRef.current.screenToFlowPosition({ x: clientX, y: clientY }),
      fromNodeId: connectionState.fromNode.id,
      fromHandleType: connectionState.fromHandle?.type ?? 'source',
    })
  }, [readonly])

  const addConnectedNode = useCallback((info: { componentId: string; label: string }) => {
    if (!connectMenu) return
    pushHistory('add')
    const id = `node-${Date.now()}`
    const node = buildPaletteNode(info, connectMenu.flowPos, id)
    setNodes(nds => [...nds, node])
    const [source, target] = connectMenu.fromHandleType === 'source'
      ? [connectMenu.fromNodeId, id]
      : [id, connectMenu.fromNodeId]
    setEdges(eds => addEdge(
      { id: `e-${source}-${target}`, source, target, type: 'flow', animated: false, data: { isErrorPath: false } },
      eds))
    setConnectMenu(null)
  }, [connectMenu, pushHistory, setNodes, setEdges])

  // ── Copiar / colar / duplicar seleção ──────────────────────────
  const clipboardRef = useRef<CanvasSnapshot | null>(null)

  const copySelection = useCallback(() => {
    let selected = localNodesRef.current.filter(n => n.selected)
    if (!selected.length) {
      // Single-click selection is tracked in the store (PropertyPanel
      // flow), not necessarily as React Flow's `selected` flag — fall
      // back to it so copy/duplicate work on the clicked node.
      const sid = useFlowStore.getState().selectedNodeId
      selected = sid ? localNodesRef.current.filter(n => n.id === sid) : []
    }
    if (!selected.length) return false
    const ids = new Set(selected.map(n => n.id))
    clipboardRef.current = {
      nodes: selected,
      edges: localEdgesRef.current.filter(e => ids.has(e.source) && ids.has(e.target)),
    }
    return true
  }, [])

  const pasteClipboard = useCallback(() => {
    const clip = clipboardRef.current
    if (!clip?.nodes.length) return
    pushHistory('paste')
    const stamp = Date.now()
    const idMap = new Map<string, string>()
    const newNodes = clip.nodes.map((n, i) => {
      const nid = `node-${stamp}-${i}`
      idMap.set(n.id, nid)
      return {
        ...n,
        id: nid,
        position: { x: n.position.x + 32, y: n.position.y + 32 },
        selected: true,
        data: { ...n.data, config: { ...n.data.config } },
      }
    })
    const newEdges = clip.edges.map((e, i) => ({
      ...e,
      id: `e-${stamp}-${i}`,
      source: idMap.get(e.source)!,
      target: idMap.get(e.target)!,
      selected: false,
    }))
    setNodes(nds => [...nds.map(n => ({ ...n, selected: false })), ...newNodes])
    setEdges(eds => [...eds, ...newEdges])
  }, [pushHistory, setNodes, setEdges])

  const duplicateSelection = useCallback(() => {
    if (copySelection()) pasteClipboard()
  }, [copySelection, pasteClipboard])

  // ── Autolayout (dagre, LR) ──────────────────────────────────────
  const tidyUp = useCallback(() => {
    const regular = localNodesRef.current.filter(n => !ANNOTATION_TYPES.has(n.type ?? ''))
    if (regular.length < 2) return
    pushHistory('tidy')
    const g = new dagre.graphlib.Graph()
    g.setGraph({ rankdir: 'LR', nodesep: 48, ranksep: 96 })
    g.setDefaultEdgeLabel(() => ({}))
    regular.forEach(n => g.setNode(n.id, {
      width: n.measured?.width ?? 200,
      height: n.measured?.height ?? 90,
    }))
    localEdgesRef.current.forEach(e => {
      if (g.hasNode(e.source) && g.hasNode(e.target)) g.setEdge(e.source, e.target)
    })
    dagre.layout(g)
    setNodes(nds => nds.map(n => {
      if (!g.hasNode(n.id)) return n
      const p = g.node(n.id)
      return {
        ...n,
        position: {
          x: p.x - (n.measured?.width ?? 200) / 2,
          y: p.y - (n.measured?.height ?? 90) / 2,
        },
      }
    }))
    requestAnimationFrame(() => rfInstanceRef.current?.fitView({ padding: 0.2 }))
  }, [pushHistory, setNodes])

  // ── Atalhos de teclado do canvas ────────────────────────────────
  useEffect(() => {
    if (readonly) return
    const onKey = (e: KeyboardEvent) => {
      const target = e.target as HTMLElement | null
      if (target?.closest('input, textarea, select, [contenteditable="true"]')) return
      const mod = e.metaKey || e.ctrlKey
      if (!mod) return
      const k = e.key.toLowerCase()
      if (k === 'z' && !e.shiftKey) { e.preventDefault(); undo(); return }
      if ((k === 'z' && e.shiftKey) || k === 'y') { e.preventDefault(); redo(); return }
      // Don't hijack copy/paste while the user has text selected elsewhere.
      if (document.getSelection()?.toString()) return
      if (k === 'c') { copySelection(); return }
      if (k === 'v') { e.preventDefault(); pasteClipboard(); return }
      if (k === 'd') { e.preventDefault(); duplicateSelection() }
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [readonly, undo, redo, copySelection, pasteClipboard, duplicateSelection])

  // ── Drop de nó da palette ──────────────────────────────────────
  const onDragOver = useCallback((event: React.DragEvent) => {
    event.preventDefault()
    event.dataTransfer.dropEffect = 'move'
  }, [])

  const onDrop = useCallback(
    (event: React.DragEvent) => {
      event.preventDefault()
      if (readonly || !rfInstanceRef.current) return

      const raw = event.dataTransfer.getData('application/archflow-node')
      if (!raw) return

      const nodeInfo = JSON.parse(raw) as {
        type?: string
        componentId: string
        label: string
        category: keyof typeof NODE_CATEGORIES
      }

      const position = rfInstanceRef.current.screenToFlowPosition({
        x: event.clientX,
        y: event.clientY,
      })

      pushHistory('add')
      setNodes(nds => [...nds, buildPaletteNode(nodeInfo, position, `node-${Date.now()}`)])
    },
    [readonly, setNodes, pushHistory]
  )

  // ── Seleção de nó ──────────────────────────────────────────────
  const onNodeClick = useCallback(
    (event: React.MouseEvent, node: Node<FlowNodeData>) => {
      event.stopPropagation()
      selectNode(node.id, node.data)
      onNodeSelect?.(node.id, node.data)
    },
    [onNodeSelect, selectNode]
  )

  const onPaneClick = useCallback(() => {
    setConnectMenu(null)
    clearSelection()
    onNodeSelect?.(null, null)
  }, [clearSelection, onNodeSelect])

  return (
    <div
      ref={wrapperRef}
      style={{ width: '100%', height: '100%', position: 'relative' }}
      onDragOver={onDragOver}
      onDrop={onDrop}
    >
      {/* Alternativa acessível ao canvas: lista textual navegável por teclado.
          O botão fica acima de tudo; a região em si é sr-only quando fechada. */}
      <button
        type="button"
        className="af-canvas-outline-toggle"
        aria-expanded={outlineOpen}
        aria-controls="af-canvas-outline"
        onClick={() => setOutlineOpen((v) => !v)}
        style={{
          position: 'absolute', top: 12, right: 12, zIndex: 7,
          fontSize: 12, padding: '6px 10px', borderRadius: 8,
          border: '1px solid var(--border2)', background: 'var(--bg2)',
          color: 'var(--text2)', cursor: 'pointer',
        }}
      >
        {t('editor.outline.toggle')}
      </button>
      <div id="af-canvas-outline">
        <CanvasOutline open={outlineOpen} />
      </div>

      <ReactFlow
        nodes={nodes.map(n => ({
          ...n,
          data: {
            ...n.data,
            executionStatus: executionState[n.id]?.status ?? 'idle',
            executionMs:     executionState[n.id]?.durationMs,
          },
        }))}
        edges={edges}
        nodeTypes={nodeTypes}
        edgeTypes={edgeTypes}
        onNodesChange={readonly ? undefined : onNodesChange}
        onEdgesChange={readonly ? undefined : onEdgesChange}
        onConnect={readonly ? undefined : onConnect}
        onConnectEnd={readonly ? undefined : onConnectEnd}
        onNodeDragStart={readonly ? undefined : () => pushHistory('move')}
        onBeforeDelete={readonly ? undefined : async (toDelete) => {
          pushHistory('delete')
          return toDelete
        }}
        onNodeClick={onNodeClick}
        onPaneClick={onPaneClick}
        onInit={instance => {
          // xyflow infla o generic do node no site JSX (props opcionais viram
          // requeridas na inferência); estruturalmente é o mesmo Node<FlowNodeData>.
          rfInstanceRef.current = instance as unknown as ReactFlowInstance<Node<FlowNodeData>, Edge>
        }}
        connectionLineType={ConnectionLineType.SmoothStep}
        defaultEdgeOptions={{
          type: 'flow',
          animated: false,
        }}
        deleteKeyCode={readonly ? null : ['Backspace', 'Delete']}
        nodesDraggable={!readonly}
        nodesConnectable={!readonly}
        elementsSelectable={!readonly}
        fitView
        fitViewOptions={{ padding: 0.2 }}
        proOptions={{ hideAttribution: true }}
        style={{ background: 'var(--color-background-tertiary)' }}
      >
        {showGrid && (
          <Background
            variant={BackgroundVariant.Dots}
            gap={20}
            size={1}
            color="var(--color-border-secondary)"
          />
        )}

        {!readonly && (
          <Panel position="top-center">
            <Group
              gap={2}
              p={2}
              style={{
                background: 'var(--color-background-primary)',
                border: '1px solid var(--color-border-secondary)',
                borderRadius: 8,
              }}
            >
              <Tooltip label={t('editor.canvasActions.undo')}>
                <ActionIcon
                  variant="subtle"
                  size="md"
                  disabled={!historyState.canUndo}
                  onClick={undo}
                  aria-label={t('editor.canvasActions.undo')}
                  data-testid="canvas-undo"
                >
                  <IconArrowBackUp size={16} />
                </ActionIcon>
              </Tooltip>
              <Tooltip label={t('editor.canvasActions.redo')}>
                <ActionIcon
                  variant="subtle"
                  size="md"
                  disabled={!historyState.canRedo}
                  onClick={redo}
                  aria-label={t('editor.canvasActions.redo')}
                  data-testid="canvas-redo"
                >
                  <IconArrowForwardUp size={16} />
                </ActionIcon>
              </Tooltip>
              <Tooltip label={t('editor.canvasActions.tidy')}>
                <ActionIcon
                  variant="subtle"
                  size="md"
                  onClick={tidyUp}
                  aria-label={t('editor.canvasActions.tidy')}
                  data-testid="canvas-tidy"
                >
                  <IconLayoutDistributeHorizontal size={16} />
                </ActionIcon>
              </Tooltip>
            </Group>
          </Panel>
        )}

        <Controls
          showInteractive={false}
          style={{
            background: 'var(--color-background-primary)',
            border: '0.5px solid var(--color-border-secondary)',
            borderRadius: 8,
            boxShadow: 'none',
          }}
        />

        {showMinimap && (
          <MiniMap
            nodeColor={minimapNodeColor}
            maskColor="rgba(0,0,0,0.04)"
            style={{
              background: 'var(--color-background-primary)',
              border: '0.5px solid var(--color-border-secondary)',
              borderRadius: 8,
            }}
          />
        )}
      </ReactFlow>

      {connectMenu && (
        <AddNodeMenu
          left={connectMenu.left}
          top={connectMenu.top}
          onPick={addConnectedNode}
          onClose={() => setConnectMenu(null)}
        />
      )}
    </div>
  )
}

// ── Helpers ──────────────────────────────────────────────────────

function minimapNodeColor(node: Node): string {
  // node.type is the category for regular nodes and the componentId for
  // annotations — resolve both to the shared NODE_CATEGORIES palette so
  // the minimap matches the canvas node colors.
  const type = node.type ?? ''
  const catKey = (type in NODE_CATEGORIES ? type : NODE_TYPE_TO_CATEGORY[type]) as
    keyof typeof NODE_CATEGORIES | undefined
  return NODE_CATEGORIES[catKey ?? 'io'].color
}

function workflowToNodes(wf: WorkflowData): Node<FlowNodeData>[] {
  return wf.steps.map(step => {
    const isAnnotation = step.category === 'annotation'
    return {
      id:       step.id,
      // Same routing as in onDrop: annotations use their componentId
      // as the React Flow type; everything else uses the category.
      type:     isAnnotation ? step.componentId : step.category,
      position: step.position,
      data: {
        label:       step.label,
        componentId: step.componentId,
        nodeType:    step.type,
        status:      'idle',
        config:      step.config ?? {},
      },
    }
  })
}

function workflowToEdges(wf: WorkflowData): Edge[] {
  return wf.connections.map(conn => ({
    id:     conn.id,
    source: conn.sourceId,
    target: conn.targetId,
    type:   'flow',
    data:   { isErrorPath: conn.isErrorPath },
  }))
}
