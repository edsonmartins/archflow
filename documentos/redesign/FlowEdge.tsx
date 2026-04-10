import { memo }         from 'react'
import {
  BaseEdge,
  EdgeLabelRenderer,
  getSmoothStepPath,
  type EdgeProps,
} from '@xyflow/react'
import { useFlowStore } from '../store/useFlowStore'

interface FlowEdgeData {
  isErrorPath?: boolean
}

export const FlowEdge = memo(function FlowEdge({
  id,
  sourceX, sourceY,
  targetX, targetY,
  sourcePosition,
  targetPosition,
  data,
  selected,
}: EdgeProps) {
  const { isExecuting, executionState } = useFlowStore()
  const isError = (data as FlowEdgeData)?.isErrorPath ?? false

  // Animar edge se execução está ativa e o nó fonte está rodando/concluído
  const animated = isExecuting

  const [edgePath, labelX, labelY] = getSmoothStepPath({
    sourceX, sourceY, sourcePosition,
    targetX, targetY, targetPosition,
  })

  const strokeColor = selected
    ? '#378ADD'
    : isError
    ? '#D85A30'
    : animated
    ? '#378ADD'
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

      {isError && (
        <EdgeLabelRenderer>
          <div
            style={{
              position:  'absolute',
              transform: `translate(-50%, -50%) translate(${labelX}px,${labelY}px)`,
              fontSize:  10,
              fontFamily: 'var(--font-sans)',
              color:     '#993C1D',
              background: '#FAECE7',
              border:    '0.5px solid #D85A30',
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
