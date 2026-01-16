/**
 * Node System Type Definitions
 *
 * Defines the type system for workflow nodes in the ArchflowDesigner.
 * Compatible with the backend Flow types but optimized for visual editing.
 */

import { StepType } from '../../types/flow-types';
import type { FlowStep } from '../../types/flow-types';

// ==========================================================================
// Node Categories
// ==========================================================================

/**
 * Categories of nodes for organization and filtering.
 */
export enum NodeCategory {
  /** Input/output nodes for workflow entry/exit points */
  IO = 'io',
  /** LLM and AI model nodes */
  AI = 'ai',
  /** Tool and function execution nodes */
  TOOL = 'tool',
  /** Data processing and transformation nodes */
  DATA = 'data',
  /** Flow control nodes (condition, parallel, loop) */
  CONTROL = 'control',
  /** Prompt template and configuration nodes */
  PROMPT = 'prompt',
  /** Vector store and retrieval nodes */
  VECTOR = 'vector',
  /** Custom nodes from third-party extensions */
  CUSTOM = 'custom'
}

// ==========================================================================
// Node Types
// ==========================================================================

/**
 * Standard node types in Archflow.
 */
export enum NodeType {
  // IO Nodes
  INPUT = 'input',
  OUTPUT = 'output',

  // AI Nodes
  LLM_CHAT = 'llm-chat',
  LLM_STREAMING = 'llm-streaming',
  ASSISTANT = 'assistant',
  AGENT = 'agent',

  // Tool Nodes
  TOOL = 'tool',
  FUNCTION = 'function',

  // Data Nodes
  MAP = 'map',
  FILTER = 'filter',
  REDUCE = 'reduce',
  TRANSFORM = 'transform',
  MERGE = 'merge',

  // Control Nodes
  CONDITION = 'condition',
  PARALLEL = 'parallel',
  LOOP = 'loop',
  SWITCH = 'switch',

  // Prompt Nodes
  PROMPT_TEMPLATE = 'prompt-template',
  PROMPT_CHUNK = 'prompt-chunk',

  // Vector Nodes
  VECTOR_SEARCH = 'vector-search',
  VECTOR_STORE = 'vector-store',
  EMBEDDING = 'embedding',

  // Custom
  CUSTOM = 'custom'
}

// ==========================================================================
// Port Definitions
// ==========================================================================

/**
 * Direction of a port (input or output).
 */
export enum PortDirection {
  INPUT = 'input',
  OUTPUT = 'output'
}

/**
 * Types of values that can flow through ports.
 */
export enum PortDataType {
  STRING = 'string',
  NUMBER = 'number',
  BOOLEAN = 'boolean',
  ARRAY = 'array',
  OBJECT = 'object',
  ANY = 'any',
  MESSAGE = 'message',      // Chat message format
  STREAM = 'stream',        // Streaming response
  BINARY = 'binary',        // File/binary data
  VOID = 'void'            // No output
}

/**
 * Connection type for ports.
 */
export enum PortConnectionType {
  /** Single connection allowed */
  SINGLE = 'single',
  /** Multiple connections allowed */
  MULTI = 'multi',
  /** No connections allowed (internal use) */
  NONE = 'none'
}

/**
 * Definition of a node port (input or output).
 */
export interface PortDefinition {
  /** Unique port identifier */
  id: string;
  /** Display name */
  label: string;
  /** Data type this port accepts/emits */
  dataType: PortDataType;
  /** Port direction */
  direction: PortDirection;
  /** Connection type */
  connectionType: PortConnectionType;
  /** Optional default value for input ports */
  defaultValue?: unknown;
  /** Whether this port is required */
  required?: boolean;
  /** Description for tooltip */
  description?: string;
}

// ==========================================================================
// Node Definitions
// ==========================================================================

/**
 * Configuration parameter for a node.
 */
export interface NodeParameter {
  /** Parameter identifier */
  name: string;
  /** Display label */
  label: string;
  /** Parameter type */
  type: 'string' | 'number' | 'boolean' | 'select' | 'textarea' | 'code' | 'json' | 'array';
  /** Default value */
  defaultValue?: unknown;
  /** Options for select type */
  options?: ParameterOption[];
  /** Whether parameter is required */
  required?: boolean;
  /** Placeholder text */
  placeholder?: string;
  /** Validation regex */
  validation?: string;
  /** Description */
  description?: string;
  /** Group for UI organization */
  group?: string;
  /** Minimum value (for number) */
  min?: number;
  /** Maximum value (for number) */
  max?: number;
  /** Step value (for number) */
  step?: number;
}

/**
 * Option for select-type parameters.
 */
export interface ParameterOption {
  /** Option value */
  value: string | number | boolean;
  /** Display label */
  label: string;
  /** Optional icon */
  icon?: string;
  /** Optional description */
  description?: string;
}

/**
 * Visual style configuration for a node.
 */
