/**
 * CanvasRenderer - Renders the visual canvas
 *
 * Handles:
 * - HTML/SVG rendering of nodes and connections
 * - Grid background
 * - Minimap
 * - Selection box
 */

import type { CanvasManager } from './CanvasManager';
import type {
  CanvasState,
  NodeInstance,
  CanvasConnection,
  ViewportTransform,
  CanvasConfig
} from './canvas-types';
import { createNodeComponent } from '../nodes/nodes/index';
import type { NodeTypeDefinition } from '../nodes/node-types';
import { getNodeRegistry } from '../nodes/NodeRegistry';

// ==========================================================================
// Render Context
// ==========================================================================

interface RenderContext {
  canvas: HTMLElement;
  svg: SVGSVGElement;
  nodesLayer: HTMLElement;
  connectionsLayer: HTMLElement;
  gridLayer: HTMLElement;
  overlayLayer: HTMLElement;
  width: number;
  height: number;
}

// ==========================================================================
// CanvasRenderer Class
// ==========================================================================

export class CanvasRenderer {
  private manager: CanvasManager;
  private context: RenderContext | null = null;
  private resizeObserver: ResizeObserver | null = null;

  constructor(manager: CanvasManager) {
    this.manager = manager;
  }

  // ==========================================================================
  // Initialization
  // ==========================================================================

  /**
   * Initialize the renderer with a container element.
   */
  initialize(container: HTMLElement): void {
    // Create canvas structure
    const canvas = document.createElement('div');
    canvas.className = 'archflow-canvas';
    canvas.innerHTML = this._getCanvasTemplate();

    container.appendChild(canvas);

    // Cache references to layers
    this.context = {
      canvas,
      svg: canvas.querySelector('.archflow-canvas__svg') as SVGSVGElement,
      nodesLayer: canvas.querySelector('.archflow-canvas__nodes') as HTMLElement,
      connectionsLayer: canvas.querySelector('.archflow-canvas__connections') as HTMLElement,
      gridLayer: canvas.querySelector('.archflow-canvas__grid') as HTMLElement,
      overlayLayer: canvas.querySelector('.archflow-canvas__overlay') as HTMLElement,
      width: container.clientWidth,
      height: container.clientHeight
    };

    // Setup resize observer
    this.resizeObserver = new ResizeObserver(() => {
      this._updateSize();
    });
    this.resizeObserver.observe(container);

    // Initial render
    this.render();
  }

  /**
   * Cleanup resources.
   */
  destroy(): void {
    if (this.resizeObserver) {
      this.resizeObserver.disconnect();
      this.resizeObserver = null;
    }

    if (this.context?.canvas) {
      this.context.canvas.remove();
    }

    this.context = null;
  }

  // ==========================================================================
  // Rendering
  // ==========================================================================

  /**
   * Full render of the canvas.
   */
  render(): void {
    if (!this.context) return;

    const state = this.manager.state;
    const dragState = this.manager.getDragState();
    const connectionState = this.manager.getConnectionState();

    // Update viewport transform
    this._updateViewportTransform(state.viewport);

    // Render grid
    this._renderGrid(state.config, state.viewport);

    // Render connections
    this._renderConnections(state.connections, state.selectedConnectionIds);

    // Render pending connection
    if (connectionState.isCreating && connectionState.currentPosition) {
      this._renderPendingConnection(connectionState);
    } else {
      this._clearPendingConnection();
    }

    // Render nodes
    this._renderNodes(state.nodes, state.selectedNodeIds);
  }

  /**
   * Update only nodes (more efficient than full render).
   */
  updateNodes(): void {
    if (!this.context) return;
    this._renderNodes(this.manager.state.nodes, this.manager.state.selectedNodeIds);
  }

  /**
   * Update only connections (more efficient than full render).
   */
  updateConnections(): void {
    if (!this.context) return;
    this._renderConnections(
      this.manager.state.connections,
      this.manager.state.selectedConnectionIds
    );
  }

  // ==========================================================================
  // Private Methods - Template
  // ==========================================================================

  private _getCanvasTemplate(): string {
    return `
      <svg class="archflow-canvas__svg" xmlns="http://www.w3.org/2000/svg">
        <defs>
          <marker id="arrowhead" markerWidth="10" markerHeight="7" refX="9" refY="3.5" orient="auto">
            <polygon points="0 0, 10 3.5, 0 7" fill="#64748b" />
          </marker>
        </defs>
        <g class="archflow-canvas__grid-layer"></g>
        <g class="archflow-canvas__connections-layer"></g>
      </svg>
      <div class="archflow-canvas__grid"></div>
      <div class="archflow-canvas__connections"></div>
      <div class="archflow-canvas__nodes"></div>
      <div class="archflow-canvas__overlay"></div>
    `;
  }

