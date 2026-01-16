/**
 * NodeRegistry - Central registry for workflow node types
 *
 * Manages:
 * - Built-in node type definitions
 * - Custom node registration from extensions
 * - Node type lookup and validation
 * - Node instance creation
 */

import {
  NodeType,
  NodeCategory,
  NodeTypeDefinition,
  NodeInstance,
  PortDefinition,
  NodeParameter,
  RegisteredNode,
  NodeFactory,
  NodeValidator,
  getNodeCategory,
  type CustomNodeManifest,
  type ExtensionContext
} from './node-types';

// ==========================================================================
// Default Factory
// ==========================================================================

/**
 * Default factory for creating node instances.
 */
function defaultFactory(type: NodeType): NodeFactory {
  return () => ({
    id: `node_${Date.now()}_${Math.random().toString(36).substr(2, 9)}`,
    type,
    position: { x: 0, y: 0 },
    config: {},
    selected: false,
    disabled: false
  });
}

// ==========================================================================
// NodeRegistry Class
// ==========================================================================

export class NodeRegistry {
  private nodes: Map<NodeType, RegisteredNode> = new Map();
  private extensions: Map<string, CustomNodeManifest> = new Map();
  private context?: ExtensionContext;

  constructor() {
    this._registerBuiltinNodes();
  }

  // ==========================================================================
  // Registration
  // ==========================================================================

  /**
   * Register a node type.
   *
   * @param type - Node type identifier
   * @param definition - Node type definition
   * @param factory - Optional factory function
   * @param validator - Optional validator function
   */
  register(
    type: NodeType,
    definition: NodeTypeDefinition,
    factory?: NodeFactory,
    validator?: NodeValidator
  ): void {
    const registered: RegisteredNode = {
      ...definition,
      type,
      factory: factory || defaultFactory(type),
      validator
    };

    this.nodes.set(type, registered);
  }

  /**
   * Register a custom node from an extension.
   *
   * @param definition - Node type definition
   * @param factory - Optional factory function
   * @param validator - Optional validator function
   * @param manifest - Extension manifest
   */
  registerCustomNode(
    definition: NodeTypeDefinition,
    factory?: NodeFactory,
    validator?: NodeValidator,
    manifest?: CustomNodeManifest
  ): void {
    this.register(definition.type, definition, factory, validator);

    if (manifest && !this.extensions.has(manifest.id)) {
      this.extensions.set(manifest.id, manifest);
    }
  }

  /**
   * Unregister a node type.
   *
   * @param type - Node type to unregister
   */
  unregister(type: NodeType): void {
    this.nodes.delete(type);
  }

  /**
   * Unregister all nodes from an extension.
   *
   * @param manifestId - Extension manifest ID
   */
  unregisterExtension(manifestId: string): void {
    const manifest = this.extensions.get(manifestId);
    if (!manifest) return;

    for (const node of manifest.nodes) {
      this.unregister(node.type);
    }

    this.extensions.delete(manifestId);
  }

  // ==========================================================================
  // Lookup
  // ==========================================================================

  /**
   * Get a node type definition.
   *
   * @param type - Node type
   * @returns Node definition or undefined
   */
  get(type: NodeType): RegisteredNode | undefined {
    return this.nodes.get(type);
  }

  /**
   * Check if a node type is registered.
   *
   * @param type - Node type
   * @returns true if registered
   */
  has(type: NodeType): boolean {
    return this.nodes.has(type);
  }

  /**
   * Get all registered node types.
   *
   * @returns Array of registered nodes
   */
  getAll(): RegisteredNode[] {
    return Array.from(this.nodes.values());
  }

  /**
   * Get nodes by category.
   *
   * @param category - Node category
   * @returns Array of nodes in category
   */
  getByCategory(category: NodeCategory): RegisteredNode[] {
    return this.getAll().filter(node => node.category === category);
  }

  /**
   * Get all categories that have at least one node.
   *
   * @returns Array of categories
   */
  getCategories(): NodeCategory[] {
    const categories = new Set<NodeCategory>();
    for (const node of this.nodes.values()) {
      categories.add(node.category);
    }
    return Array.from(categories);
  }