export interface NodeStyle {
  /** Background color (CSS) */
  backgroundColor?: string;
  /** Border color (CSS) */
  borderColor?: string;
  /** Text color (CSS) */
  color?: string;
  /** Icon to display */
  icon?: string;
  /** Node size hint */
  size?: 'small' | 'medium' | 'large';
  /** CSS class for custom styling */
  className?: string;
}

/**
 * Definition of a node type.
 */
export interface NodeTypeDefinition {
  /** Unique type identifier */
  type: NodeType;
  /** Display name */
  label: string;
  /** Category for grouping */
  category: NodeCategory;
  /** Description */
  description: string;
  /** Input ports */
  inputs: PortDefinition[];
  /** Output ports */
  outputs: PortDefinition[];
  /** Configuration parameters */
  parameters: NodeParameter[];
  /** Visual style */
  style?: NodeStyle;
  /** Minimum height for UI */
  minHeight?: number;
  /** Whether this node can be deleted */
  deletable?: boolean;
  /** Whether this node can have multiple instances */
  multiInstance?: boolean;
}

// ==========================================================================
// Node Instances
// ==========================================================================

/**
 * A node instance in a workflow.
 */
export interface NodeInstance {
  /** Unique instance ID */
  id: string;
  /** Node type */
  type: NodeType;
  /** Position in canvas (x, y) */
  position: { x: number; y: number };
  /** Configuration parameter values */
  config: Record<string, unknown>;
  /** Currently selected */
  selected?: boolean;
  /** Disabled state */
  disabled?: boolean;
}

/**
 * Connection between two nodes.
 */
export interface NodeConnection {
  /** Unique connection ID */
  id: string;
  /** Source node ID */
  sourceNodeId: string;
  /** Source port ID */
  sourcePortId: string;
  /** Target node ID */
  targetNodeId: string;
  /** Target port ID */
  targetPortId: string;
  /** Optional condition label */
  label?: string;
}

// ==========================================================================
// Node Execution State
// ==========================================================================

/**
 * Execution state of a node instance.
 */
export enum NodeExecutionState {
  /** Not yet executed */
  IDLE = 'idle',
  /** Currently executing */
  RUNNING = 'running',
  /** Completed successfully */
  COMPLETED = 'completed',
  /** Completed with errors */
  ERROR = 'error',
  /** Skipped (branch not taken) */
  SKIPPED = 'skipped',
  /** Waiting for input */
  WAITING = 'waiting'
}

/**
 * Execution result for a node.
 */
export interface NodeExecutionResult {
  /** Node ID */
  nodeId: string;
  /** Execution state */
  state: NodeExecutionState;
  /** Output data by port ID */
  outputs: Record<string, unknown>;
  /** Error message if state is ERROR */
  error?: string;
  /** Execution time in milliseconds */
  executionTime?: number;
  /** Token usage for AI nodes */
  tokensUsed?: number;
  /** Timestamp of execution */
  timestamp?: number;
}

// ==========================================================================
// Node Registry Types
// ==========================================================================

/**
 * Factory function for creating node instances.
 */
export type NodeFactory = () => NodeInstance;

/**
 * Validator function for node configuration.
 */
export type NodeValidator = (config: Record<string, unknown>) => boolean | string;

/**
 * Node definition with factory and validator.
 */
export interface RegisteredNode extends NodeTypeDefinition {
  /** Factory function to create instances */
  factory: NodeFactory;
  /** Optional validator */
  validator?: NodeValidator;
  /** Module path for lazy loading */
  modulePath?: string;
}

// ==========================================================================
// Custom Node Extension Types
// ==========================================================================

/**
 * Manifest for a custom node extension.
 */
export interface CustomNodeManifest {
  /** Unique identifier (vendor:name) */
  id: string;
  /** Display name */
  name: string;
  /** Version */
  version: string;
  /** Author */
  author: string;
  /** Description */
  description: string;
  /** Icon URL */
  icon?: string;
  /** Homepage URL */
  homepage?: string;
  /** List of node types provided */
  nodes: NodeTypeDefinition[];
  /** Minimum archflow version required */
  minArchflowVersion?: string;
  /** Permissions required */
  permissions?: string[];
}

/**
 * Custom node extension loader.
 */
export interface CustomNodeExtension {
  /** Manifest */
  manifest: CustomNodeManifest;
  /** Initialize the extension */
  initialize?(context: ExtensionContext): Promise<void>;
  /** Cleanup on unload */
  destroy?(): Promise<void>;
}

/**
 * Context provided to custom node extensions.
 */
export interface ExtensionContext {
  /** API base URL */
  apiBase: string;
  /** Theme */
  theme: 'light' | 'dark';
  /** Register a new node type */
  registerNode(definition: NodeTypeDefinition, factory?: NodeFactory): void;
  /** Unregister a node type */
  unregisterNode(type: NodeType): void;
  /** Get current workflow data */
  getWorkflow(): unknown;
  /** Emit events to host */
  emit(event: string, data: unknown): void;
}

