/**
 * ExecutionPanel - Visual execution status panel
 *
 * Shows:
 * - Current execution state
 * - Node execution status indicators
 * - Error display
 * - Execution metrics
 */

import type { ExecutionStore } from './ExecutionStore';
import type { NodeExecutionResultMap } from './execution-types';
import { ExecutionState as ES, type ExecutionState, formatDuration, formatTimestamp } from './execution-types';

// ==========================================================================
// Panel Configuration
// ==========================================================================

export interface ExecutionPanelOptions {
  /** Panel position */
  position?: 'top' | 'bottom' | 'left' | 'right';
  /** Show metrics */
  showMetrics?: boolean;
  /** Show history */
  showHistory?: boolean;
  /** Maximum number of errors to display */
  maxErrors?: number;
  /** Theme */
  theme?: 'light' | 'dark';
}

// ==========================================================================
// ExecutionPanel Class
// ==========================================================================

export class ExecutionPanel {
  private store: ExecutionStore;
  private container: HTMLElement | null = null;
  private options: Required<ExecutionPanelOptions>;

  constructor(store: ExecutionStore, options: ExecutionPanelOptions = {}) {
    this.store = store;
    this.options = {
      position: options.position || 'bottom',
      showMetrics: options.showMetrics !== false,
      showHistory: options.showHistory !== false,
      maxErrors: options.maxErrors || 10,
      theme: options.theme || 'light'
    };
  }

  // ==========================================================================
  // Lifecycle
  // ==========================================================================

  /**
   * Initialize the panel in a container.
   */
  initialize(overlayLayer: HTMLElement): void {
    this.container = document.createElement('div');
    this.container.className = 'archflow-execution-panel';

    // Set position styles
    this._updatePosition();

    // Initial render
    this._render();

    overlayLayer.appendChild(this.container);

    // Subscribe to store changes
    this.store['state']; // This will be replaced with proper subscription
  }

  /**
   * Cleanup resources.
   */
  destroy(): void {
    if (this.container && this.container.parentElement) {
      this.container.parentElement.removeChild(this.container);
    }
    this.container = null;
  }

  // ==========================================================================
  // Rendering
  // ==========================================================================

  /**
   * Update the panel display.
   */
  update(): void {
    if (!this.container) return;

    const state = this.store.state;
    const metrics = this.store.metrics;
    const errors = this.store.errors;

    // Update state indicator
    const stateEl = this.container.querySelector('.archflow-execution-panel__state');
    if (stateEl) {
      stateEl.className = `archflow-execution-panel__state archflow-execution-panel__state--${state.state.toLowerCase()}`;
      stateEl.textContent = this._getStateLabel(state.state);
    }

    // Update metrics
    if (this.options.showMetrics) {
      const metricsEl = this.container.querySelector('.archflow-execution-panel__metrics');
      if (metricsEl) {
        metricsEl.innerHTML = this._renderMetrics(metrics);
      }
    }

    // Update errors
    const errorsEl = this.container.querySelector('.archflow-execution-panel__errors');
    if (errorsEl) {
      const errorsToShow = errors.slice(-this.options.maxErrors);
      errorsEl.innerHTML = errorsToShow.length > 0
        ? errorsToShow.map(e => this._renderError(e)).join('')
        : '<div class="archflow-execution-panel__no-errors">No errors</div>';
    }
  }

  /**
   * Update node status indicator.
   */
  updateNodeStatus(nodeId: string, status: string): void {
    const nodeEl = this.container.querySelector(`[data-node-status="${nodeId}"]`);
    if (nodeEl) {
      nodeEl.className = `archflow-execution-panel__node-status archflow-execution-panel__node-status--${status.toLowerCase()}`;
    }
  }

  // ==========================================================================
  // Private Methods
  // ==========================================================================

  private _updatePosition(): void {
    if (!this.container) return;

    const positions: Record<string, string> = {
      'top': 'top: 0; left: 0; right: 0;',
      'bottom': 'bottom: 0; left: 0; right: 0;',
      'left': 'top: 0; bottom: 0; left: 0; width: 200px;',
      'right': 'top: 0; bottom: 0; right: 0; width: 200px;'
    };

    this.container.style.cssText += positions[this.options.position];
  }