  /**
   * Get all custom (extension) nodes.
   *
   * @returns Array of custom nodes
   */
  getCustomNodes(): RegisteredNode[] {
    return this.getAll().filter(node => node.category === NodeCategory.CUSTOM);
  }

  // ==========================================================================
  // Instance Creation
  // ==========================================================================

  /**
   * Create a new node instance.
   *
   * @param type - Node type
   * @param position - Initial position
   * @param config - Initial configuration
   * @returns Node instance
   * @throws Error if node type not registered
   */
  createInstance(
    type: NodeType,
    position: { x: number; y: number } = { x: 0, y: 0 },
    config: Record<string, unknown> = {}
  ): NodeInstance {
    const definition = this.nodes.get(type);

    if (!definition) {
      throw new Error(`Node type '${type}' is not registered`);
    }

    const instance = definition.factory();
    instance.type = type;
    instance.position = position;
    instance.config = { ...this.getDefaultConfig(definition), ...config };

    return instance;
  }

  /**
   * Get default configuration for a node type.
   *
   * @param type - Node type or definition
   * @returns Default configuration values
   */
  getDefaultConfig(typeOrDefinition: NodeType | RegisteredNode): Record<string, unknown> {
    const definition = typeof typeOrDefinition === 'string'
      ? this.nodes.get(typeOrDefinition)
      : typeOrDefinition;

    if (!definition) return {};

    const defaults: Record<string, unknown> = {};
    for (const param of definition.parameters) {
      if (param.defaultValue !== undefined) {
        defaults[param.name] = param.defaultValue;
      }
    }

    return defaults;
  }

  // ==========================================================================
  // Validation
  // ==========================================================================

  /**
   * Validate a node's configuration.
   *
   * @param type - Node type
   * @param config - Configuration to validate
   * @returns true if valid, or error message string
   */
  validate(type: NodeType, config: Record<string, unknown>): boolean | string {
    const definition = this.nodes.get(type);

    if (!definition) {
      return `Node type '${type}' is not registered`;
    }

    // Check required parameters
    for (const param of definition.parameters) {
      if (param.required && config[param.name] === undefined) {
        return `Required parameter '${param.label}' is missing`;
      }
    }

    // Run custom validator if present
    if (definition.validator) {
      const result = definition.validator(config);
      if (result !== true) {
        return result || 'Configuration validation failed';
      }
    }

    return true;
  }

  /**
   * Validate a connection between two ports.
   *
   * @param sourceType - Source node type
   * @param sourcePortId - Source port ID
   * @param targetType - Target node type
   * @param targetPortId - Target port ID
   * @returns true if connection is valid, or error message
   */
  validateConnection(
    sourceType: NodeType,
    sourcePortId: string,
    targetType: NodeType,
    targetPortId: string
  ): boolean | string {
    const sourceNode = this.nodes.get(sourceType);
    const targetNode = this.nodes.get(targetType);

    if (!sourceNode || !targetNode) {
      return 'One or both node types not registered';
    }

    const sourcePort = sourceNode.outputs.find(p => p.id === sourcePortId);
    const targetPort = targetNode.inputs.find(p => p.id === targetPortId);

    if (!sourcePort) {
      return `Source port '${sourcePortId}' not found in ${sourceType}`;
    }

    if (!targetPort) {
      return `Target port '${targetPortId}' not found in ${targetType}`;
    }

    // Check connection type compatibility
    if (targetPort.connectionType === 'none') {
      return `Target port '${targetPortId}' does not accept connections`;
    }

    // Check if source port allows multiple connections
    if (sourcePort.connectionType === 'single') {
      // This would need to be checked against existing connections
      // For now, just note that the port type doesn't allow multi
    }

    return true;
  }

  // ==========================================================================
  // Extension Management
  // ==========================================================================

  /**
   * Set the extension context for custom nodes.
   *
   * @param context - Extension context
   */
  setExtensionContext(context: ExtensionContext): void {
    this.context = context;
  }

