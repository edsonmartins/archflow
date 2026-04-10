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
    : 'var(--color-border-secondary)'

  const boxShadow = selected
    ? `0 0 0 2px ${cat.color}40`
    : 'none'

  return (
    <div
      style={{
        minWidth:        160,
        maxWidth:        220,
        background:      'var(--color-background-primary)',
        border:          `1.5px solid ${borderColor}`,
        borderRadius:    10,
        boxShadow,
        transition:      'border-color 0.15s, box-shadow 0.15s',
        fontFamily:      'var(--font-sans)',
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
          background:  'var(--color-background-primary)',
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
          padding:       '8px 10px 6px',
          borderBottom:  '0.5px solid var(--color-border-tertiary)',
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
              color:        'var(--color-text-primary)',
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
              color:      'var(--color-text-tertiary)',
              fontFamily: 'var(--font-mono)',
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
          background:  'var(--color-background-primary)',
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
    running: { icon: '◌', text: 'Running...',                           bg: '#FAEEDA', fg: '#854F0B' },
    success: { icon: '✓', text: durationMs ? `${durationMs}ms` : 'Done', bg: '#E1F5EE', fg: '#085041' },
    error:   { icon: '✕', text: 'Error',                                bg: '#FAECE7', fg: '#712B13' },
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
