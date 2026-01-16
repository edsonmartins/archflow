/**
 * ArchflowEventDispatcher - Manages CustomEvents for the Web Component
 *
 * Provides type-safe event dispatching with automatic timestamp injection.
 * All events follow the CustomEvent specification for framework compatibility.
 */

// ==========================================================================
// Event Types
// ==========================================================================

/**
 * Base event detail with timestamp.
 */
export interface BaseEventDetail {
  timestamp: number;
}

/**
 * Workflow saved event detail.
 */
export interface WorkflowSavedDetail extends BaseEventDetail {
  workflow: unknown;
}

/**
 * Workflow loaded event detail.
 */
export interface WorkflowLoadedDetail extends BaseEventDetail {
  workflow: unknown;
}

/**
 * Workflow executed event detail.
 */
export interface WorkflowExecutedDetail extends BaseEventDetail {
  result: unknown;
}

/**
 * Node selected event detail.
 */
export interface NodeSelectedDetail extends BaseEventDetail {
  nodeId: string;
}

/**
 * Nodes selected event detail.
 */
export interface NodesSelectedDetail extends BaseEventDetail {
  nodeIds: string[];
}

/**
 * Error event detail.
 */
export interface ErrorDetail extends BaseEventDetail {
  type: string;
  message: string;
}

/**
 * Connected event detail.
 */
export interface ConnectedDetail extends BaseEventDetail {
  component: string;
  version: string;
}

/**
 * Disconnected event detail.
 */
export interface DisconnectedDetail extends BaseEventDetail {
  workflowId: string | null;
}

/**
 * All event detail types union.
 */
export type EventDetail =
  | BaseEventDetail
  | WorkflowSavedDetail
  | WorkflowLoadedDetail
  | WorkflowExecutedDetail
  | NodeSelectedDetail
  | NodesSelectedDetail
  | ErrorDetail
  | ConnectedDetail
  | DisconnectedDetail;

// ==========================================================================
// Event Type Definitions
// ==========================================================================

/**
 * Event names that can be dispatched by ArchflowDesigner.
 */
export const EVENT_NAMES = {
  WORKFLOW_SAVED: 'workflow-saved',
  WORKFLOW_LOADED: 'workflow-loaded',
  WORKFLOW_EXECUTED: 'workflow-executed',
  NODE_SELECTED: 'node-selected',
  NODES_SELECTED: 'nodes-selected',
  SELECTION_CLEARED: 'selection-cleared',
  ERROR: 'archflow-error',
  CONNECTED: 'archflow-connected',
  DISCONNECTED: 'archflow-disconnected'
} as const;

export type ArchflowEventName = (typeof EVENT_NAMES)[keyof typeof EVENT_NAMES];

// ==========================================================================
// ArchflowEventDispatcher Class
// ==========================================================================

export class ArchflowEventDispatcher {
  private target: EventTarget;

  constructor(target: EventTarget) {
    this.target = target;
  }

  /**
   * Emit a workflow-saved event.
   */
  emitWorkflowSaved(workflow: unknown): void {
    this.emit(EVENT_NAMES.WORKFLOW_SAVED, { workflow });
  }

  /**
   * Emit a workflow-loaded event.
   */
  emitWorkflowLoaded(workflow: unknown): void {
    this.emit(EVENT_NAMES.WORKFLOW_LOADED, { workflow });
  }

  /**
   * Emit a workflow-executed event.
   */
  emitWorkflowExecuted(result: unknown): void {
    this.emit(EVENT_NAMES.WORKFLOW_EXECUTED, { result });
  }

  /**
   * Emit a node-selected event.
   */
  emitNodeSelected(nodeId: string): void {
    this.emit(EVENT_NAMES.NODE_SELECTED, { nodeId });
  }

  /**
   * Emit a nodes-selected event.
   */
  emitNodesSelected(nodeIds: string[]): void {
    this.emit(EVENT_NAMES.NODES_SELECTED, { nodeIds });
  }

  /**
   * Emit a selection-cleared event.
   */
  emitSelectionCleared(): void {
    this.emit(EVENT_NAMES.SELECTION_CLEARED, {});
  }

  /**
   * Emit an error event.
   */
  emitError(type: string, message: string): void {
    this.emit(EVENT_NAMES.ERROR, { type, message });
  }

  /**
   * Emit a connected event.
   */
  emitConnected(component: string, version: string): void {
    this.emit(EVENT_NAMES.CONNECTED, { component, version });
  }

  /**
   * Emit a disconnected event.
   */
  emitDisconnected(workflowId: string | null): void {
    this.emit(EVENT_NAMES.DISCONNECTED, { workflowId });
  }

  /**
   * Generic emit method.
   *
   * @param name Event name
   * @param detail Event detail (timestamp added automatically)
   */
  emit(name: string, detail: Omit<EventDetail, 'timestamp'>): void {
    const eventDetail = {
      ...detail,
      timestamp: Date.now()
    };

    const event = new CustomEvent(name, {
      detail: eventDetail,
      bubbles: true,
      composed: true, // Allows event to cross Shadow DOM boundary
      cancelable: true
    });

    this.target.dispatchEvent(event);
  }

  /**
   * Check if an event listener is attached.
   */
  hasListener(name: string): boolean {
    // This is a simplified check - actual implementation would need
    // to track registered listeners
    return true;
  }
}

// ==========================================================================
// Utility Functions
// ==========================================================================

/**
 * Create a typed event listener for archflow events.
 */
export function createEventListener<T extends EventDetail>(
  callback: (detail: T & BaseEventDetail) => void
): (event: Event) => void {
  return (event: Event) => {
    const customEvent = event as CustomEvent<T & BaseEventDetail>;
    if (customEvent.detail) {
      callback(customEvent.detail);
    }
  };
}

/**
 * Safe event detail extractor with null check.
 */
export function getEventDetail<T extends EventDetail>(
  event: Event
): (T & BaseEventDetail) | null {
  const customEvent = event as CustomEvent<T & BaseEventDetail>;
  return customEvent.detail || null;
}
