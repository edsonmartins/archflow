import { memo, useMemo } from 'react'
import { Handle, Position } from '@xyflow/react'
import { useMantineColorScheme } from '@mantine/core'
import { NODE_CATEGORIES, EXECUTION_STATUS_COLORS } from '../constants'
import { NodeIcon } from '../nodeIcons'
import type { FlowNodeData } from '../types'

/**
 * Canvas-facing node component inspired by modern AI workflow editors
 * (LangFlow, Dify, n8n): rounded card with a category accent stripe,
 * an iconic badge, a title + technical subtitle, and a preview chip
 * row showing the key configuration the user has wired up so the
 * canvas stays readable without opening the property panel.
 *
 * <p>The previous incarnation of this file leaned on classical flow-
 * chart silhouettes (diamond, parallelogram, cylinder, …). That looked
 * corporate and dated for an AI-native tool: a reader expected to see
 * <em>what the agent does</em>, not a decision diamond. The card form
 * factor here is now uniform — what changes per category is the
 * colour, the accent pattern and the icon — which is the vocabulary of
 * every serious AI flow tool today.</p>
 */

type CategoryKey = keyof typeof NODE_CATEGORIES

interface ShapedNodeProps {
  id:       string
  data:     FlowNodeData
  selected: boolean
  category: CategoryKey
}

const WIDTH = 240

/**
 * Derives an at-a-glance summary of the most important config values
 * the user has set, so the card can surface them without opening the
 * property panel. Each category chooses the handful of fields that
 * best describe the node.
 */
function configChips(category: CategoryKey, data: FlowNodeData): string[] {
  const cfg = (data.config ?? {}) as Record<string, unknown>
  const asStr = (v: unknown) => (v == null || v === '' ? null : String(v))
  const chips: (string | null)[] = []

  switch (category) {
    case 'agent': {
      if (data.nodeType === 'assistant' || data.nodeType === 'agent') {
        chips.push(asStr(cfg.agentTypeId) ?? asStr(cfg.assistantTypeId))
      }
      chips.push(
          asStr(cfg.provider) && asStr(cfg.model)
              ? `${cfg.provider} · ${cfg.model}`
              : asStr(cfg.model))
      chips.push(asStr(cfg.agentPattern))
      const temp = cfg.temperature
      if (typeof temp === 'number') chips.push(`T=${temp}`)
      break
    }
    case 'tool':
      if (data.nodeType === 'mcp-tool') {
        chips.push(asStr(cfg.mcpServer))
        chips.push(asStr(cfg.mcpTool))
      } else {
        chips.push(asStr(cfg.toolId))
        chips.push(asStr(cfg.onError) === 'retry'
            ? `retry×${asStr(cfg.retryMaxAttempts) ?? 3}` : asStr(cfg.onError))
      }
      break
    case 'vector': {
      if (data.nodeType === 'rag') {
        chips.push(asStr(cfg.embeddingProvider))
        chips.push(asStr(cfg.vectorBackend))
        const k = cfg.retrieverMaxResults
        if (typeof k === 'number') chips.push(`top-${k}`)
      } else if (data.nodeType === 'embedding') {
        chips.push(asStr(cfg.embeddingProvider))
        chips.push(asStr(cfg.embeddingModel))
      } else if (data.nodeType === 'memory') {
        chips.push(asStr(cfg.memoryBackend))
        const max = cfg.memoryMaxMessages
        if (typeof max === 'number') chips.push(`max ${max}`)
      } else {
        chips.push(asStr(cfg.vectorBackend))
      }
      break
    }
    case 'control': {
      if (data.nodeType === 'condition') chips.push(asStr(cfg.conditionExpression))
      if (data.nodeType === 'switch')    chips.push(asStr(cfg.switchExpression))
      if (data.nodeType === 'parallel')  chips.push(asStr(cfg.parallelStrategy))
      if (data.nodeType === 'loop') {
        chips.push(asStr(cfg.loopCollection))
        if (cfg.loopParallel) chips.push('parallel')
      }
      if (data.nodeType === 'approval') {
        chips.push(`timeout ${asStr(cfg.approvalTimeoutMinutes) ?? 30}m`)
      }
      if (data.nodeType === 'subflow') chips.push(asStr(cfg.subflowId))
      break
    }
    case 'data':
      chips.push(asStr(cfg.transformExpression)?.split('\n')[0]?.slice(0, 40) ?? null)
      break
    case 'io':
      chips.push(asStr(cfg.promptOutputVar)
          ?? (cfg.promptChunkOrder !== undefined ? `order ${cfg.promptChunkOrder}` : null))
      break
    case 'knowledge':
      chips.push(asStr(cfg.skillsOperation))
      chips.push(asStr(cfg.skillName))
      break
    case 'integration':
      chips.push(asStr(cfg.linktorConversationExpr))
      chips.push(asStr(cfg.linktorTargetUser))
      break
  }

  return chips.filter((x): x is string => x != null && x.length > 0).slice(0, 3)
}

