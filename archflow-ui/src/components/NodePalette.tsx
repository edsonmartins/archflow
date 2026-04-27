import { useState, useMemo }  from 'react'
import { ScrollArea, TextInput, Accordion } from '@mantine/core'
import { IconSearch }          from '@tabler/icons-react'
import { useTranslation }      from 'react-i18next'
import { PALETTE_NODES, NODE_CATEGORIES } from './FlowCanvas/constants'
import { NodeIcon }            from './FlowCanvas/nodeIcons'
import type { PaletteNode }    from './FlowCanvas/types'

type CategoryKey = keyof typeof NODE_CATEGORIES

/**
 * Compact (30×30) icon badge matching the canvas card aesthetic:
 * rounded square with a vertical category stripe on the left edge and
 * a gradient fill — same visual vocabulary as
 * {@link ../FlowCanvas/nodes/ShapedNode} so users see at a glance
 * which category a draggable item belongs to without squinting at
 * silhouettes.
 */
function PaletteShapeIcon({
  category, componentId,
}: { category: CategoryKey; componentId: string }) {
  const cat = NODE_CATEGORIES[category]
  const stripeGradient = `linear-gradient(180deg, ${cat.color}, ${cat.colorDark})`
  const isPill = category === 'integration'
  return (
    <div
      style={{
        position:       'relative',
        width:          30,
        height:         30,
        flexShrink:     0,
        borderRadius:   isPill ? 15 : 8,
        background:     stripeGradient,
        color:          '#fff',
        display:        'flex',
        alignItems:     'center',
        justifyContent: 'center',
        boxShadow:      `0 2px 6px ${cat.color}44, inset 0 1px 0 rgba(255,255,255,0.25)`,
        overflow:       'hidden',
      }}
    >
      {/* Inner accent to give the badge depth — matches the canvas icon. */}
      <div
        style={{
          position: 'absolute', inset: 0,
          background: 'radial-gradient(circle at 28% 22%, rgba(255,255,255,0.35), transparent 55%)',
          pointerEvents: 'none',
        }}
      />
      <span style={{ position: 'relative', display: 'flex' }}>
        <NodeIcon componentId={componentId} size={16} stroke={2} />
      </span>
    </div>
  )
}

export function NodePalette() {
  const [search, setSearch] = useState('')
  const { t } = useTranslation()

  // Lookup a localized label/description for a palette node, falling
  // back to the English string baked into constants.ts when a
  // translation is missing (e.g. for user-added custom node types that
  // weren't declared in the locale file).
  const nodeLabel = (n: PaletteNode) => t(`nodes.${n.componentId}.label`, { defaultValue: n.label })
  const nodeDesc  = (n: PaletteNode) => t(`nodes.${n.componentId}.description`, { defaultValue: n.description })
  const catLabel  = (c: CategoryKey) => t(`categories.${c}`, { defaultValue: NODE_CATEGORIES[c].label })

  const filtered = useMemo(() => {
    const q = search.toLowerCase().trim()
    if (!q) return PALETTE_NODES
    return PALETTE_NODES.filter(n => {
      const node = n as unknown as PaletteNode
      return (
        nodeLabel(node).toLowerCase().includes(q) ||
        nodeDesc(node).toLowerCase().includes(q) ||
        catLabel(node.category).toLowerCase().includes(q) ||
        node.category.toLowerCase().includes(q)
      )
    })
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [search, t])

  // Agrupar por categoria
  const byCategory = useMemo(() => {
    const map = new Map<string, PaletteNode[]>()
    for (const node of filtered) {
      const list = map.get(node.category) ?? []
      list.push(node as unknown as PaletteNode)
      map.set(node.category, list)
    }
    return map
  }, [filtered])

  const categoryOrder = [
    'agent', 'control', 'data', 'tool', 'vector', 'io', 'knowledge', 'integration', 'annotation',
  ] as const

  return (
    <div
      style={{
        display:        'flex',
        flexDirection:  'column',
        height:         '100%',
        background:     'var(--color-background-secondary)',
        borderRight:    '0.5px solid var(--color-border-tertiary)',
      }}
    >
      {/* Search */}
      <div style={{ padding: '10px 12px', borderBottom: '0.5px solid var(--color-border-tertiary)' }}>
        <TextInput
          size="xs"
          placeholder={t('editor.palette.search')}
          leftSection={<IconSearch size={13} />}
          value={search}
          onChange={e => setSearch(e.currentTarget.value)}
          styles={{
            input: {
              background:   'var(--color-background-primary)',
              border:       '0.5px solid var(--color-border-tertiary)',
              borderRadius: 7,
              fontSize:     12,
            },
          }}
        />
      </div>

      {/* Lista */}
      <ScrollArea style={{ flex: 1 }} p="xs">
        {search
          ? /* Resultados planos */
            filtered.map(node => (
              <DraggableNode key={node.componentId} node={node as unknown as PaletteNode}
                             label={nodeLabel(node as unknown as PaletteNode)}
                             description={nodeDesc(node as unknown as PaletteNode)} />
            ))
          : /* Agrupado por categoria */
            categoryOrder.map(cat => {
              const nodes = byCategory.get(cat)
              if (!nodes?.length) return null
              return (
                <div key={cat} style={{ marginBottom: 4 }}>
                  <div
                    style={{
                      fontSize:      10,
                      fontWeight:    500,
                      color:         'var(--color-text-tertiary)',
                      letterSpacing: '0.07em',
                      textTransform: 'uppercase',
                      padding:       '8px 4px 4px',
                    }}
                  >
                    {catLabel(cat)}
                  </div>
                  {nodes.map(n => (
                    <DraggableNode key={n.componentId} node={n}
                                   label={nodeLabel(n)} description={nodeDesc(n)} />
                  ))}
                </div>
              )
            })}
      </ScrollArea>
    </div>
  )
}

// ── Item arrastável da palette ───────────────────────────────────
function DraggableNode({ node, label, description }: {
  node: PaletteNode; label: string; description: string
}) {
  const cat = NODE_CATEGORIES[node.category]

  const onDragStart = (event: React.DragEvent) => {
    event.dataTransfer.setData(
      'application/archflow-node',
      JSON.stringify({
        type:        node.type,
        componentId: node.componentId,
        label,
        category:    node.category,
      })
    )
    event.dataTransfer.effectAllowed = 'move'
  }

  return (
    <div
      draggable
      onDragStart={onDragStart}
      style={{
        display:     'flex',
        alignItems:  'center',
        gap:         8,
        padding:     '7px 8px',
        borderRadius: 7,
        cursor:      'grab',
        border:      '0.5px solid transparent',
        marginBottom: 2,
        transition:  'background 0.1s, border-color 0.1s',
      }}
      onMouseEnter={e => {
        const el = e.currentTarget as HTMLElement
        el.style.background   = 'var(--color-background-primary)'
        el.style.borderColor  = 'var(--color-border-tertiary)'
      }}
      onMouseLeave={e => {
        const el = e.currentTarget as HTMLElement
        el.style.background  = 'transparent'
        el.style.borderColor = 'transparent'
      }}
    >
      <PaletteShapeIcon category={node.category} componentId={node.componentId} />
      <div style={{ minWidth: 0 }}>
        <div style={{ fontSize: 12, fontWeight: 500, color: 'var(--color-text-primary)' }}>
          {label}
        </div>
        <div
          style={{
            fontSize:     11,
            color:        'var(--color-text-tertiary)',
            overflow:     'hidden',
            textOverflow: 'ellipsis',
            whiteSpace:   'nowrap',
          }}
        >
          {description}
        </div>
      </div>
    </div>
  )
}