  /**
   * Get all registered extensions.
   *
   * @returns Array of extension manifests
   */
  getExtensions(): CustomNodeManifest[] {
    return Array.from(this.extensions.values());
  }

  /**
   * Get an extension by ID.
   *
   * @param id - Extension ID
   * @returns Extension manifest or undefined
   */
  getExtension(id: string): CustomNodeManifest | undefined {
    return this.extensions.get(id);
  }

  // ==========================================================================
  // Built-in Node Registration
  // ==========================================================================

  /**
   * Register all built-in node types.
   * @private
   */
  private _registerBuiltinNodes(): void {
    // IO Nodes
    this._registerIONodes();
    // AI Nodes
    this._registerAINodes();
    // Tool Nodes
    this._registerToolNodes();
    // Control Nodes
    this._registerControlNodes();
    // Prompt Nodes
    this._registerPromptNodes();
    // Vector Nodes
    this._registerVectorNodes();
  }

  private _registerIONodes(): void {
    // Input Node
    this.register(NodeType.INPUT, {
      type: NodeType.INPUT,
      label: 'Input',
      category: NodeCategory.IO,
      description: 'Workflow entry point for incoming data',
      inputs: [],
      outputs: [
        { id: 'data', label: 'Data', dataType: 'any' as any, direction: 'input' as any, connectionType: 'multi' as any }
      ],
      parameters: [
        { name: 'inputSchema', label: 'Input Schema', type: 'json', description: 'JSON schema for input validation' }
      ],
      style: { icon: 'input', backgroundColor: '#10b981' },
      multiInstance: true
    });

    // Output Node
    this.register(NodeType.OUTPUT, {
      type: NodeType.OUTPUT,
      label: 'Output',
      category: NodeCategory.IO,
      description: 'Workflow exit point for returning data',
      inputs: [
        { id: 'data', label: 'Data', dataType: 'any' as any, direction: 'input' as any, connectionType: 'single' as any, required: true }
      ],
      outputs: [],
      parameters: [
        { name: 'outputPath', label: 'Output Path', type: 'string', description: 'JSON path to extract from input' }
      ],
      style: { icon: 'output', backgroundColor: '#10b981' },
      multiInstance: true
    });
  }

