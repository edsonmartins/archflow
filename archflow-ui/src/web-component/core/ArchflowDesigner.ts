/**
 * ArchflowDesigner - Web Component for Visual AI Workflow Builder
 *
 * <archflow-designer
 *   workflow-id="wf-001"
 *   api-base="http://localhost:8080/api"
 *   theme="dark"
 *   readonly="false">
 * </archflow-designer>
 *
 * @example
 * ```html
 * <archflow-designer
 *   workflow-id="customer-support-flow"
 *   api-base="http://localhost:8080/api"
 *   theme="dark"
 *   on-save="handleSave">
 * </archflow-designer>
 *
 * <script>
 *   const designer = document.querySelector('archflow-designer');
 *   designer.addEventListener('workflow-saved', (e) => {
 *     console.log('Workflow saved:', e.detail);
 *   });
 *   designer.addEventListener('node-selected', (e) => {
 *     console.log('Node selected:', e.detail.nodeId);
 *   });
 *   designer.addEventListener('workflow-executed', (e) => {
 *     console.log('Execution result:', e.detail);
 *   });
 * </script>
 * ```
 */

import { ArchflowShadowDom } from './ArchflowShadowDom';
import { ArchflowEventDispatcher } from './ArchflowEventDispatcher';
import { ThemeManager } from './ThemeManager';
import { LogLevel, StepType, type Flow, type FlowConfiguration, type FlowResult, type FlowStep } from '../../types/flow-types';

// Attribute names
const ATTR_WORKFLOW_ID = 'workflow-id';
const ATTR_API_BASE = 'api-base';
const ATTR_THEME = 'theme';
const ATTR_READONLY = 'readonly';
const ATTR_WIDTH = 'width';
const ATTR_HEIGHT = 'height';

// Default values
const DEFAULT_API_BASE = '/api';
const DEFAULT_THEME: Theme = 'light';
const DEFAULT_READONLY = false;
const DEFAULT_WIDTH = '100%';
const DEFAULT_HEIGHT = '600px';

const DEFAULT_FLOW_CONFIGURATION: FlowConfiguration = {
  timeout: 30000,
  retryPolicy: {
    maxAttempts: 1,
    delay: 1000,
    multiplier: 1,
    retryableExceptions: [],
  },
  llmConfig: {
    model: 'gpt-4',
    temperature: 0.7,
    maxTokens: 2048,
    timeout: 30000,
    additionalConfig: {},
  },
  monitoringConfig: {
    detailedMetrics: false,
    fullHistory: false,
    logLevel: LogLevel.INFO,
    tags: {},
  },
};

interface AddNodeInput {
  type: string;
  label?: string;
  position?: { x: number; y: number };
  config?: Record<string, unknown>;
}

/**
 * ArchflowDesigner Web Component
 *
 * Custom Element for building AI workflows visually.
 * Framework-agnostic - works with React, Vue, Angular, Svelte, or vanilla JS.
 */
export class ArchflowDesigner extends HTMLElement {
  // ==========================================================================
  // Private Properties
  // ==========================================================================

  private _shadowRoot: ShadowRoot | null = null;
  private _shadowDom: ArchflowShadowDom | null = null;
  private _eventDispatcher: ArchflowEventDispatcher | null = null;
  private _themeManager: ThemeManager | null = null;

  // Internal state
  private _workflow: Flow | null = null;
  private _isLoading = false;
  private _isExecuting = false;
  private _selectedNodeIds: string[] = [];
  private _pendingConnectionSourceId: string | null = null;

  // Property backing stores (for React 19 compatibility)
  private _workflowId: string | null = null;
  private _apiBase: string = DEFAULT_API_BASE;
  private _theme: Theme = DEFAULT_THEME;
  private _readonly: boolean = DEFAULT_READONLY;
  private _width: string = DEFAULT_WIDTH;
  private _height: string = DEFAULT_HEIGHT;

  // ==========================================================================
  // Static Methods - Custom Element Registration
  // ==========================================================================

