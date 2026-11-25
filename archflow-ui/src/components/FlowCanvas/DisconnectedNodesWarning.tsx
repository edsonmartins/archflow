import { useMemo } from 'react'
import { useTranslation } from 'react-i18next'
import { Alert } from '@mantine/core'
import { IconAlertTriangle } from '@tabler/icons-react'
import type { Edge, Node } from '@xyflow/react'
import { disconnectedNodes } from './connectivity'
import type { FlowNodeData } from './types'

/** Aviso do canvas: há nós sem conexão, e a ordem em que eles rodam não aparece na tela (RCHFLW-88). */
export function DisconnectedNodesWarning({ nodes, edges }: { nodes: Node<FlowNodeData>[]; edges: Edge[] }) {
  const { t } = useTranslation()
  const loose = useMemo(() => disconnectedNodes(nodes, edges), [nodes, edges])
  if (loose.length === 0) return null
  const names = loose.map(n => (n.data?.label ? String(n.data.label) : n.id)).join(', ')
  return (
    <Alert
      data-testid="canvas-disconnected-warning"
      color="yellow"
      variant="light"
      icon={<IconAlertTriangle size={16} />}
      title={t('editor.disconnectedWarning.title')}
      style={{ maxWidth: 520 }}
    >
      {t('editor.disconnectedWarning.message', { names })}
    </Alert>
  )
}