  private _render(): void {
    if (!this.container) return;

    const state = this.store.state;
    const metrics = this.store.metrics;
    const errors = this.store.errors;

    this.container.innerHTML = `
      <div class="archflow-execution-panel__header">
        <span class="archflow-execution-panel__state archflow-execution-panel__state--${state.state.toLowerCase()}">
          ${this._getStateLabel(state.state)}
        </span>
        <button class="archflow-execution-panel__close" data-action="close">×</button>
      </div>

      ${this.options.showMetrics ? `
        <div class="archflow-execution-panel__metrics">
          ${this._renderMetrics(metrics)}
        </div>
      ` : ''}

      ${errors.length > 0 ? `
        <div class="archflow-execution-panel__errors">
          ${errors.slice(-this.options.maxErrors).map(e => this._renderError(e)).join('')}
        </div>
      ` : ''}
    `;

    // Add close button handler
    const closeBtn = this.container.querySelector('[data-action="close"]');
    closeBtn?.addEventListener('click', () => {
      this.container?.classList.add('archflow-execution-panel--hidden');
    });
  }

  private _renderMetrics(metrics: Readonly<{
    totalDuration: number;
    nodesExecuted: number;
    nodesSucceeded: number;
    nodesFailed: number;
    nodesSkipped: number;
    totalTokens: number;
  }>): string {
    return `
      <div class="archflow-execution-panel__metric">
        <span class="archflow-execution-panel__metric-label">Time:</span>
        <span class="archflow-execution-panel__metric-value">${formatDuration(metrics.totalDuration)}</span>
      </div>
      <div class="archflow-execution-panel__metric">
        <span class="archflow-execution-panel__metric-label">Nodes:</span>
        <span class="archflow-execution-panel__metric-value">${metrics.nodesExecuted}</span>
        <span class="archflow-execution-panel__metric-sub">
          ${metrics.nodesSucceeded}✓ ${metrics.nodesFailed}✕
        </span>
      </div>
      ${metrics.totalTokens > 0 ? `
        <div class="archflow-execution-panel__metric">
          <span class="archflow-execution-panel__metric-label">Tokens:</span>
          <span class="archflow-execution-panel__metric-value">${metrics.totalTokens.toLocaleString()}</span>
        </div>
      ` : ''}
    `;
  }

  private _renderError(error: {
    id: string;
    nodeId: string;
    type: string;
    message: string;
    timestamp: number;
  }): string {
    return `
      <div class="archflow-execution-panel__error">
        <div class="archflow-execution-panel__error-header">
          <span class="archflow-execution-panel__error-node">${error.nodeId}</span>
          <span class="archflow-execution-panel__error-time">${formatTimestamp(error.timestamp)}</span>
        </div>
        <div class="archflow-execution-panel__error-message">${error.message}</div>
      </div>
    `;
  }

  private _getStateLabel(state: ExecutionState): string {
    const labels: Record<ExecutionState, string> = {
      [ES.IDLE]: 'Idle',
      [ES.RUNNING]: 'Running...',
      [ES.PAUSED]: 'Paused',
      [ES.COMPLETED]: 'Completed',
      [ES.FAILED]: 'Failed',
      [ES.CANCELLED]: 'Cancelled'
    };
    return labels[state] || state;
  }

  /**
   * Show the panel.
   */
  show(): void {
    this.container?.classList.remove('archflow-execution-panel--hidden');
  }

  /**
   * Hide the panel.
   */
  hide(): void {
    this.container?.classList.add('archflow-execution-panel--hidden');
  }

  /**
   * Toggle panel visibility.
   */
  toggle(): void {
    this.container?.classList.toggle('archflow-execution-panel--hidden');
  }
}

// ==========================================================================
// CSS Styles
// ==========================================================================