  private _registerAINodes(): void {
    // LLM Chat Node
    this.register(NodeType.LLM_CHAT, {
      type: NodeType.LLM_CHAT,
      label: 'LLM Chat',
      category: NodeCategory.AI,
      description: 'Chat with a language model',
      inputs: [
        { id: 'messages', label: 'Messages', dataType: 'array' as any, direction: 'input' as any, connectionType: 'single' as any },
        { id: 'system', label: 'System Prompt', dataType: 'string' as any, direction: 'input' as any, connectionType: 'single' as any, required: false }
      ],
      outputs: [
        { id: 'response', label: 'Response', dataType: 'object' as any, direction: 'output' as any, connectionType: 'multi' as any },
        { id: 'content', label: 'Content', dataType: 'string' as any, direction: 'output' as any, connectionType: 'multi' as any }
      ],
      parameters: [
        { name: 'model', label: 'Model', type: 'select', options: [
          { value: 'gpt-4', label: 'GPT-4' },
          { value: 'gpt-4-turbo', label: 'GPT-4 Turbo' },
          { value: 'gpt-3.5-turbo', label: 'GPT-3.5 Turbo' },
          { value: 'claude-3-opus', label: 'Claude 3 Opus' },
          { value: 'claude-3-sonnet', label: 'Claude 3 Sonnet' }
        ], defaultValue: 'gpt-4', required: true },
        { name: 'temperature', label: 'Temperature', type: 'number', defaultValue: 0.7, min: 0, max: 2, step: 0.1 },
        { name: 'maxTokens', label: 'Max Tokens', type: 'number', defaultValue: 2048, min: 1, max: 128000 },
        { name: 'stream', label: 'Stream Response', type: 'boolean', defaultValue: false }
      ],
      style: { icon: 'cpu', backgroundColor: '#8b5cf6' }
    });

    // LLM Streaming Node
    this.register(NodeType.LLM_STREAMING, {
      type: NodeType.LLM_STREAMING,
      label: 'LLM Stream',
      category: NodeCategory.AI,
      description: 'Streaming chat with a language model',
      inputs: [
        { id: 'messages', label: 'Messages', dataType: 'array' as any, direction: 'input' as any, connectionType: 'single' as any },
        { id: 'system', label: 'System Prompt', dataType: 'string' as any, direction: 'input' as any, connectionType: 'single' as any, required: false }
      ],
      outputs: [
        { id: 'stream', label: 'Stream', dataType: 'stream' as any, direction: 'output' as any, connectionType: 'multi' as any },
        { id: 'delta', label: 'Delta', dataType: 'string' as any, direction: 'output' as any, connectionType: 'multi' as any }
      ],
      parameters: [
        { name: 'model', label: 'Model', type: 'select', options: [
          { value: 'gpt-4', label: 'GPT-4' },
          { value: 'gpt-4-turbo', label: 'GPT-4 Turbo' },
          { value: 'claude-3-opus', label: 'Claude 3 Opus' },
          { value: 'claude-3-sonnet', label: 'Claude 3 Sonnet' }
        ], defaultValue: 'gpt-4', required: true },
        { name: 'temperature', label: 'Temperature', type: 'number', defaultValue: 0.7, min: 0, max: 2, step: 0.1 },
        { name: 'maxTokens', label: 'Max Tokens', type: 'number', defaultValue: 4096 }
      ],
      style: { icon: 'cpu', backgroundColor: '#8b5cf6' }
    });

    // Agent Node
    this.register(NodeType.AGENT, {
      type: NodeType.AGENT,
      label: 'Agent',
      category: NodeCategory.AI,
      description: 'AI agent with tool use capabilities',
      inputs: [
        { id: 'input', label: 'Input', dataType: 'string' as any, direction: 'input' as any, connectionType: 'single' as any, required: true }
      ],
      outputs: [
        { id: 'output', label: 'Output', dataType: 'string' as any, direction: 'output' as any, connectionType: 'multi' as any },
        { id: 'steps', label: 'Steps', dataType: 'array' as any, direction: 'output' as any, connectionType: 'multi' as any }
      ],
      parameters: [
        { name: 'agentType', label: 'Agent Type', type: 'select', options: [
          { value: 'react', label: 'ReAct Agent' },
          { value: 'openai-functions', label: 'OpenAI Functions' },
          { value: 'plan-and-execute', label: 'Plan & Execute' }
        ], defaultValue: 'react', required: true },
        { name: 'maxIterations', label: 'Max Iterations', type: 'number', defaultValue: 10, min: 1, max: 100 },
        { name: 'verbose', label: 'Verbose Logging', type: 'boolean', defaultValue: false }
      ],
      style: { icon: 'robot', backgroundColor: '#6366f1' }
    });
  }

  private _registerToolNodes(): void {
    // Tool Node
    this.register(NodeType.TOOL, {
      type: NodeType.TOOL,
      label: 'Tool',
      category: NodeCategory.TOOL,
      description: 'Execute a tool or function',
      inputs: [
        { id: 'input', label: 'Input', dataType: 'any' as any, direction: 'input' as any, connectionType: 'single' as any, required: true }
      ],
      outputs: [
        { id: 'output', label: 'Output', dataType: 'any' as any, direction: 'output' as any, connectionType: 'multi' as any }
      ],
      parameters: [
        { name: 'toolId', label: 'Tool ID', type: 'select', options: [
          { value: 'search', label: 'Search' },
          { value: 'calculator', label: 'Calculator' },
          { value: 'weather', label: 'Weather' },
          { value: 'custom', label: 'Custom Tool' }
        ], required: true },
        { name: 'toolConfig', label: 'Tool Configuration', type: 'json', description: 'Additional configuration for the tool' }
      ],
      style: { icon: 'tool', backgroundColor: '#f59e0b' }
    });
  }

