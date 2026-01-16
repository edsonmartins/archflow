/**
 * ArchflowShadowDom - Manages Shadow DOM rendering and styling
 *
 * Handles:
 * - Shadow DOM creation and management
 * - CSS encapsulation with theme support
 * - Component rendering based on state
 * - Size management
 */

import type { Theme } from './ThemeManager';
import type { ArchflowEventDispatcher } from './ArchflowEventDispatcher';
import type { Flow } from '../../types/flow-types';

// ==========================================================================
// Types
// ==========================================================================

export interface ShadowDomState {
  workflow: Flow | null;
  isLoading: boolean;
  isExecuting: boolean;
  readonly: boolean;
  selectedNodeIds: string[];
}

export interface ShadowDomConfig {
  width: string;
  height: string;
}

// ==========================================================================
// CSS Styles (embedded for standalone functionality)
// ==========================================================================

const BASE_STYLES = `
  :host {
    display: block;
    box-sizing: border-box;
    font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, Oxygen, Ubuntu, sans-serif;
  }

  * {
    box-sizing: border-box;
  }

  .archflow-designer {
    width: 100%;
    height: 100%;
    display: flex;
    flex-direction: column;
    border-radius: 8px;
    overflow: hidden;
  }

  .archflow-header {
    display: flex;
    align-items: center;
    justify-content: space-between;
    padding: 12px 16px;
    border-bottom: 1px solid var(--border-color);
    min-height: 48px;
  }

  .archflow-title {
    font-size: 14px;
    font-weight: 600;
    color: var(--text-primary);
    display: flex;
    align-items: center;
    gap: 8px;
  }

  .archflow-title svg {
    width: 20px;
    height: 20px;
  }

  .archflow-actions {
    display: flex;
    gap: 8px;
  }

  .archflow-button {
    display: inline-flex;
    align-items: center;
    gap: 6px;
    padding: 6px 12px;
    border-radius: 6px;
    border: 1px solid var(--border-color);
    background: var(--bg-secondary);
    color: var(--text-primary);
    font-size: 13px;
    font-weight: 500;
    cursor: pointer;
    transition: all 0.15s ease;
  }

  .archflow-button:hover:not(:disabled) {
    background: var(--bg-tertiary);
    border-color: var(--accent-color);
  }

  .archflow-button:disabled {
    opacity: 0.5;
    cursor: not-allowed;
  }

  .archflow-button.primary {
    background: var(--accent-color);
    color: var(--accent-text);
    border-color: var(--accent-color);
  }

  .archflow-button.primary:hover:not(:disabled) {
    opacity: 0.9;
  }

  .archflow-canvas {
    flex: 1;
    position: relative;
    overflow: hidden;
    background: var(--bg-canvas);
  }

  .archflow-empty {
    display: flex;
    flex-direction: column;
    align-items: center;
    justify-content: center;
    height: 100%;
    color: var(--text-secondary);
    text-align: center;
    padding: 24px;
  }

  .archflow-empty svg {
    width: 64px;
    height: 64px;
    margin-bottom: 16px;
    opacity: 0.5;
  }

  .archflow-empty h3 {
    font-size: 16px;
    font-weight: 600;
    margin-bottom: 8px;
    color: var(--text-primary);
  }

  .archflow-empty p {
    font-size: 13px;
    max-width: 300px;
  }

  .archflow-loading {
    display: flex;
    align-items: center;
    justify-content: center;
    height: 100%;
  }

  .archflow-spinner {
    width: 32px;
    height: 32px;
    border: 3px solid var(--border-color);
    border-top-color: var(--accent-color);
    border-radius: 50%;
    animation: spin 0.8s linear infinite;
  }

  @keyframes spin {
    to { transform: rotate(360deg); }
  }

  .archflow-status-bar {
    display: flex;
    align-items: center;
    justify-content: space-between;
    padding: 8px 16px;
    border-top: 1px solid var(--border-color);
    font-size: 12px;
    color: var(--text-secondary);
    min-height: 32px;
  }

  .archflow-status-left {
    display: flex;
    align-items: center;
    gap: 12px;
  }

  .archflow-status-right {
    display: flex;
    align-items: center;
    gap: 12px;
  }

  .archflow-badge {
    display: inline-flex;
    align-items: center;
    padding: 2px 8px;
    border-radius: 12px;
    font-size: 11px;
    font-weight: 500;
  }

  .archflow-badge.success {
    background: var(--success-bg);
    color: var(--success-text);
  }

  .archflow-badge.error {
    background: var(--error-bg);
    color: var(--error-text);
  }

  .archflow-badge.warning {
    background: var(--warning-bg);
    color: var(--warning-text);
  }

  .archflow-badge.info {
    background: var(--info-bg);
    color: var(--info-text);
  }
`;

