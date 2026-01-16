/**
 * Canvas Type Definitions
 *
 * Types for the visual canvas that handles node positioning,
 * connections, and user interactions.
 */

import type { NodeInstance, NodeConnection } from '../nodes/node-types';

// Re-export commonly used types
export type { NodeInstance, NodeConnection };

// ==========================================================================
// Canvas State
// ==========================================================================

/**
 * Complete canvas state.
 */
export interface CanvasState {
  /** All nodes on the canvas */
  nodes: NodeInstance[];
  /** All connections between nodes */
  connections: CanvasConnection[];
  /** Currently selected node IDs */
  selectedNodeIds: string[];
  /** Currently selected connection IDs */
  selectedConnectionIds: string[];
  /** Viewport transform */
  viewport: ViewportTransform;
  /** Canvas configuration */
  config: CanvasConfig;
}

/**
 * Viewport transform for zoom/pan.
 */
export interface ViewportTransform {
  /** X offset in pixels */
  x: number;
  /** Y offset in pixels */
  y: number;
  /** Zoom level (1.0 = 100%) */
  zoom: number;
  /** Minimum zoom level */
  minZoom: number;
  /** Maximum zoom level */
  maxZoom: number;
}

/**
 * Canvas configuration.
 */
export interface CanvasConfig {
  /** Grid size for snap-to-grid */
  gridSize: number;
  /** Enable snap-to-grid */
  snapToGrid: boolean;
  /** Enable minimap */
  showMinimap: boolean;
  /** Background grid type */
  gridType: 'dots' | 'lines' | 'none';
  /** Background color */
  backgroundColor: string;
  /** Grid color */
  gridColor: string;
  /** Connection color */
  connectionColor: string;
  /** Connection width */
  connectionWidth: number;
  /** Enable zoom */
  enableZoom: boolean;
  /** Enable pan */
  enablePan: boolean;
  /** Minimap position */
  minimapPosition: 'top-right' | 'top-left' | 'bottom-right' | 'bottom-left';
}

// ==========================================================================
// Canvas Connection (Edge)
// ==========================================================================

/**
 * Extended connection type with canvas-specific properties.
 */
export interface CanvasConnection extends NodeConnection {
  /** Bezier curve control points */
  controlPoints?: ControlPoints;
  /** Connection is invalid (type mismatch) */
  invalid?: boolean;
  /** Connection is highlighted */
  highlighted?: boolean;
  /** Connection is being created (dragging) */
  pending?: boolean;
}

/**
 * Control points for bezier curves.
 */
export interface ControlPoints {
  /** Source control point */
  source: { x: number; y: number };
  /** Target control point */
  target: { x: number; y: number };
}

// ==========================================================================
// Canvas Events
// ==========================================================================

/**
 * Canvas event types.
 */
export enum CanvasEventType {
  /** Node position changed */
  NODE_MOVED = 'node-moved',
  /** Node added to canvas */
  NODE_ADDED = 'node-added',
  /** Node removed from canvas */
  NODE_REMOVED = 'node-removed',
  /** Node selected */
  NODE_SELECTED = 'node-selected',
  /** Node deselected */
  NODE_DESELECTED = 'node-deselected',
  /** Connection created */
  CONNECTION_CREATED = 'connection-created',
  /** Connection removed */
  CONNECTION_REMOVED = 'connection-removed',
  /** Connection selected */
  CONNECTION_SELECTED = 'connection-selected',
  /** Viewport changed (zoom/pan) */
  VIEWPORT_CHANGED = 'viewport-changed',
  /** Selection cleared */
  SELECTION_CLEARED = 'selection-cleared',
  /** Canvas state changed */
  STATE_CHANGED = 'state-changed'
}

/**
 * Base canvas event.
 */
export interface CanvasEvent {
  type: CanvasEventType;
  timestamp: number;
}

/**
 * Node moved event.
 */
export interface NodeMovedEvent extends CanvasEvent {
  type: CanvasEventType.NODE_MOVED;
  nodeId: string;
  oldPosition: { x: number; y: number };
  newPosition: { x: number; y: number };
}

/**
 * Node added event.
 */
export interface NodeAddedEvent extends CanvasEvent {
  type: CanvasEventType.NODE_ADDED;
  node: NodeInstance;
}

