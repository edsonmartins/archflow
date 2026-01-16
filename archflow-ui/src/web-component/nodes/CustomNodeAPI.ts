/**
 * Custom Node Extension API
 *
 * Allows third-party developers to create custom nodes for ArchflowDesigner.
 * Custom nodes can be loaded from external modules or defined inline.
 */

import type {
  NodeTypeDefinition,
  NodeInstance,
  NodeFactory,
  CustomNodeManifest,
  CustomNodeExtension,
  ExtensionContext
} from './node-types';
import { getNodeRegistry, type NodeRegistry } from './NodeRegistry';
import { NodeType, NodeCategory } from './node-types';

// ==========================================================================
// Extension Manager
// ==========================================================================

/**
 * Manages loading and lifecycle of custom node extensions.
 */
export class ExtensionManager {
  private registry: NodeRegistry;
  private extensions: Map<string, CustomNodeExtension> = new Map();
  private loadedModules: Set<string> = new Set();

  constructor(registry?: NodeRegistry) {
    this.registry = registry || getNodeRegistry();
  }

  // -----------------------------------------------------------------------
  // Extension Loading
  // -----------------------------------------------------------------------

  /**
   * Load a custom node extension from a URL.
   *
   * @param url - URL to the extension module
   * @param manifest - Optional manifest (if not included in module)
   * @returns Promise that resolves when extension is loaded
   */
  async loadFromURL(url: string, manifest?: CustomNodeManifest): Promise<void> {
    if (this.loadedModules.has(url)) {
      console.warn(`Extension already loaded: ${url}`);
      return;
    }

    try {
      // Dynamically import the module
      const module = await import(/* @vite-ignore */ url);

      // Get manifest from module or parameter
      const extensionManifest = manifest || module.manifest;
      if (!extensionManifest) {
        throw new Error('Extension must provide a manifest');
      }

      // Validate manifest
      this._validateManifest(extensionManifest);

      // Create extension object
      const extension: CustomNodeExtension = {
        manifest: extensionManifest,
        initialize: module.initialize,
        destroy: module.destroy
      };

      // Register nodes
      for (const nodeDef of extensionManifest.nodes) {
        const factory = module.factories?.[nodeDef.type];
        const validator = module.validators?.[nodeDef.type];

        this.registry.registerCustomNode(nodeDef, factory, validator, extensionManifest);
      }

      // Initialize extension if needed
      if (extension.initialize) {
        const context = this._createContext(extensionManifest);
        await extension.initialize(context);
      }

      this.extensions.set(extensionManifest.id, extension);
      this.loadedModules.add(url);

      console.info(`[ExtensionManager] Loaded extension: ${extensionManifest.name} v${extensionManifest.version}`);
    } catch (error) {
      console.error(`[ExtensionManager] Failed to load extension from ${url}:`, error);
      throw error;
    }
  }

  /**
   * Load a custom node extension from a JavaScript object.
   *
   * @param extension - Extension object with manifest and optional lifecycle methods
   * @returns Promise that resolves when extension is loaded
   */
  async loadFromObject(extension: CustomNodeExtension): Promise<void> {
    const manifest = extension.manifest;

    // Validate manifest
    this._validateManifest(manifest);

    // Register nodes
    for (const nodeDef of manifest.nodes) {
      // Use default factory if not provided
      const factory = extension.manifest.nodes.find(n => n.type === nodeDef.type);
      this.registry.registerCustomNode(nodeDef);
    }

    // Initialize extension if needed
    if (extension.initialize) {
      const context = this._createContext(manifest);
      await extension.initialize(context);
    }

    this.extensions.set(manifest.id, extension);

    console.info(`[ExtensionManager] Loaded extension: ${manifest.name} v${manifest.version}`);
  }

  /**
   * Load multiple extensions from URLs.
   *
   * @param urls - Array of URLs to load
   * @returns Promise that resolves when all extensions are loaded
   */
  async loadMultiple(urls: string[]): Promise<void> {
    await Promise.all(urls.map(url => this.loadFromURL(url).catch(err => {
      console.error(`Failed to load extension from ${url}:`, err);
    })));
  }

  // -----------------------------------------------------------------------
  // Extension Unloading
  // -----------------------------------------------------------------------

  /**
   * Unload an extension by ID.
   *
   * @param extensionId - Extension ID to unload
   * @returns Promise that resolves when extension is unloaded
   */
  async unload(extensionId: string): Promise<void> {
    const extension = this.extensions.get(extensionId);

    if (!extension) {
      console.warn(`Extension not found: ${extensionId}`);
      return;
    }

    // Call destroy if present
    if (extension.destroy) {
      await extension.destroy();
    }

    // Unregister nodes
    this.registry.unregisterExtension(extensionId);

    this.extensions.delete(extensionId);

    console.info(`[ExtensionManager] Unloaded extension: ${extensionId}`);
  }

  /**
   * Unload all extensions.
   *
   * @returns Promise that resolves when all extensions are unloaded
   */
  async unloadAll(): Promise<void> {
    const extensionIds = Array.from(this.extensions.keys());
    await Promise.all(extensionIds.map(id => this.unload(id)));
  }

  // -----------------------------------------------------------------------
  // Querying
  // -----------------------------------------------------------------------

  /**
   * Get all loaded extensions.
   *
   * @returns Array of extension manifests
   */
  getExtensions(): CustomNodeManifest[] {
    return Array.from(this.extensions.values()).map(ext => ext.manifest);
  }