  private _registerControlNodes(): void {
    // Condition Node
    this.register(NodeType.CONDITION, {
      type: NodeType.CONDITION,
      label: 'Condition',
      category: NodeCategory.CONTROL,
      description: 'Branch execution based on a condition',
      inputs: [
        { id: 'value', label: 'Value', dataType: 'any' as any, direction: 'input' as any, connectionType: 'single' as any, required: true }
      ],
      outputs: [
        { id: 'true', label: 'True', dataType: 'any' as any, direction: 'output' as any, connectionType: 'multi' as any },
        { id: 'false', label: 'False', dataType: 'any' as any, direction: 'output' as any, connectionType: 'multi' as any }
      ],
      parameters: [
        { name: 'operator', label: 'Operator', type: 'select', options: [
          { value: 'equals', label: 'Equals' },
          { value: 'not-equals', label: 'Not Equals' },
          { value: 'greater', label: 'Greater Than' },
          { value: 'less', label: 'Less Than' },
          { value: 'contains', label: 'Contains' },
          { value: 'exists', label: 'Exists' },
          { value: 'empty', label: 'Is Empty' }
        ], defaultValue: 'equals', required: true },
        { name: 'compareValue', label: 'Compare Value', type: 'string', description: 'Value to compare against' }
      ],
      style: { icon: 'git-branch', backgroundColor: '#ef4444' }
    });

    // Parallel Node
    this.register(NodeType.PARALLEL, {
      type: NodeType.PARALLEL,
      label: 'Parallel',
      category: NodeCategory.CONTROL,
      description: 'Execute multiple branches in parallel',
      inputs: [
        { id: 'input', label: 'Input', dataType: 'any' as any, direction: 'input' as any, connectionType: 'single' as any, required: true }
      ],
      outputs: [
        { id: 'branch1', label: 'Branch 1', dataType: 'any' as any, direction: 'output' as any, connectionType: 'multi' as any },
        { id: 'branch2', label: 'Branch 2', dataType: 'any' as any, direction: 'output' as any, connectionType: 'multi' as any },
        { id: 'branch3', label: 'Branch 3', dataType: 'any' as any, direction: 'output' as any, connectionType: 'multi' as any, required: false }
      ],
      parameters: [
        { name: 'mode', label: 'Merge Mode', type: 'select', options: [
          { value: 'all', label: 'Wait for All' },
          { value: 'any', label: 'First Complete' },
          { value: 'race', label: 'Race' }
        ], defaultValue: 'all' },
        { name: 'branchCount', label: 'Branch Count', type: 'number', defaultValue: 2, min: 2, max: 10 }
      ],
      style: { icon: 'git-merge', backgroundColor: '#3b82f6' }
    });

    // Loop Node
    this.register(NodeType.LOOP, {
      type: NodeType.LOOP,
      label: 'Loop',
      category: NodeCategory.CONTROL,
      description: 'Iterate over items in an array',
      inputs: [
        { id: 'items', label: 'Items', dataType: 'array' as any, direction: 'input' as any, connectionType: 'single' as any, required: true }
      ],
      outputs: [
        { id: 'item', label: 'Item', dataType: 'any' as any, direction: 'output' as any, connectionType: 'multi' as any },
        { id: 'index', label: 'Index', dataType: 'number' as any, direction: 'output' as any, connectionType: 'multi' as any },
        { id: 'results', label: 'Results', dataType: 'array' as any, direction: 'output' as any, connectionType: 'multi' as any }
      ],
      parameters: [
        { name: 'maxIterations', label: 'Max Iterations', type: 'number', defaultValue: 100, min: 1, description: 'Limit iterations for safety' },
        { name: 'variableName', label: 'Variable Name', type: 'string', defaultValue: 'item', description: 'Name of the loop variable' }
      ],
      style: { icon: 'refresh-cw', backgroundColor: '#8b5cf6' }
    });
  }

