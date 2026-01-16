/**
 * ExecutionStore - Manages workflow execution state
 *
 * Handles:
 * - Execution lifecycle (start, pause, resume, cancel)
 * - Node execution tracking
 * - Result accumulation
 * - Error handling
 * - SSE streaming connection
 */

import type {
  ExecutionRequest,
  ExecutionOptions,
  ExecutionCallbacks,
  ExecutionResult,
  ExecutionStoreState,
  NodeExecutionResult,
  NodeExecutionResultMap,
  ExecutionError,
  ExecutionMetrics,
  StreamEvent,
  ExecutionHistoryEntry,
  ExecutionHistoryState
} from './execution-types';
import type { Flow } from '../../types/flow-types';
import { StepStatus } from '../../types/flow-types';
import {
  ExecutionState,
  generateExecutionId,
  generateId,
  createEmptyExecutionResult,
  createNodeResult,
  flowStatusToExecutionState,
  executionStateToStepStatus,
  formatDuration,
  isExecutionActive,
  isExecutionFinished,
  StreamEventType
} from './execution-types';

// ==========================================================================
// ExecutionStore Options
// ==========================================================================

export interface ExecutionStoreOptions {
  /** API base URL */
  apiBase?: string;
  /** Default execution timeout (ms) */
  defaultTimeout?: number;
  /** Maximum history size */
  maxHistorySize?: number;
  /** Event callback for state changes */
  onStateChange?: (state: ExecutionStoreState) => void;
}

// ==========================================================================
// ExecutionStore Class
// ==========================================================================

export class ExecutionStore {
  private _state: ExecutionStoreState;
  private _history: ExecutionHistoryState;
  private _options: Required<Pick<ExecutionStoreOptions, 'apiBase' | 'defaultTimeout' | 'maxHistorySize'>>;
  private _onStateChange?: (state: ExecutionStoreState) => void;
  private _currentCallbacks: ExecutionCallbacks = {};

  // Store the current execution result (accumulating)
  private _currentResult: ExecutionResult | null = null;

  constructor(options: ExecutionStoreOptions = {}) {
    this._options = {
      apiBase: options.apiBase || '/api',
      defaultTimeout: options.defaultTimeout || 300000, // 5 minutes
      maxHistorySize: options.maxHistorySize || 50
    };

    this._onStateChange = options.onStateChange;

    this._state = this._createInitialState();
    this._history = this._createInitialHistory();
  }

  // ==========================================================================
  // Public Properties
  // ==========================================================================

  get state(): Readonly<ExecutionStoreState> {
    return this._state;
  }

  get executionId(): string | null {
    return this._state.executionId;
  }

  get executionState(): ExecutionState {
    return this._state.state;
  }

  get isExecuting(): boolean {
    return isExecutionActive(this._state.state);
  }

  get isFinished(): boolean {
    return isExecutionFinished(this._state.state);
  }

  get nodeStates(): Readonly<Map<string, StepStatus>> {
    return this._state.nodeStates;
  }

  get nodeResults(): Readonly<NodeExecutionResultMap> {
    return this._state.nodeResults;
  }

  get output(): Readonly<Record<string, unknown>> {
    return this._state.output;
  }

  get errors(): Readonly<ExecutionError[]> {
    return this._state.errors;
  }

  get metrics(): Readonly<ExecutionMetrics> {
    return {
      totalDuration: this._state.metrics.totalDuration || 0,
      nodesExecuted: this._state.metrics.nodesExecuted || 0,
      nodesSucceeded: this._state.metrics.nodesSucceeded || 0,
      nodesFailed: this._state.metrics.nodesFailed || 0,
      nodesSkipped: this._state.metrics.nodesSkipped || 0,
      totalTokens: this._state.metrics.totalTokens || 0,
      avgNodeExecutionTime: this._state.metrics.avgNodeExecutionTime || 0
    } as ExecutionMetrics;
  }

  // ==========================================================================
  // Execution Lifecycle
  // ==========================================================================

