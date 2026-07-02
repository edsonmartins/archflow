/**
 * ArchflowDesigner — Web Component
 *
 * Wrapper fino que monta o FlowCanvas React dentro do Shadow DOM.
 * A API pública (atributos, métodos, eventos) é mantida compatível
 * com a documentação existente. A implementação interna migrou de
 * CanvasManager/CanvasRenderer para @xyflow/react.
 *
 * Uso:
 *   <archflow-designer workflow-id="flow-789" theme="dark" />
 */

import React            from 'react'
import ReactDOM         from 'react-dom/client'
import { FlowCanvas }   from '../../components/FlowCanvas/FlowCanvas'
import type { WorkflowData, FlowNodeData } from '../../components/FlowCanvas/types'

// ── CSS global necessário para @xyflow/react ─────────────────────
// O Shadow DOM isola o CSS — precisamos injetar o estilo do React Flow.
const XYFLOW_CSS_URL = 'https://unpkg.com/@xyflow/react/dist/style.css'

class ArchflowDesignerElement extends HTMLElement {
  private root:         ReactDOM.Root | null = null
  private container:   HTMLDivElement | null = null
  private shadowRoot_: ShadowRoot

  // Estado interno
  private _workflow:   WorkflowData | null = null
  private _theme:      'light' | 'dark'   = 'light'
  private _readonly:   boolean            = false
  private _showMinimap: boolean           = true
  private _showGrid:    boolean           = true
  private _isLoading:   boolean            = false
  private _isExecuting: boolean            = false
  private _selectedNodes: string[]         = []

  static get observedAttributes() {
    return ['workflow-id', 'api-base', 'theme', 'readonly', 'show-minimap', 'show-grid', 'width', 'height']
  }

  static register(): void {
    if (!customElements.get('archflow-designer')) {
      customElements.define('archflow-designer', ArchflowDesignerElement)
    }
  }

  static isRegistered(): boolean {
    return customElements.get('archflow-designer') === ArchflowDesignerElement
  }

  constructor() {
    super()
    this.shadowRoot_ = this.attachShadow({ mode: 'open' })
  }

  // ── Lifecycle ──────────────────────────────────────────────────
  connectedCallback() {
    this.injectStyles()
    this.mountReact()
    this.emit('connected', {})
    const workflowId = this.workflowId
    if (workflowId) {
      void this.loadWorkflow(workflowId)
    }
  }

  disconnectedCallback() {
    this.root?.unmount()
    this.root = null
  }

  attributeChangedCallback(name: string, _old: string, val: string) {
    switch (name) {
      case 'theme':        this._theme       = val === 'dark' ? 'dark' : 'light'; break
      case 'readonly':     this._readonly    = val !== null && val !== 'false';    break
      case 'show-minimap': this._showMinimap = val !== 'false';                   break
      case 'show-grid':    this._showGrid    = val !== 'false';                   break
      case 'width':
      case 'height':
        if (this.container) this.applyDimensions()
        break
      case 'workflow-id':
        if (val && this.isConnected) void this.loadWorkflow(val)
        break
    }
    this.render()
  }

  // ── API pública (métodos JavaScript) ──────────────────────────

  get workflowId(): string | null {
    return this.getAttribute('workflow-id')
  }

  set workflowId(value: string | null) {
    if (value === null) this.removeAttribute('workflow-id')
    else this.setAttribute('workflow-id', value)
  }

  get apiBase(): string {
    return this.getAttribute('api-base') ?? '/api'
  }

  set apiBase(value: string) {
    this.setAttribute('api-base', value)
  }

  get theme(): 'light' | 'dark' {
    return this._theme
  }

  set theme(value: 'light' | 'dark') {
    this.setAttribute('theme', value)
  }

  get readonly(): boolean {
    return this._readonly
  }

  set readonly(value: boolean) {
    if (value) this.setAttribute('readonly', '')
    else this.removeAttribute('readonly')
  }

  get width(): string | null {
    return this.getAttribute('width')
  }

  set width(value: string | null) {
    if (value === null) this.removeAttribute('width')
    else this.setAttribute('width', value)
  }

  get height(): string | null {
    return this.getAttribute('height')
  }

  set height(value: string | null) {
    if (value === null) this.removeAttribute('height')
    else this.setAttribute('height', value)
  }

  get selectedNodes(): string[] {
    return [...this._selectedNodes]
  }

  get isLoading(): boolean {
    return this._isLoading
  }

  get isExecuting(): boolean {
    return this._isExecuting
  }

  get workflow(): WorkflowData | null {
    return this._workflow
  }

  /** Carrega um workflow a partir de um objeto JSON */
  setWorkflow(data: WorkflowData): void {
    this._workflow = data
    this.render()
  }

  /** Retorna o workflow atual como objeto JSON */
  getWorkflow(): WorkflowData | null {
    return this._workflow
  }

  getWorkflowJson(): string {
    return JSON.stringify(this._workflow)
  }

  selectNodes(nodeIds: string[]): void {
    this._selectedNodes = [...nodeIds]
    this.emit('nodes-selected', { nodeIds: this.selectedNodes })
  }

