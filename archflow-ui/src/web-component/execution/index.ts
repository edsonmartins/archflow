/**
 * ArchflowDesigner - Execution System
 *
 * Complete execution system for running workflows including:
 * - ExecutionStore for state management
 * - REST and streaming execution
 * - ExecutionPanel for visual feedback
 * - Error handling and metrics
 *
 * @example
 * ```ts
 * import { createExecutionStore, createExecutionPanel } from '@archflow/component/execution';
 *
 * const store = createExecutionStore({
 *   apiBase: 'http://localhost:8080/api',
 *   onStateChange: (state) => console.log('Execution state:', state)
 * });
 *
 * const panel = new ExecutionPanel(store, {
 *   position: 'bottom',
 *   showMetrics: true
 * });
 * panel.initialize(overlayElement);
 *
 * // Execute workflow
 * const result = await store.execute(workflow, { input: 'Hello' }, {
 *   stream: true,
 *   callbacks: {
 *     onNodeComplete: (nodeId, result) => console.log(nodeId, result),
 *     onError: (error) => console.error(error)
 *   }
 * });
 * ```
 */

// ==========================================================================
// Type Definitions
// ==========================================================================

export * from './execution-types';

// ==========================================================================
// ExecutionStore
// ==========================================================================

export {
  ExecutionStore,
  createExecutionStore
} from './ExecutionStore';

// ==========================================================================
// ExecutionPanel
// ==========================================================================

export {
  ExecutionPanel,
  executionPanelStyles
} from './ExecutionPanel';

// ==========================================================================
// ExecutionHistoryPanel
// ==========================================================================

export {
  ExecutionHistoryPanel,
  executionHistoryPanelStyles
} from './ExecutionHistoryPanel';

// ==========================================================================
// Default Export
// ==========================================================================

import { createExecutionStore } from './ExecutionStore';
export default createExecutionStore;
