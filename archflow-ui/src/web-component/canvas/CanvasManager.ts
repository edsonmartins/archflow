/**
 * CanvasManager - Manages the visual canvas state and interactions
 *
 * Handles:
 * - Node positioning and drag-drop
 * - Connection management
 * - Viewport (zoom/pan)
 * - Selection (single and multi)
 * - Undo/redo history
 */

import type {
  CanvasState,
  ViewportTransform,
  CanvasConfig,
  CanvasConnection,
  NodeInstance,
  NodeBounds,
  PortPosition,
  DragState,
  ConnectionState,
  BoxSelectionState,
  HistoryState,
  HistoryAction,
  CanvasEvent,
  AnyCanvasEvent
} from './canvas-types';
import {
  CanvasEventType,
  InteractionMode,
  getDefaultCanvasConfig,
  getDefaultViewport,
  generateId,
  snapToGrid,
  screenToCanvas,
  distance
} from './canvas-types';
import { getNodeRegistry } from '../nodes/NodeRegistry';

// ==========================================================================
// Canvas Options
// ==========================================================================

export interface CanvasManagerOptions {
  /** Initial canvas config */
  config?: Partial<CanvasConfig>;
  /** Initial viewport */
  viewport?: Partial<ViewportTransform>;
  /** Max history size */
  maxHistorySize?: number;
  /** Event callback */
  onEvent?: (event: AnyCanvasEvent) => void;
}

// ==========================================================================
// CanvasManager Class
// ==========================================================================

export class CanvasManager {
  private _state: CanvasState;
  private _dragState: DragState;
  private _connectionState: ConnectionState;
  private _boxSelectionState: BoxSelectionState;
  private _history: HistoryState;
  private _interactionMode: InteractionMode = InteractionMode.SELECT;
  private _eventCallback?: (event: AnyCanvasEvent) => void;

  // Node dimensions (cached for performance)
  private _nodeDimensions: Map<string, { width: number; height: number }> = new Map();

  constructor(options: CanvasManagerOptions = {}) {
    this._state = {
      nodes: [],
      connections: [],
      selectedNodeIds: [],
      selectedConnectionIds: [],
      viewport: { ...getDefaultViewport(), ...options.viewport },
      config: { ...getDefaultCanvasConfig(), ...options.config }
    };

    this._dragState = this._createInitialDragState();
    this._connectionState = this._createInitialConnectionState();
    this._boxSelectionState = this._createInitialBoxSelectionState();
    this._history = this._createInitialHistory(options.maxHistorySize);
    this._eventCallback = options.onEvent;

    this._setDefaultNodeDimensions();
  }

  // ==========================================================================
  // Public Properties
  // ==========================================================================

  get state(): Readonly<CanvasState> {
    return this._state;
  }

  get viewport(): Readonly<ViewportTransform> {
    return this._state.viewport;
  }

  get config(): Readonly<CanvasConfig> {
    return this._state.config;
  }

  get nodes(): Readonly<NodeInstance[]> {
    return this._state.nodes;
  }

  get connections(): Readonly<CanvasConnection[]> {
    return this._state.connections;
  }

  get selectedNodeIds(): Readonly<string[]> {
    return this._state.selectedNodeIds;
  }

  get interactionMode(): InteractionMode {
    return this._interactionMode;
  }

  // ==========================================================================
  // Node Management
  // ==========================================================================

  /**
   * Add a node to the canvas.
   */
  addNode(
    type: string,
    position: { x: number; y: number },
    config?: Record<string, unknown>
  ): NodeInstance {
    const registry = getNodeRegistry();
    const node = registry.createInstance(type as any, position, config || {});

    // Apply snap-to-grid if enabled
    if (this._state.config.snapToGrid) {
      node.position.x = snapToGrid(node.position.x, this._state.config.gridSize);
      node.position.y = snapToGrid(node.position.y, this._state.config.gridSize);
    }

    this._state.nodes.push(node);

    this._emitEvent({
      type: CanvasEventType.NODE_ADDED,
      timestamp: Date.now()
    });

    this._emitStateChanged();

    return node;
  }

  /**
   * Remove a node from the canvas.
   */
  removeNode(nodeId: string): boolean {
    const index = this._state.nodes.findIndex(n => n.id === nodeId);
    if (index === -1) return false;

    this._state.nodes.splice(index, 1);
    this._state.selectedNodeIds = this._state.selectedNodeIds.filter(id => id !== nodeId);

    // Also remove connections to/from this node
    this._state.connections = this._state.connections.filter(
      c => c.sourceNodeId !== nodeId && c.targetNodeId !== nodeId
    );

    this._emitEvent({
      type: CanvasEventType.NODE_REMOVED,
      timestamp: Date.now()
    });

    this._emitStateChanged();

    return true;
  }