const LIGHT_THEME_STYLES = `
  :host {
    --bg-primary: #ffffff;
    --bg-secondary: #f8fafc;
    --bg-tertiary: #f1f5f9;
    --bg-canvas: #fafafa;
    --text-primary: #0f172a;
    --text-secondary: #64748b;
    --border-color: #e2e8f0;
    --accent-color: #3b82f6;
    --accent-text: #ffffff;
    --success-bg: #dcfce7;
    --success-text: #166534;
    --error-bg: #fee2e2;
    --error-text: #991b1b;
    --warning-bg: #fef3c7;
    --warning-text: #92400e;
    --info-bg: #dbeafe;
    --info-text: #1e40af;
  }
`;

const DARK_THEME_STYLES = `
  :host {
    --bg-primary: #1e293b;
    --bg-secondary: #334155;
    --bg-tertiary: #475569;
    --bg-canvas: #0f172a;
    --text-primary: #f1f5f9;
    --text-secondary: #94a3b8;
    --border-color: #475569;
    --accent-color: #60a5fa;
    --accent-text: #ffffff;
    --success-bg: #166534;
    --success-text: #dcfce7;
    --error-bg: #991b1b;
    --error-text: #fee2e2;
    --warning-bg: #92400e;
    --warning-text: #fef3c7;
    --info-bg: #1e40af;
    --info-text: #dbeafe;
  }
`;

// ==========================================================================
// Icons (SVG)
// ==========================================================================

const ICONS = {
  logo: `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
    <path d="M12 2L2 7l10 5 10-5-10-5z"/>
    <path d="M2 17l10 5 10-5"/>
    <path d="M2 12l10 5 10-5"/>
  </svg>`,
  save: `<svg xmlns="http://www.w3.org/2000/svg" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
    <path d="M19 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h11l5 5v11a2 2 0 0 1-2 2z"/>
    <polyline points="17 21 17 13 7 13 7 21"/>
    <polyline points="7 3 7 8 15 8"/>
  </svg>`,
  execute: `<svg xmlns="http://www.w3.org/2000/svg" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
    <polygon points="5 3 19 12 5 21 5 3"/>
  </svg>`,
  reset: `<svg xmlns="http://www.w3.org/2000/svg" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
    <path d="M3 12a9 9 0 1 0 9-9 9.75 9.75 0 0 0-6.74 2.74L3 8"/>
    <path d="M3 3v5h5"/>
  </svg>`,
  empty: `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5">
    <rect x="3" y="3" width="18" height="18" rx="2"/>
    <circle cx="8.5" cy="8.5" r="1.5"/>
    <path d="M21 15l-5-5L5 21"/>
  </svg>`
};

// ==========================================================================
// ArchflowShadowDom Class
// ==========================================================================

export class ArchflowShadowDom {
  private shadowRoot: ShadowRoot;
  private themeManager: { theme: Theme } | null;
  private eventDispatcher: ArchflowEventDispatcher | null;
  private container: HTMLElement | null = null;

  constructor(
    shadowRoot: ShadowRoot,
    themeManager: { theme: Theme } | null,
    eventDispatcher: ArchflowEventDispatcher | null
  ) {
    this.shadowRoot = shadowRoot;
    this.themeManager = themeManager;
    this.eventDispatcher = eventDispatcher;

    this._initStyles();
  }

  // ==========================================================================
  // Public Methods
  // ==========================================================================

  /**
   * Render the component with given state.
   */
  render(state: ShadowDomState): void {
    // Update theme styles if needed
    this._updateThemeStyles();

    // Get or create container
    if (!this.container) {
      this.container = this.shadowRoot.getElementById('archflow-container') as HTMLElement;
      if (!this.container) {
        this.container = document.createElement('div');
        this.container.id = 'archflow-container';
        this.shadowRoot.appendChild(this.container);
      }
    }

    // Render based on state
    if (state.isLoading) {
      this.container.innerHTML = this._renderLoading();
    } else if (!state.workflow) {
      this.container.innerHTML = this._renderEmpty(state.readonly);
    } else {
      this.container.innerHTML = this._renderDesigner(state);
    }

    // Attach event listeners
    this._attachListeners(state);
  }

  /**
   * Update selected nodes visual state.
   */
  updateSelection(nodeIds: string[]): void {
    // Update visual selection state
    const nodes = this.shadowRoot.querySelectorAll('[data-node-id]');
    nodes.forEach(node => {
      const nodeId = node.getAttribute('data-node-id');
      if (nodeId && nodeIds.includes(nodeId)) {
        node.classList.add('selected');
      } else {
        node.classList.remove('selected');
      }
    });
  }

  /**
   * Set component size.
   */
  setSize(width: string, height: string): void {
    const host = this.shadowRoot.host as HTMLElement;
    if (host) {
      host.style.width = width;
      host.style.height = height;
    }
  }

