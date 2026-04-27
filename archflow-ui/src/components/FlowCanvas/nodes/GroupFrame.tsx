import { useState } from 'react'
import { NodeResizer, type NodeProps } from '@xyflow/react'
import type { FlowNodeData } from '../types'
import { useFlowStore } from '../store/useFlowStore'

/**
 * Group / frame node, like the dashed bounding box in n8n that wraps
 * a logical block of steps. Renders as a translucent rounded rectangle
 * with a label in the top-left corner; nodes positioned inside it stay
 * visually grouped but are NOT actually parented in React Flow (we keep
 * the workflow graph flat to preserve YAML round-trip simplicity).
 */
export function GroupFrameNode({ id, data, selected }: NodeProps<FlowNodeData>) {
    const { updateNodeConfig } = useFlowStore()
    const label = (data.config?.label as string) ?? data.label ?? 'Group'
    const color = (data.config?.color as string) ?? '#7C3AED'
    const [editing, setEditing] = useState(false)

    return (
        <div
            style={{
                position: 'relative',
                width: '100%',
                height: '100%',
                minWidth: 240,
                minHeight: 160,
                background: `${color}0D`, // ~5% alpha
                border: `2px dashed ${selected ? color : `${color}66`}`,
                borderRadius: 12,
                pointerEvents: 'all',
                fontFamily: 'var(--font-sans, system-ui, sans-serif)',
            }}
        >
            <NodeResizer
                isVisible={selected}
                minWidth={200}
                minHeight={140}
                lineStyle={{ borderColor: color }}
                handleStyle={{ background: color, width: 6, height: 6 }}
            />
            <div
                onDoubleClick={() => setEditing(true)}
                style={{
                    position: 'absolute',
                    top: 8,
                    left: 12,
                    padding: '2px 10px',
                    fontSize: 11,
                    fontWeight: 600,
                    letterSpacing: '0.04em',
                    textTransform: 'uppercase',
                    color: '#fff',
                    background: color,
                    borderRadius: 4,
                    cursor: editing ? 'text' : 'pointer',
                    userSelect: editing ? 'text' : 'none',
                }}
            >
                {editing ? (
                    <input
                        autoFocus
                        value={label}
                        onChange={e => updateNodeConfig(id, 'label', e.currentTarget.value)}
                        onBlur={() => setEditing(false)}
                        onKeyDown={e => { if (e.key === 'Enter' || e.key === 'Escape') setEditing(false) }}
                        style={{
                            background: 'transparent',
                            border: 'none',
                            outline: 'none',
                            color: 'inherit',
                            font: 'inherit',
                            width: Math.max(60, label.length * 7),
                            textTransform: 'uppercase',
                        }}
                    />
                ) : label}
            </div>
        </div>
    )
}