  /**
   * Update node position.
   */
  updateNodePosition(nodeId: string, position: { x: number; y: number }): void {
    const node = this._state.nodes.find(n => n.id === nodeId);
    if (!node) return;

    const oldPosition = { ...node.position };

    // Apply snap-to-grid if enabled
    if (this._state.config.snapToGrid) {
      position = {
        x: snapToGrid(position.x, this._state.config.gridSize),
        y: snapToGrid(position.y, this._state.config.gridSize)
      };
    }

    node.position = position;

    this._emitEvent({
      type: CanvasEventType.NODE_MOVED,
      timestamp: Date.now()
    } as any);

    this._emitStateChanged();
  }

  /**
   * Update node configuration.
   */
  updateNodeConfig(nodeId: string, config: Record<string, unknown>): void {
    const node = this._state.nodes.find(n => n.id === nodeId);
    if (!node) return;

    node.config = { ...node.config, ...config };
    this._emitStateChanged();
  }

  /**
   * Get node bounds.
   */
  getNodeBounds(nodeId: string): NodeBounds | null {
    const node = this._state.nodes.find(n => n.id === nodeId);
    if (!node) return null;

    const dimensions = this._nodeDimensions.get(node.type) || { width: 180, height: 80 };

    return {
      nodeId,
      x: node.position.x,
      y: node.position.y,
      width: dimensions.width,
      height: dimensions.height
    };
  }

  /**
   * Get all node bounds.
   */
  getAllNodeBounds(): NodeBounds[] {
    return this._state.nodes.map(node => {
      const dimensions = this._nodeDimensions.get(node.type) || { width: 180, height: 80 };
      return {
        nodeId: node.id,
        x: node.position.x,
        y: node.position.y,
        width: dimensions.width,
        height: dimensions.height
      };
    });
  }

  // ==========================================================================
  // Connection Management
  // ==========================================================================

  /**
   * Create a connection between two nodes.
   */
  createConnection(
    sourceNodeId: string,
    sourcePortId: string,
    targetNodeId: string,
    targetPortId: string
  ): CanvasConnection | null {
    // Check if connection already exists
    const exists = this._state.connections.some(
      c => c.sourceNodeId === sourceNodeId &&
           c.sourcePortId === sourcePortId &&
           c.targetNodeId === targetNodeId &&
           c.targetPortId === targetPortId
    );

    if (exists) return null;

    // Validate connection
    const registry = getNodeRegistry();
    const sourceNode = this._state.nodes.find(n => n.id === sourceNodeId);
    const targetNode = this._state.nodes.find(n => n.id === targetNodeId);

    if (!sourceNode || !targetNode) return null;

    const validation = registry.validateConnection(
      sourceNode.type as any,
      sourcePortId,
      targetNode.type as any,
      targetPortId
    );

    const connection: CanvasConnection = {
      id: generateId('conn'),
      sourceNodeId,
      sourcePortId,
      targetNodeId,
      targetPortId,
      invalid: validation !== true
    };

    this._state.connections.push(connection);

    this._emitEvent({
      type: CanvasEventType.CONNECTION_CREATED,
      timestamp: Date.now()
    } as any);

    this._emitStateChanged();

    return connection;
  }

  /**
   * Remove a connection.
   */
  removeConnection(connectionId: string): boolean {
    const index = this._state.connections.findIndex(c => c.id === connectionId);
    if (index === -1) return false;

    this._state.connections.splice(index, 1);
    this._state.selectedConnectionIds = this._state.selectedConnectionIds.filter(
      id => id !== connectionId
    );

    this._emitEvent({
      type: CanvasEventType.CONNECTION_REMOVED,
      timestamp: Date.now()
    } as any);

    this._emitStateChanged();

    return true;
  }

  /**
   * Remove all connections to/from a node.
   */
  removeNodeConnections(nodeId: string): void {
    this._state.connections = this._state.connections.filter(
      c => c.sourceNodeId !== nodeId && c.targetNodeId !== nodeId
    );
    this._emitStateChanged();
  }

  // ==========================================================================
  // Selection
  // ==========================================================================

