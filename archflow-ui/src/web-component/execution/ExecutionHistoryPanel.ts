/**
 * ExecutionHistoryPanel - Visual execution history panel
 *
 * Shows:
 * - List of past executions
 * - Execution status indicators
 * - Duration and timestamp
 * - Re-run capability
 * - History navigation
 */

import type { ExecutionStore } from './ExecutionStore';
import type { ExecutionHistoryEntry, ExecutionState } from './execution-types';
import { ExecutionState as ES, formatDuration, formatTimestamp } from './execution-types';

// ==========================================================================
// History Panel Configuration
// ==========================================================================

export interface ExecutionHistoryPanelOptions {
  /** Panel position */
  position?: 'top' | 'bottom' | 'left' | 'right';
  /** Maximum number of entries to display */
  maxEntries?: number;
  /** Show execution details */
  showDetails?: boolean;
  /** Enable re-run functionality */
  enableRerun?: boolean;
  /** Theme */
  theme?: 'light' | 'dark';
}

// ==========================================================================
// ExecutionHistoryPanel Class
// ==========================================================================

export class ExecutionHistoryPanel {
  private store: ExecutionStore;
  private container: HTMLElement | null = null;
  private options: Required<ExecutionHistoryPanelOptions>;
  private selectedEntryId: string | null = null;
  private onRerunCallback?: (workflowId: string, input: Record<string, unknown>) => void;

  constructor(store: ExecutionStore, options: ExecutionHistoryPanelOptions = {}) {
    this.store = store;
    this.options = {
      position: options.position || 'right',
      maxEntries: options.maxEntries || 20,
      showDetails: options.showDetails !== false,
      enableRerun: options.enableRerun !== false,
      theme: options.theme || 'light'
    };
  }

  // ==========================================================================
  // Lifecycle
  // ==========================================================================

  /**
   * Initialize the history panel in a container.
   */
  initialize(overlayLayer: HTMLElement): void {
    this.container = document.createElement('div');
    this.container.className = 'archflow-history-panel';

    // Set position styles
    this._updatePosition();

    // Initial render
    this._render();

    overlayLayer.appendChild(this.container);
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

    const history = this.store.getHistory();
    const entriesToShow = history.slice(-this.options.maxEntries).reverse();

    const entriesContainer = this.container.querySelector('.archflow-history-panel__entries');
    if (entriesContainer) {
      entriesContainer.innerHTML = this._renderEntries(entriesToShow);
    }

    // Re-attach event listeners
    this._attachEventListeners();
  }

  /**
   * Set callback for re-run functionality.
   */
  onRerun(callback: (workflowId: string, input: Record<string, unknown>) => void): void {
    this.onRerunCallback = callback;
  }

  // ==========================================================================
  // Private Methods
  // ==========================================================================

  private _updatePosition(): void {
    if (!this.container) return;

    const positions: Record<string, string> = {
      'top': 'top: 0; left: 0; right: 0; height: 200px; overflow-y: auto;',
      'bottom': 'bottom: 0; left: 0; right: 0; height: 200px; overflow-y: auto;',
      'left': 'top: 0; bottom: 0; left: 0; width: 280px; overflow-y: auto;',
      'right': 'top: 0; bottom: 0; right: 0; width: 280px; overflow-y: auto;'
    };

    this.container.style.cssText = positions[this.options.position];
  }

