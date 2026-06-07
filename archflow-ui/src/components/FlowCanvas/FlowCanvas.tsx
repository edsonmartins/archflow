import { useCallback, useEffect, useRef } from 'react'
import {
  ReactFlow,
  Background,
  Controls,
  MiniMap,
  BackgroundVariant,
  ConnectionLineType,
  addEdge,
  useNodesState,
  useEdgesState,
  type Node,
  type Edge,
  type Connection,
  type OnConnect,
  type NodeTypes,
  type ReactFlowInstance,
} from '@xyflow/react'
import '@xyflow/react/dist/style.css'

import {
  AgentNode, ControlNode, DataNode, ToolNode, VectorNode, IONode,
  KnowledgeNode, IntegrationNode,
  StickyNoteNode, GroupFrameNode, SectionDividerNode,
} from './nodes/index'
import { FlowEdge }      from './edges/FlowEdge'
import { useFlowStore, type CanvasApi }  from './store/useFlowStore'
import { NODE_CATEGORIES, NODE_TYPE_TO_CATEGORY } from './constants'
import type { FlowNodeData, WorkflowData } from './types'

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
  const rfInstanceRef = useRef<ReactFlowInstance | null>(null)

  const initialNodes = initialWorkflow
    ? workflowToNodes(initialWorkflow)
    : []
  const initialEdges = initialWorkflow
    ? workflowToEdges(initialWorkflow)
    : []

  const [nodes, setNodes, onNodesChange] = useNodesState(initialNodes)
  const [edges, setEdges, onEdgesChange] = useEdgesState(initialEdges)

  const { executionState, setNodes: syncNodes, setEdges: syncEdges, nodes: storeNodes, setCanvasApi } = useFlowStore()

  // Track whether an update came from the store (PropertyPanel edit)
  // or from React Flow (drag/drop, connect, delete). Prevents ping-pong
  // between the two sync effects during remount.
  const fromStoreRef = useRef(false)
  const localNodesRef = useRef(nodes)
  localNodesRef.current = nodes

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
      fromStoreRef.current = true
      setNodes(next)
    }
  }, [storeNodes, setNodes])

  // ── API imperativa p/ o copilot global (só o canvas editável) ──
  useEffect(() => {
    if (readonly) return
    const api: CanvasApi = {
      addNode: ({ nodeType, label }) => {
        const id = `node-${Date.now()}`
        const category = NODE_TYPE_TO_CATEGORY[nodeType] ?? 'tool'
        const count = localNodesRef.current.length
        const node: Node<FlowNodeData> = {
          id,
          type: category,
          position: { x: 120 + (count % 5) * 220, y: 120 + Math.floor(count / 5) * 130 },
          data: { label: label ?? nodeType, componentId: nodeType, nodeType, status: 'idle', config: {} },
        }
        setNodes(nds => [...nds, node])
        return id
      },
      connectNodes: (sourceId, targetId) => {
        const ids = new Set(localNodesRef.current.map(n => n.id))
        if (!ids.has(sourceId) || !ids.has(targetId)) return false
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
  }, [readonly, setCanvasApi, setNodes, setEdges])

  // ── Conexão entre nós ──────────────────────────────────────────
  const onConnect: OnConnect = useCallback(
    (params: Connection) =>
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
      ),
    [setEdges]
  )

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

      // Annotation shapes are not generic category cards — each has
      // its own dedicated React Flow component (StickyNote / GroupFrame
      // / SectionDivider), so we register them under their componentId
      // and route by that. Default sizes also differ per shape.
      const isAnnotation = nodeInfo.category === 'annotation'
      const defaultSize: Record<string, { width: number; height: number }> = {
        'sticky-note':     { width: 220, height: 140 },
        'group-frame':     { width: 360, height: 220 },
        'section-divider': { width: 480, height: 36  },
      }
      const newNode: Node<FlowNodeData> = {
        id:       `node-${Date.now()}`,
        type:     isAnnotation ? nodeInfo.componentId : nodeInfo.category,
        position,
        ...(isAnnotation ? { ...defaultSize[nodeInfo.componentId] } : {}),
        data: {
          label:       nodeInfo.label,
          componentId: nodeInfo.componentId,
          nodeType:    nodeInfo.type ?? nodeInfo.componentId,
          status:      'idle',
          config:      {},
        },
      }

      setNodes(nds => [...nds, newNode])
    },
    [readonly, setEdges, setNodes]
  )

  // ── Seleção de nó ──────────────────────────────────────────────
  const onNodeClick = useCallback(
    (_: React.MouseEvent, node: Node<FlowNodeData>) => {
      onNodeSelect?.(node.id, node.data)
    },
    [onNodeSelect]
  )

  const onPaneClick = useCallback(() => {
    onNodeSelect?.(null, null)
  }, [onNodeSelect])

  // ── Snapshot do workflow ───────────────────────────────────────
  const getWorkflowSnapshot = useCallback((): WorkflowData => ({
    steps: nodes.map(n => ({
      id:          n.id,
      type:        n.data.nodeType,
      componentId: n.data.componentId,
      label:       n.data.label,
      category:    n.type as keyof typeof NODE_CATEGORIES,
      position:    n.position,
      config:      n.data.config,
    })),
    connections: edges.map(e => ({
      id:          e.id,
      sourceId:    e.source,
      targetId:    e.target,
      isErrorPath: (e.data as any)?.isErrorPath ?? false,
    })),
  }), [nodes, edges])

  return (
    <div
      style={{ width: '100%', height: '100%', position: 'relative' }}
      onDragOver={onDragOver}
      onDrop={onDrop}
    >
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
        onNodeClick={onNodeClick}
        onPaneClick={onPaneClick}
        onInit={instance => { rfInstanceRef.current = instance }}
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
    </div>
  )
}

// ── Helpers ──────────────────────────────────────────────────────

function minimapNodeColor(node: Node): string {
  const MAP: Record<string, string> = {
    agent:   '#378ADD',
    control: '#7F77DD',
    data:    '#1D9E75',
    tool:    '#D85A30',
    vector:  '#BA7517',
    io:      '#888780',
  }
  return MAP[node.type ?? ''] ?? '#888780'
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
