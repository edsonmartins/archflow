import { memo }         from 'react'
import {
  BaseEdge,
  EdgeLabelRenderer,
  getSmoothStepPath,
  useReactFlow,
  type EdgeProps,
} from '@xyflow/react'
import { useFlowStore } from '../store/useFlowStore'
import { branchOptions } from '../branches'

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
  const { isExecuting, executionState } = useFlowStore()
  const { setEdges, getNode } = useReactFlow()
  const edgeData = (data ?? {}) as FlowEdgeData
  const isError = edgeData.isErrorPath ?? false
  const branch = edgeData.branch
  const condition = edgeData.condition
  const sourceData = getNode(source)?.data as { nodeType?: string; config?: Record<string, unknown> } | undefined
  const options = branchOptions(String(sourceData?.nodeType ?? ''), sourceData?.config)
  const patch = (changes: Partial<FlowEdgeData>) =>
    setEdges(es => es.map(e => (e.id === id ? { ...e, data: { ...e.data, ...changes } } : e)))

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
            title={condition ?? branch}
            style={{
              ...CHIP_STYLE,
              transform: `translate(-50%, -50%) translate(${labelX}px,${labelY - (isError ? 16 : 0)}px)`,
              color: 'var(--blue)',
              background: 'var(--color-background-secondary, var(--bg2))',
              border: '1px solid var(--blue)',
              pointerEvents: 'none',
              maxWidth: 160,
              overflow: 'hidden',
              textOverflow: 'ellipsis',
              whiteSpace: 'nowrap',
            }}
          >
            {condition ? `ƒ ${condition}` : branch}
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
              </select>
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