  /**
   * Register this component as a custom element.
   * Call this once to make <archflow-designer> available.
   */
  static register(tagName = 'archflow-designer'): void {
    if (!customElements.get(tagName)) {
      customElements.define(tagName, ArchflowDesigner);
    }
  }

  /**
   * Check if this component is already registered.
   */
  static isRegistered(tagName = 'archflow-designer'): boolean {
    return customElements.get(tagName) === ArchflowDesigner;
  }

  // ==========================================================================
  // Lifecycle Callbacks
  // ==========================================================================

  /**
   * Called when element is added to DOM.
   */
  connectedCallback(): void {
    this._shadowRoot = this.attachShadow({ mode: 'open' });

    // Initialize components
    this._themeManager = new ThemeManager(this._theme);
    this._eventDispatcher = new ArchflowEventDispatcher(this);
    this._shadowDom = new ArchflowShadowDom(
      this._shadowRoot,
      this._themeManager,
      this._eventDispatcher
    );

    // Parse initial attributes
    this._parseAttributes();

    // Render initial UI
    this._render();

    this.addEventListener('archflow:save', this._handleSaveRequested);
    this.addEventListener('archflow:execute', this._handleExecuteRequested);
    this.addEventListener('archflow:reset', this._handleResetRequested);
    this.addEventListener('archflow:connect', this._handleConnectRequested);
    this.addEventListener('archflow:remove', this._handleRemoveRequested);

    // Load workflow if ID is provided
    if (this._workflowId) {
      this.loadWorkflow(this._workflowId);
    }

    // Emit connected event
    this._eventDispatcher.emit('connected', {
      component: 'archflow-designer',
      version: '1.0.0-beta'
    });
  }

  /**
   * Called when element is removed from DOM.
   */
  disconnectedCallback(): void {
    // Cleanup
    if (this._shadowDom) {
      this._shadowDom.destroy();
    }

    this.removeEventListener('archflow:save', this._handleSaveRequested);
    this.removeEventListener('archflow:execute', this._handleExecuteRequested);
    this.removeEventListener('archflow:reset', this._handleResetRequested);
    this.removeEventListener('archflow:connect', this._handleConnectRequested);
    this.removeEventListener('archflow:remove', this._handleRemoveRequested);

    this._eventDispatcher?.emit('disconnected', {
      workflowId: this._workflowId
    });

    this._shadowRoot = null;
    this._shadowDom = null;
    this._eventDispatcher = null;
    this._themeManager = null;
  }

  /**
   * Called when observed attributes change.
   */
  attributeChangedCallback(
    name: string,
    oldValue: string | null,
    newValue: string | null
  ): void {
    if (oldValue === newValue) return;

    switch (name) {
      case ATTR_WORKFLOW_ID:
        this._workflowId = newValue;
        if (newValue && this.isConnected) {
          this.loadWorkflow(newValue);
        }
        break;
      case ATTR_API_BASE:
        this._apiBase = newValue || DEFAULT_API_BASE;
        break;
      case ATTR_THEME:
        this._theme = (newValue as Theme) || DEFAULT_THEME;
        this._themeManager?.setTheme(this._theme);
        break;
      case ATTR_READONLY:
        this._readonly = newValue !== null && newValue !== 'false';
        break;
      case ATTR_WIDTH:
        this._width = newValue || DEFAULT_WIDTH;
        this._updateSize();
        break;
      case ATTR_HEIGHT:
        this._height = newValue || DEFAULT_HEIGHT;
        this._updateSize();
        break;
    }
  }

  /**
   * Attributes to observe for changes.
   */
  static get observedAttributes(): string[] {
    return [
      ATTR_WORKFLOW_ID,
      ATTR_API_BASE,
      ATTR_THEME,
      ATTR_READONLY,
      ATTR_WIDTH,
      ATTR_HEIGHT
    ];
  }

  // ==========================================================================
  // Public Properties (for React 19 compatibility)
  // ==========================================================================

  /**
   * Workflow ID to load.
   */
  get workflowId(): string | null {
    return this._workflowId;
  }