  /**
   * Select a node.
   */
  selectNode(nodeId: string, addToSelection = false): void {
    if (addToSelection) {
      if (!this._state.selectedNodeIds.includes(nodeId)) {
        this._state.selectedNodeIds.push(nodeId);
      }
    } else {
      this._state.selectedNodeIds = [nodeId];
    }

    this._state.selectedConnectionIds = [];
    this._emitEvent({
      type: CanvasEventType.NODE_SELECTED,
      timestamp: Date.now()
    });

    this._emitStateChanged();
  }

  /**
   * Select multiple nodes.
   */
  selectNodes(nodeIds: string[]): void {
    this._state.selectedNodeIds = [...nodeIds];
    this._state.selectedConnectionIds = [];

    this._emitEvent({
      type: CanvasEventType.NODE_SELECTED,
      timestamp: Date.now()
    });

    this._emitStateChanged();
  }

  /**
   * Deselect a node.
   */
  deselectNode(nodeId: string): void {
    this._state.selectedNodeIds = this._state.selectedNodeIds.filter(id => id !== nodeId);
    this._emitStateChanged();
  }

  /**
   * Clear all selection.
   */
  clearSelection(): void {
    this._state.selectedNodeIds = [];
    this._state.selectedConnectionIds = [];

    this._emitEvent({
      type: CanvasEventType.SELECTION_CLEARED,
      timestamp: Date.now()
    });

    this._emitStateChanged();
  }

  /**
   * Select a connection.
   */
  selectConnection(connectionId: string): void {
    this._state.selectedConnectionIds = [connectionId];
    this._state.selectedNodeIds = [];
    this._emitStateChanged();
  }

  /**
   * Delete selected items.
   */
  deleteSelected(): void {
    // Remove selected nodes
    for (const nodeId of this._state.selectedNodeIds) {
      this.removeNode(nodeId);
    }

    // Remove selected connections
    for (const connectionId of this._state.selectedConnectionIds) {
      this.removeConnection(connectionId);
    }

    this.clearSelection();
  }

  // ==========================================================================
  // Viewport (Zoom/Pan)
  // ==========================================================================

  /**
   * Set viewport transform.
   */
  setViewport(viewport: Partial<ViewportTransform>): void {
    const oldViewport = { ...this._state.viewport };
    this._state.viewport = {
      ...this._state.viewport,
      ...viewport
    };

    // Clamp zoom
    this._state.viewport.zoom = Math.max(
      this._state.viewport.minZoom,
      Math.min(this._state.viewport.maxZoom, this._state.viewport.zoom)
    );

    this._emitEvent({
      type: CanvasEventType.VIEWPORT_CHANGED,
      timestamp: Date.now()
    } as any);

    this._emitStateChanged();
  }

  /**
   * Zoom by delta.
   */
  zoom(delta: number, centerX?: number, centerY?: number): void {
    const oldZoom = this._state.viewport.zoom;
    const newZoom = Math.max(
      this._state.viewport.minZoom,
      Math.min(this._state.viewport.maxZoom, oldZoom + delta)
    );

    if (newZoom === oldZoom) return;

    // Adjust x/y to zoom towards center point
    if (centerX !== undefined && centerY !== undefined) {
      const zoomRatio = newZoom / oldZoom;
      this._state.viewport.x = centerX - (centerX - this._state.viewport.x) * zoomRatio;
      this._state.viewport.y = centerY - (centerY - this._state.viewport.y) * zoomRatio;
    }

    this._state.viewport.zoom = newZoom;

    this._emitEvent({
      type: CanvasEventType.VIEWPORT_CHANGED,
      timestamp: Date.now()
    } as any);

    this._emitStateChanged();
  }

  /**
   * Pan by delta.
   */
  pan(dx: number, dy: number): void {
    this._state.viewport.x += dx;
    this._state.viewport.y += dy;

    this._emitEvent({
      type: CanvasEventType.VIEWPORT_CHANGED,
      timestamp: Date.now()
    } as any);

    this._emitStateChanged();
  }

  /**
   * Reset viewport to default.
   */
  resetViewport(): void {
    this._state.viewport = getDefaultViewport();

    this._emitEvent({
      type: CanvasEventType.VIEWPORT_CHANGED,
      timestamp: Date.now()
    } as any);

    this._emitStateChanged();
  }