  private _render(): void {
    if (!this.container) return;

    const history = this.store.getHistory();
    const entriesToShow = history.slice(-this.options.maxEntries).reverse();

    this.container.innerHTML = `
      <div class="archflow-history-panel__header">
        <span class="archflow-history-panel__title">Execution History</span>
        <div class="archflow-history-panel__actions">
          ${this.options.enableRerun ? `
            <button class="archflow-history-panel__action" data-action="clear" title="Clear history">
              <svg width="14" height="14" viewBox="0 0 14 14" fill="none">
                <path d="M3 4H11M3 4V11C3 11.55 3.45 12 4 12H10C10.55 12 11 11.55 11 11V4M3 4H4M5 4V3M9 4V3M4.5 7H9.5" stroke="currentColor" stroke-width="1" stroke-linecap="round"/>
              </svg>
            </button>
          ` : ''}
          <button class="archflow-history-panel__action" data-action="close" title="Close">
            <svg width="14" height="14" viewBox="0 0 14 14" fill="none">
              <path d="M10 4L4 10M4 4L10 10" stroke="currentColor" stroke-width="1.5" stroke-linecap="round"/>
            </svg>
          </button>
        </div>
      </div>
      <div class="archflow-history-panel__entries">
        ${entriesToShow.length > 0
          ? this._renderEntries(entriesToShow)
          : this._renderEmptyState()
        }
      </div>
      ${this.selectedEntryId ? this._renderDetails(this.selectedEntryId) : ''}
    `;

    this._attachEventListeners();
  }

  private _renderEntries(entries: ExecutionHistoryEntry[]): string {
    return entries.map(entry => this._renderEntry(entry)).join('');
  }

  private _renderEntry(entry: ExecutionHistoryEntry): string {
    const isSelected = entry.executionId === this.selectedEntryId;
    const statusClass = this._getStatusClass(entry.state);
    const statusIcon = this._getStatusIcon(entry.state);

    return `
      <div class="archflow-history-entry ${isSelected ? 'archflow-history-entry--selected' : ''}"
           data-entry-id="${entry.executionId}">
        <div class="archflow-history-entry__status ${statusClass}">
          ${statusIcon}
        </div>
        <div class="archflow-history-entry__content">
          <div class="archflow-history-entry__name">${entry.workflowName}</div>
          <div class="archflow-history-entry__meta">
            <span class="archflow-history-entry__time">${formatTimestamp(entry.startTime)}</span>
            <span class="archflow-history-entry__separator">•</span>
            <span class="archflow-history-entry__duration">${entry.duration ? formatDuration(entry.duration) : 'Running...'}</span>
          </div>
        </div>
        ${this.options.enableRerun && entry.state === ES.COMPLETED ? `
          <button class="archflow-history-entry__rerun" data-action="rerun" data-entry-id="${entry.executionId}" title="Re-run">
            <svg width="14" height="14" viewBox="0 0 14 14" fill="none">
              <path d="M7 3V5M7 9V11M3 7H5M9 7H11M4.22 4.22L5.64 5.64M8.36 8.36L9.78 9.78M4.22 9.78L5.64 8.36M8.36 5.64L9.78 4.22"
                    stroke="currentColor" stroke-width="1.5" stroke-linecap="round"/>
            </svg>
          </button>
        ` : ''}
      </div>
    `;
  }

  private _renderEmptyState(): string {
    return `
      <div class="archflow-history-panel__empty">
        <svg width="32" height="32" viewBox="0 0 32 32" fill="none" class="archflow-history-panel__empty-icon">
          <circle cx="16" cy="16" r="12" stroke="currentColor" stroke-width="1.5" stroke-dasharray="4 2"/>
          <path d="M16 10V16L20 18" stroke="currentColor" stroke-width="1.5" stroke-linecap="round"/>
        </svg>
        <p class="archflow-history-panel__empty-text">No executions yet</p>
        <p class="archflow-history-panel__empty-hint">Run a workflow to see history here</p>
      </div>
    `;
  }

