import { createContext } from 'react'

/** O que a edição na própria aresta muda: o ramo e a condição escrita à mão. */
export interface EdgeDataPatch {
  branch?:    string
  condition?: string
}

/**
 * Como a aresta grava a própria edição. O FlowCanvas editável fornece uma que passa pelo histórico do canvas
 * (RCHFLW-188, item 10): o FlowEdge mudava o ramo/condição direto no React Flow, sem passo de undo — o Ctrl+Z
 * seguinte desfazia a edição ANTERIOR (o rename do nó) junto com a da aresta, e com o histórico vazio o Desfazer
 * ficava desabilitado com o cabeçalho dizendo NÃO SALVO. Sem provedor (fora do editor), a aresta grava sozinha.
 */
export const EdgeEditContext = createContext<((edgeId: string, changes: EdgeDataPatch) => void) | null>(null)
