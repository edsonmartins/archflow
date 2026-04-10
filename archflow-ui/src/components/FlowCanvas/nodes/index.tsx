import { type NodeProps } from '@xyflow/react'
import { BaseNode }       from './BaseNode'
import { PALETTE_NODES }  from '../constants'
import type { FlowNodeData } from '../types'

// ── Fábrica: cria o nó com ícone correto injetado ────────────────
function makeNode(category: 'agent' | 'control' | 'data' | 'tool' | 'vector' | 'io') {
  return function CategoryNode({ id, data, selected }: NodeProps<FlowNodeData>) {
    const paletteEntry = PALETTE_NODES.find(p => p.componentId === data.componentId)
    const icon = paletteEntry?.icon ?? '●'

    return (
      <BaseNode
        id={id}
        data={{ ...data, config: { ...data.config, icon } }}
        selected={selected ?? false}
        category={category}
      />
    )
  }
}

export const AgentNode   = makeNode('agent')
export const ControlNode = makeNode('control')
export const DataNode    = makeNode('data')
export const ToolNode    = makeNode('tool')
export const VectorNode  = makeNode('vector')
export const IONode      = makeNode('io')
