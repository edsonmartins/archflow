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

import { AgentNode, ControlNode, DataNode, ToolNode, VectorNode, IONode } from './nodes/index'
import { FlowEdge }      from './edges/FlowEdge'
import { useFlowStore }  from './store/useFlowStore'
import { NODE_CATEGORIES } from './constants'
import type { FlowNodeData, WorkflowData } from './types'

// ── Registro de tipos ────────────────────────────────────────────
const nodeTypes: NodeTypes = {
  agent:   AgentNode,
  control: ControlNode,
  data:    DataNode,
  tool:    ToolNode,
  vector:  VectorNode,
  io:      IONode,
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

  const { executionState, setNodes: syncNodes, setEdges: syncEdges, nodes: storeNodes } = useFlowStore()

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

      const newNode: Node<FlowNodeData> = {
        id:       `node-${Date.now()}`,
        type:     nodeInfo.category,
        position,
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
        deleteKeyCode={readonly ? null : 'Backspace'}
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
  return wf.steps.map(step => ({
    id:       step.id,
    type:     step.category,
    position: step.position,
    data: {
      label:       step.label,
      componentId: step.componentId,
      nodeType:    step.type,
      status:      'idle',
      config:      step.config ?? {},
    },
  }))
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