/**
 * Node removed event.
 */
export interface NodeRemovedEvent extends CanvasEvent {
  type: CanvasEventType.NODE_REMOVED;
  nodeId: string;
}

/**
 * Connection created event.
 */
export interface ConnectionCreatedEvent extends CanvasEvent {
  type: CanvasEventType.CONNECTION_CREATED;
  connection: CanvasConnection;
}

/**
 * Connection removed event.
 */
export interface ConnectionRemovedEvent extends CanvasEvent {
  type: CanvasEventType.CONNECTION_REMOVED;
  connectionId: string;
}

/**
 * Viewport changed event.
 */
export interface ViewportChangedEvent extends CanvasEvent {
  type: CanvasEventType.VIEWPORT_CHANGED;
  viewport: ViewportTransform;
}

/**
 * Union type of all canvas events.
 */
export type AnyCanvasEvent =
  | NodeMovedEvent
  | NodeAddedEvent
  | NodeRemovedEvent
  | ConnectionCreatedEvent
  | ConnectionRemovedEvent
  | ViewportChangedEvent
  | CanvasEvent;

// ==========================================================================
// Interaction States
// ==========================================================================

/**
 * Current interaction mode.
 */
export enum InteractionMode {
  /** Normal mode - select and drag nodes */
  SELECT = 'select',
  /** Panning the canvas */
  PAN = 'pan',
  /** Creating a connection */
  CONNECT = 'connect',
  /** Box selection */
  BOX_SELECT = 'box-select',
  /** Disabled */
  NONE = 'none'
}

/**
 * Current drag state.
 */
export interface DragState {
  /** Currently dragging */
  isDragging: boolean;
  /** Type being dragged */
  dragType: 'node' | 'connection' | 'selection' | 'viewport' | null;
  /** ID being dragged (if single) */
  draggedId: string | null;
  /** IDs being dragged (if multiple) */
  draggedIds: string[];
  /** Start position */
  startPosition: { x: number; y: number };
  /** Current position */
  currentPosition: { x: number; y: number };
  /** Drag offset for each node */
  offsets: Map<string, { x: number; y: number }>;
}

/**
 * Connection creation state.
 */
export interface ConnectionState {
  /** Currently creating connection */
  isCreating: boolean;
  /** Source node ID */
  sourceNodeId: string | null;
  /** Source port ID */
  sourcePortId: string | null;
  /** Current mouse position */
  currentPosition: { x: number; y: number } | null;
  /** Target port under cursor */
  targetPortId: string | null;
  /** Connection is valid */
  isValid: boolean;
}

/**
 * Box selection state.
 */
export interface BoxSelectionState {
  /** Currently selecting */
  isSelecting: boolean;
  /** Start position */
  startPosition: { x: number; y: number };
  /** Current position */
  currentPosition: { x: number; y: number };
  /** Selected node IDs */
  selectedNodeIds: string[];
}

// ==========================================================================
// Canvas Layout
// ==========================================================================

/**
 * Node bounds on canvas.
 */
export interface NodeBounds {
  /** Node ID */
  nodeId: string;
  /** X position */
  x: number;
  /** Y position */
  y: number;
  /** Width */
  width: number;
  /** Height */
  height: number;
}

/**
 * Port position on canvas.
 */
export interface PortPosition {
  /** Node ID */
  nodeId: string;
  /** Port ID */
  portId: string;
  /** Port type (input/output) */
  type: 'input' | 'output';
  /** Absolute X position */
  x: number;
  /** Absolute Y position */
  y: number;
}

/**
 * Canvas bounds (all nodes combined).
 */
export interface CanvasBounds {
  /** Minimum X */
  minX: number;
  /** Minimum Y */
  minY: number;
  /** Maximum X */
  maxX: number;
  /** Maximum Y */
  maxY: number;
  /** Width */
  width: number;
  /** Height */
  height;
}

// ==========================================================================
// Rendering
// ==========================================================================

/**
 * Canvas layer for rendering order.
 */
