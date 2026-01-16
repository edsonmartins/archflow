/// <reference types="vite/client" />

declare module '*.vue' {
  import type { DefineComponent } from 'vue'
  const component: DefineComponent<{}, {}, any>
  export default component
}

// ArchflowDesigner Web Component types
declare global {
  namespace JSX {
    interface IntrinsicElements {
      'archflow-designer': ArchflowDesignerAttributes
    }
  }

  interface HTMLElementTagNameMap {
    'archflow-designer': ArchflowDesignerElement
  }
}

interface ArchflowDesignerAttributes {
  'workflow-id'?: string
  'api-base'?: string
  'theme'?: 'light' | 'dark'
  'readonly'?: string
  'width'?: string
  'height'?: string
}

interface ArchflowDesignerElement extends HTMLElement {
  workflowId?: string
  apiBase?: string
  theme?: 'light' | 'dark'
  readonly?: boolean
  width?: string
  height?: string
  loadWorkflow(workflowId: string): Promise<unknown>
  setWorkflow(workflow: unknown): void
  saveWorkflow(): Promise<unknown>
  executeWorkflow(input?: Record<string, unknown>): Promise<unknown>
  selectNodes(nodeIds: string[]): void
  workflow: unknown
}

export {}