export const executionPanelStyles = `
.archflow-execution-panel {
  position: absolute;
  background: var(--bg-primary, #ffffff);
  border: 1px solid var(--border-color, #e2e8f0);
  border-radius: 8px;
  box-shadow: 0 4px 12px rgba(0, 0, 0, 0.15);
  padding: 12px 16px;
  font-size: 13px;
  font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;
  z-index: 1000;
  max-height: 300px;
  overflow-y: auto;
  transition: opacity 0.15s ease;
}

.archflow-execution-panel--hidden {
  display: none;
}

.archflow-execution-panel__header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 12px;
}

.archflow-execution-panel__state {
  font-weight: 600;
  padding: 4px 10px;
  border-radius: 4px;
  font-size: 12px;
  text-transform: uppercase;
}

.archflow-execution-panel__state--idle {
  background: #f1f5f9;
  color: #64748b;
}

.archflow-execution-panel__state--running {
  background: #dbeafe;
  color: #1e40af;
  animation: pulse 1.5s ease-in-out infinite;
}

.archflow-execution-panel__state--paused {
  background: #fef3c7;
  color: #92400e;
}

.archflow-execution-panel__state--completed {
  background: #dcfce7;
  color: #166534;
}

.archflow-execution-panel__state--failed,
.archflow-execution-panel__state--cancelled {
  background: #fee2e2;
  color: #991b1b;
}

.archflow-execution-panel__close {
  background: none;
  border: none;
  font-size: 18px;
  line-height: 1;
  cursor: pointer;
  padding: 0;
  width: 20px;
  height: 20px;
  display: flex;
  align-items: center;
  justify-content: center;
  color: #64748b;
  border-radius: 4px;
}

.archflow-execution-panel__close:hover {
  background: #f1f5f9;
}

.archflow-execution-panel__metrics {
  display: flex;
  flex-direction: column;
  gap: 8px;
  margin-bottom: 12px;
}

.archflow-execution-panel__metric {
  display: flex;
  align-items: baseline;
  gap: 6px;
}

.archflow-execution-panel__metric-label {
  color: #64748b;
  font-size: 12px;
}

.archflow-execution-panel__metric-value {
  font-weight: 600;
  color: #0f172a;
}

.archflow-execution-panel__metric-sub {
  font-size: 11px;
  color: #64748b;
}

.archflow-execution-panel__errors {
  display: flex;
  flex-direction: column;
  gap: 8px;
  max-height: 150px;
  overflow-y: auto;
}

.archflow-execution-panel__no-errors {
  color: #94a3b8;
  font-size: 12px;
  font-style: italic;
  text-align: center;
  padding: 8px;
}

.archflow-execution-panel__error {
  background: #fef2f2;
  border: 1px solid #fecaca;
  border-radius: 6px;
  padding: 8px 12px;
}

.archflow-execution-panel__error-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 4px;
  font-size: 11px;
}

.archflow-execution-panel__error-node {
  font-weight: 600;
  color: #991b1b;
}

.archflow-execution-panel__error-time {
  color: #94a3b8;
}

.archflow-execution-panel__error-message {
  font-size: 12px;
  color: #7f1d1d;
  word-break: break-word;
}

@keyframes pulse {
  0%, 100% { opacity: 1; }
  50% { opacity: 0.7; }
}

/* Dark theme */
:host([theme='dark']) .archflow-execution-panel {
  background: #1e293b;
  border-color: #475569;
}

:host([theme='dark']) .archflow-execution-panel__state--idle {
  background: #334155;
  color: #94a3b8;
}

:host([theme='dark']) .archflow-execution-panel__state--running {
  background: #1e3a5f;
  color: #93c5fd;
}

:host([theme='dark']) .archflow-execution-panel__state--completed {
  background: #14532d;
  color: #86efac;
}

:host([theme='dark']) .archflow-execution-panel__state--failed {
  background: #450a0a;
  color: #fca5a5;
}

:host([theme='dark']) .archflow-execution-panel__metric-label {
  color: #94a3b8;
}

:host([theme='dark']) .archflow-execution-panel__metric-value {
  color: #f1f5f9;
}

:host([theme='dark']) .archflow-execution-panel__close {
  color: #94a3b8;
}

:host([theme='dark']) .archflow-execution-panel__close:hover {
  background: #334155;
}
`;
