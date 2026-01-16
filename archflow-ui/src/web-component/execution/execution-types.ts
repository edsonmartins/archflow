/**
 * Execution System Type Definitions
 *
 * Types for workflow execution including execution state,
 * results, streaming, and history.
 */

import type { NodeInstance } from '../nodes/node-types';
import type { Flow, FlowResult, FlowStatus } from '../../types/flow-types';
import { StepStatus } from '../../types/flow-types';

// ==========================================================================
// Execution State
// ==========================================================================

/**
 * Overall execution state of a workflow.
 */
export enum ExecutionState {
  /** Not yet started */
  IDLE = 'idle',
  /** Currently executing */
  RUNNING = 'running',
  /** Paused waiting for input */
  PAUSED = 'paused',
  /** Completed successfully */
  COMPLETED = 'completed',
  /** Completed with errors */
  FAILED = 'failed',
  /** Cancelled by user */
  CANCELLED = 'cancelled'
}

/**
 * Execution request parameters.
 */
export interface ExecutionRequest {
  /** Workflow ID to execute */
  workflowId: string;
  /** Input data for the workflow */
  input: Record<string, unknown>;
  /** Execution options */
  options?: ExecutionOptions;
}

/**
 * Execution options.
 */
export interface ExecutionOptions {
  /** Enable streaming responses */
  stream?: boolean;
  /** Timeout in milliseconds */
  timeout?: number;
  /** Debug mode for detailed logging */
  debug?: boolean;
  /** Callbacks for events */
  callbacks?: ExecutionCallbacks;
}

/**
 * Event callbacks during execution.
 */
export interface ExecutionCallbacks {
  /** Called when execution starts */
  onStart?: () => void;
  /** Called when execution completes */
  onComplete?: (result: ExecutionResult) => void;
  /** Called when execution fails */
  onError?: (error: ExecutionError) => void;
  /** Called when a node starts executing */
  onNodeStart?: (nodeId: string) => void;
  /** Called when a node completes */
  onNodeComplete?: (nodeId: string, result: NodeExecutionResult) => void;
  /** Called when a node fails */
  onNodeError?: (nodeId: string, error: string) => void;
  /** Called on stream event */
  onStreamEvent?: (event: StreamEvent) => void;
}

// ==========================================================================
// Execution Result
// ==========================================================================

/**
 * Complete execution result.
 */
export interface ExecutionResult {
  /** Execution ID */
  executionId: string;
  /** Final execution state */
  state: ExecutionState;
  /** Workflow output data */
  output: Record<string, unknown>;
  /** All node results */
  nodeResults: NodeExecutionResultMap;
  /** Errors that occurred */
  errors: ExecutionError[];
  /** Execution metrics */
  metrics: ExecutionMetrics;
  /** Start timestamp */
  startTime: number;
  /** End timestamp */
  endTime: number;
  /** Duration in milliseconds */
  duration: number;
}

/**
 * Map of node ID to execution result.
 */
export type NodeExecutionResultMap = Map<string, NodeExecutionResult>;

/**
 * Result of a single node execution.
 */
export interface NodeExecutionResult {
  /** Node ID */
  nodeId: string;
  /** Execution state */
  state: StepStatus;
  /** Output data by port ID */
  outputs: Record<string, unknown>;
  /** Error message if failed */
  error?: string;
  /** Execution time in milliseconds */
  executionTime: number;
  /** Timestamp when execution started */
  startTime: number;
  /** Timestamp when execution ended */
  endTime: number;
  /** Token usage for AI nodes */
  tokensUsed?: number;
}

/**
 * Execution error details.
 */
export interface ExecutionError {
  /** Error ID */
  id: string;
  /** Node ID where error occurred */
  nodeId: string;
  /** Error type */
  type: string;
  /** Error message */
  message: string;
  /** Stack trace */
  stack?: string;
  /** Timestamp */
  timestamp: number;
}

/**
 * Execution metrics.
 */
