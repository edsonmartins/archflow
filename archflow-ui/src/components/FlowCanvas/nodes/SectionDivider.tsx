import { useState } from 'react'
import { NodeResizer, type Node, type NodeProps } from '@xyflow/react'
import type { FlowNodeData } from '../types'
import { useFlowStore } from '../store/useFlowStore'

/**
 * Horizontal section divider with a centered label, used to split the
 * canvas into named regions ("Pre-flight checks", "Main pipeline",
 * "Post-processing"). Has no handles and renders behind regular nodes
 * in z-order — purely visual scaffolding for large workflows.
 */
export function SectionDividerNode({ id, data, selected }: NodeProps<Node<FlowNodeData>>) {
    const { updateNodeConfig } = useFlowStore()
    const label = (data.config?.label as string) ?? data.label ?? 'Section'
    const color = (data.config?.color as string) ?? '#0D9488'
    const [editing, setEditing] = useState(false)

    return (
        <div
            style={{
                position: 'relative',
                width: '100%',
                height: '100%',
                minWidth: 320,
                minHeight: 36,
                display: 'flex',
                alignItems: 'center',
                gap: 12,
                fontFamily: 'var(--font-sans, system-ui, sans-serif)',
                pointerEvents: 'all',
            }}
        >
            <NodeResizer
                isVisible={selected}
                minWidth={240}
                minHeight={36}
                lineStyle={{ borderColor: color }}
                handleStyle={{ background: color, width: 6, height: 6 }}
            />
            <div
                onDoubleClick={() => setEditing(true)}
                style={{
                    fontSize: 12,
                    fontWeight: 600,
                    color,
                    letterSpacing: '0.04em',
                    textTransform: 'uppercase',
                    flexShrink: 0,
                    cursor: editing ? 'text' : 'pointer',
                    padding: '4px 10px',
                    background: `${color}14`,
                    borderRadius: 4,
                    border: `1px solid ${color}33`,
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
                            width: Math.max(80, label.length * 8),
                            textTransform: 'uppercase',
                        }}
                    />
                ) : label}
            </div>
            <div style={{ flex: 1, height: 1, background: `${color}55`, borderTop: `1px dashed ${color}` }} />
        </div>
    )
}