  set workflowId(value: string | null) {
    this._workflowId = value;
    if (value) {
      this.setAttribute(ATTR_WORKFLOW_ID, value);
    } else {
      this.removeAttribute(ATTR_WORKFLOW_ID);
    }
  }

  /**
   * API base URL for backend calls.
   */
  get apiBase(): string {
    return this._apiBase;
  }

  set apiBase(value: string) {
    this._apiBase = value;
    this.setAttribute(ATTR_API_BASE, value);
  }

  /**
   * Theme: 'light' or 'dark'.
   */
  get theme(): Theme {
    return this._theme;
  }

  set theme(value: Theme) {
    this._theme = value;
    this.setAttribute(ATTR_THEME, value);
  }

  /**
   * Read-only mode (disable editing).
   */
  get readonly(): boolean {
    return this._readonly;
  }

  set readonly(value: boolean) {
    this._readonly = value;
    if (value) {
      this.setAttribute(ATTR_READONLY, '');
    } else {
      this.removeAttribute(ATTR_READONLY);
    }
  }

  /**
   * Component width.
   */
  get width(): string {
    return this._width;
  }

  set width(value: string) {
    this._width = value;
    this.setAttribute(ATTR_WIDTH, value);
  }

  /**
   * Component height.
   */
  get height(): string {
    return this._height;
  }

  set height(value: string) {
    this._height = value;
    this.setAttribute(ATTR_HEIGHT, value);
  }

  /**
   * Current workflow data.
   */
  get workflow(): Flow | null {
    return this._workflow;
  }

  /**
   * Loading state.
   */
  get isLoading(): boolean {
    return this._isLoading;
  }

  /**
   * Executing state.
   */
  get isExecuting(): boolean {
    return this._isExecuting;
  }

  /**
   * Selected node IDs.
   */
  get selectedNodes(): string[] {
    return [...this._selectedNodeIds];
  }

  get pendingConnectionSourceId(): string | null {
    return this._pendingConnectionSourceId;
  }

  // ==========================================================================
  // Public Methods
  // ==========================================================================

  /**
   * Load a workflow by ID.
   */
  async loadWorkflow(workflowId: string): Promise<Flow | null> {
    this._isLoading = true;
    this._render();

    try {
      const response = await this._request(`${this._apiBase}/workflows/${workflowId}`);

      if (!response.ok) {
        throw new Error(`Failed to load workflow: ${response.statusText}`);
      }

      const workflow: Flow = await response.json();
      this._workflow = workflow;
      this._workflowId = workflowId;
      this._isLoading = false;

      this._render();
      this._eventDispatcher?.emit('workflow-loaded', { workflow });

      return workflow;
    } catch (error) {
      this._isLoading = false;
      this._eventDispatcher?.emit('error', {
        type: 'load-failed',
        message: error instanceof Error ? error.message : 'Unknown error'
      });
      this._render();
      return null;
    }
  }

  /**
   * Save the current workflow.
   */
  async saveWorkflow(): Promise<Flow | null> {
    if (!this._workflow || this._readonly) {
      return null;
    }

    try {
      const hasExistingId = Boolean(this._workflow.id);
      const endpoint = hasExistingId
        ? `${this._apiBase}/workflows/${this._workflow.id}`
        : `${this._apiBase}/workflows`;

      const response = await this._request(endpoint, {
        method: hasExistingId ? 'PUT' : 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(this._workflow)
      });

      if (!response.ok) {
        throw new Error(`Failed to save workflow: ${response.statusText}`);
      }

      const saved: Flow = await response.json();
      this._workflow = saved;

      this._eventDispatcher?.emit('workflow-saved', { workflow: saved });

      return saved;
    } catch (error) {
      this._eventDispatcher?.emit('error', {
        type: 'save-failed',
        message: error instanceof Error ? error.message : 'Unknown error'
      });
      return null;
    }
  }

