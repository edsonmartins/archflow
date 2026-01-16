/**
 * ArchflowDesigner - Node System
 *
 * Complete node system for visual workflow building including:
 * - Node type definitions and registry
 * - Node component factories
 * - Custom node extension API
 *
 * @example
 * ```ts
 * import { getNodeRegistry } from '@archflow/component/nodes';
 *
 * const registry = getNodeRegistry();
 *
 * // Get all available nodes
 * const allNodes = registry.getAll();
 *
 * // Create a new node instance
 * const inputNode = registry.createInstance('input', { x: 100, y: 100 });
 *
 * // Register a custom node
 * registry.registerCustomNode(
 *   {
 *     type: 'my-custom-node',
 *     label: 'My Custom Node',
 *     category: NodeCategory.CUSTOM,
 *     description: 'A custom node from an extension',
 *     inputs: [createPort('in', 'Input', 'any')],
 *     outputs: [createPort('out', 'Output', 'any')],
 *     parameters: []
 *   },
 *   () => ({ id: 'custom-1', type: 'my-custom-node', position: { x: 0, y: 0 }, config: {} })
 * );
 * ```
 */

// ==========================================================================
// Type Definitions
// ==========================================================================

export * from './node-types';

// ==========================================================================
// Node Registry
// ==========================================================================

export {
  NodeRegistry,
  getNodeRegistry,
  resetNodeRegistry
} from './NodeRegistry';

// Import for internal use
import { getNodeRegistry } from './NodeRegistry';

// ==========================================================================
// Node Components
// ==========================================================================

export {
  BaseNodeComponent,
  InputNodeComponent,
  OutputNodeComponent,
  LLMNodeComponent,
  AgentNodeComponent,
  ToolNodeComponent,
  ConditionNodeComponent,
  ParallelNodeComponent,
  LoopNodeComponent,
  PromptTemplateNodeComponent,
  VectorSearchNodeComponent,
  EmbeddingNodeComponent,
  CustomNodeComponent,
  createNodeComponent,
  type NodeComponent
} from './nodes/index';

// ==========================================================================
// Node Styles
// ==========================================================================

export { default as nodeStyles } from './nodes/node-styles.css';

// ==========================================================================
// Custom Node API
// ==========================================================================

export {
  ExtensionManager,
  getExtensionManager,
  resetExtensionManager,
  createCustomNodeManifest,
  createInlineCustomNode,
  registerCustomNode,
  unregisterCustomNode
} from './CustomNodeAPI';

// ==========================================================================
// Utilities
// ==========================================================================

/**
 * Get a list of all built-in node types.
 */
export function getBuiltinNodeTypes(): string[] {
  return [
    'input',
    'output',
    'llm-chat',
    'llm-streaming',
    'assistant',
    'agent',
    'tool',
    'function',
    'condition',
    'parallel',
    'loop',
    'switch',
    'prompt-template',
    'prompt-chunk',
    'vector-search',
    'vector-store',
    'embedding',
    'map',
    'filter',
    'reduce',
    'transform',
    'merge'
  ];
}

/**
 * Get node type metadata for display.
 */
export function getNodeTypeMetadata(type: string): {
  category: string;
  label: string;
  description: string;
} | null {
  const registry = getNodeRegistry();
  const definition = registry.get(type as any);

  if (!definition) return null;

  return {
    category: definition.category,
    label: definition.label,
    description: definition.description
  };
}

/**
 * Validate a node configuration against its definition.
 */
export function validateNodeConfig(
  type: string,
  config: Record<string, unknown>
): boolean | string {
  const registry = getNodeRegistry();
  return registry.validate(type as any, config);
}

/**
 * Create a new node instance with auto-generated ID.
 */
export function createNode(
  type: string,
  position: { x: number; y: number } = { x: 0, y: 0 },
  config: Record<string, unknown> = {}
) {
  const registry = getNodeRegistry();
  return registry.createInstance(type as any, position, config);
}

// ==========================================================================
// Default Export
// ==========================================================================

export default getNodeRegistry;
