import { useState } from 'react'
import { NodeResizer, type Node, type NodeProps } from '@xyflow/react'
import type { FlowNodeData } from '../types'
import { useFlowStore } from '../store/useFlowStore'

/**
 * Sticky-note style annotation, like the yellow comment cards in n8n.
 *
 * <p>No connection handles, freely resizable, single editable text
 * area. Stored as a regular React Flow node with {@code nodeType =
 * "sticky-note"} so it round-trips through the existing workflow JSON
 * without any backend changes.</p>
 */
export function StickyNoteNode({ id, data, selected }: NodeProps<Node<FlowNodeData>>) {
    const { updateNodeConfig } = useFlowStore()
    const text = (data.config?.text as string) ?? ''
    const color = (data.config?.color as string) ?? '#FEF3C7' // yellow
    const [editing, setEditing] = useState(false)

    return (
        <div
            style={{
                position: 'relative',
                width: '100%',
                height: '100%',
                minWidth: 160,
                minHeight: 100,
                background: color,
                border: `1px solid ${selected ? '#D97706' : 'rgba(0,0,0,0.08)'}`,
                borderRadius: 6,
                padding: 12,
                boxShadow: selected
                    ? '0 4px 12px rgba(0,0,0,0.12)'
                    : '0 1px 3px rgba(0,0,0,0.08)',
                fontSize: 13,
                lineHeight: 1.45,
                color: '#1F2937',
                fontFamily: 'var(--font-sans, system-ui, sans-serif)',
                cursor: editing ? 'text' : 'move',
            }}
            onDoubleClick={() => setEditing(true)}
        >
            <NodeResizer
                isVisible={selected}
                minWidth={120}
                minHeight={80}
                lineStyle={{ borderColor: '#D97706' }}
                handleStyle={{ background: '#D97706', width: 6, height: 6 }}
            />
            {editing ? (
                <textarea
                    autoFocus
                    value={text}
                    onChange={e => updateNodeConfig(id, 'text', e.currentTarget.value)}
                    onBlur={() => setEditing(false)}
                    onKeyDown={e => {
                        if (e.key === 'Escape') {
                            e.preventDefault()
                            setEditing(false)
                        }
                    }}
                    style={{
                        width: '100%',
                        height: '100%',
                        background: 'transparent',
                        border: 'none',
                        outline: 'none',
                        resize: 'none',
                        font: 'inherit',
                        color: 'inherit',
                    }}
                />
            ) : (
                <div style={{ whiteSpace: 'pre-wrap', wordBreak: 'break-word' }}>
                    {text || <span style={{ color: 'rgba(0,0,0,0.35)', fontStyle: 'italic' }}>Double-click to edit</span>}
                </div>
            )}
        </div>
    )
}