  /**
   * Fit all nodes in view.
   */
  fitToNodes(padding = 50): void {
    if (this._state.nodes.length === 0) {
      this.resetViewport();
      return;
    }

    const bounds = this._getAllNodesBounds();
    const width = bounds.maxX - bounds.minX + padding * 2;
    const height = bounds.maxY - bounds.minY + padding * 2;

    // Get canvas size (would need to be passed in or stored)
    // For now, just center on the bounds
    this._state.viewport.x = -bounds.minX + padding;
    this._state.viewport.y = -bounds.minY + padding;
    this._state.viewport.zoom = 1;

    this._emitEvent({
      type: CanvasEventType.VIEWPORT_CHANGED,
      timestamp: Date.now()
    } as any);

    this._emitStateChanged();
  }

  // ==========================================================================
  // Drag State
  // ==========================================================================

  /**
   * Start dragging a node.
   */
  startDragNode(
    nodeId: string,
    startX: number,
    startY: number
  ): void {
    const node = this._state.nodes.find(n => n.id === nodeId);
    if (!node) return;

    this._dragState = {
      isDragging: true,
      dragType: 'node',
      draggedId: nodeId,
      draggedIds: this._state.selectedNodeIds.includes(nodeId)
        ? [...this._state.selectedNodeIds]
        : [nodeId],
      startPosition: { x: startX, y: startY },
      currentPosition: { x: startX, y: startY },
      offsets: new Map()
    };

    // Calculate offsets for all dragged nodes
    for (const id of this._dragState.draggedIds) {
      const n = this._state.nodes.find(node => node.id === id);
      if (n) {
        this._dragState.offsets.set(id, {
          x: startX - n.position.x,
          y: startY - n.position.y
        });
      }
    }

    this._interactionMode = InteractionMode.SELECT;
  }

  /**
   * Update drag position.
   */
  updateDrag(x: number, y: number): void {
    if (!this._dragState.isDragging) return;

    this._dragState.currentPosition = { x, y };

    // Update node positions
    for (const id of this._dragState.draggedIds) {
      const offset = this._dragState.offsets.get(id);
      if (offset) {
        let newX = x - offset.x;
        let newY = y - offset.y;

        // Apply snap-to-grid if enabled
        if (this._state.config.snapToGrid) {
          newX = snapToGrid(newX, this._state.config.gridSize);
          newY = snapToGrid(newY, this._state.config.gridSize);
        }

        this.updateNodePosition(id, { x: newX, y: newY });
      }
    }
  }

  /**
   * End drag.
   */
  endDrag(): void {
    this._dragState = this._createInitialDragState();
  }

  /**
   * Get current drag state.
   */
  getDragState(): Readonly<DragState> {
    return this._dragState;
  }

  // ==========================================================================
  // Connection Creation
  // ==========================================================================

  /**
   * Start creating a connection.
   */
  startConnection(sourceNodeId: string, sourcePortId: string): void {
    this._connectionState = {
      isCreating: true,
      sourceNodeId,
      sourcePortId,
      currentPosition: null,
      targetPortId: null,
      isValid: true
    };

    this._interactionMode = InteractionMode.CONNECT;
  }

  /**
   * Update connection creation position.
   */
  updateConnection(x: number, y: number): void {
    if (!this._connectionState.isCreating) return;

    this._connectionState.currentPosition = { x, y };
  }

  /**
   * Complete connection creation.
   */
  completeConnection(targetNodeId: string, targetPortId: string): boolean {
    if (!this._connectionState.isCreating || !this._connectionState.sourceNodeId) {
      return false;
    }

    const connection = this.createConnection(
      this._connectionState.sourceNodeId,
      this._connectionState.sourcePortId!,
      targetNodeId,
      targetPortId
    );

    this._connectionState = this._createInitialConnectionState();
    this._interactionMode = InteractionMode.SELECT;

    return connection !== null;
  }

  /**
   * Cancel connection creation.
   */
  cancelConnection(): void {
    this._connectionState = this._createInitialConnectionState();
    this._interactionMode = InteractionMode.SELECT;
  }

  /**
   * Get connection state.
   */
  getConnectionState(): Readonly<ConnectionState> {
    return this._connectionState;
  }

  // ==========================================================================
  // History (Undo/Redo)
  // ==========================================================================

  /**
   * Save current state to history.
   */
  saveToHistory(description: string): void {
    const action: HistoryAction = {
      id: generateId('action'),
      type: 'batch',
      timestamp: Date.now(),
      description,
      before: this._serializeState(),
      after: {}
    };

    this._history.undo.push(action);
    this._history.redo = [];

    // Limit history size
    if (this._history.undo.length > this._history.maxSize) {
      this._history.undo.shift();
    }
  }

