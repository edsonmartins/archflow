/**
 * ArchflowDesigner - Canvas System
 *
 * Complete canvas system for visual workflow building including:
 * - Canvas state management
 * - Node positioning and drag-drop
 * - Connection management with bezier curves
 * - Viewport (zoom/pan)
 * - Selection and history (undo/redo)
 *
 * @example
 * ```ts
 * import { createCanvasManager, createCanvasRenderer } from '@archflow/component/canvas';
 *
 * const manager = createCanvasManager({
 *   config: { snapToGrid: true, gridSize: 20 },
 *   onEvent: (event) => console.log(event)
 * });
 *
 * const renderer = createCanvasRenderer(manager);
 * renderer.initialize(containerElement);
 *
 * // Add a node
 * const node = manager.addNode('input', { x: 100, y: 100 });
 *
 * // Render
 * renderer.render();
 * ```
 */

// ==========================================================================
// Type Definitions
// ==========================================================================

export * from './canvas-types';

// ==========================================================================
// Canvas Manager
// ==========================================================================

export {
  CanvasManager,
  createCanvasManager
} from './CanvasManager';

// ==========================================================================
// Canvas Renderer
// ==========================================================================

export {
  CanvasRenderer,
  canvasStyles
} from './CanvasRenderer';

// ==========================================================================
// Minimap
// ==========================================================================

export {
  Minimap,
  createMinimap,
  minimapStyles
} from './Minimap';

// ==========================================================================
// Utilities
// ==========================================================================

import { CanvasManager } from './CanvasManager';
import { CanvasRenderer } from './CanvasRenderer';

/**
 * Create a complete canvas system with manager and renderer.
 */
export function createCanvasSystem(options?: {
  config?: Partial<import('./canvas-types').CanvasConfig>;
  viewport?: Partial<import('./canvas-types').ViewportTransform>;
  onEvent?: (event: any) => void;
}): {
  manager: CanvasManager;
  renderer: CanvasRenderer;
} {
  const manager = new CanvasManager(options);
  const renderer = new CanvasRenderer(manager);

  return { manager, renderer };
}

// ==========================================================================
// Default Export
// ==========================================================================

import { createCanvasManager } from './CanvasManager';
export default createCanvasManager;