  // ==========================================================================
  // Private Methods - Viewport
  // ==========================================================================

  private _updateViewportTransform(viewport: ViewportTransform): void {
    if (!this.context) return;

    const transform = `translate(${viewport.x}px, ${viewport.y}px) scale(${viewport.zoom})`;

    // Apply to nodes layer
    this.context.nodesLayer.style.transform = transform;
    this.context.nodesLayer.style.transformOrigin = '0 0';

    // Apply to connections layer
    const connectionsGroup = this.context.svg.querySelector('.archflow-canvas__connections-layer');
    if (connectionsGroup) {
      connectionsGroup.setAttribute('transform', `translate(${viewport.x}, ${viewport.y}) scale(${viewport.zoom})`);
    }
  }

  private _updateSize(): void {
    if (!this.context || !this.context.canvas.parentElement) return;

    const rect = this.context.canvas.parentElement.getBoundingClientRect();
    this.context.width = rect.width;
    this.context.height = rect.height;

    this.context.canvas.style.width = `${rect.width}px`;
    this.context.canvas.style.height = `${rect.height}px`;

    // Update SVG size
    this.context.svg.setAttribute('width', String(rect.width));
    this.context.svg.setAttribute('height', String(rect.height));

    // Re-render grid
    this._renderGrid(this.manager.state.config, this.manager.state.viewport);
  }

  // ==========================================================================
  // Private Methods - Grid
  // ==========================================================================

  private _renderGrid(config: CanvasConfig, viewport: ViewportTransform): void {
    if (!this.context) return;

    const gridLayer = this.context.gridLayer;
    const svgGridLayer = this.context.svg.querySelector('.archflow-canvas__grid-layer');

    if (config.gridType === 'none') {
      gridLayer.style.backgroundImage = 'none';
      if (svgGridLayer) {
        svgGridLayer.innerHTML = '';
      }
      return;
    }

    const gridSize = config.gridSize * viewport.zoom;
    const offsetX = viewport.x % gridSize;
    const offsetY = viewport.y % gridSize;

    if (config.gridType === 'dots') {
      // Use CSS for dots
      gridLayer.style.backgroundImage = `
        radial-gradient(circle, ${config.gridColor} 1px, transparent 1px)
      `;
      gridLayer.style.backgroundSize = `${gridSize}px ${gridSize}px`;
      gridLayer.style.backgroundPosition = `${offsetX}px ${offsetY}px`;
    } else if (config.gridType === 'lines') {
      // Use SVG for lines
      if (svgGridLayer) {
        this._renderGridLines(svgGridLayer as SVGGElement, config, viewport);
      }
    }
  }

  private _renderGridLines(
    layer: SVGGElement,
    config: CanvasConfig,
    viewport: ViewportTransform
  ): void {
    const width = this.context?.width || 800;
    const height = this.context?.height || 600;
    const gridSize = config.gridSize;

    // Calculate visible grid lines
    const startX = Math.floor(-viewport.x / viewport.zoom / gridSize) * gridSize;
    const startY = Math.floor(-viewport.y / viewport.zoom / gridSize) * gridSize;
    const endX = startX + width / viewport.zoom + gridSize * 2;
    const endY = startY + height / viewport.zoom + gridSize * 2;

    let svg = '';

    // Vertical lines
    for (let x = startX; x < endX; x += gridSize) {
      svg += `<line x1="${x}" y1="${startY}" x2="${x}" y2="${endY}" stroke="${config.gridColor}" stroke-width="0.5" opacity="0.5"/>`;
    }

    // Horizontal lines
    for (let y = startY; y < endY; y += gridSize) {
      svg += `<line x1="${startX}" y1="${y}" x2="${endX}" y2="${y}" stroke="${config.gridColor}" stroke-width="0.5" opacity="0.5"/>`;
    }

    layer.innerHTML = svg;
  }

  // ==========================================================================
  // Private Methods - Connections
  // ==========================================================================

  private _renderConnections(
    connections: CanvasConnection[],
    selectedIds: string[]
  ): void {
    if (!this.context) return;

    const svgLayer = this.context.svg.querySelector('.archflow-canvas__connections-layer');
    if (!svgLayer) return;

    let svg = '';

    for (const conn of connections) {
      const path = this.manager.getConnectionPath(conn);
      if (!path) continue;

      const selected = selectedIds.includes(conn.id);
      const invalid = conn.invalid;
      const highlighted = conn.highlighted;

      let color = this.manager.state.config.connectionColor;
      let strokeWidth = this.manager.state.config.connectionWidth;

      if (invalid) {
        color = '#ef4444';
      } else if (selected) {
        color = '#3b82f6';
        strokeWidth = 3;
      } else if (highlighted) {
        color = '#8b5cf6';
      }

      svg += `<path
        d="${path}"
        fill="none"
        stroke="${color}"
        stroke-width="${strokeWidth}"
        class="archflow-connection ${selected ? 'archflow-connection--selected' : ''} ${invalid ? 'archflow-connection--invalid' : ''}"
        data-connection-id="${conn.id}"
        marker-end="url(#arrowhead)"
      />`;
    }

    svgLayer.innerHTML = svg;
  }