  clearSelection(): void {
    this._selectedNodes = []
    this.emit('selection-cleared', {})
  }

  reset(): void {
    this.workflowId = null
    this._workflow = null
    this._selectedNodes = []
    this.render()
  }

  async loadWorkflow(id: string): Promise<void> {
    await this.loadWorkflowById(id)
  }

  async saveWorkflow(): Promise<void> {
    if (!this._workflow) return
    this.emit('workflow-saved', {
      workflowId: this.workflowId,
      version: '1.0.0',
    })
  }

  /** Adiciona um nó ao canvas */
  addNode(nodeInfo: {
    type:        string
    componentId: string
    label?:      string
    position?:   { x: number; y: number }
    config?:     Record<string, unknown>
  }): void {
    // Delegar ao store do React (via evento interno)
    this.dispatchInternal('__add-node', nodeInfo)
  }

  /** Remove todos os nós e conexões */
  clearCanvas(): void {
    this._workflow = { steps: [], connections: [] }
    this.render()
  }

  /** Define o nível de zoom */
  zoomTo(level: number): void {
    this.dispatchInternal('__zoom-to', { level })
  }

  /** Ajusta o zoom para mostrar todos os nós */
  fitView(): void {
    this.dispatchInternal('__fit-view', {})
  }

  // ── Internals ─────────────────────────────────────────────────

  private injectStyles() {
    // Injetar CSS do xyflow no shadow DOM
    const link = document.createElement('link')
    link.rel  = 'stylesheet'
    link.href = XYFLOW_CSS_URL
    this.shadowRoot_.appendChild(link)

    // Repassar CSS variables do documento para o shadow DOM
    const style = document.createElement('style')
    style.textContent = `
      :host {
        display: block;
        width: ${this.getAttribute('width') ?? '100%'};
        height: ${this.getAttribute('height') ?? '600px'};
        font-family: var(--font-sans, system-ui, sans-serif);
      }
      .canvas-root {
        width: 100%;
        height: 100%;
      }
      @keyframes archflow-dash {
        to { stroke-dashoffset: -18; }
      }
      @keyframes pulse {
        0%, 100% { opacity: 1; }
        50%       { opacity: 0.6; }
      }
    `
    this.shadowRoot_.appendChild(style)
  }

  private mountReact() {
    this.container = document.createElement('div')
    this.container.className = 'canvas-root'
    this.shadowRoot_.appendChild(this.container)

    this.root = ReactDOM.createRoot(this.container)
    this.render()
  }

  private render() {
    if (!this.root) return

    this.root.render(
      React.createElement(FlowCanvas, {
        initialWorkflow: this._workflow,
        readonly:        this._readonly,
        showMinimap:     this._showMinimap,
        showGrid:        this._showGrid,

        onNodeSelect: (nodeId, data) => {
          if (nodeId) {
            this.emit('node-selected', {
              nodeId,
              nodeType:    (data as FlowNodeData)?.nodeType,
              componentId: (data as FlowNodeData)?.componentId,
            })
          } else {
            this.emit('selection-cleared', {})
          }
        },

        onWorkflowChange: (workflow) => {
          this._workflow = workflow
          this.emit('workflow-saved', {
            workflowId: this.getAttribute('workflow-id'),
            version:    '1.0.0',
          })
        },

        onExecutionRequest: () => {
          const execId = `exec-${Date.now()}`
          this.emit('workflow-executed', {
            executionId: execId,
            status:      'RUNNING',
          })
        },
      })
    )
  }

  private applyDimensions() {
    if (!this.container) return
    const w = this.getAttribute('width')
    const h = this.getAttribute('height')
    if (w) this.container.style.width  = w
    if (h) this.container.style.height = h
  }

  private emit(eventName: string, detail: Record<string, unknown>) {
    this.dispatchEvent(new CustomEvent(eventName, {
      detail,
      bubbles:    true,
      composed:   true,   // atravessa o shadow DOM boundary
    }))
  }

  private dispatchInternal(type: string, payload: unknown) {
    if (this.container) {
      this.container.dispatchEvent(new CustomEvent(type, {
        detail: payload,
        bubbles: true,
      }))
    }
  }

  private async loadWorkflowById(id: string) {
    const apiUrl = this.apiBase
    this._isLoading = true
    try {
      const token = (window as Window & { __archflow_token?: string }).__archflow_token ?? ''
      const res   = await fetch(`${apiUrl}/workflows/${id}`, {
        headers: token ? { Authorization: `Bearer ${token}` } : {},
      })
      if (!res.ok) return
      const data = await res.json()
      this.setWorkflow(data)
      this.emit('workflow-loaded', { workflowId: id, workflow: data })
    } catch {
      // Falha silenciosa — consumer deve tratar via evento de erro futuro
    } finally {
      this._isLoading = false
    }
  }
}

// ── Registro do Custom Element ───────────────────────────────────
ArchflowDesignerElement.register()

export { ArchflowDesignerElement }
export { ArchflowDesignerElement as ArchflowDesigner }