  /**
   * Undo last action.
   */
  undo(): boolean {
    const action = this._history.undo.pop();
    if (!action) return false;

    this._history.redo.push(action);
    this._deserializeState(action.before);

    return true;
  }

  /**
   * Redo last undone action.
   */
  redo(): boolean {
    const action = this._history.redo.pop();
    if (!action) return false;

    this._history.undo.push(action);
    if (action.after) {
      this._deserializeState(action.after);
    }

    return true;
  }

  /**
   * Check if can undo.
   */
  canUndo(): boolean {
    return this._history.undo.length > 0;
  }

  /**
   * Check if can redo.
   */
  canRedo(): boolean {
    return this._history.redo.length > 0;
  }

  /**
   * Clear history.
   */
  clearHistory(): void {
    this._history.undo = [];
    this._history.redo = [];
  }

  // ==========================================================================
  // Utilities
  // ==========================================================================

  /**
   * Find node at position.
   */
  findNodeAtPosition(x: number, y: number): NodeInstance | null {
    // Search in reverse order (top nodes first)
    for (let i = this._state.nodes.length - 1; i >= 0; i--) {
      const node = this._state.nodes[i];
      const bounds = this.getNodeBounds(node.id);

      if (bounds &&
          x >= bounds.x && x <= bounds.x + bounds.width &&
          y >= bounds.y && y <= bounds.y + bounds.height) {
        return node;
      }
    }

    return null;
  }

  /**
   * Find nodes in rectangle.
   */
  findNodesInRect(x: number, y: number, width: number, height: number): NodeInstance[] {
    const minX = Math.min(x, x + width);
    const maxX = Math.max(x, x + width);
    const minY = Math.min(y, y + height);
    const maxY = Math.max(y, y + height);

    return this._state.nodes.filter(node => {
      const bounds = this.getNodeBounds(node.id);
      if (!bounds) return false;

      const nodeMinX = bounds.x;
      const nodeMaxX = bounds.x + bounds.width;
      const nodeMinY = bounds.y;
      const nodeMaxY = bounds.y + bounds.height;

      // Check for overlap
      return !(maxX < nodeMinX || minX > nodeMaxX || maxY < nodeMinY || minY > nodeMaxY);
    });
  }

  /**
   * Get port position on canvas.
   */
  getPortPosition(nodeId: string, portId: string, type: 'input' | 'output'): PortPosition | null {
    const bounds = this.getNodeBounds(nodeId);
    if (!bounds) return null;

    const registry = getNodeRegistry();
    const node = this._state.nodes.find(n => n.id === nodeId);
    if (!node) return null;

    const definition = registry.get(node.type as any);
    if (!definition) return null;

    const ports = type === 'input' ? definition.inputs : definition.outputs;
    const portIndex = ports.findIndex(p => p.id === portId);

    if (portIndex === -1) return null;

    // Calculate port position
    const portCount = ports.length;
    const portSpacing = 24;
    const startY = bounds.y + 40 + (bounds.height - 40 - portCount * portSpacing) / 2;

    return {
      nodeId,
      portId,
      type,
      x: type === 'input' ? bounds.x : bounds.x + bounds.width,
      y: startY + portIndex * portSpacing
    };
  }

  /**
   * Get connection path as SVG path string.
   */
  getConnectionPath(connection: CanvasConnection): string {
    const sourcePos = this.getPortPosition(connection.sourceNodeId, connection.sourcePortId, 'output');
    const targetPos = this.getPortPosition(connection.targetNodeId, connection.targetPortId, 'input');

    if (!sourcePos || !targetPos) return '';

    return this._calculateBezierPath(
      sourcePos.x,
      sourcePos.y,
      targetPos.x,
      targetPos.y
    );
  }

  /**
   * Get pending connection path (while creating).
   */
  getPendingConnectionPath(x: number, y: number): string {
    if (!this._connectionState.sourceNodeId || !this._connectionState.sourcePortId) {
      return '';
    }

    const sourcePos = this.getPortPosition(
      this._connectionState.sourceNodeId,
      this._connectionState.sourcePortId,
      'output'
    );

    if (!sourcePos) return '';

    return this._calculateBezierPath(sourcePos.x, sourcePos.y, x, y);
  }

  // ==========================================================================
  // Private Methods
  // ==========================================================================