  private _registerPromptNodes(): void {
    // Prompt Template Node
    this.register(NodeType.PROMPT_TEMPLATE, {
      type: NodeType.PROMPT_TEMPLATE,
      label: 'Prompt Template',
      category: NodeCategory.PROMPT,
      description: 'Create a prompt from a template with variables',
      inputs: [
        { id: 'variables', label: 'Variables', dataType: 'object' as any, direction: 'input' as any, connectionType: 'single' as any }
      ],
      outputs: [
        { id: 'prompt', label: 'Prompt', dataType: 'string' as any, direction: 'output' as any, connectionType: 'multi' as any }
      ],
      parameters: [
        { name: 'template', label: 'Template', type: 'textarea', required: true, description: 'Use {{variable}} for placeholders', placeholder: 'You are a helpful assistant. Answer: {{question}}' },
        { name: 'systemPrompt', label: 'System Prompt', type: 'textarea', description: 'Optional system prompt' }
      ],
      style: { icon: 'file-text', backgroundColor: '#06b6d4' }
    });
  }

  private _registerVectorNodes(): void {
    // Vector Search Node
    this.register(NodeType.VECTOR_SEARCH, {
      type: NodeType.VECTOR_SEARCH,
      label: 'Vector Search',
      category: NodeCategory.VECTOR,
      description: 'Search a vector store for similar documents',
      inputs: [
        { id: 'query', label: 'Query', dataType: 'string' as any, direction: 'input' as any, connectionType: 'single' as any, required: true }
      ],
      outputs: [
        { id: 'results', label: 'Results', dataType: 'array' as any, direction: 'output' as any, connectionType: 'multi' as any }
      ],
      parameters: [
        { name: 'vectorStore', label: 'Vector Store', type: 'select', options: [
          { value: 'pinecone', label: 'Pinecone' },
          { value: 'weaviate', label: 'Weaviate' },
          { value: 'qdrant', label: 'Qdrant' },
          { value: 'redis', label: 'Redis' },
          { value: 'pgvector', label: 'pgvector' }
        ], required: true },
        { name: 'topK', label: 'Top K', type: 'number', defaultValue: 5, min: 1, max: 100 },
        { name: 'collection', label: 'Collection', type: 'string', required: true }
      ],
      style: { icon: 'database', backgroundColor: '#ec4899' }
    });

    // Embedding Node
    this.register(NodeType.EMBEDDING, {
      type: NodeType.EMBEDDING,
      label: 'Embedding',
      category: NodeCategory.VECTOR,
      description: 'Generate embeddings for text',
      inputs: [
        { id: 'text', label: 'Text', dataType: 'string' as any, direction: 'input' as any, connectionType: 'single' as any, required: true }
      ],
      outputs: [
        { id: 'embedding', label: 'Embedding', dataType: 'array' as any, direction: 'output' as any, connectionType: 'multi' as any }
      ],
      parameters: [
        { name: 'model', label: 'Model', type: 'select', options: [
          { value: 'openai-text-embedding-ada-002', label: 'OpenAI Ada 002' },
          { value: 'openai-text-embedding-3-small', label: 'OpenAI Embedding 3 Small' },
          { value: 'openai-text-embedding-3-large', label: 'OpenAI Embedding 3 Large' },
          { value: 'cohere-embed-english-v3', label: 'Cohere English v3' }
        ], defaultValue: 'openai-text-embedding-ada-002', required: true }
      ],
      style: { icon: 'layers', backgroundColor: '#ec4899' }
    });
  }
}

// ==========================================================================
// Singleton Instance
// ==========================================================================

/**
 * Global node registry instance.
 */
let globalRegistry: NodeRegistry | null = null;

/**
 * Get or create the global node registry.
 */
export function getNodeRegistry(): NodeRegistry {
  if (!globalRegistry) {
    globalRegistry = new NodeRegistry();
  }
  return globalRegistry;
}

/**
 * Reset the global node registry (useful for testing).
 */
export function resetNodeRegistry(): void {
  globalRegistry = null;
}
