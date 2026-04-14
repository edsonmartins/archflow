import { describe, it, expect, beforeEach } from 'vitest'
import { useFlowStore } from '../useFlowStore'
import type { FlowNodeData } from '../../types'
import type { Node, Edge } from '@xyflow/react'

function makeNode(id: string, overrides?: Partial<FlowNodeData>): Node<FlowNodeData> {
  return {
    id,
    type: 'agent',
    position: { x: 100, y: 200 },
    data: {
      label: `Node ${id}`,
      componentId: 'agent',
      nodeType: 'agent',
      status: 'idle',
      config: {},
      ...overrides,
    },
  }
}

function makeEdge(id: string, source: string, target: string, errorPath = false): Edge {
  return {
    id,
    source,
    target,
    type: 'flow',
    data: { isErrorPath: errorPath },
  }
}

describe('useFlowStore', () => {
  beforeEach(() => {
    const store = useFlowStore.getState()
    store.setNodes([])
    store.setEdges([])
    store.clearSelection()
    useFlowStore.setState({
      isExecuting: false,
      executionId: null,
      executionState: {},
    })
  })

  describe('setNodes / setEdges', () => {
    it('stores nodes', () => {
      const nodes = [makeNode('n1'), makeNode('n2')]
      useFlowStore.getState().setNodes(nodes)
      expect(useFlowStore.getState().nodes).toHaveLength(2)
    })

    it('stores edges', () => {
      const edges = [makeEdge('e1', 'n1', 'n2')]
      useFlowStore.getState().setEdges(edges)
      expect(useFlowStore.getState().edges).toHaveLength(1)
    })
  })

  describe('selectNode / clearSelection', () => {
    it('stores selected node id and data', () => {
      const data: FlowNodeData = {
        label: 'Agent', componentId: 'agent', nodeType: 'agent',
        status: 'idle', config: { model: 'gpt-4o' },
      }
      useFlowStore.getState().selectNode('n1', data)
      expect(useFlowStore.getState().selectedNodeId).toBe('n1')
      expect(useFlowStore.getState().selectedNodeData).toBe(data)
    })

    it('clearSelection resets both fields', () => {
      useFlowStore.getState().selectNode('n1', { label: '', componentId: '', nodeType: '', status: 'idle', config: {} })
      useFlowStore.getState().clearSelection()
      expect(useFlowStore.getState().selectedNodeId).toBeNull()
      expect(useFlowStore.getState().selectedNodeData).toBeNull()
    })
  })

  describe('updateNodeConfig', () => {
    it('updates a config key on the target node', () => {
      useFlowStore.getState().setNodes([makeNode('n1'), makeNode('n2')])

      useFlowStore.getState().updateNodeConfig('n1', 'model', 'gpt-4o')

      const n1 = useFlowStore.getState().nodes.find(n => n.id === 'n1')!
      const n2 = useFlowStore.getState().nodes.find(n => n.id === 'n2')!
      expect(n1.data.config.model).toBe('gpt-4o')
      expect(n2.data.config.model).toBeUndefined()
    })

    it('preserves existing config keys', () => {
      useFlowStore.getState().setNodes([
        makeNode('n1', { config: { provider: 'anthropic', temperature: 0.7 } }),
      ])

      useFlowStore.getState().updateNodeConfig('n1', 'model', 'claude-sonnet-4-6')

      const config = useFlowStore.getState().nodes[0].data.config
      expect(config.provider).toBe('anthropic')
      expect(config.temperature).toBe(0.7)
      expect(config.model).toBe('claude-sonnet-4-6')
    })

    it('updates selectedNodeData when the selected node is modified', () => {
      useFlowStore.getState().setNodes([makeNode('n1')])
      useFlowStore.getState().selectNode('n1', useFlowStore.getState().nodes[0].data)

      useFlowStore.getState().updateNodeConfig('n1', 'agentPattern', 'rewoo')

      expect(useFlowStore.getState().selectedNodeData?.config.agentPattern).toBe('rewoo')
    })

    it('does NOT update selectedNodeData when a different node is modified', () => {
      useFlowStore.getState().setNodes([makeNode('n1'), makeNode('n2')])
      useFlowStore.getState().selectNode('n1', useFlowStore.getState().nodes[0].data)

      useFlowStore.getState().updateNodeConfig('n2', 'model', 'gpt-4o')

      expect(useFlowStore.getState().selectedNodeData?.config.model).toBeUndefined()
    })

    it('handles non-existent node id gracefully', () => {
      useFlowStore.getState().setNodes([makeNode('n1')])
      useFlowStore.getState().updateNodeConfig('nope', 'model', 'gpt-4o')
      expect(useFlowStore.getState().nodes[0].data.config.model).toBeUndefined()
    })
  })

  describe('updateNodeLabel', () => {
    it('updates the label on the target node', () => {
      useFlowStore.getState().setNodes([makeNode('n1')])
      useFlowStore.getState().updateNodeLabel('n1', 'My Agent')
      expect(useFlowStore.getState().nodes[0].data.label).toBe('My Agent')
    })

    it('syncs selectedNodeData when the selected node label changes', () => {
      useFlowStore.getState().setNodes([makeNode('n1')])
      useFlowStore.getState().selectNode('n1', useFlowStore.getState().nodes[0].data)

      useFlowStore.getState().updateNodeLabel('n1', 'Renamed')
      expect(useFlowStore.getState().selectedNodeData?.label).toBe('Renamed')
    })
  })

  describe('getCanvasSnapshot', () => {
    it('returns empty workflow when no nodes/edges', () => {
      const snapshot = useFlowStore.getState().getCanvasSnapshot()
      expect(snapshot.steps).toEqual([])
      expect(snapshot.connections).toEqual([])
    })

    it('maps nodes to steps with all fields', () => {
      useFlowStore.getState().setNodes([
        makeNode('n1', {
          label: 'Support Agent',
          componentId: 'agent',
          nodeType: 'agent',
          config: { model: 'gpt-4o', temperature: 0.5 },
        }),
      ])

      const snapshot = useFlowStore.getState().getCanvasSnapshot()
      expect(snapshot.steps).toHaveLength(1)

      const step = snapshot.steps[0]
      expect(step.id).toBe('n1')
      expect(step.type).toBe('agent')
      expect(step.componentId).toBe('agent')
      expect(step.label).toBe('Support Agent')
      expect(step.category).toBe('agent')
      expect(step.position).toEqual({ x: 100, y: 200 })
      expect(step.config).toEqual({ model: 'gpt-4o', temperature: 0.5 })
    })

    it('maps edges to connections with isErrorPath', () => {
      useFlowStore.getState().setNodes([makeNode('n1'), makeNode('n2'), makeNode('n3')])
      useFlowStore.getState().setEdges([
        makeEdge('e1', 'n1', 'n2', false),
        makeEdge('e2', 'n1', 'n3', true),
      ])

      const snapshot = useFlowStore.getState().getCanvasSnapshot()
      expect(snapshot.connections).toHaveLength(2)
      expect(snapshot.connections[0]).toEqual({
        id: 'e1', sourceId: 'n1', targetId: 'n2', isErrorPath: false,
      })
      expect(snapshot.connections[1]).toEqual({
        id: 'e2', sourceId: 'n1', targetId: 'n3', isErrorPath: true,
      })
    })

    it('reflects config changes made via updateNodeConfig', () => {
      useFlowStore.getState().setNodes([makeNode('n1')])
      useFlowStore.getState().updateNodeConfig('n1', 'agentPattern', 'plan-execute')
      useFlowStore.getState().updateNodeConfig('n1', 'maxTokens', 8192)

      const snapshot = useFlowStore.getState().getCanvasSnapshot()
      expect(snapshot.steps[0].config).toMatchObject({
        agentPattern: 'plan-execute',
        maxTokens: 8192,
      })
    })
  })

  describe('execution actions', () => {
    it('startExecution sets isExecuting and clears prior state', () => {
      useFlowStore.getState().startExecution('exec-1')
      const s = useFlowStore.getState()
      expect(s.isExecuting).toBe(true)
      expect(s.executionId).toBe('exec-1')
      expect(s.executionState).toEqual({})
    })

    it('updateNodeStatus records per-node execution state', () => {
      useFlowStore.getState().startExecution('exec-1')
      useFlowStore.getState().updateNodeStatus('n1', { status: 'running', startedAt: 1000 })
      expect(useFlowStore.getState().executionState.n1.status).toBe('running')
    })

    it('finishExecution clears isExecuting', () => {
      useFlowStore.getState().startExecution('exec-1')
      useFlowStore.getState().finishExecution()
      expect(useFlowStore.getState().isExecuting).toBe(false)
    })

    it('abortExecution marks running nodes as error', () => {
      useFlowStore.getState().startExecution('exec-1')
      useFlowStore.getState().updateNodeStatus('n1', { status: 'running', startedAt: 1000 })
      useFlowStore.getState().updateNodeStatus('n2', { status: 'success', startedAt: 900, durationMs: 100 })

      useFlowStore.getState().abortExecution()

      const s = useFlowStore.getState()
      expect(s.isExecuting).toBe(false)
      expect(s.executionState.n1.status).toBe('error')
      expect(s.executionState.n2.status).toBe('success')
    })
  })
})