  /**
   * Cleanup resources.
   */
  destroy(): void {
    if (this.container) {
      this.container.innerHTML = '';
    }
  }

  // ==========================================================================
  // Private Methods - Style Management
  // ==========================================================================

  private _initStyles(): void {
    // Create style element
    const style = document.createElement('style');
    style.id = 'archflow-styles';
    this.shadowRoot.appendChild(style);

    // Initial styles
    this._updateThemeStyles();
  }

  private _updateThemeStyles(): void {
    const style = this.shadowRoot.getElementById('archflow-styles') as HTMLStyleElement;
    if (!style) return;

    const theme = this.themeManager?.theme || 'light';
    const themeStyles = theme === 'dark' ? DARK_THEME_STYLES : LIGHT_THEME_STYLES;

    style.textContent = BASE_STYLES + themeStyles;
  }

  // ==========================================================================
  // Private Methods - Rendering
  // ==========================================================================

  private _renderLoading(): string {
    return `
      <div class="archflow-designer">
        <div class="archflow-loading">
          <div class="archflow-spinner"></div>
        </div>
      </div>
    `;
  }

  private _renderEmpty(readonly: boolean): string {
    return `
      <div class="archflow-designer">
        <div class="archflow-header">
          <div class="archflow-title">
            ${ICONS.logo}
            <span>Archflow Designer</span>
          </div>
          <div class="archflow-actions">
            ${readonly ? '' : `
              <button class="archflow-button" data-action="create">
                New Workflow
              </button>
            `}
          </div>
        </div>
        <div class="archflow-canvas">
          <div class="archflow-empty">
            ${ICONS.empty}
            <h3>No Workflow Loaded</h3>
            <p>Create a new workflow or load an existing one to get started.</p>
          </div>
        </div>
      </div>
    `;
  }

  private _renderDesigner(state: ShadowDomState): string {
    const { workflow, isExecuting, readonly } = state;
    const workflowName = workflow?.metadata?.name || 'Untitled Workflow';

    return `
      <div class="archflow-designer">
        <div class="archflow-header">
          <div class="archflow-title">
            ${ICONS.logo}
            <span>${this._escapeHtml(workflowName)}</span>
            ${workflow?.id ? `<span class="archflow-badge info">v${workflow.metadata.version || '1.0.0'}</span>` : ''}
          </div>
          <div class="archflow-actions">
            ${readonly ? '' : `
              <button class="archflow-button" data-action="save" ${isExecuting ? 'disabled' : ''}>
                ${ICONS.save}
                Save
              </button>
            `}
            <button class="archflow-button primary" data-action="execute" ${isExecuting ? 'disabled' : ''}>
              ${ICONS.execute}
              ${isExecuting ? 'Running...' : 'Execute'}
            </button>
            ${!readonly ? `
              <button class="archflow-button" data-action="reset">
                ${ICONS.reset}
              </button>
            ` : ''}
          </div>
        </div>

        <div class="archflow-canvas" data-canvas>
          <!-- Canvas will be rendered here by the canvas module -->
          <div class="archflow-empty">
            <p>Canvas: ${workflow?.steps?.length || 0} steps</p>
          </div>
        </div>

        <div class="archflow-status-bar">
          <div class="archflow-status-left">
            <span>Steps: ${workflow?.steps?.length || 0}</span>
            <span>Status: ${workflow?.id ? 'Loaded' : 'Draft'}</span>
          </div>
          <div class="archflow-status-right">
            ${isExecuting ? '<span class="archflow-badge warning">Executing</span>' : ''}
            <span class="archflow-badge ${workflow?.id ? 'success' : 'info'}">
              ${workflow?.id ? 'Ready' : 'Unsaved'}
            </span>
          </div>
        </div>
      </div>
    `;
  }

  private _escapeHtml(text: string): string {
    const div = document.createElement('div');
    div.textContent = text;
    return div.innerHTML;
  }

  // ==========================================================================
  // Private Methods - Event Listeners
  // ==========================================================================

  private _attachListeners(state: ShadowDomState): void {
    // Button click handlers
    const buttons = this.container?.querySelectorAll('[data-action]');
    buttons?.forEach(button => {
      button.removeEventListener('click', this._handleActionClick);
      button.addEventListener('click', this._handleActionClick);
    });
  }

  private _handleActionClick = (event: Event): void => {
    const button = event.currentTarget as HTMLElement;
    const action = button.getAttribute('data-action');

    if (this.eventDispatcher) {
      // Emit action event - the parent ArchflowDesigner will handle it
      const customEvent = new CustomEvent(`archflow:${action}`, {
        bubbles: true,
        composed: true,
        detail: { action }
      });
      this.shadowRoot.host.dispatchEvent(customEvent);
    }
  };
}