  /**
   * Get an extension by ID.
   *
   * @param id - Extension ID
   * @returns Extension manifest or undefined
   */
  getExtension(id: string): CustomNodeManifest | undefined {
    return this.extensions.get(id)?.manifest;
  }

  /**
   * Check if an extension is loaded.
   *
   * @param id - Extension ID
   * @returns true if loaded
   */
  hasExtension(id: string): boolean {
    return this.extensions.has(id);
  }

  /**
   * Get all custom nodes from all extensions.
   *
   * @returns Array of custom node definitions
   */
  getCustomNodes(): NodeTypeDefinition[] {
    const nodes: NodeTypeDefinition[] = [];

    for (const extension of this.extensions.values()) {
      nodes.push(...extension.manifest.nodes);
    }

    return nodes;
  }

  // -----------------------------------------------------------------------
  // Private Methods
  // -----------------------------------------------------------------------

  /**
   * Validate a custom node manifest.
   * @private
   */
  private _validateManifest(manifest: CustomNodeManifest): void {
    if (!manifest.id) {
      throw new Error('Manifest must have an id');
    }

    if (!manifest.name) {
      throw new Error('Manifest must have a name');
    }

    if (!manifest.version) {
      throw new Error('Manifest must have a version');
    }

    if (!Array.isArray(manifest.nodes) || manifest.nodes.length === 0) {
      throw new Error('Manifest must have at least one node');
    }

    // Validate each node definition
    for (const node of manifest.nodes) {
      if (!node.type) {
        throw new Error('Node must have a type');
      }

      if (!node.label) {
        throw new Error('Node must have a label');
      }

      if (!node.category) {
        throw new Error('Node must have a category');
      }
    }
  }

  /**
   * Create an extension context for an extension.
   * @private
   */
  private _createContext(manifest: CustomNodeManifest): ExtensionContext {
    return {
      apiBase: '',
      theme: 'light',
      registerNode: (definition: NodeTypeDefinition, factory?: NodeFactory) => {
        this.registry.registerCustomNode(definition, factory, undefined, manifest);
      },
      unregisterNode: (type: NodeType) => {
        this.registry.unregister(type);
      },
      getWorkflow: () => {
        return null; // TODO: Implement
      },
      emit: (event: string, data: unknown) => {
        // Emit event to host
        window.dispatchEvent(new CustomEvent(`archflow-extension:${manifest.id}`, {
          detail: { event, data }
        }));
      }
    };
  }
}

// ==========================================================================
// Singleton Instance
// ==========================================================================

/**
 * Global extension manager instance.
 */
let globalManager: ExtensionManager | null = null;

/**
 * Get or create the global extension manager.
 */
export function getExtensionManager(): ExtensionManager {
  if (!globalManager) {
    globalManager = new ExtensionManager();
  }
  return globalManager;
}

/**
 * Reset the global extension manager (useful for testing).
 */
export function resetExtensionManager(): void {
  globalManager = null;
}

// ==========================================================================
// Helper Functions
// -----------------------------------------------------------------------

/**
 * Create a custom node manifest programmatically.
 */
export function createCustomNodeManifest(config: {
  id: string;
  name: string;
  version: string;
  author: string;
  description: string;
  nodes: Omit<NodeTypeDefinition, 'type'>[];
}): CustomNodeManifest {
  return {
    ...config,
    nodes: config.nodes.map((node, index) => ({
      ...node,
      type: `${config.id}-node-${index}` as NodeType
    }))
  };
}

/**
 * Create a simple inline custom node.
 */
export function createInlineCustomNode(config: {
  id: string;
  label: string;
  description: string;
  inputs?: Array<{ id: string; label: string; type: string }>;
  outputs?: Array<{ id: string; label: string; type: string }>;
  parameters?: Array<{ name: string; label: string; type: string }>;
}): NodeTypeDefinition {
  return {
    type: config.id as NodeType,
    label: config.label,
    category: NodeCategory.CUSTOM,
    description: config.description,
    inputs: (config.inputs || []).map(p => ({
      id: p.id,
      label: p.label,
      dataType: p.type as any,
      direction: 'input' as any,
      connectionType: 'single' as any
    })),
    outputs: (config.outputs || []).map(p => ({
      id: p.id,
      label: p.label,
      dataType: p.type as any,
      direction: 'output' as any,
      connectionType: 'multi' as any
    })),
    parameters: (config.parameters || []).map(p => ({
      name: p.name,
      label: p.label,
      type: p.type as any
    }))
  };
}

/**
 * Register a custom node inline without creating a full extension.
 *
 * @example
 * ```ts
 * import { registerCustomNode } from '@archflow/component/nodes';
 *
 * registerCustomNode({
 *   id: 'my-node',
 *   label: 'My Node',
 *   description: 'Does something cool',
 *   inputs: [{ id: 'in', label: 'Input', type: 'string' }],
 *   outputs: [{ id: 'out', label: 'Output', type: 'string' }]
 * });
 * ```
 */
export function registerCustomNode(config: {
  id: string;
  label: string;
  description: string;
  inputs?: Array<{ id: string; label: string; type: string }>;
  outputs?: Array<{ id: string; label: string; type: string }>;
  parameters?: Array<{ name: string; label: string; type: string }>;
}): void {
  const definition = createInlineCustomNode(config);
  const registry = getNodeRegistry();
  registry.registerCustomNode(definition);
}

/**
 * Unregister a custom node by ID.
 */
export function unregisterCustomNode(type: NodeType): void {
  const registry = getNodeRegistry();
  registry.unregister(type);
}