// ==========================================================================
// Type Guards
// ==========================================================================

/**
 * Check if a node type is an input/output node.
 */
export function isIONode(type: NodeType): boolean {
  return type === NodeType.INPUT || type === NodeType.OUTPUT;
}

/**
 * Check if a node type is an AI node.
 */
export function isAINode(type: NodeType): boolean {
  return [
    NodeType.LLM_CHAT,
    NodeType.LLM_STREAMING,
    NodeType.ASSISTANT,
    NodeType.AGENT
  ].includes(type);
}

/**
 * Check if a node type is a control flow node.
 */
export function isControlNode(type: NodeType): boolean {
  return [
    NodeType.CONDITION,
    NodeType.PARALLEL,
    NodeType.LOOP,
    NodeType.SWITCH
  ].includes(type);
}

/**
 * Get node category from node type.
 */
export function getNodeCategory(type: NodeType): NodeCategory {
  if (isIONode(type)) return NodeCategory.IO;
  if (isAINode(type)) return NodeCategory.AI;
  if (isControlNode(type)) return NodeCategory.CONTROL;
  if (type === NodeType.TOOL || type === NodeType.FUNCTION) return NodeCategory.TOOL;
  if (type === NodeType.PROMPT_TEMPLATE || type === NodeType.PROMPT_CHUNK) return NodeCategory.PROMPT;
  if (type === NodeType.VECTOR_SEARCH || type === NodeType.VECTOR_STORE || type === NodeType.EMBEDDING) {
    return NodeCategory.VECTOR;
  }
  if ([NodeType.MAP, NodeType.FILTER, NodeType.REDUCE, NodeType.TRANSFORM, NodeType.MERGE].includes(type)) {
    return NodeCategory.DATA;
  }
  return NodeCategory.CUSTOM;
}

// ==========================================================================
// Port Type Utilities
// ==========================================================================

/**
 * Check if two port data types are compatible.
 */
export function arePortTypesCompatible(
  source: PortDataType,
  target: PortDataType
): boolean {
  if (target === PortDataType.ANY) return true;
  if (source === PortDataType.ANY) return true;
  return source === target;
}

/**
 * Create a port definition.
 */
export function createPort(
  id: string,
  label: string,
  dataType: PortDataType,
  direction: PortDirection = PortDirection.INPUT,
  connectionType: PortConnectionType = PortConnectionType.SINGLE,
  required = false
): PortDefinition {
  return {
    id,
    label,
    dataType,
    direction,
    connectionType,
    required
  };
}

// ==========================================================================
// Node Type Utilities
// ==========================================================================

/**
 * Create a node type definition.
 */
export function createNodeTypeDefinition(
  type: NodeType,
  label: string,
  category: NodeCategory,
  description: string,
  inputs: PortDefinition[] = [],
  outputs: PortDefinition[] = [],
  parameters: NodeParameter[] = []
): NodeTypeDefinition {
  return {
    type,
    label,
    category,
    description,
    inputs,
    outputs,
    parameters
  };
}

// ==========================================================================
// Mappings
// ==========================================================================

/**
 * Map node type to step type for backend compatibility.
 */
export function nodeTypeToStepType(nodeType: NodeType): StepType {
  const mapping: Record<NodeType, StepType> = {
    [NodeType.INPUT]: StepType.CUSTOM,
    [NodeType.OUTPUT]: StepType.CUSTOM,
    [NodeType.LLM_CHAT]: StepType.ASSISTANT,
    [NodeType.LLM_STREAMING]: StepType.ASSISTANT,
    [NodeType.ASSISTANT]: StepType.ASSISTANT,
    [NodeType.AGENT]: StepType.AGENT,
    [NodeType.TOOL]: StepType.TOOL,
    [NodeType.FUNCTION]: StepType.TOOL,
    [NodeType.MAP]: StepType.CUSTOM,
    [NodeType.FILTER]: StepType.CUSTOM,
    [NodeType.REDUCE]: StepType.CUSTOM,
    [NodeType.TRANSFORM]: StepType.CUSTOM,
    [NodeType.MERGE]: StepType.CUSTOM,
    [NodeType.CONDITION]: StepType.CUSTOM,
    [NodeType.PARALLEL]: StepType.CUSTOM,
    [NodeType.LOOP]: StepType.CUSTOM,
    [NodeType.SWITCH]: StepType.CUSTOM,
    [NodeType.PROMPT_TEMPLATE]: StepType.CUSTOM,
    [NodeType.PROMPT_CHUNK]: StepType.CUSTOM,
    [NodeType.VECTOR_SEARCH]: StepType.TOOL,
    [NodeType.VECTOR_STORE]: StepType.TOOL,
    [NodeType.EMBEDDING]: StepType.TOOL,
    [NodeType.CUSTOM]: StepType.CUSTOM
  };

  return mapping[nodeType] || StepType.CUSTOM;
}
