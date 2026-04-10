import { useState, useMemo }  from 'react'
import { ScrollArea, TextInput, Accordion } from '@mantine/core'
import { IconSearch }          from '@tabler/icons-react'
import { PALETTE_NODES, NODE_CATEGORIES } from './FlowCanvas/constants'
import type { PaletteNode }    from './FlowCanvas/types'

export function NodePalette() {
  const [search, setSearch] = useState('')

  const filtered = useMemo(() => {
    const q = search.toLowerCase().trim()
    if (!q) return PALETTE_NODES
    return PALETTE_NODES.filter(
      n =>
        n.label.toLowerCase().includes(q) ||
        n.description.toLowerCase().includes(q) ||
        n.category.toLowerCase().includes(q)
    )
  }, [search])

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

  const categoryOrder = ['agent', 'control', 'data', 'tool', 'vector', 'io'] as const

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
          placeholder="Search nodes..."
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
              <DraggableNode key={node.componentId} node={node as unknown as PaletteNode} />
            ))
          : /* Agrupado por categoria */
            categoryOrder.map(cat => {
              const nodes = byCategory.get(cat)
              if (!nodes?.length) return null
              const catDef = NODE_CATEGORIES[cat]
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
                    {catDef.label}
                  </div>
                  {nodes.map(n => (
                    <DraggableNode key={n.componentId} node={n} />
                  ))}
                </div>
              )
            })}
      </ScrollArea>
    </div>
  )
}

// ── Item arrastável da palette ───────────────────────────────────
function DraggableNode({ node }: { node: PaletteNode }) {
  const cat = NODE_CATEGORIES[node.category]

  const onDragStart = (event: React.DragEvent) => {
    event.dataTransfer.setData(
      'application/archflow-node',
      JSON.stringify({
        type:        node.type,
        componentId: node.componentId,
        label:       node.label,
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
      <div
        style={{
          width:          26,
          height:         26,
          borderRadius:   6,
          background:     cat.colorLight,
          display:        'flex',
          alignItems:     'center',
          justifyContent: 'center',
          fontSize:       13,
          flexShrink:     0,
        }}
      >
        {node.icon}
      </div>
      <div style={{ minWidth: 0 }}>
        <div style={{ fontSize: 12, fontWeight: 500, color: 'var(--color-text-primary)' }}>
          {node.label}
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
          {node.description}
        </div>
      </div>
    </div>
  )
}