  /**
   * Execute the current workflow.
   */
  async executeWorkflow(input?: Record<string, unknown>): Promise<FlowResult | null> {
    if (!this._workflow || this._isExecuting) {
      return null;
    }

    this._isExecuting = true;
    this._render();

    try {
      const response = await this._request(`${this._apiBase}/workflows/${this._workflow.id}/execute`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(input || {})
      });

      if (!response.ok) {
        throw new Error(`Failed to execute workflow: ${response.statusText}`);
      }

      const result: FlowResult = await response.json();
      this._isExecuting = false;

      this._eventDispatcher?.emit('workflow-executed', { result });
      this._render();

      return result;
    } catch (error) {
      this._isExecuting = false;
      this._eventDispatcher?.emit('error', {
        type: 'execution-failed',
        message: error instanceof Error ? error.message : 'Unknown error'
      });
      this._render();
      return null;
    }
  }

  /**
   * Set workflow data programmatically.
   */
  setWorkflow(workflow: Flow): void {
    this._workflow = workflow;
    this._workflowId = workflow.id;
    this._render();
  }

  /**
   * Add a node to the current workflow.
   */
  addNode(input: AddNodeInput): FlowStep | null {
    if (this._readonly) {
      return null;
    }

    if (!this._workflow) {
      this._workflow = {
        id: this._workflowId || '',
        metadata: {
          name: 'Untitled Workflow',
          description: '',
          version: '1.0.0',
          author: 'Archflow UI',
          category: 'custom',
          tags: [],
        },
        steps: [],
        configuration: DEFAULT_FLOW_CONFIGURATION,
      };
    }

    const normalizedType = this._normalizeStepType(input.type);
    const nodeId = `step-${this._workflow.steps.length + 1}`;
    const node: FlowStep = {
      id: nodeId,
      type: normalizedType,
      componentId: `${normalizedType.toLowerCase()}-${this._workflow.steps.length + 1}`,
      operation: input.label || normalizedType,
      connections: [],
      configuration: input.config || {},
    };

    this._workflow = {
      ...this._workflow,
      steps: [...this._workflow.steps, node],
    };
    this._render();
    this._eventDispatcher?.emit('node-selected', {
      nodeId,
      nodeType: normalizedType,
      nodeLabel: input.label || nodeId,
      position: input.position || { x: 0, y: 0 },
      config: node.configuration || {},
    } as never);

    return node;
  }

  /**
   * Remove a node and any related connections.
   */
  removeNode(nodeId: string): void {
    if (!this._workflow) {
      return;
    }

    this._workflow = {
      ...this._workflow,
      steps: this._workflow.steps
        .filter((step) => step.id !== nodeId)
        .map((step) => ({
          ...step,
          connections: step.connections.filter((connection) => connection.targetId !== nodeId),
        })),
    };

    if (this._selectedNodeIds.includes(nodeId)) {
      this.clearSelection();
    }

    if (this._pendingConnectionSourceId === nodeId) {
      this._pendingConnectionSourceId = null;
    }

    this._render();
  }

  /**
   * Update an existing node in the workflow.
   */
  updateNode(nodeId: string, updates: { label?: string; config?: Record<string, unknown> }): void {
    if (!this._workflow) {
      return;
    }

    this._workflow = {
      ...this._workflow,
      steps: this._workflow.steps.map((step) =>
        step.id === nodeId
          ? {
              ...step,
              operation: updates.label ?? step.operation,
              configuration: updates.config ? { ...(step.configuration || {}), ...updates.config } : step.configuration,
            }
          : step
      ),
    };
    this._render();
  }

  /**
   * Create a connection between two steps.
   */
  connectSteps(sourceId: string, targetId: string): void {
    if (!this._workflow || sourceId === targetId) {
      return;
    }

    this._workflow = {
      ...this._workflow,
      steps: this._workflow.steps.map((step) =>
        step.id === sourceId
          ? {
              ...step,
              connections: [
                ...step.connections.filter((connection) => connection.targetId !== targetId),
                {
                  sourceId,
                  targetId,
                  isErrorPath: false,
                  type: 'success',
                },
              ],
            }
          : step
      ),
    };
    this._pendingConnectionSourceId = null;
    this._render();
  }

  /**
   * Select nodes by IDs.
   */
  selectNodes(nodeIds: string[]): void {
    this._selectedNodeIds = nodeIds;
    this._eventDispatcher?.emit('nodes-selected', { nodeIds });
    this._shadowDom?.updateSelection(nodeIds);
  }

  /**
   * Clear node selection.
   */
  clearSelection(): void {
    this._selectedNodeIds = [];
    this._eventDispatcher?.emit('selection-cleared', {});
    this._shadowDom?.updateSelection([]);
  }

  /**
   * Get the current workflow JSON.
   */
  getWorkflowJson(): string {
    return JSON.stringify(this._workflow, null, 2);
  }

  /**
   * Reset to empty state.
   */
  reset(): void {
    this.clearSelection();
    this._workflow = null;
    this._workflowId = null;
    this._pendingConnectionSourceId = null;
    this._isLoading = false;
    this._isExecuting = false;
    this.removeAttribute(ATTR_WORKFLOW_ID);
    this._render();
  }

  // ==========================================================================
  // Event Methods (for addEventListener compatibility)
  // ==========================================================================

  /**
   * Add event listener.
   * Supports standard CustomEvents and specific archflow events.
   *
   * Events:
   * - 'workflow-saved': Emitted when workflow is saved
   * - 'workflow-loaded': Emitted when workflow is loaded
   * - 'workflow-executed': Emitted when workflow execution completes
   * - 'node-selected': Emitted when a node is selected
   * - 'nodes-selected': Emitted when multiple nodes are selected
   * - 'selection-cleared': Emitted when selection is cleared
   * - 'error': Emitted when an error occurs
   * - 'connected': Emitted when component is connected to DOM
   * - 'disconnected': Emitted when component is disconnected from DOM
   */
  addEventListener<K extends keyof ArchflowEventMap>(
    type: K,
    listener: (this: ArchflowDesigner, ev: ArchflowEventMap[K]) => unknown,
    options?: boolean | AddEventListenerOptions
  ): void;

  addEventListener(
    type: string,
    listener: EventListenerOrEventListenerObject,
    options?: boolean | AddEventListenerOptions
  ): void;

  addEventListener(
    type: string,
    listener: EventListenerOrEventListenerObject,
    options?: boolean | AddEventListenerOptions
  ): void {
    super.addEventListener(type, listener, options);
  }

  // ==========================================================================
  // Private Methods
  // ==========================================================================

  /**
   * Parse HTML attributes into properties.
   */
  private _parseAttributes(): void {
    this._workflowId = this.getAttribute(ATTR_WORKFLOW_ID);
    this._apiBase = this.getAttribute(ATTR_API_BASE) || DEFAULT_API_BASE;
    this._theme = (this.getAttribute(ATTR_THEME) as Theme) || DEFAULT_THEME;
    this._readonly = this.getAttribute(ATTR_READONLY) !== null;
    this._width = this.getAttribute(ATTR_WIDTH) || DEFAULT_WIDTH;
    this._height = this.getAttribute(ATTR_HEIGHT) || DEFAULT_HEIGHT;
  }

  /**
   * Update component size.
   */
  private _updateSize(): void {
    if (this._shadowDom) {
      this._shadowDom.setSize(this._width, this._height);
    }
  }

  /**
   * Render the component.
   */
  private _render(): void {
    if (this._shadowDom) {
      this._shadowDom.render({
        workflow: this._workflow,
        isLoading: this._isLoading,
        isExecuting: this._isExecuting,
      readonly: this._readonly,
      selectedNodeIds: this._selectedNodeIds,
      pendingConnectionSourceId: this._pendingConnectionSourceId
    });
  }
  }

  private _normalizeStepType(type: string): StepType {
    switch (type.toUpperCase()) {
      case StepType.AGENT:
        return StepType.AGENT;
      case StepType.ASSISTANT:
      case 'LLM_CHAT':
        return StepType.ASSISTANT;
      case StepType.TOOL:
      case 'FUNCTION':
      case 'CONDITION':
      case 'PARALLEL':
      case 'LOOP':
        return StepType.TOOL;
      default:
        return StepType.CUSTOM;
    }
  }

  private async _request(input: string, init: RequestInit = {}): Promise<Response> {
    const token = window.localStorage.getItem('archflow_token');
    const headers = new Headers(init.headers || {});

    if (token) {
      headers.set('Authorization', `Bearer ${token}`);
    }

    const response = await fetch(input, {
      ...init,
      headers,
    });

    if (response.status === 401) {
      window.localStorage.removeItem('archflow_token');
      window.localStorage.removeItem('archflow_refresh_token');
      window.location.href = '/login';
      throw new Error('Unauthorized');
    }

    return response;
  }

  private _handleSaveRequested = (): void => {
    void this.saveWorkflow();
  };

  private _handleExecuteRequested = (): void => {
    void this.executeWorkflow();
  };

  private _handleResetRequested = (): void => {
    this.reset();
  };

  private _handleConnectRequested = (event: Event): void => {
    const detail = (event as CustomEvent<{ sourceId?: string; targetId?: string }>).detail;
    const sourceId = detail?.sourceId;
    const targetId = detail?.targetId;

    if (sourceId && targetId) {
      this.connectSteps(sourceId, targetId);
      return;
    }

    if (sourceId) {
      this._pendingConnectionSourceId = sourceId;
      this._render();
    }
  };

  private _handleRemoveRequested = (event: Event): void => {
    const detail = (event as CustomEvent<{ nodeId?: string }>).detail;
    if (detail?.nodeId) {
      this.removeNode(detail.nodeId);
    }
  };
}

