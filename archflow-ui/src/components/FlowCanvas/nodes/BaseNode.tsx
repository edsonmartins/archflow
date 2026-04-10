import { memo }          from 'react'
import { Handle, Position } from '@xyflow/react'
import { NODE_CATEGORIES, EXECUTION_STATUS_COLORS } from '../constants'
import type { FlowNodeData } from '../types'

interface BaseNodeProps {
  id:       string
  data:     FlowNodeData
  selected: boolean
  category: keyof typeof NODE_CATEGORIES
}

export const BaseNode = memo(function BaseNode({
  id, data, selected, category,
}: BaseNodeProps) {
  const cat    = NODE_CATEGORIES[category]
  const exec   = data.executionStatus ?? 'idle'
  const colors = EXECUTION_STATUS_COLORS[exec]

  const borderColor = selected
    ? cat.color
    : exec !== 'idle'
    ? colors.border
    : 'rgba(0,0,0,0.13)'

  const boxShadow = selected
    ? `0 0 0 3px ${cat.color}30`
    : 'none'

  return (
    <div
      style={{
        minWidth:        164,
        maxWidth:        220,
        background:      '#FFFFFF',
        border:          `1.5px solid ${borderColor}`,
        borderRadius:    10,
        boxShadow,
        transition:      'border-color 0.15s, box-shadow 0.15s',
        fontFamily:      "'DM Sans', sans-serif",
        fontSize:        13,
        userSelect:      'none',
      }}
    >
      {/* Handle de entrada */}
      <Handle
        type="target"
        position={Position.Left}
        style={{
          width:       10,
          height:      10,
          background:  '#FFFFFF',
          border:      `2px solid ${cat.color}`,
          borderRadius: '50%',
          left:        -5,
        }}
      />

      {/* Cabeçalho */}
      <div
        style={{
          display:       'flex',
          alignItems:    'center',
          gap:           7,
          padding:       '8px 10px 7px',
          borderBottom:  '1px solid rgba(0,0,0,0.07)',
        }}
      >
        {/* Ícone colorido */}
        <div
          style={{
            width:        22,
            height:       22,
            borderRadius: 5,
            background:   cat.colorLight,
            display:      'flex',
            alignItems:   'center',
            justifyContent: 'center',
            fontSize:     11,
            flexShrink:   0,
          }}
        >
          {data.config?.icon as string ?? '●'}
        </div>

        <div style={{ flex: 1, minWidth: 0 }}>
          <div
            style={{
              fontWeight:   500,
              fontSize:     12,
              color:        '#111827',
              overflow:     'hidden',
              textOverflow: 'ellipsis',
              whiteSpace:   'nowrap',
            }}
          >
            {data.label}
          </div>
          <div
            style={{
              fontSize:   10,
              color:      '#9CA3AF',
              fontFamily: "'DM Mono', monospace",
              marginTop:  1,
            }}
          >
            {data.nodeType.toUpperCase()}
          </div>
        </div>
      </div>

      {/* Badge de execução */}
      <div style={{ padding: '5px 10px 7px' }}>
        <ExecutionBadge status={exec} durationMs={data.executionMs} color={cat.color} />
      </div>

      {/* Handle de saída */}
      <Handle
        type="source"
        position={Position.Right}
        style={{
          width:       10,
          height:      10,
          background:  '#FFFFFF',
          border:      `2px solid ${cat.color}`,
          borderRadius: '50%',
          right:       -5,
        }}
      />
    </div>
  )
})

// ── Badge de status de execução ──────────────────────────────────
function ExecutionBadge({
  status, durationMs, color,
}: { status: string; durationMs?: number; color: string }) {
  if (status === 'idle') {
    return (
      <div style={{ height: 18 }} />
    )
  }

  const MAP = {
    running: { icon: '◌', text: 'Running…',                                  bg: '#FFFBEB', fg: '#D97706' },
    success: { icon: '✓', text: durationMs ? `${Math.round(durationMs)}ms` : 'Done', bg: '#ECFDF5', fg: '#059669' },
    error:   { icon: '✕', text: 'Error',                                     bg: '#FEF2F2', fg: '#DC2626' },
  } as const

  const s = MAP[status as keyof typeof MAP]
  if (!s) return null

  return (
    <div
      style={{
        display:      'inline-flex',
        alignItems:   'center',
        gap:          4,
        padding:      '2px 8px',
        borderRadius: 10,
        background:   s.bg,
        color:        s.fg,
        fontSize:     10,
        fontWeight:   500,
        animation:    status === 'running' ? 'pulse 1.5s ease-in-out infinite' : 'none',
      }}
    >
      <span>{s.icon}</span>
      <span>{s.text}</span>
    </div>
  )
}
