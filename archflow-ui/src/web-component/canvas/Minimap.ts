/**
 * Minimap - Small overview map of the canvas
 *
 * Shows a bird's eye view of all nodes with a viewport indicator.
 */

import type { CanvasManager } from './CanvasManager';
import type { ViewportTransform, NodeBounds } from './canvas-types';

// ==========================================================================
// Minimap Options
// ==========================================================================

export interface MinimapOptions {
  /** Minimap width */
  width?: number;
  /** Minimap height */
  height?: number;
  /** Position on canvas */
  position?: 'top-right' | 'top-left' | 'bottom-right' | 'bottom-left';
  /** Background color */
  backgroundColor?: string;
  /** Node color */
  nodeColor?: string;
  /** Viewport indicator color */
  viewportColor?: string;
  /** Border color */
  borderColor?: string;
}

// ==========================================================================
// Minimap Class
// ==========================================================================

export class Minimap {
  private manager: CanvasManager;
  private container: HTMLElement | null = null;
  private canvas: HTMLCanvasElement | null = null;
  private ctx: CanvasRenderingContext2D | null = null;
  private viewportIndicator: HTMLElement | null = null;
  private updateScheduled = false;

  // Options
  private width = 200;
  private height = 150;
  private position: 'top-right' | 'top-left' | 'bottom-right' | 'bottom-left' = 'top-right';
  private backgroundColor = '#f1f5f9';
  private nodeColor = '#3b82f6';
  private viewportColor = 'rgba(59, 130, 246, 0.2)';
  private borderColor = '#e2e8f0';

  constructor(manager: CanvasManager, options: MinimapOptions = {}) {
    this.manager = manager;
    this.width = options.width ?? this.width;
    this.height = options.height ?? this.height;
    this.position = options.position ?? this.position;
    this.backgroundColor = options.backgroundColor ?? this.backgroundColor;
    this.nodeColor = options.nodeColor ?? this.nodeColor;
    this.viewportColor = options.viewportColor ?? this.viewportColor;
    this.borderColor = options.borderColor ?? this.borderColor;
  }

  // ==========================================================================
  // Lifecycle
  // ==========================================================================

  /**
   * Initialize the minimap in a container.
   */
  initialize(overlayLayer: HTMLElement): void {
    // Create container
    this.container = document.createElement('div');
    this.container.className = 'archflow-minimap';
    this.container.style.position = 'absolute';
    this.container.style.width = `${this.width}px`;
    this.container.style.height = `${this.height}px`;
    this._updatePosition();

    // Create canvas for nodes
    this.canvas = document.createElement('canvas');
    this.canvas.width = this.width;
    this.canvas.height = this.height;
    this.canvas.className = 'archflow-minimap__canvas';
    this.canvas.style.width = '100%';
    this.canvas.style.height = '100%';
    this.ctx = this.canvas.getContext('2d');

    // Create viewport indicator
    this.viewportIndicator = document.createElement('div');
    this.viewportIndicator.className = 'archflow-minimap__viewport';
    this.viewportIndicator.style.position = 'absolute';
    this.viewportIndicator.style.border = '2px solid #3b82f6';
    this.viewportIndicator.style.backgroundColor = this.viewportColor;
    this.viewportIndicator.style.pointerEvents = 'none';

    this.container.appendChild(this.canvas);
    this.container.appendChild(this.viewportIndicator);
    overlayLayer.appendChild(this.container);

    // Initial render
    this.scheduleUpdate();
  }

  /**
   * Cleanup resources.
   */
  destroy(): void {
    if (this.container && this.container.parentElement) {
      this.container.parentElement.removeChild(this.container);
    }
    this.container = null;
    this.canvas = null;
    this.ctx = null;
    this.viewportIndicator = null;
  }

  // ==========================================================================
  // Rendering
  // ==========================================================================

  /**
   * Schedule an update (throttled).
   */
  scheduleUpdate(): void {
    if (this.updateScheduled) return;
    this.updateScheduled = true;
    requestAnimationFrame(() => this.update());
  }

  /**
   * Update the minimap rendering.
   */
  update(): void {
    this.updateScheduled = false;

    if (!this.ctx || !this.canvas || !this.viewportIndicator) return;

    const nodes = this.manager.nodes;
    const viewport = this.manager.viewport;

    // Calculate bounds
    const bounds = this._calculateBounds(nodes);
    if (!bounds) {
      this._clear();
      return;
    }

    // Calculate scale to fit all nodes in minimap
    const padding = 10;
    const scaleX = (this.width - padding * 2) / bounds.width;
    const scaleY = (this.height - padding * 2) / bounds.height;
    const scale = Math.min(scaleX, scaleY, 1); // Max scale 1 for performance

    // Calculate offset to center content
    const offsetX = (this.width - bounds.width * scale) / 2 - bounds.minX * scale;
    const offsetY = (this.height - bounds.height * scale) / 2 - bounds.minY * scale;

    // Clear canvas
    this.ctx.clearRect(0, 0, this.width, this.height);

    // Draw background
    this.ctx.fillStyle = this.backgroundColor;
    this.ctx.fillRect(0, 0, this.width, this.height);

    // Draw nodes
    this.ctx.fillStyle = this.nodeColor;
    for (const node of nodes) {
      const x = node.position.x * scale + offsetX;
      const y = node.position.y * scale + offsetY;
      const w = 10 * scale; // Fixed width for minimap
      const h = 6 * scale;  // Fixed height for minimap

      // Clamp to canvas bounds
      if (x + w > 0 && x < this.width && y + h > 0 && y < this.height) {
        this.ctx.fillRect(x, y, Math.max(w, 2), Math.max(h, 2));
      }
    }

    // Update viewport indicator
    this._updateViewportIndicator(scale, offsetX, offsetY, bounds);
  }

