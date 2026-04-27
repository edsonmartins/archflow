/**
 * ArchflowDesigner Web Component
 *
 * Framework-agnostic visual AI workflow builder powered by @xyflow/react.
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
 *   theme="dark">
 * </archflow-designer>
 * ```
 */

// Side-effect import: initializes the i18next instance so any UI text
// rendered inside the web component (in particular ExecutionHistoryPanel
// which is plain DOM, not React) can resolve translation keys via
// `i18next.t(...)`. Idempotent — `init()` only runs the first time.
import '../i18n';
import { ArchflowDesigner } from './core/ArchflowDesigner';

// Export main component
export { ArchflowDesigner, ArchflowDesignerElement } from './core/ArchflowDesigner';

// Export supporting classes (still available)
export { ThemeManager, createThemeManager, getDefaultTheme, prefersDarkMode } from './core/ThemeManager';
export type { ThemeColors, ThemeConfig } from './core/ThemeManager';

// Export FlowCanvas types for consumers
export type { FlowNodeData, WorkflowData, WorkflowStep, WorkflowConnection } from '../components/FlowCanvas/types';
export { NODE_CATEGORIES, PALETTE_NODES } from '../components/FlowCanvas/constants';

// ==========================================================================
// Registration Helper
// ==========================================================================

export function registerArchflowDesigner(tagName = 'archflow-designer'): boolean {
  if (customElements.get(tagName)) {
    console.warn(`[ArchflowDesigner] Custom element '${tagName}' is already registered.`);
    return false;
  }

  customElements.define(tagName, ArchflowDesigner);
  console.info(`[ArchflowDesigner] Registered as <${tagName}>`);
  return true;
}

export function isArchflowDesignerRegistered(tagName = 'archflow-designer'): boolean {
  return customElements.get(tagName) !== undefined;
}

export function autoRegister(tagName = 'archflow-designer'): void {
  registerArchflowDesigner(tagName);
}

// ==========================================================================
// TypeScript Declarations for HTML
// ==========================================================================

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
  'show-minimap'?: string;
  'show-grid'?: string;
  'width'?: string;
  'height'?: string;
}

export type { ArchflowDesignerAttributes };
export default ArchflowDesigner;