  private _renderDetails(entryId: string): string {
    const entry = this.store.getHistoryEntry(entryId);
    if (!entry || !entry.result) return '';

    const result = entry.result;

    return `
      <div class="archflow-history-details">
        <div class="archflow-history-details__header">
          <span class="archflow-history-details__title">Execution Details</span>
          <button class="archflow-history-details__close" data-action="close-details">×</button>
        </div>
        <div class="archflow-history-details__content">
          <div class="archflow-history-details__section">
            <h4 class="archflow-history-details__section-title">Information</h4>
            <div class="archflow-history-details__info">
              <div class="archflow-history-details__info-row">
                <span class="archflow-history-details__label">ID:</span>
                <span class="archflow-history-details__value">${entry.executionId}</span>
              </div>
              <div class="archflow-history-details__info-row">
                <span class="archflow-history-details__label">State:</span>
                <span class="archflow-history-details__value archflow-history-details__value--${entry.state.toLowerCase()}">
                  ${this._getStateLabel(entry.state)}
                </span>
              </div>
              <div class="archflow-history-details__info-row">
                <span class="archflow-history-details__label">Started:</span>
                <span class="archflow-history-details__value">${new Date(entry.startTime).toLocaleString()}</span>
              </div>
              <div class="archflow-history-details__info-row">
                <span class="archflow-history-details__label">Duration:</span>
                <span class="archflow-history-details__value">${entry.duration ? formatDuration(entry.duration) : '-'}</span>
              </div>
            </div>
          </div>

          ${result.metrics ? `
            <div class="archflow-history-details__section">
              <h4 class="archflow-history-details__section-title">Metrics</h4>
              <div class="archflow-history-details__metrics">
                <div class="archflow-history-details__metric">
                  <span class="archflow-history-details__metric-value">${result.metrics.nodesExecuted || 0}</span>
                  <span class="archflow-history-details__metric-label">Nodes</span>
                </div>
                <div class="archflow-history-details__metric">
                  <span class="archflow-history-details__metric-value">${result.metrics.nodesSucceeded || 0}✓</span>
                  <span class="archflow-history-details__metric-label">Success</span>
                </div>
                <div class="archflow-history-details__metric">
                  <span class="archflow-history-details__metric-value">${result.metrics.nodesFailed || 0}✕</span>
                  <span class="archflow-history-details__metric-label">Failed</span>
                </div>
                ${result.metrics.totalTokens ? `
                  <div class="archflow-history-details__metric">
                    <span class="archflow-history-details__metric-value">${result.metrics.totalTokens.toLocaleString()}</span>
                    <span class="archflow-history-details__metric-label">Tokens</span>
                  </div>
                ` : ''}
              </div>
            </div>
          ` : ''}

          ${Object.keys(result.output || {}).length > 0 ? `
            <div class="archflow-history-details__section">
              <h4 class="archflow-history-details__section-title">Output</h4>
              <pre class="archflow-history-details__output">${JSON.stringify(result.output, null, 2)}</pre>
            </div>
          ` : ''}

          ${result.errors && result.errors.length > 0 ? `
            <div class="archflow-history-details__section">
              <h4 class="archflow-history-details__section-title">Errors</h4>
              <div class="archflow-history-details__errors">
                ${result.errors.map(err => `
                  <div class="archflow-history-details__error">
                    <div class="archflow-history-details__error-header">
                      <span class="archflow-history-details__error-node">${err.nodeId}</span>
                      <span class="archflow-history-details__error-type">${err.type}</span>
                    </div>
                    <div class="archflow-history-details__error-message">${err.message}</div>
                  </div>
                `).join('')}
              </div>
            </div>
          ` : ''}
        </div>
      </div>
    `;
  }

  private _getStatusClass(state: ExecutionState): string {
    const classes: Record<ExecutionState, string> = {
      [ES.IDLE]: 'archflow-history-entry__status--idle',
      [ES.RUNNING]: 'archflow-history-entry__status--running',
      [ES.PAUSED]: 'archflow-history-entry__status--paused',
      [ES.COMPLETED]: 'archflow-history-entry__status--completed',
      [ES.FAILED]: 'archflow-history-entry__status--failed',
      [ES.CANCELLED]: 'archflow-history-entry__status--cancelled'
    };
    return classes[state] || classes[ES.IDLE];
  }

