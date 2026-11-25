import { useState, useMemo }  from 'react'
import { ScrollArea, TextInput, Accordion } from '@mantine/core'
import { IconSearch }          from '@tabler/icons-react'
import { useTranslation }      from 'react-i18next'
import { NODE_CATEGORIES, NOT_EXECUTABLE_NODE_TYPES } from './FlowCanvas/constants'
import { NodeIcon, hasNodeImage } from './FlowCanvas/nodeIcons'
import { useFlowStore }        from './FlowCanvas/store/useFlowStore'
import {
  paletteCategoryLabel, searchPaletteEntries, type PaletteEntry,
} from './FlowCanvas/paletteSearch'

type CategoryKey = keyof typeof NODE_CATEGORIES

/** Lado do ícone na paleta (arte própria e badge Tabler têm o mesmo, para os textos alinharem). */
const PALETTE_ICON = 36
/** Tamanho da arte própria — maior que o espaço por causa da margem transparente dela. */
const PALETTE_ART = 38

/**
 * Compact (36×36) icon badge matching the canvas card aesthetic:
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
  if (hasNodeImage(componentId)) {
    // Arte própria: já tem forma e cor — sem o degradê nem o brilho por trás.
    return (
      <div style={{ width: PALETTE_ICON, height: PALETTE_ICON, flexShrink: 0, display: 'flex',
                    alignItems: 'center', justifyContent: 'center' }}>
        {/* A arte traz ~5% de margem transparente; 38 px num espaço de 36 deixa o
            desenho visível do tamanho do badge Tabler, sem mexer no alinhamento dos textos. */}
        <span style={{ margin: -(PALETTE_ART - PALETTE_ICON) / 2, display: 'flex' }}>
          <NodeIcon componentId={componentId} imageSize={PALETTE_ART} />
        </span>
      </div>
    )
  }
  return (
    <div
      style={{
        position:       'relative',
        width:          PALETTE_ICON,
        height:         PALETTE_ICON,
        flexShrink:     0,
        borderRadius:   isPill ? PALETTE_ICON / 2 : 9,
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
        <NodeIcon componentId={componentId} size={18} stroke={2} />
      </span>
    </div>
  )
}

export function NodePalette() {
  const [search, setSearch] = useState('')
  const { t } = useTranslation()

  const catLabel = (c: CategoryKey) => paletteCategoryLabel(t, c)

  // Shared search semantics with the drop-connection AddNodeMenu.
  const filtered = useMemo(() => searchPaletteEntries(t, search), [search, t])

  // Agrupar por categoria
  const byCategory = useMemo(() => {
    const map = new Map<string, PaletteEntry[]>()
    for (const node of filtered) {
      const list = map.get(node.category) ?? []
      list.push(node)
      map.set(node.category, list)
    }
    return map
  }, [filtered])

  const categoryOrder = [
    'agent', 'control', 'data', 'tool', 'vector', 'io', 'knowledge', 'integration', 'annotation',
  ] as const

  return (
    <div
      className="af-palette"
      style={{
        display:        'flex',
        flexDirection:  'column',
        height:         '100%',
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
            filtered.map(node => <DraggableNode key={node.componentId} node={node} />)
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
                  {nodes.map(n => <DraggableNode key={n.componentId} node={n} />)}
                </div>
              )
            })}
      </ScrollArea>
    </div>
  )
}

// ── Item arrastável (e clicável) da palette ──────────────────────
function DraggableNode({ node }: { node: PaletteEntry }) {
  const { t } = useTranslation()
  const cat = NODE_CATEGORIES[node.category]
  const { label, description } = node
  // Continua arrastável — fluxos que já têm o nó precisam editá-lo —, mas diz que não roda (RCHFLW-11).
  const notExecutable = NOT_EXECUTABLE_NODE_TYPES.has(node.componentId)

  const onDragStart = (event: React.DragEvent) => {
    event.dataTransfer.setData(
      'application/archflow-node',
      JSON.stringify({
        componentId: node.componentId,
        label,
        category:    node.category,
      })
    )
    event.dataTransfer.effectAllowed = 'move'
  }

  // Click-to-add: drops the node at the current viewport centre via
  // the canvas' imperative API — the keyboard/trackpad complement to
  // drag-and-drop (Enter/Space work too).
  const addToCanvas = () => {
    useFlowStore.getState().canvasApi?.addNode({ nodeType: node.componentId, label })
  }

  return (
    <div
      draggable
      role="button"
      tabIndex={0}
      aria-label={label}
      onDragStart={onDragStart}
      onClick={addToCanvas}
      onKeyDown={e => {
        if (e.key === 'Enter' || e.key === ' ') {
          e.preventDefault()
          addToCanvas()
        }
      }}
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
        {notExecutable && (
          <div
            data-testid={`palette-not-executable-${node.componentId}`}
            style={{ fontSize: 10, fontWeight: 500, color: 'var(--amber-text)', marginTop: 1 }}
          >
            {t('editor.palette.notExecutable')}
          </div>
        )}
      </div>
    </div>
  )
}