  /**
   * Start a workflow execution.
   */
  async execute(
    workflow: Flow,
    input: Record<string, unknown>,
    options: ExecutionOptions = {}
  ): Promise<ExecutionResult> {
    if (this.isExecuting) {
      throw new Error('Execution already in progress');
    }

    const executionId = generateExecutionId();

    // Reset state for new execution
    this._state.executionId = executionId;
    this._state.workflow = workflow;
    this._state.state = ExecutionState.RUNNING;
    this._state.nodeStates = new Map();
    this._state.nodeResults = new Map();
    this._state.output = {};
    this._state.errors = [];

    // Initialize result
    this._currentResult = createEmptyExecutionResult(executionId);
    this._currentResult.startTime = Date.now();

    // Store callbacks
    this._currentCallbacks = options.callbacks || {};

    // Notify start
    this._notifyStateChange();
    this._currentCallbacks.onStart?.();

    try {
      let result: ExecutionResult;

      if (options.stream) {
        // Execute with streaming
        result = await this._executeWithStreaming(workflow, input, options);
      } else {
        // Execute with simple REST call
        result = await this._executeWithREST(workflow, input, options);
      }

      // Update final state
      this._state.state = result.state;
      this._state.output = result.output;
      this._state.metrics = result.metrics;

      // Add to history
      this._addToHistory({
        executionId: result.executionId,
        workflowId: workflow.id,
        workflowName: workflow.metadata.name || workflow.id,
        state: result.state,
        startTime: result.startTime,
        endTime: result.endTime,
        duration: result.duration,
        result
      });

      // Notify completion
      this._notifyStateChange();
      this._currentCallbacks.onComplete?.(result);

      return result;
    } catch (error) {
      // Handle execution error
      this._state.state = ExecutionState.FAILED;
      this._state.errors.push({
        id: generateId(),
        nodeId: 'workflow',
        type: 'ExecutionError',
        message: error instanceof Error ? error.message : 'Unknown error',
        stack: error instanceof Error ? error.stack : undefined,
        timestamp: Date.now()
      });

      // Add to history
      this._addToHistory({
        executionId,
        workflowId: workflow.id,
        workflowName: workflow.metadata.name || workflow.id,
        state: ExecutionState.FAILED,
        startTime: this._currentResult?.startTime || Date.now(),
        endTime: Date.now(),
        duration: 0
      });

      this._notifyStateChange();
      this._currentCallbacks.onError?.(this._state.errors[0]);

      throw error;
    } finally {
      // Close stream connection if open
      if (this._state.streamConnection) {
        this._state.streamConnection.close();
        this._state.streamConnection = null;
      }

      // Update duration
      if (this._currentResult) {
        this._currentResult.endTime = Date.now();
        this._currentResult.duration = this._currentResult.endTime - this._currentResult.startTime;
      }

      this._currentResult = null;
      this._currentCallbacks = {};
    }
  }

  /**
   * Cancel current execution.
   */
  cancel(): void {
    if (!this.isExecuting) return;

    if (this._state.streamConnection) {
      this._state.streamConnection.close();
      this._state.streamConnection = null;
    }

    this._state.state = ExecutionState.CANCELLED;
    this._notifyStateChange();
  }

  /**
   * Pause current execution.
   */
  pause(): void {
    if (this._state.state !== ExecutionState.RUNNING) return;

    this._state.state = ExecutionState.PAUSED;
    this._notifyStateChange();
  }

  /**
   * Resume paused execution.
   */
  resume(): void {
    if (this._state.state !== ExecutionState.PAUSED) return;

    this._state.state = ExecutionState.RUNNING;
    this._notifyStateChange();
  }

  // ==========================================================================
  // Node State Tracking
  // ==========================================================================

  /**
   * Update node execution state.
   */
  updateNodeState(nodeId: string, state: StepStatus): void {
    this._state.nodeStates.set(nodeId, state);
    this._notifyStateChange();
  }

  /**
   * Add node execution result.
   */
  addNodeResult(result: NodeExecutionResult): void {
    this._state.nodeResults.set(result.nodeId, result);

    // Update metrics
    const metrics = this._state.metrics;
    metrics.nodesExecuted = (metrics.nodesExecuted || 0) + 1;

    if (result.state === 'COMPLETED') {
      metrics.nodesSucceeded = (metrics.nodesSucceeded || 0) + 1;
    } else if (result.state === 'FAILED') {
      metrics.nodesFailed = (metrics.nodesFailed || 0) + 1;
    } else if (result.state === 'SKIPPED') {
      metrics.nodesSkipped = (metrics.nodesSkipped || 0) + 1;
    }

    // Add to total tokens
    if (result.tokensUsed) {
      metrics.totalTokens = (metrics.totalTokens || 0) + result.tokensUsed;
    }

    // Update average execution time
    const totalTime = (metrics.totalDuration || 0) + result.executionTime;
    const avgTime = totalTime / (metrics.nodesExecuted || 1);
    metrics.avgNodeExecutionTime = avgTime;

    this._notifyStateChange();
  }

