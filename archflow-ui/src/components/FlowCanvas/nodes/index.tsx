import { type Node, type NodeProps } from '@xyflow/react'
import { ShapedNode }     from './ShapedNode'
import { NODE_CATEGORIES, PALETTE_NODES } from '../constants'
import type { FlowNodeData } from '../types'

/**
 * Factory producing a per-category React Flow node component. Each
 * component looks up the palette entry so the custom silhouette can
 * still show the entry's icon. Registered on {@code FlowCanvas.tsx}
 * under the matching type name.
 */
function makeNode(category: keyof typeof NODE_CATEGORIES) {
  return function CategoryNode({ id, data, selected }: NodeProps<Node<FlowNodeData>>) {
    const paletteEntry = PALETTE_NODES.find(p => p.componentId === data.componentId)
    const icon = paletteEntry?.icon ?? '●'

    return (
      <ShapedNode
        id={id}
        data={{ ...data, config: { ...data.config, icon } }}
        selected={selected ?? false}
        category={category}
      />
    )
  }
}

export const AgentNode       = makeNode('agent')
export const ControlNode     = makeNode('control')
export const DataNode        = makeNode('data')
export const ToolNode        = makeNode('tool')
export const VectorNode      = makeNode('vector')
export const IONode          = makeNode('io')
export const KnowledgeNode   = makeNode('knowledge')
export const IntegrationNode = makeNode('integration')

// Auxiliary canvas shapes (n8n-style annotations)
export { StickyNoteNode }     from './StickyNote'
export { GroupFrameNode }     from './GroupFrame'
export { SectionDividerNode } from './SectionDivider'
