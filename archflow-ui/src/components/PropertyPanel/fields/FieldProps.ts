import type { FlowNodeData } from '../../FlowCanvas/types'

/** Props comuns dos componentes de campos por categoria de nó. */
export type FieldProps = { nodeData: FlowNodeData; update: (key: string, value: unknown) => void }
