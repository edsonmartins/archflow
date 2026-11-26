import { memo, useContext } from 'react'
import { useTranslation } from 'react-i18next'
import {
  BaseEdge,
  EdgeLabelRenderer,
  getSmoothStepPath,
  useNodesData,
  useReactFlow,
  type EdgeProps,
} from '@xyflow/react'
import { useFlowStore } from '../store/useFlowStore'
import { branchOptions, undeclaredBranch } from '../branches'
import { EdgeEditContext, type EdgeDataPatch } from './edgeEdit'

interface FlowEdgeData {
  isErrorPath?: boolean
  /** Ramo que a aresta quer dizer (true/false, caso do switch, rota da decisão). */
  branch?:      string
  /** Condição escrita à mão; vence o ramo no backend. */
  condition?:   string
}

const CHIP_STYLE = {
  position: 'absolute' as const,
  fontSize: 10,
  fontFamily: 'var(--font-sans)',
  padding: '1px 6px',
  borderRadius: 6,
}

export const FlowEdge = memo(function FlowEdge({
  id,
  source,
  sourceX, sourceY,
  targetX, targetY,
  sourcePosition,
  targetPosition,
  data,
  selected,
}: EdgeProps) {
  const { t } = useTranslation()
  const { isExecuting } = useFlowStore()
  const { setEdges } = useReactFlow()
  const editEdge = useContext(EdgeEditContext)
  const edgeData = (data ?? {}) as FlowEdgeData
  const isError = edgeData.isErrorPath ?? false
  const branch = edgeData.branch
  const condition = edgeData.condition
  // Assinado, e não lido com getNode: a aresta precisa redesenhar quando os casos do nó de origem mudam, senão o
  // aviso de ramo não declarado só apareceria depois de outra coisa redesenhar a aresta.
  const sourceData = useNodesData(source)?.data as { nodeType?: string; config?: Record<string, unknown> } | undefined
  const sourceType = String(sourceData?.nodeType ?? '')
  const options = branchOptions(sourceType, sourceData?.config)
  // O ramo gravado que o nó não declara mais (RCHFLW-147: caso do switch renomeado). Antes o rótulo seguia
  // mostrando o nome antigo como se valesse e o seletor caía em "—"; nenhum valor chega a essa aresta.
  const staleBranch = undeclaredBranch(sourceType, sourceData?.config, edgeData)
  const staleTitle = staleBranch != null
    ? t('editor.edge.undeclaredBranch', { branch: staleBranch, options: options.join(', ') }) : undefined
  const patch = (changes: EdgeDataPatch) => {
    if (editEdge) editEdge(id, changes)
    else setEdges(es => es.map(e => (e.id === id ? { ...e, data: { ...e.data, ...changes } } : e)))
  }

  // Animar edge se execução está ativa e o nó fonte está rodando/concluído
  const animated = isExecuting

  const [edgePath, labelX, labelY] = getSmoothStepPath({
    sourceX, sourceY, sourcePosition,
    targetX, targetY, targetPosition,
  })

  const strokeColor = selected
    ? 'var(--blue)'
    : isError
    ? 'var(--red)'
    : animated
    ? 'var(--blue)'
    : 'var(--color-border-secondary)'

  const strokeWidth = selected ? 2 : 1.5

  return (
    <>
      <BaseEdge
        id={id}
        path={edgePath}
        style={{
          stroke:          strokeColor,
          strokeWidth,
          strokeDasharray: animated ? '6 3' : undefined,
          animation:       animated
            ? 'archflow-dash 0.8s linear infinite'
            : undefined,
        }}
      />

      {(branch || condition) && !selected && (
        <EdgeLabelRenderer>
          <div
            title={staleTitle ?? condition ?? branch}
            data-testid={staleBranch != null ? 'edge-undeclared-branch' : undefined}
            style={{
              ...CHIP_STYLE,
              transform: `translate(-50%, -50%) translate(${labelX}px,${labelY - (isError ? 16 : 0)}px)`,
              color: staleBranch != null ? 'var(--red)' : 'var(--blue)',
              background: staleBranch != null ? 'var(--red-l)' : 'var(--color-background-secondary, var(--bg2))',
              border: `1px solid ${staleBranch != null ? 'var(--red)' : 'var(--blue)'}`,
              pointerEvents: 'none',
              maxWidth: 160,
              overflow: 'hidden',
              textOverflow: 'ellipsis',
              whiteSpace: 'nowrap',
            }}
          >
            {condition ? `ƒ ${condition}` : staleBranch != null ? `⚠ ${branch}` : branch}
          </div>
        </EdgeLabelRenderer>
      )}

      {/* Edição na própria aresta: o ramo (quando o nó de origem tem ramos) e a condição à mão. */}
      {selected && !isError && (
        <EdgeLabelRenderer>
          <div
            className="nodrag nopan"
            style={{
              ...CHIP_STYLE,
              transform: `translate(-50%, -50%) translate(${labelX}px,${labelY}px)`,
              display: 'flex', flexDirection: 'column', gap: 4,
              background: 'var(--color-background-primary, var(--bg1))',
              border: '1px solid var(--blue)',
              padding: 6,
              pointerEvents: 'all',
              minWidth: 180,
            }}
          >
            {options.length > 0 && (
              <select
                aria-label="branch"
                value={branch ?? ''}
                onChange={e => patch({ branch: e.target.value || undefined })}
                style={{ fontSize: 11 }}
              >
                <option value="">—</option>
                {options.map(o => <option key={o} value={o}>{o}</option>)}
                {/* O ramo que o nó não declara continua visível no seletor, marcado: sumir com ele ("—")
                    escondia que a aresta ainda está gravada nele. */}
                {staleBranch != null && (
                  <option value={branch}>{t('editor.edge.undeclaredOption', { branch: staleBranch })}</option>
                )}
              </select>
            )}
            {staleBranch != null && (
              <div role="alert" style={{ fontSize: 10, color: 'var(--red)', maxWidth: 220, whiteSpace: 'normal' }}>
                {staleTitle}
              </div>
            )}
            <input
              aria-label="condition"
              placeholder="${node.field} == 'x'"
              value={condition ?? ''}
              onChange={e => patch({ condition: e.target.value || undefined })}
              style={{ fontSize: 11, fontFamily: 'var(--font-mono)' }}
            />
          </div>
        </EdgeLabelRenderer>
      )}

      {isError && (
        <EdgeLabelRenderer>
          <div
            style={{
              position:  'absolute',
              transform: `translate(-50%, -50%) translate(${labelX}px,${labelY}px)`,
              fontSize:  10,
              fontFamily: 'var(--font-sans)',
              color:     'var(--red)',
              background: 'var(--red-l)',
              border:    '1px solid var(--red)',
              padding:   '1px 6px',
              borderRadius: 6,
              pointerEvents: 'none',
            }}
          >
            error
          </div>
        </EdgeLabelRenderer>
      )}
    </>
  )
})