/**
 * A subtle texture layered inside the icon badge. Keeps every card
 * the same silhouette but still hints at what kind of thing it is.
 */
function accentPattern(category: CategoryKey): string | null {
  switch (category) {
    case 'agent':       return 'radial-gradient(circle at 30% 20%, rgba(255,255,255,0.35), transparent 50%)'
    case 'vector':      return 'repeating-linear-gradient(180deg, rgba(255,255,255,0.18) 0 2px, transparent 2px 6px)'
    case 'tool':        return 'repeating-linear-gradient(45deg, rgba(255,255,255,0.15) 0 4px, transparent 4px 8px)'
    case 'control':     return 'linear-gradient(135deg, rgba(255,255,255,0.25), transparent 60%)'
    case 'data':        return 'repeating-linear-gradient(90deg, rgba(255,255,255,0.18) 0 3px, transparent 3px 6px)'
    case 'io':          return 'linear-gradient(90deg, rgba(255,255,255,0.3), transparent)'
    case 'knowledge':   return 'linear-gradient(180deg, rgba(255,255,255,0.3), transparent 70%)'
    case 'integration': return 'radial-gradient(circle at 70% 70%, rgba(255,255,255,0.3), transparent 50%)'
    default:            return null
  }
}

export const ShapedNode = memo(function ShapedNode({
  data, selected, category,
}: ShapedNodeProps) {
  const cat = NODE_CATEGORIES[category]
  const exec = data.executionStatus ?? 'idle'
  const execColors = EXECUTION_STATUS_COLORS[exec]
  const chips = useMemo(() => configChips(category, data), [category, data])
  const accent = accentPattern(category)

  // Theme-aware palette: the card floats on the React Flow canvas
  // which is dark in dark-mode, so the card background has to switch
  // too — otherwise bright white tiles blast the user's retinas.
  const { colorScheme } = useMantineColorScheme()
  const isDark = colorScheme === 'dark'
  const palette = {
    cardBg:         isDark ? '#1E2635' : '#FFFFFF',
    cardShimmer:    isDark ? 0.18 : 0.6,          // multiplier on the category wash
    idleBorder:     isDark ? 'rgba(255,255,255,0.08)' : 'rgba(15,23,42,0.1)',
    title:          isDark ? '#F1F5F9' : '#0F172A',
    subtitle:       isDark ? '#94A3B8' : '#64748B',
    chipBg:         isDark ? `${cat.color}33` : cat.colorLight,
    chipText:       isDark ? cat.colorLight   : cat.colorDark,
    shadowColor:    isDark ? 'rgba(0,0,0,0.45)' : 'rgba(15,23,42,0.10)',
    shadowColorSub: isDark ? 'rgba(0,0,0,0.25)' : 'rgba(15,23,42,0.06)',
  }

  const stripeGradient = `linear-gradient(180deg, ${cat.color}, ${cat.colorDark})`
  const borderColor = selected
    ? cat.color
    : exec === 'error'   ? '#DC2626'
    : exec === 'success' ? '#059669'
    : exec === 'running' ? '#D97706'
    : palette.idleBorder

  return (
    <div
      style={{
        position:     'relative',
        width:        WIDTH,
        background:   palette.cardBg,
        borderRadius: 12,
        border:       `1.5px solid ${borderColor}`,
        boxShadow: selected
            ? `0 0 0 4px ${cat.color}22, 0 6px 18px ${palette.shadowColor}`
            : `0 2px 8px ${palette.shadowColorSub}`,
        overflow:     'hidden',
        fontFamily:   "'DM Sans', 'Inter', system-ui, sans-serif",
        userSelect:   'none',
        cursor:       'pointer',
        transition:   'border-color 0.18s ease, box-shadow 0.18s ease, transform 0.18s ease',
        transform:    selected ? 'translateY(-1px)' : 'translateY(0)',
      }}
    >
      {/* Left accent stripe — the single most identifiable visual cue
          for the category. Thin, crisp, gradient. */}
      <div
        style={{
          position:   'absolute',
          left:       0,
          top:        0,
          bottom:     0,
          width:      4,
          background: stripeGradient,
        }}
      />

      {/* Top shimmer overlay — a barely-there wash of the category
          colour across the card's top 40%, giving every node a slight
          identity without painting the whole thing. In dark mode the
          wash switches to the category's mid-tone at low opacity so
          the gradient reads as a glow rather than a pale smear. */}
      <div
        style={{
          position:      'absolute',
          left:          4,
          right:         0,
          top:           0,
          height:        '40%',
          background: isDark
              ? `linear-gradient(180deg, ${cat.color}30 0%, transparent 100%)`
              : `linear-gradient(180deg, ${cat.colorLight}60 0%, transparent 100%)`,
          pointerEvents: 'none',
        }}
      />

      {/* ── Header row ──────────────────────────────────────────── */}
      <div
        style={{
          display:    'flex',
          alignItems: 'center',
          gap:        10,
          padding:    '11px 12px 9px 14px',
          position:   'relative',
        }}
      >
        {/* Icon badge: category gradient + accent pattern + vector
            Tabler icon. No emojis here — every icon renders as proper
            SVG and scales with zoom. */}
        <div
          style={{
            width:          34,
            height:         34,
            borderRadius:   category === 'integration' ? '50%' : 9,
            background:     stripeGradient,
            backgroundImage: accent ? `${accent}, ${stripeGradient}` : stripeGradient,
            color:          '#fff',
            display:        'flex',
            alignItems:     'center',
            justifyContent: 'center',
            flexShrink:     0,
            boxShadow:      `0 3px 8px ${cat.color}55, inset 0 1px 0 rgba(255,255,255,0.25)`,
          }}
        >
          <NodeIcon componentId={data.componentId} size={18} stroke={2} />
        </div>
        <div style={{ flex: 1, minWidth: 0 }}>
          <div
            style={{
              fontSize:     13,
              fontWeight:   600,
              color:        palette.title,
              lineHeight:   1.25,
              overflow:     'hidden',
              textOverflow: 'ellipsis',
              whiteSpace:   'nowrap',
            }}
          >
            {data.label}
          </div>
          <div
            style={{
              fontSize:      10,
              color:         palette.subtitle,
              fontFamily:    "'DM Mono', ui-monospace, 'SF Mono', Menlo, monospace",
              marginTop:     2,
              letterSpacing: 0.4,
              textTransform: 'uppercase',
              overflow:      'hidden',
              textOverflow:  'ellipsis',
              whiteSpace:    'nowrap',
            }}
          >
            {(data.nodeType ?? data.componentId ?? '').replace(/_/g, ' ')}
            <span style={{ marginLeft: 6, color: cat.color }}>· {cat.label.split(' ')[0]}</span>
          </div>
        </div>
      </div>

      {/* ── Config preview chips ────────────────────────────────
          Surfaces the handful of fields the user has configured so the
          canvas stays readable without opening PropertyPanel. Renders
          nothing when no chips are available, keeping the card
          compact. */}
      {chips.length > 0 && (
        <div
          style={{
            display:  'flex',
            flexWrap: 'wrap',
            gap:      4,
            padding:  '0 12px 10px 14px',
          }}
        >
          {chips.map((chip, i) => (
            <span
              key={i}
              style={{
                padding:      '2px 7px',
                borderRadius: 6,
                background:   palette.chipBg,
                color:        palette.chipText,
                fontSize:     10,
                fontWeight:   500,
                fontFamily:   "'DM Mono', ui-monospace, monospace",
                maxWidth:     100,
                overflow:     'hidden',
                textOverflow: 'ellipsis',
                whiteSpace:   'nowrap',
              }}
              title={chip}
            >
              {chip}
            </span>
          ))}
        </div>
      )}

      {/* Execution status pill in the top-right corner. */}
      {exec !== 'idle' && (
        <div
          style={{
            position:   'absolute',
            top:        -7,
            right:      -7,
            background: execColors.bg,
            border:     `1.5px solid ${execColors.border}`,
            borderRadius:   999,
            padding:    '2px 8px',
            fontSize:   10,
            fontWeight: 600,
            color:      execColors.text,
            animation:  exec === 'running' ? 'pulse 1.5s ease-in-out infinite' : 'none',
            pointerEvents: 'none',
          }}
        >
          {exec === 'running' ? '◌' : exec === 'success' ? '✓' : '✕'}
          {data.executionMs != null && exec === 'success' ? ` ${Math.round(data.executionMs)}ms` : ''}
        </div>
      )}

      {/* React Flow handles — small, minimal, coloured per category
          for visual grouping. Source handle is filled to signal flow
          direction at a glance. */}
      <Handle
        type="target"
        position={Position.Left}
        style={{
          width:        11,
          height:       11,
          background:   palette.cardBg,
          border:       `2.5px solid ${cat.color}`,
          borderRadius: '50%',
          left:         -6,
          top:          '50%',
          transform:    'translateY(-50%)',
          boxShadow:    `0 2px 6px ${cat.color}44`,
          zIndex:       2,
        }}
      />
      <Handle
        type="source"
        position={Position.Right}
        style={{
          width:        11,
          height:       11,
          background:   cat.color,
          border:       `2.5px solid #FFFFFF`,
          borderRadius: '50%',
          right:        -6,
          top:          '50%',
          transform:    'translateY(-50%)',
          boxShadow:    `0 0 0 1px ${cat.color}44, 0 2px 6px ${cat.color}55`,
          zIndex:       2,
        }}
      />
    </div>
  )
})