  private _getStatusIcon(state: ExecutionState): string {
    const icons: Record<ExecutionState, string> = {
      [ES.IDLE]: '<svg width="12" height="12" viewBox="0 0 12 12" fill="currentColor"><circle cx="6" cy="6" r="5"/></svg>',
      [ES.RUNNING]: '<svg width="12" height="12" viewBox="0 0 12 12" fill="none"><circle cx="6" cy="6" r="4" stroke="currentColor" stroke-width="2"/></svg>',
      [ES.PAUSED]: '<svg width="12" height="12" viewBox="0 0 12 12" fill="currentColor"><rect x="2" y="2" width="3" height="8" rx="1"/><rect x="7" y="2" width="3" height="8" rx="1"/></svg>',
      [ES.COMPLETED]: '<svg width="12" height="12" viewBox="0 0 12 12" fill="none"><path d="M2 6L4.5 8.5L10 3" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"/></svg>',
      [ES.FAILED]: '<svg width="12" height="12" viewBox="0 0 12 12" fill="none"><path d="M3 3L9 9M9 3L3 9" stroke="currentColor" stroke-width="1.5" stroke-linecap="round"/></svg>',
      [ES.CANCELLED]: '<svg width="12" height="12" viewBox="0 0 12 12" fill="none"><circle cx="6" cy="6" r="4" stroke="currentColor" stroke-width="1.5"/><path d="M4 6H8" stroke="currentColor" stroke-width="1.5" stroke-linecap="round"/></svg>'
    };
    return icons[state] || icons[ES.IDLE];
  }

  private _getStateLabel(state: ExecutionState): string {
    const labels: Record<ExecutionState, string> = {
      [ES.IDLE]: 'Idle',
      [ES.RUNNING]: 'Running',
      [ES.PAUSED]: 'Paused',
      [ES.COMPLETED]: 'Completed',
      [ES.FAILED]: 'Failed',
      [ES.CANCELLED]: 'Cancelled'
    };
    return labels[state] || state;
  }

  private _attachEventListeners(): void {
    if (!this.container) return;

    // Entry selection
    this.container.querySelectorAll('[data-entry-id]').forEach(el => {
      el.addEventListener('click', (e) => {
        const entryId = el.getAttribute('data-entry-id');
        if (entryId) {
          this.selectedEntryId = entryId;
          this._render();
        }
      });
    });

    // Rerun button
    this.container.querySelectorAll('[data-action="rerun"]').forEach(el => {
      el.addEventListener('click', (e) => {
        e.stopPropagation();
        const entryId = el.getAttribute('data-entry-id');
        if (entryId && this.onRerunCallback) {
          const entry = this.store.getHistoryEntry(entryId);
          if (entry) {
            this.onRerunCallback(entry.workflowId, {});
          }
        }
      });
    });

    // Clear button
    const clearBtn = this.container.querySelector('[data-action="clear"]');
    clearBtn?.addEventListener('click', () => {
      this.store.clearHistory();
      this.selectedEntryId = null;
      this._render();
    });

    // Close button
    const closeBtn = this.container.querySelector('[data-action="close"]');
    closeBtn?.addEventListener('click', () => {
      this.container?.classList.add('archflow-history-panel--hidden');
    });

    // Close details button
    const closeDetailsBtn = this.container.querySelector('[data-action="close-details"]');
    closeDetailsBtn?.addEventListener('click', () => {
      this.selectedEntryId = null;
      this._render();
    });
  }

  /**
   * Show the panel.
   */
  show(): void {
    this.container?.classList.remove('archflow-history-panel--hidden');
  }

  /**
   * Hide the panel.
   */
  hide(): void {
    this.container?.classList.add('archflow-history-panel--hidden');
  }

  /**
   * Toggle panel visibility.
   */
  toggle(): void {
    this.container?.classList.toggle('archflow-history-panel--hidden');
  }
}

// ==========================================================================
// CSS Styles
// ==========================================================================