// ==========================================================================
// Type Definitions
// ==========================================================================

/**
 * Event map for ArchflowDesigner events.
 */
export interface ArchflowEventMap {
  'workflow-saved': ArchflowWorkflowSavedEvent;
  'workflow-loaded': ArchflowWorkflowLoadedEvent;
  'workflow-executed': ArchflowWorkflowExecutedEvent;
  'node-selected': ArchflowNodeSelectedEvent;
  'nodes-selected': ArchflowNodesSelectedEvent;
  'selection-cleared': ArchflowSelectionClearedEvent;
  'error': ArchflowErrorEvent;
  'connected': ArchflowConnectedEvent;
  'disconnected': ArchflowDisconnectedEvent;
}

/**
 * Base detail for all archflow events.
 */
export interface ArchflowEventDetail {
  timestamp: number;
}

/**
 * Workflow saved event detail.
 */
export interface ArchflowWorkflowSavedEvent extends CustomEvent {
  detail: ArchflowEventDetail & {
    workflow: Flow;
  };
}

/**
 * Workflow loaded event detail.
 */
export interface ArchflowWorkflowLoadedEvent extends CustomEvent {
  detail: ArchflowEventDetail & {
    workflow: Flow;
  };
}

/**
 * Workflow executed event detail.
 */