export enum CanvasLayer {
  /** Background grid */
  BACKGROUND = 'background',
  /** Connections (behind nodes) */
  CONNECTIONS = 'connections',
  /** Pending connection */
  PENDING_CONNECTION = 'pending-connection',
  /** Nodes */
  NODES = 'nodes',
  /** Selection box */
  SELECTION = 'selection',
  /** UI overlays (minimap, etc.) */
  OVERLAY = 'overlay'
}

/**
 * Render data for the canvas.
 */
export interface CanvasRenderData {
  /** Nodes to render */
  nodes: Array<{
    node: NodeInstance;
    bounds: NodeBounds;
    selected: boolean;
  }>;
  /** Connections to render */
  connections: Array<{
    connection: CanvasConnection;
    path: string;
    selected: boolean;
    highlighted: boolean;
  }>;
  /** Pending connection path (if creating) */
  pendingConnection?: string;
  /** Selection box (if selecting) */
  selectionBox?: { x: number; y: number; width: number; height: number };
}

// ==========================================================================
// Undo/Redo
// ==========================================================================

/**
 * History action for undo/redo.
 */
export interface HistoryAction {
  /** Action ID */
  id: string;
  /** Action type */
  type: 'add-node' | 'remove-node' | 'move-node' | 'add-connection' | 'remove-connection' | 'batch';
  /** Timestamp */
  timestamp: number;
  /** Description */
  description: string;
  /** Before state */
  before: Partial<CanvasState>;
  /** After state */
  after: Partial<CanvasState>;
  /** Sub-actions (for batch) */
  actions?: HistoryAction[];
}

/**
 * History state.
 */
export interface HistoryState {
  /** Undo stack */
  undo: HistoryAction[];
  /** Redo stack */
  redo: HistoryAction[];
  /** Current position */
  currentPosition: number;
  /** Maximum history size */
  maxSize: number;
}

// ==========================================================================
// Utilities
// ==========================================================================

/**
 * Convert screen coordinates to canvas coordinates.
 */
export function screenToCanvas(
  screenX: number,
  screenY: number,
  viewport: ViewportTransform,
  canvasRect: DOMRect
): { x: number; y: number } {
  return {
    x: (screenX - canvasRect.left - viewport.x) / viewport.zoom,
    y: (screenY - canvasRect.top - viewport.y) / viewport.zoom
  };
}

/**
 * Convert canvas coordinates to screen coordinates.
 */
export function canvasToScreen(
  canvasX: number,
  canvasY: number,
  viewport: ViewportTransform,
  canvasRect: DOMRect
): { x: number; y: number } {
  return {
    x: canvasX * viewport.zoom + viewport.x + canvasRect.left,
    y: canvasY * viewport.zoom + viewport.y + canvasRect.top
  };
}

/**
 * Snap a value to grid.
 */
export function snapToGrid(value: number, gridSize: number): number {
  return Math.round(value / gridSize) * gridSize;
}

/**
 * Calculate distance between two points.
 */
export function distance(
  x1: number,
  y1: number,
  x2: number,
  y2: number
): number {
  return Math.sqrt((x2 - x1) ** 2 + (y2 - y1) ** 2);
}

/**
 * Check if a point is inside a rectangle.
 */
export function pointInRect(
  px: number,
  py: number,
  rx: number,
  ry: number,
  rw: number,
  rh: number
): boolean {
  return px >= rx && px <= rx + rw && py >= ry && py <= ry + rh;
}

/**
 * Get default canvas config.
 */
export function getDefaultCanvasConfig(): CanvasConfig {
  return {
    gridSize: 20,
    snapToGrid: true,
    showMinimap: false,
    gridType: 'dots',
    backgroundColor: '#f8fafc',
    gridColor: '#e2e8f0',
    connectionColor: '#64748b',
    connectionWidth: 2,
    enableZoom: true,
    enablePan: true,
    minimapPosition: 'top-right'
  };
}

/**
 * Get default viewport transform.
 */
export function getDefaultViewport(): ViewportTransform {
  return {
    x: 0,
    y: 0,
    zoom: 1,
    minZoom: 0.1,
    maxZoom: 3
  };
}

/**
 * Generate a unique ID.
 */
export function generateId(prefix: string): string {
  return `${prefix}_${Date.now()}_${Math.random().toString(36).substr(2, 9)}`;
}