export const executionHistoryPanelStyles = `
.archflow-history-panel {
  position: absolute;
  background: var(--bg-primary, #ffffff);
  border: 1px solid var(--border-color, #e2e8f0);
  border-radius: 8px;
  box-shadow: 0 4px 12px rgba(0, 0, 0, 0.15);
  font-size: 13px;
  font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;
  z-index: 1000;
  display: flex;
  flex-direction: column;
  max-height: 400px;
  transition: opacity 0.15s ease;
}

.archflow-history-panel--hidden {
  display: none;
}

.archflow-history-panel__header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 12px 16px;
  border-bottom: 1px solid var(--border-color, #e2e8f0);
}

.archflow-history-panel__title {
  font-weight: 600;
  color: var(--text-primary, #0f172a);
  font-size: 14px;
}

.archflow-history-panel__actions {
  display: flex;
  gap: 4px;
}

.archflow-history-panel__action {
  background: none;
  border: none;
  cursor: pointer;
  padding: 4px;
  border-radius: 4px;
  color: var(--text-secondary, #64748b);
  display: flex;
  align-items: center;
  justify-content: center;
  transition: background 0.15s ease;
}

.archflow-history-panel__action:hover {
  background: var(--bg-hover, #f1f5f9);
  color: var(--text-primary, #0f172a);
}

.archflow-history-panel__entries {
  flex: 1;
  overflow-y: auto;
  padding: 8px;
}

.archflow-history-entry {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 8px 10px;
  border-radius: 6px;
  cursor: pointer;
  transition: background 0.15s ease;
}

.archflow-history-entry:hover {
  background: var(--bg-hover, #f8fafc);
}

.archflow-history-entry--selected {
  background: var(--bg-selected, #eff6ff);
}

.archflow-history-entry__status {
  width: 24px;
  height: 24px;
  display: flex;
  align-items: center;
  justify-content: center;
  border-radius: 50%;
  flex-shrink: 0;
}

.archflow-history-entry__status--idle {
  background: #f1f5f9;
  color: #64748b;
}

.archflow-history-entry__status--running {
  background: #dbeafe;
  color: #1e40af;
  animation: spin 1s linear infinite;
}

.archflow-history-entry__status--paused {
  background: #fef3c7;
  color: #92400e;
}

.archflow-history-entry__status--completed {
  background: #dcfce7;
  color: #166534;
}

.archflow-history-entry__status--failed,
.archflow-history-entry__status--cancelled {
  background: #fee2e2;
  color: #991b1b;
}

.archflow-history-entry__content {
  flex: 1;
  min-width: 0;
}

.archflow-history-entry__name {
  font-weight: 500;
  color: var(--text-primary, #0f172a);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.archflow-history-entry__meta {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 11px;
  color: var(--text-secondary, #64748b);
  margin-top: 2px;
}

.archflow-history-entry__separator {
  color: var(--text-muted, #cbd5e1);
}

.archflow-history-entry__rerun {
  background: none;
  border: none;
  cursor: pointer;
  padding: 4px;
  border-radius: 4px;
  color: var(--text-secondary, #64748b);
  opacity: 0;
  transition: all 0.15s ease;
}

.archflow-history-entry:hover .archflow-history-entry__rerun {
  opacity: 1;
}

.archflow-history-entry__rerun:hover {
  background: var(--bg-primary, #ffffff);
  color: #3b82f6;
}

.archflow-history-panel__empty {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  padding: 32px 16px;
  text-align: center;
}

.archflow-history-panel__empty-icon {
  color: var(--text-muted, #cbd5e1);
  margin-bottom: 12px;
}

.archflow-history-panel__empty-text {
  color: var(--text-secondary, #64748b);
  font-weight: 500;
  margin: 0 0 4px 0;
}

.archflow-history-panel__empty-hint {
  color: var(--text-muted, #94a3b8);
  font-size: 12px;
  margin: 0;
}

/* Details Panel */
.archflow-history-details {
  border-top: 1px solid var(--border-color, #e2e8f0);
  background: var(--bg-secondary, #f8fafc);
  max-height: 300px;
  overflow-y: auto;
}

.archflow-history-details__header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 12px 16px;
  border-bottom: 1px solid var(--border-color, #e2e8f0);
}

.archflow-history-details__title {
  font-weight: 600;
  color: var(--text-primary, #0f172a);
  font-size: 13px;
}

.archflow-history-details__close {
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
  color: var(--text-secondary, #64748b);
  border-radius: 4px;
}

.archflow-history-details__close:hover {
  background: var(--bg-hover, #f1f5f9);
}

.archflow-history-details__content {
  padding: 16px;
}

.archflow-history-details__section {
  margin-bottom: 16px;
}

.archflow-history-details__section:last-child {
  margin-bottom: 0;
}

.archflow-history-details__section-title {
  font-size: 11px;
  font-weight: 600;
  text-transform: uppercase;
  letter-spacing: 0.05em;
  color: var(--text-secondary, #64748b);
  margin: 0 0 8px 0;
}

.archflow-history-details__info {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.archflow-history-details__info-row {
  display: flex;
  justify-content: space-between;
  font-size: 12px;
}

.archflow-history-details__label {
  color: var(--text-secondary, #64748b);
}

.archflow-history-details__value {
  color: var(--text-primary, #0f172a);
  font-weight: 500;
}

.archflow-history-details__value--completed {
  color: #16a34a;
}

.archflow-history-details__value--failed {
  color: #dc2626;
}

.archflow-history-details__metrics {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 8px;
}

.archflow-history-details__metric {
  text-align: center;
  padding: 8px;
  background: var(--bg-primary, #ffffff);
  border-radius: 6px;
}

.archflow-history-details__metric-value {
  display: block;
  font-size: 14px;
  font-weight: 600;
  color: var(--text-primary, #0f172a);
}

.archflow-history-details__metric-label {
  font-size: 10px;
  color: var(--text-secondary, #64748b);
  text-transform: uppercase;
}

.archflow-history-details__output {
  background: var(--bg-primary, #ffffff);
  border: 1px solid var(--border-color, #e2e8f0);
  border-radius: 6px;
  padding: 10px;
  font-size: 11px;
  font-family: 'Monaco', 'Menlo', 'Courier New', monospace;
  color: var(--text-primary, #0f172a);
  overflow-x: auto;
  margin: 0;
  white-space: pre-wrap;
  word-break: break-all;
}

.archflow-history-details__errors {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.archflow-history-details__error {
  background: #fef2f2;
  border: 1px solid #fecaca;
  border-radius: 6px;
  padding: 8px 12px;
}

.archflow-history-details__error-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 4px;
}

.archflow-history-details__error-node {
  font-weight: 600;
  color: #991b1b;
  font-size: 11px;
}

.archflow-history-details__error-type {
  font-size: 10px;
  color: #dc2626;
  background: #fee2e2;
  padding: 2px 6px;
  border-radius: 4px;
}

.archflow-history-details__error-message {
  font-size: 12px;
  color: #7f1d1d;
}

@keyframes spin {
  from { transform: rotate(0deg); }
  to { transform: rotate(360deg); }
}

/* Dark theme */
:host([theme='dark']) .archflow-history-panel {
  background: #1e293b;
  border-color: #475569;
}

:host([theme='dark']) .archflow-history-panel__title {
  color: #f1f5f9;
}

:host([theme='dark']) .archflow-history-entry:hover {
  background: #334155;
}

:host([theme='dark']) .archflow-history-entry--selected {
  background: #1e3a5f;
}

:host([theme='dark']) .archflow-history-entry__name {
  color: #f1f5f9;
}

:host([theme='dark']) .archflow-history-entry__status--idle {
  background: #334155;
  color: #94a3b8;
}

:host([theme='dark']) .archflow-history-entry__status--running {
  background: #1e3a5f;
  color: #93c5fd;
}

:host([theme='dark']) .archflow-history-entry__status--completed {
  background: #14532d;
  color: #86efac;
}

:host([theme='dark']) .archflow-history-entry__status--failed {
  background: #450a0a;
  color: #fca5a5;
}

:host([theme='dark']) .archflow-history-details {
  background: #0f172a;
}

:host([theme='dark']) .archflow-history-details__title {
  color: #f1f5f9;
}

:host([theme='dark']) .archflow-history-details__value {
  color: #f1f5f9;
}

:host([theme='dark']) .archflow-history-details__metric {
  background: #1e293b;
}

:host([theme='dark']) .archflow-history-details__output {
  background: #1e293b;
  border-color: #475569;
  color: #e2e8f0;
}
`;