  private _renderPendingConnection(state: {
    isCreating: boolean;
    sourceNodeId: string | null;
    sourcePortId: string | null;
    currentPosition: { x: number; y: number } | null;
  }): void {
    if (!this.context) return;

    const svgLayer = this.context.svg.querySelector('.archflow-canvas__connections-layer');
    if (!svgLayer) return;

    const path = this.manager.getPendingConnectionPath(
      state.currentPosition?.x || 0,
      state.currentPosition?.y || 0
    );

    const existing = svgLayer.querySelector('.archflow-connection--pending');

    if (path) {
      const svg = `<path
        d="${path}"
        fill="none"
        stroke="#8b5cf6"
        stroke-width="2"
        stroke-dasharray="5,5"
        class="archflow-connection archflow-connection--pending"
      />`;

      if (existing) {
        existing.outerHTML = svg;
      } else {
        svgLayer.insertAdjacentHTML('beforeend', svg);
      }
    } else if (existing) {
      existing.remove();
    }
  }

  private _clearPendingConnection(): void {
    if (!this.context) return;

    const existing = this.context.svg.querySelector('.archflow-connection--pending');
    if (existing) {
      existing.remove();
    }
  }

  // ==========================================================================
  // Private Methods - Nodes
  // ==========================================================================

  private _renderNodes(nodes: NodeInstance[], selectedIds: string[]): void {
    if (!this.context) return;

    const registry = getNodeRegistry();
    const nodesHtml: string[] = [];

    for (const node of nodes) {
      const definition = registry.get(node.type as any);
      if (!definition) continue;

      const component = createNodeComponent(node, definition);
      const selected = selectedIds.includes(node.id);

      // Update selected state
      if (selected !== node.selected) {
        node.selected = selected;
      }

      nodesHtml.push(`
        <div class="archflow-node-wrapper"
             data-node-id="${node.id}"
             style="transform: translate(${node.position.x}px, ${node.position.y}px);">
          ${component.render()}
        </div>
      `);
    }

    this.context.nodesLayer.innerHTML = nodesHtml.join('');
  }
}

// ==========================================================================
// CSS Styles (embedded)
// ==========================================================================

export const canvasStyles = `
/* ==========================================================================
// Canvas Container
// ========================================================================== */

.archflow-canvas {
  position: relative;
  width: 100%;
  height: 100%;
  overflow: hidden;
  background-color: #f8fafc;
  user-select: none;
}

.archflow-canvas__svg {
  position: absolute;
  top: 0;
  left: 0;
  width: 100%;
  height: 100%;
  pointer-events: none;
  z-index: 1;
}

.archflow-canvas__grid {
  position: absolute;
  top: 0;
  left: 0;
  width: 100%;
  height: 100%;
  pointer-events: none;
  z-index: 0;
}

.archflow-canvas__connections {
  position: absolute;
  top: 0;
  left: 0;
  width: 100%;
  height: 100%;
  pointer-events: none;
  z-index: 2;
}

.archflow-canvas__nodes {
  position: absolute;
  top: 0;
  left: 0;
  width: 100%;
  height: 100%;
  z-index: 3;
}

.archflow-canvas__overlay {
  position: absolute;
  top: 0;
  left: 0;
  width: 100%;
  height: 100%;
  pointer-events: none;
  z-index: 10;
}

/* ==========================================================================
// Node Wrapper
// ========================================================================== */

.archflow-node-wrapper {
  position: absolute;
  will-change: transform;
}

/* ==========================================================================
// Connections
// ========================================================================== */

.archflow-connection {
  cursor: pointer;
  pointer-events: stroke;
  transition: stroke 0.15s ease, stroke-width 0.15s ease;
}

.archflow-connection:hover {
  stroke: #3b82f6 !important;
  stroke-width: 3 !important;
}

.archflow-connection--selected {
  stroke: #3b82f6 !important;
  stroke-width: 3 !important;
}

.archflow-connection--invalid {
  stroke-dasharray: 5, 5;
}

.archflow-connection--pending {
  pointer-events: none;
}

/* ==========================================================================
// Dark Theme
// ========================================================================== */

:host([theme='dark']) .archflow-canvas {
  background-color: #0f172a;
}

:host([theme='dark']) .archflow-canvas__grid-layer line {
  stroke: #334155 !important;
}
`;