export interface ArchflowWorkflowExecutedEvent extends CustomEvent {
  detail: ArchflowEventDetail & {
    result: FlowResult;
  };
}

/**
 * Node selected event detail.
 */
export interface ArchflowNodeSelectedEvent extends CustomEvent {
  detail: ArchflowEventDetail & {
    nodeId: string;
  };
}

/**
 * Nodes selected event detail.
 */
export interface ArchflowNodesSelectedEvent extends CustomEvent {
  detail: ArchflowEventDetail & {
    nodeIds: string[];
  };
}

/**
 * Selection cleared event detail.
 */
export interface ArchflowSelectionClearedEvent extends CustomEvent {
  detail: ArchflowEventDetail;
}

/**
 * Error event detail.
 */
export interface ArchflowErrorEvent extends CustomEvent {
  detail: ArchflowEventDetail & {
    type: string;
    message: string;
  };
}

/**
 * Connected event detail.
 */
export interface ArchflowConnectedEvent extends CustomEvent {
  detail: ArchflowEventDetail & {
    component: string;
    version: string;
  };
}

/**
 * Disconnected event detail.
 */
export interface ArchflowDisconnectedEvent extends CustomEvent {
  detail: ArchflowEventDetail & {
    workflowId: string | null;
  };
}

/**
 * Theme type.
 */
export type Theme = 'light' | 'dark';
