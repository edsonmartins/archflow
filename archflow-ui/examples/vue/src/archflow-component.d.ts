declare module '@archflow/component' {
  export function registerArchflowDesigner(tagName?: string): boolean
  export function isArchflowDesignerRegistered(tagName?: string): boolean
  export function autoRegister(tagName?: string): void

  export class ArchflowDesigner extends HTMLElement {
    workflowId: string | null
    apiBase: string
    theme: 'light' | 'dark'
    readonly: boolean
    width: string
    height: string
    workflow: unknown

    loadWorkflow(workflowId: string): Promise<unknown>
    setWorkflow(workflow: unknown): void
    saveWorkflow(): Promise<unknown>
    executeWorkflow(input?: Record<string, unknown>): Promise<unknown>
    selectNodes(nodeIds: string[]): void
    clearSelection(): void
    reset(): void
    getWorkflowJson(): string
  }

  export type Theme = 'light' | 'dark'
}