export interface ExecutionMetrics {
  /** Total execution time */
  totalDuration: number;
  /** Number of nodes executed */
  nodesExecuted: number;
  /** Number of nodes that succeeded */
  nodesSucceeded: number;
  /** Number of nodes that failed */
  nodesFailed: number;
  /** Number of nodes that were skipped */
  nodesSkipped: number;
  /** Total tokens used (for AI nodes) */
  totalTokens: number;
  /** Average execution time per node */
  avgNodeExecutionTime: number;
}

// ==========================================================================
// Streaming Events
// ==========================================================================

/**
 * Streaming event types.
 */
export enum StreamEventType {
  /** Chat message delta (streaming text) */
  DELTA = 'delta',
  /** Full message received */
  MESSAGE = 'message',
  /** Tool execution started */
  TOOL_START = 'tool-start',
  /** Tool execution progress */
  TOOL_PROGRESS = 'tool-progress',
  /** Tool execution complete */
  TOOL_RESULT = 'tool-result',
  /** Tool execution error */
  TOOL_ERROR = 'tool-error',
  /** Node execution started */
  NODE_START = 'node-start',
  /** Node execution complete */
  NODE_COMPLETE = 'node-complete',
  /** Node execution error */
  NODE_ERROR = 'node-error',
  /** Thinking/reasoning (o1) */
  THINKING = 'thinking',
  /** Execution error */
  ERROR = 'error',
  /** Execution complete */
  COMPLETE = 'complete',
  /** Heartbeat */
  HEARTBEAT = 'heartbeat'
}

/**
 * Base streaming event.
 */
export interface StreamEvent {
  /** Event type */
  type: StreamEventType;
  /** Execution ID */
  executionId: string;
  /** Event data */
  data: unknown;
  /** Timestamp */
  timestamp: number;
}

/**
 * Chat delta event (streaming text).
 */
export interface DeltaEvent extends StreamEvent {
  type: StreamEventType.DELTA;
  data: {
    /** Delta text content */
    delta: string;
    /** Node ID */
    nodeId: string;
    /** Message index */
    messageIndex: number;
  };
}

/**
 * Tool execution event.
 */
export interface ToolEvent extends StreamEvent {
  type: StreamEventType.TOOL_START | StreamEventType.TOOL_PROGRESS | StreamEventType.TOOL_RESULT | StreamEventType.TOOL_ERROR;
  data: {
    /** Tool name */
    toolName: string;
    /** Tool input */
    input: Record<string, unknown>;
    /** Tool output (if complete) */
    output?: unknown;
    /** Error message (if error) */
    error?: string;
    /** Progress (0-1) */
    progress?: number;
    /** Node ID */
    nodeId: string;
  };
}

/**
 * Node execution event.
 */
export interface NodeEvent extends StreamEvent {
  type: StreamEventType.NODE_START | StreamEventType.NODE_COMPLETE | StreamEventType.NODE_ERROR;
  data: {
    /** Node ID */
    nodeId: string;
    /** Node type */
    nodeType: string;
    /** Result data (if complete) */
    result?: NodeExecutionResult;
    /** Error message (if error) */
    error?: string;
  };
}

// ==========================================================================
// Execution Store State
// ==========================================================================

/**
 * Current state of the execution store.
 */
export interface ExecutionStoreState {
  /** Current execution state */
  state: ExecutionState;
  /** Current execution ID */
  executionId: string | null;
  /** Current workflow being executed */
  workflow: Flow | null;
  /** Node execution states */
  nodeStates: Map<string, StepStatus>;
  /** Node results */
  nodeResults: NodeExecutionResultMap;
  /** Accumulated output */
  output: Record<string, unknown>;
  /** Errors */
  errors: ExecutionError[];
  /** Current metrics */
  metrics: Partial<ExecutionMetrics>;
  /** Stream connection */
  streamConnection: EventSource | null;
}

// ==========================================================================
// Execution History
// ==========================================================================

/**
 * Entry in execution history.
 */
export interface ExecutionHistoryEntry {
  /** Execution ID */
  executionId: string;
  /** Workflow ID */
  workflowId: string;
  /** Workflow name */
  workflowName: string;
  /** Execution state */
  state: ExecutionState;
  /** Start time */
  startTime: number;
  /** End time */
  endTime?: number;
  /** Duration (ms) */
  duration?: number;
  /** Result (if complete) */
  result?: ExecutionResult;
}