  private _createInitialDragState(): DragState {
    return {
      isDragging: false,
      dragType: null,
      draggedId: null,
      draggedIds: [],
      startPosition: { x: 0, y: 0 },
      currentPosition: { x: 0, y: 0 },
      offsets: new Map()
    };
  }

  private _createInitialConnectionState(): ConnectionState {
    return {
      isCreating: false,
      sourceNodeId: null,
      sourcePortId: null,
      currentPosition: null,
      targetPortId: null,
      isValid: true
    };
  }

  private _createInitialBoxSelectionState(): BoxSelectionState {
    return {
      isSelecting: false,
      startPosition: { x: 0, y: 0 },
      currentPosition: { x: 0, y: 0 },
      selectedNodeIds: []
    };
  }

  private _createInitialHistory(maxSize = 100): HistoryState {
    return {
      undo: [],
      redo: [],
      currentPosition: 0,
      maxSize
    };
  }

  private _setDefaultNodeDimensions(): void {
    // Default dimensions for node types
    this._nodeDimensions.set('input', { width: 160, height: 70 });
    this._nodeDimensions.set('output', { width: 160, height: 70 });
    this._nodeDimensions.set('llm-chat', { width: 220, height: 100 });
    this._nodeDimensions.set('llm-streaming', { width: 220, height: 100 });
    this._nodeDimensions.set('agent', { width: 200, height: 90 });
    this._nodeDimensions.set('tool', { width: 180, height: 80 });
    this._nodeDimensions.set('condition', { width: 180, height: 90 });
    this._nodeDimensions.set('parallel', { width: 200, height: 90 });
    this._nodeDimensions.set('loop', { width: 180, height: 90 });
    this._nodeDimensions.set('prompt-template', { width: 220, height: 100 });
    this._nodeDimensions.set('vector-search', { width: 200, height: 90 });
    this._nodeDimensions.set('embedding', { width: 180, height: 80 });
  }

  private _getAllNodesBounds(): { minX: number; minY: number; maxX: number; maxY: number } {
    let minX = Infinity;
    let minY = Infinity;
    let maxX = -Infinity;
    let maxY = -Infinity;

    for (const node of this._state.nodes) {
      const bounds = this.getNodeBounds(node.id);
      if (bounds) {
        minX = Math.min(minX, bounds.x);
        minY = Math.min(minY, bounds.y);
        maxX = Math.max(maxX, bounds.x + bounds.width);
        maxY = Math.max(maxY, bounds.y + bounds.height);
      }
    }

    return { minX, minY, maxX, maxY };
  }

  private _calculateBezierPath(
    x1: number,
    y1: number,
    x2: number,
    y2: number
  ): string {
    const dx = Math.abs(x2 - x1);
    const controlOffset = Math.max(dx * 0.5, 50);

    const cp1x = x1 + controlOffset;
    const cp1y = y1;
    const cp2x = x2 - controlOffset;
    const cp2y = y2;

    return `M ${x1} ${y1} C ${cp1x} ${cp1y}, ${cp2x} ${cp2y}, ${x2} ${y2}`;
  }

  private _serializeState(): Partial<CanvasState> {
    return {
      nodes: this._state.nodes.map(n => ({ ...n })),
      connections: this._state.connections.map(c => ({ ...c })),
      selectedNodeIds: [...this._state.selectedNodeIds],
      selectedConnectionIds: [...this._state.selectedConnectionIds]
    };
  }

  private _deserializeState(state: Partial<CanvasState>): void {
    if (state.nodes) {
      this._state.nodes = state.nodes.map(n => ({ ...n }));
    }
    if (state.connections) {
      this._state.connections = state.connections.map(c => ({ ...c }));
    }
    if (state.selectedNodeIds) {
      this._state.selectedNodeIds = [...state.selectedNodeIds];
    }
    if (state.selectedConnectionIds) {
      this._state.selectedConnectionIds = [...state.selectedConnectionIds];
    }

    this._emitStateChanged();
  }

  private _emitEvent(event: CanvasEvent): void {
    if (this._eventCallback) {
      this._eventCallback(event as AnyCanvasEvent);
    }
  }

  private _emitStateChanged(): void {
    this._emitEvent({
      type: CanvasEventType.STATE_CHANGED,
      timestamp: Date.now()
    });
  }
}

// ==========================================================================
// Helper Functions
// ==========================================================================

/**
 * Create a canvas manager with default options.
 */
export function createCanvasManager(options?: CanvasManagerOptions): CanvasManager {
  return new CanvasManager(options);
}
