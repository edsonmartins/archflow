import { useMemo } from 'react'
import { useTranslation } from 'react-i18next'
import { useFlowStore } from './store/useFlowStore'
import type { FlowNodeData } from './types'
import type { Node } from '@xyflow/react'

/**
 * Visão textual acessível do grafo do editor — alternativa navegável por
 * teclado ao canvas React Flow (que é mouse-only e opaco para leitores de
 * tela). Lista cada nó com seu tipo, estado de execução e conexões de saída;
 * ativar um item seleciona o nó (abrindo o painel de propriedades).
 *
 * <p>Renderizada como uma região ARIA; quando recolhida, fica fora de tela
 * mas continua acessível por leitores de tela e pelo skip-link.
 */
export function CanvasOutline({ open = false }: { open?: boolean }) {
  const { t } = useTranslation()
  const nodes = useFlowStore((s) => s.nodes)
  const edges = useFlowStore((s) => s.edges)
  const executionState = useFlowStore((s) => s.executionState)
  const selectedNodeId = useFlowStore((s) => s.selectedNodeId)
  const selectNode = useFlowStore((s) => s.selectNode)

  // Conexões de saída por nó (id de origem → labels de destino)
  const outgoing = useMemo(() => {
    const bySource = new Map<string, string[]>()
    const labelOf = (id: string) =>
      (nodes.find((n) => n.id === id)?.data as FlowNodeData | undefined)?.label ?? id
    for (const e of edges) {
      const list = bySource.get(e.source) ?? []
      list.push(labelOf(e.target))
      bySource.set(e.source, list)
    }
    return bySource
  }, [nodes, edges])

  const statusLabel = (id: string) => {
    const st = executionState[id]?.status ?? 'idle'
    return t(`editor.outline.status.${st}`)
  }

  return (
    <section
      aria-label={t('editor.outline.title')}
      className={open ? 'af-canvas-outline af-canvas-outline--open' : 'af-canvas-outline'}
    >
      <h2 className="af-canvas-outline__heading">{t('editor.outline.title')}</h2>
      {nodes.length === 0 ? (
        <p>{t('editor.outline.empty')}</p>
      ) : (
        <ul>
          {nodes.map((n: Node<FlowNodeData>) => {
            const targets = outgoing.get(n.id) ?? []
            return (
              <li key={n.id}>
                <button
                  type="button"
                  aria-current={selectedNodeId === n.id ? 'true' : undefined}
                  onClick={() => selectNode(n.id, n.data as FlowNodeData)}
                >
                  <strong>{n.data.label}</strong>
                  {' — '}
                  <span>{n.data.nodeType}</span>
                  {', '}
                  <span>{statusLabel(n.id)}</span>
                  {targets.length > 0 && (
                    <>
                      {'. '}
                      <span>{t('editor.outline.connectsTo', { targets: targets.join(', ') })}</span>
                    </>
                  )}
                </button>
              </li>
            )
          })}
        </ul>
      )}
    </section>
  )
}