/**
 * Execution history state.
 */
export interface ExecutionHistoryState {
  /** History entries */
  entries: ExecutionHistoryEntry[];
  /** Maximum history size */
  maxSize: number;
  /** Current position */
  currentPosition: number;
}

// ==========================================================================
// Type Guards
// ==========================================================================

/**
 * Check if execution is currently active.
 */
export function isExecutionActive(state: ExecutionState): boolean {
  return [ExecutionState.RUNNING, ExecutionState.PAUSED].includes(state);
}

/**
 * Check if execution is finished.
 */
export function isExecutionFinished(state: ExecutionState): boolean {
  return [ExecutionState.COMPLETED, ExecutionState.FAILED, ExecutionState.CANCELLED].includes(state);
}

/**
 * Check if execution was successful.
 */
export function isExecutionSuccessful(state: ExecutionState): boolean {
  return state === ExecutionState.COMPLETED;
}

/**
 * Check if execution failed.
 */
export function isExecutionFailed(state: ExecutionState): boolean {
  return state === ExecutionState.FAILED;
}

/**
 * Get execution state from FlowStatus.
 */
export function flowStatusToExecutionState(status: FlowStatus): ExecutionState {
  const mapping: Record<FlowStatus, ExecutionState> = {
    INITIALIZED: ExecutionState.IDLE,
    RUNNING: ExecutionState.RUNNING,
    PAUSED: ExecutionState.PAUSED,
    COMPLETED: ExecutionState.COMPLETED,
    FAILED: ExecutionState.FAILED,
    STOPPED: ExecutionState.CANCELLED
  };

  return mapping[status] || ExecutionState.FAILED;
}

/**
 * Get StepStatus from execution state.
 */
export function executionStateToStepStatus(state: ExecutionState): StepStatus {
  const mapping: Record<string, StepStatus> = {
    idle: StepStatus.PENDING,
    running: StepStatus.RUNNING,
    paused: StepStatus.PAUSED,
    completed: StepStatus.COMPLETED,
    failed: StepStatus.FAILED,
    cancelled: StepStatus.CANCELLED
  };

  return mapping[state as string] || StepStatus.PENDING;
}

// ==========================================================================
// Utility Functions
// ==========================================================================

/**
 * Generate a unique execution ID.
 */
export function generateExecutionId(): string {
  return `exec_${Date.now()}_${Math.random().toString(36).substr(2, 9)}`;
}

/**
 * Generate a unique ID.
 */
export function generateId(prefix: string = 'id'): string {
  return `${prefix}_${Date.now()}_${Math.random().toString(36).substr(2, 9)}`;
}

/**
 * Create an empty execution result.
 */
export function createEmptyExecutionResult(executionId: string): ExecutionResult {
  return {
    executionId,
    state: ExecutionState.IDLE,
    output: {},
    nodeResults: new Map(),
    errors: [],
    metrics: {
      totalDuration: 0,
      nodesExecuted: 0,
      nodesSucceeded: 0,
      nodesFailed: 0,
      nodesSkipped: 0,
      totalTokens: 0,
      avgNodeExecutionTime: 0
    },
    startTime: Date.now(),
    endTime: Date.now(),
    duration: 0
  };
}

/**
 * Create a node execution result.
 */
export function createNodeResult(nodeId: string): NodeExecutionResult {
  return {
    nodeId,
    state: StepStatus.PENDING,
    outputs: {},
    executionTime: 0,
    startTime: Date.now(),
    endTime: Date.now()
  };
}

/**
 * Format duration as human-readable string.
 */
export function formatDuration(ms: number): string {
  if (ms < 1000) {
    return `${ms}ms`;
  } else if (ms < 60000) {
    return `${(ms / 1000).toFixed(1)}s`;
  } else {
    const minutes = Math.floor(ms / 60000);
    const seconds = ((ms % 60000) / 1000).toFixed(0);
    return `${minutes}m ${seconds}s`;
  }
}

/**
 * Format timestamp for display.
 */
export function formatTimestamp(timestamp: number): string {
  const date = new Date(timestamp);
  return date.toLocaleTimeString();
}
