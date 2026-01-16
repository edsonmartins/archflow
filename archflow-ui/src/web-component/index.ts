/**
 * ArchflowDesigner Web Component
 *
 * Framework-agnostic visual AI workflow builder.
 *
 * @example
 * ```html
 * <script type="module">
 *   import { registerArchflowDesigner } from '@archflow/component';
 *   registerArchflowDesigner();
 * </script>
 *
 * <archflow-designer
 *   workflow-id="wf-001"
 *   api-base="http://localhost:8080/api"
 *   theme="dark">
 * </archflow-designer>
 * ```
 */

// Import ArchflowDesigner class for re-export and registration
import { ArchflowDesigner } from './core/ArchflowDesigner';

// Export main component
export { ArchflowDesigner } from './core/ArchflowDesigner';
export type {
  ArchflowEventMap,
  ArchflowWorkflowSavedEvent,
  ArchflowWorkflowLoadedEvent,
  ArchflowWorkflowExecutedEvent,
  ArchflowNodeSelectedEvent,
  ArchflowNodesSelectedEvent,
  ArchflowSelectionClearedEvent,
  ArchflowErrorEvent,
  ArchflowConnectedEvent,
  ArchflowDisconnectedEvent,
  Theme
} from './core/ArchflowDesigner';

// Export supporting classes
export { ArchflowShadowDom } from './core/ArchflowShadowDom';
export type { ShadowDomState, ShadowDomConfig } from './core/ArchflowShadowDom';

export { ArchflowEventDispatcher, EVENT_NAMES } from './core/ArchflowEventDispatcher';
export type {
  BaseEventDetail,
  WorkflowSavedDetail,
  WorkflowLoadedDetail,
  WorkflowExecutedDetail,
  NodeSelectedDetail,
  NodesSelectedDetail,
  ErrorDetail,
  ConnectedDetail,
  DisconnectedDetail,
  EventDetail,
  ArchflowEventName
} from './core/ArchflowEventDispatcher';

export {
  ThemeManager,
  createThemeManager,
  getDefaultTheme,
  prefersDarkMode
} from './core/ThemeManager';
export type { ThemeColors, ThemeConfig } from './core/ThemeManager';

// ==========================================================================
// Registration Helper
// ==========================================================================

/**
 * Register the ArchflowDesigner web component.
 *
 * @param tagName - Custom element tag name (default: 'archflow-designer')
 * @returns true if registered, false if already registered
 *
 * @example
 * ```ts
 * import { registerArchflowDesigner } from '@archflow/component';
 *
 * // Register with default tag name
 * registerArchflowDesigner();
 *
 * // Register with custom tag name
 * registerArchflowDesigner('my-flow-designer');
 * ```
 */
export function registerArchflowDesigner(tagName = 'archflow-designer'): boolean {
  if (customElements.get(tagName)) {
    console.warn(`[ArchflowDesigner] Custom element '${tagName}' is already registered.`);
    return false;
  }

  customElements.define(tagName, ArchflowDesigner);
  console.info(`[ArchflowDesigner] Registered as <${tagName}>`);
  return true;
}

/**
 * Check if ArchflowDesigner is registered.
 *
 * @param tagName - Custom element tag name to check
 */
export function isArchflowDesignerRegistered(tagName = 'archflow-designer'): boolean {
  return customElements.get(tagName) !== undefined;
}

/**
 * Auto-register on import (optional).
 *
 * Call this if you want the component to register immediately when imported.
 */
export function autoRegister(tagName = 'archflow-designer'): void {
  registerArchflowDesigner(tagName);
}

// ==========================================================================
// TypeScript Declarations for HTML
// ==========================================================================

/**
 * Extend HTML element types for TypeScript.
 *
 * Add this to your global declarations or include in your tsconfig:
 * ```json
 * {
 *   "compilerOptions": {
 *     "types": ["@archflow/component/types"]
 *   }
 * }
 * ```
 */
declare global {
  namespace JSX {
    interface IntrinsicElements {
      'archflow-designer': ArchflowDesignerAttributes;
    }
  }

  interface HTMLElementTagNameMap {
    'archflow-designer': typeof ArchflowDesigner;
  }
}

interface ArchflowDesignerAttributes {
  'workflow-id'?: string;
  'api-base'?: string;
  'theme'?: 'light' | 'dark';
  'readonly'?: string;
  'width'?: string;
  'height'?: string;
}

export type { ArchflowDesignerAttributes };

// Default export for convenience
export default ArchflowDesigner;