  // ==========================================================================
  // Private Methods
  // ==========================================================================

  private _calculateBounds(nodes: typeof this.manager.nodes): {
    minX: number;
    minY: number;
    maxX: number;
    maxY: number;
    width: number;
    height: number;
  } | null {
    if (nodes.length === 0) return null;

    let minX = Infinity;
    let minY = Infinity;
    let maxX = -Infinity;
    let maxY = -Infinity;

    for (const node of nodes) {
      const bounds = this.manager.getNodeBounds(node.id);
      if (bounds) {
        minX = Math.min(minX, bounds.x);
        minY = Math.min(minY, bounds.y);
        maxX = Math.max(maxX, bounds.x + bounds.width);
        maxY = Math.max(maxY, bounds.y + bounds.height);
      } else {
        minX = Math.min(minX, node.position.x);
        minY = Math.min(minY, node.position.y);
        maxX = Math.max(maxX, node.position.x + 100);
        maxY = Math.max(maxY, node.position.y + 60);
      }
    }

    return {
      minX,
      minY,
      maxX,
      maxY,
      width: maxX - minX,
      height: maxY - minY
    };
  }

  private _updateViewportIndicator(
    scale: number,
    offsetX: number,
    offsetY: number,
    bounds: { minX: number; minY: number; width: number; height: number }
  ): void {
    if (!this.viewportIndicator) return;

    const viewport = this.manager.viewport;

    // Calculate visible area in canvas coordinates
    const visibleX = -viewport.x / viewport.zoom;
    const visibleY = -viewport.y / viewport.zoom;
    const visibleWidth = (this.container?.parentElement?.clientWidth || 800) / viewport.zoom;
    const visibleHeight = (this.container?.parentElement?.clientHeight || 600) / viewport.zoom;

    // Convert to minimap coordinates
    const mmX = visibleX * scale + offsetX;
    const mmY = visibleY * scale + offsetY;
    const mmWidth = visibleWidth * scale;
    const mmHeight = visibleHeight * scale;

    this.viewportIndicator.style.left = `${mmX}px`;
    this.viewportIndicator.style.top = `${mmY}px`;
    this.viewportIndicator.style.width = `${Math.max(mmWidth, 4)}px`;
    this.viewportIndicator.style.height = `${Math.max(mmHeight, 4)}px`;
  }

  private _clear(): void {
    if (!this.ctx || !this.canvas || !this.viewportIndicator) return;

    this.ctx.clearRect(0, 0, this.width, this.height);
    this.ctx.fillStyle = this.backgroundColor;
    this.ctx.fillRect(0, 0, this.width, this.height);

    this.viewportIndicator.style.display = 'none';
  }

  private _updatePosition(): void {
    if (!this.container) return;

    const positions: Record<typeof this.position, string> = {
      'top-right': 'top: 10px; right: 10px;',
      'top-left': 'top: 10px; left: 10px;',
      'bottom-right': 'bottom: 10px; right: 10px;',
      'bottom-left': 'bottom: 10px; left: 10px;'
    };

    this.container.style.cssText += positions[this.position];
  }
}

// ==========================================================================
// Helper Functions
// ==========================================================================

/**
 * Create a minimap instance.
 */
export function createMinimap(
  manager: CanvasManager,
  options?: MinimapOptions
): Minimap {
  return new Minimap(manager, options);
}

// ==========================================================================
// CSS Styles
// ==========================================================================

export const minimapStyles = `
.archflow-minimap {
  border: 1px solid #e2e8f0;
  border-radius: 4px;
  overflow: hidden;
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.1);
  z-index: 100;
}

.archflow-minimap__canvas {
  display: block;
}

.archflow-minimap__viewport {
  position: absolute;
  border: 2px solid #3b82f6;
  background-color: rgba(59, 130, 246, 0.1);
  pointer-events: none;
}

/* Dark theme */
:host([theme='dark']) .archflow-minimap {
  border-color: #475569;
  background-color: #1e293b;
}

:host([theme='dark']) .archflow-minimap__viewport {
  border-color: #60a5fa;
  background-color: rgba(96, 165, 250, 0.1);
}
`;