  /**
   * Add execution error.
   */
  addError(error: ExecutionError): void {
    this._state.errors.push(error);
    this._notifyStateChange();
  }

  // ==========================================================================
  // History
  // ==========================================================================

  /**
   * Get execution history.
   */
  getHistory(): ExecutionHistoryEntry[] {
    return this._history.entries;
  }

  /**
   * Clear execution history.
   */
  clearHistory(): void {
    this._history.entries = [];
    this._history.currentPosition = 0;
  }

  /**
   * Get history entry by execution ID.
   */
  getHistoryEntry(executionId: string): ExecutionHistoryEntry | undefined {
    return this._history.entries.find(e => e.executionId === executionId);
  }

  // ==========================================================================
  // Private Methods - Execution
  // ==========================================================================

  private async _executeWithREST(
    workflow: Flow,
    input: Record<string, unknown>,
    options: ExecutionOptions
  ): Promise<ExecutionResult> {
    const timeout = options.timeout || this._options.defaultTimeout;

    const response = await fetch(`${this._options.apiBase}/workflows/execute`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        workflowId: workflow.id,
        input,
        options: {
          timeout,
          debug: options.debug || false
        }
      }),
      signal: AbortSignal.timeout(timeout)
    });

    if (!response.ok) {
      throw new Error(`Execution failed: ${response.statusText}`);
    }

    const result = await response.json();
    return this._parseExecutionResult(result);
  }

  private async _executeWithStreaming(
    workflow: Flow,
    input: Record<string, unknown>,
    options: ExecutionOptions
  ): Promise<ExecutionResult> {
    return new Promise((resolve, reject) => {
      const timeout = options.timeout || this._options.defaultTimeout;

      // Create SSE connection
      const url = new URL(`${this._options.apiBase}/workflows/execute/stream`, window.location.origin);
      url.searchParams.append('workflowId', workflow.id);
      url.searchParams.append('input', JSON.stringify(input));

      const eventSource = new EventSource(url.toString());
      this._state.streamConnection = eventSource;

      // Track node states during streaming
      const nodeStartTimes = new Map<string, number>();

      // Handle stream events
      eventSource.addEventListener('message', (event: MessageEvent) => {
        try {
          const data = JSON.parse(event.data);
          const streamEvent = data as StreamEvent;

          switch (streamEvent.type) {
            case StreamEventType.NODE_START:
            case StreamEventType.NODE_COMPLETE:
            case StreamEventType.NODE_ERROR: {
              const nodeEvent = streamEvent as import('./execution-types').NodeEvent;
              this._handleNodeEvent(nodeEvent, nodeStartTimes);
              break;
            }
            case StreamEventType.TOOL_START:
            case StreamEventType.TOOL_RESULT:
            case StreamEventType.TOOL_ERROR: {
              const toolEvent = streamEvent as import('./execution-types').ToolEvent;
              this._handleToolEvent(toolEvent);
              break;
            }
            case StreamEventType.DELTA: {
              const deltaEvent = streamEvent as import('./execution-types').DeltaEvent;
              this._handleDeltaEvent(deltaEvent);
              break;
            }
            case StreamEventType.ERROR: {
              this._handleStreamError(streamEvent.data);
              break;
            }
            case StreamEventType.COMPLETE: {
              const completeResult = streamEvent.data as ExecutionResult;
              eventSource.close();
              resolve(this._parseExecutionResult(completeResult));
              break;
            }
          }

          // Notify callback
          this._currentCallbacks.onStreamEvent?.(streamEvent);

        } catch (error) {
          console.error('Error parsing stream event:', error);
        }
      });

      // Handle connection errors
      eventSource.addEventListener('error', (error) => {
        console.error('SSE connection error:', error);
        eventSource.close();
        reject(new Error('Stream connection failed'));
      });

      // Set timeout
      setTimeout(() => {
        if (eventSource.readyState === EventSource.CONNECTING || eventSource.readyState === EventSource.OPEN) {
          eventSource.close();
          reject(new Error('Execution timeout'));
        }
      }, timeout);
    });
  }

  private _handleNodeEvent(
    event: import('./execution-types').NodeEvent,
    nodeStartTimes: Map<string, number>
  ): void {
    const { nodeId, nodeType, result, error } = event.data;

    switch (event.type) {
      case StreamEventType.NODE_START:
        nodeStartTimes.set(nodeId, Date.now());
        this.updateNodeState(nodeId, StepStatus.RUNNING);
        this._currentCallbacks.onNodeStart?.(nodeId);
        break;

      case StreamEventType.NODE_COMPLETE:
        const startTime = nodeStartTimes.get(nodeId) || Date.now();
        const executionTime = Date.now() - startTime;

        const nodeResult: NodeExecutionResult = {
          nodeId,
          state: StepStatus.COMPLETED,
          outputs: (result as NodeExecutionResult)?.outputs || {},
          executionTime,
          startTime,
          endTime: Date.now(),
          tokensUsed: (result as NodeExecutionResult)?.tokensUsed
        };

        this.addNodeResult(nodeResult);
        this.updateNodeState(nodeId, StepStatus.COMPLETED);
        this._currentCallbacks.onNodeComplete?.(nodeId, nodeResult);
        nodeStartTimes.delete(nodeId);
        break;

      case StreamEventType.NODE_ERROR:
        this.updateNodeState(nodeId, StepStatus.FAILED);
        this.addError({
          id: generateId(),
          nodeId,
          type: 'NodeError',
          message: error || 'Node execution failed',
          timestamp: Date.now()
        });
        this._currentCallbacks.onNodeError?.(nodeId, error || 'Unknown error');
        nodeStartTimes.delete(nodeId);
        break;
    }
  }

  private _handleToolEvent(event: import('./execution-types').ToolEvent): void {
    const { toolName, progress, output, error } = event.data;

    // Tool events are handled through node callbacks
    // Could add specific tool tracking here
  }

  private _handleDeltaEvent(event: import('./execution-types').DeltaEvent): void {
    const { delta, nodeId } = event.data;

    // Accumulate delta in output
    const currentOutput = this._state.output[nodeId] as string || '';
    this._state.output[nodeId] = currentOutput + delta;

    this._notifyStateChange();
  }

  private _handleStreamError(data: unknown): void {
    const errorMessage = typeof data === 'string' ? data : JSON.stringify(data);
    this.addError({
      id: generateId(),
      nodeId: 'workflow',
      type: 'StreamError',
      message: errorMessage,
      timestamp: Date.now()
    });
  }

  private _parseExecutionResult(data: any): ExecutionResult {
    return {
      executionId: data.executionId || this._state.executionId || generateExecutionId(),
      state: flowStatusToExecutionState(data.status || 'COMPLETED'),
      output: data.output || {},
      nodeResults: new Map(),
      errors: (data.errors || []).map((e: any) => ({
        id: e.id || generateId(),
        nodeId: e.nodeId || 'unknown',
        type: e.type || 'Error',
        message: e.message || 'Unknown error',
        timestamp: e.timestamp || Date.now()
      })),
      metrics: {
        totalDuration: data.duration || 0,
        nodesExecuted: data.nodesExecuted || 0,
        nodesSucceeded: data.nodesSucceeded || 0,
        nodesFailed: data.nodesFailed || 0,
        nodesSkipped: data.nodesSkipped || 0,
        totalTokens: data.totalTokens || 0,
        avgNodeExecutionTime: data.avgNodeExecutionTime || 0
      },
      startTime: data.startTime || Date.now(),
      endTime: data.endTime || Date.now(),
      duration: data.duration || 0
    };
  }

  // ==========================================================================
  // Private Methods - History
  // ==========================================================================

  private _addToHistory(entry: ExecutionHistoryEntry): void {
    this._history.entries.push(entry);

    // Limit history size
    if (this._history.entries.length > this._history.maxSize) {
      this._history.entries.shift();
    }

    this._history.currentPosition = this._history.entries.length;
  }

  // ==========================================================================
  // Private Methods - State
  // ==========================================================================

  private _createInitialState(): ExecutionStoreState {
    return {
      state: ExecutionState.IDLE,
      executionId: null,
      workflow: null,
      nodeStates: new Map(),
      nodeResults: new Map(),
      output: {},
      errors: [],
      metrics: {},
      streamConnection: null
    };
  }

  private _createInitialHistory(): ExecutionHistoryState {
    return {
      entries: [],
      maxSize: this._options.maxHistorySize,
      currentPosition: 0
    };
  }

  private _notifyStateChange(): void {
    if (this._onStateChange) {
      this._onStateChange(this._state);
    }
  }
}

// ==========================================================================
// Helper Functions
// ==========================================================================

/**
 * Create an execution store with default options.
 */
export function createExecutionStore(options?: ExecutionStoreOptions): ExecutionStore {
  return new ExecutionStore(options);
}
