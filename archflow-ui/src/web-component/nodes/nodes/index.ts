/**
 * Node Components - Visual representations of workflow nodes
 *
 * Each node component handles its own rendering and state management.
 * These are designed to be framework-agnostic and work within the Web Component.
 */

import type { NodeInstance, NodeTypeDefinition, NodeExecutionState } from '../node-types';

// ==========================================================================
// Base Node Component Interface
// ==========================================================================

/**
 * Base interface for all node components.
 */
export interface NodeComponent {
  /** Node instance data */
  node: NodeInstance;
  /** Node type definition */
  definition: NodeTypeDefinition;
  /** Current execution state */
  executionState?: NodeExecutionState;
  /** Render the node's HTML */
  render(): string;
  /** Handle node click */
  onClick?(): void;
  /** Handle configuration change */
  onConfigChange?(key: string, value: unknown): void;
  /** Validate current configuration */
  validate?(): boolean | string;
}

// ==========================================================================
// Base Node Component Class
// ==========================================================================

/**
 * Abstract base class for node components.
 * Provides common functionality for all node types.
 */
export abstract class BaseNodeComponent implements NodeComponent {
  constructor(
    public node: NodeInstance,
    public definition: NodeTypeDefinition,
    public executionState?: NodeExecutionState
  ) {}

  /**
   * Get the CSS classes for this node.
   */
  protected getClasses(): string {
    const classes = ['archflow-node'];

    // Add type class
    classes.push(`archflow-node--${this.node.type}`);

    // Add state classes
    if (this.node.selected) classes.push('archflow-node--selected');
    if (this.node.disabled) classes.push('archflow-node--disabled');

    // Add execution state class
    if (this.executionState) {
      classes.push(`archflow-node--${this.executionState}`);
    }

    // Add category class
    classes.push(`archflow-node--${this.definition.category}`);

    // Add custom class from style
    if (this.definition.style?.className) {
      classes.push(this.definition.style.className);
    }

    return classes.join(' ');
  }

  /**
   * Get the node's background color.
   */
  protected getBackgroundColor(): string {
    return this.definition.style?.backgroundColor || '#3b82f6';
  }

  /**
   * Get the node's border color.
   */
  protected getBorderColor(): string {
    return this.definition.style?.borderColor || '';
  }

  /**
   * Get the node's text color.
   */
  protected getTextColor(): string {
    return this.definition.style?.color || '#ffffff';
  }

  /**
   * Get the node's icon HTML.
   */
  protected getIcon(): string {
    const iconName = this.definition.style?.icon || 'box';
    return this.renderIcon(iconName);
  }

  /**
   * Render an SVG icon by name.
   */
  protected renderIcon(name: string): string {
    const icons: Record<string, string> = {
      'input': `<svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M5 12h14M12 5l7 7-7 7"/></svg>`,
      'output': `<svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M19 12H5M12 19l-7-7 7-7"/></svg>`,
      'cpu': `<svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><rect x="4" y="4" width="16" height="16" rx="2"/><rect x="9" y="9" width="6" height="6"/><line x1="9" y1="1" x2="9" y2="4"/><line x1="15" y1="1" x2="15" y2="4"/><line x1="9" y1="20" x2="9" y2="23"/><line x1="15" y1="20" x2="15" y2="23"/><line x1="20" y1="9" x2="23" y2="9"/><line x1="20" y1="14" x2="23" y2="14"/><line x1="1" y1="9" x2="4" y2="9"/><line x1="1" y1="14" x2="4" y2="14"/></svg>`,
      'robot': `<svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><rect x="3" y="11" width="18" height="10" rx="2"/><circle cx="12" cy="5" r="2"/><path d="M12 7v4"/><line x1="8" y1="16" x2="8" y2="16"/><line x1="16" y1="16" x2="16" y2="16"/></svg>`,
      'tool': `<svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M14.7 6.3a1 1 0 0 0 0 1.4l1.6 1.6a1 1 0 0 0 1.4 0l3.77-3.77a6 6 0 0 1-7.94 7.94l-6.91 6.91a2.12 2.12 0 0 1-3-3l6.91-6.91a6 6 0 0 1 7.94-7.94l-3.76 3.76z"/></svg>`,
      'git-branch': `<svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><line x1="6" y1="3" x2="6" y2="15"/><circle cx="18" cy="6" r="3"/><circle cx="6" cy="18" r="3"/><path d="M18 9a9 9 0 0 1-9 9"/></svg>`,
      'git-merge': `<svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><circle cx="18" cy="18" r="3"/><circle cx="6" cy="6" r="3"/><path d="M6 21v-3a9 9 0 0 1 9-9 9 9 0 0 0 9-9 9 9 0 0 0-9-9H6"/></svg>`,
      'refresh-cw': `<svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M23 4v6h-6"/><path d="M1 20v-6h6"/><path d="M3.51 9a9 9 0 0 1 14.85-3.36L23 10M1 14l4.64 4.36A9 9 0 0 0 20.49 15"/></svg>`,
      'file-text': `<svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"/><polyline points="14 2 14 8 20 8"/><line x1="16" y1="13" x2="8" y2="13"/><line x1="16" y1="17" x2="8" y2="17"/><polyline points="10 9 9 9 8 9"/></svg>`,
      'database': `<svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><ellipse cx="12" cy="5" rx="9" ry="3"/><path d="M21 12c0 1.66-4 3-9 3s-9-1.34-9-3"/><path d="M3 5v14c0 1.66 4 3 9 3s9-1.34 9-3V5"/></svg>`,
      'layers': `<svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><polygon points="12 2 2 7 12 12 22 7 12 2"/><polyline points="2 17 12 22 22 17"/><polyline points="2 12 12 17 22 12"/></svg>`,
      'box': `<svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M21 16V8a2 2 0 0 0-1-1.73l-7-4a2 2 0 0 0-2 0l-7 4A2 2 0 0 0 3 8v8a2 2 0 0 0 1 1.73l7 4a2 2 0 0 0 2 0l7-4A2 2 0 0 0 21 16z"/><polyline points="3.27 6.96 12 12.01 20.73 6.96"/><line x1="12" y1="22.08" x2="12" y2="12"/></svg>`
    };

    return icons[name] || icons['box'];
  }

  /**
   * Render input ports.
   */
  protected renderInputPorts(): string {
    if (!this.definition.inputs.length) return '';

    const ports = this.definition.inputs.map(port => `
      <div class="archflow-node__port archflow-node__port--input"
           data-port-id="${port.id}"
           data-port-type="${port.dataType}"
           title="${port.label}${port.description ? ': ' + port.description : ''}">
        <div class="archflow-node__port-handle"></div>
        <span class="archflow-node__port-label">${port.label}</span>
      </div>
    `).join('');

    return `<div class="archflow-node__ports archflow-node__ports--inputs">${ports}</div>`;
  }

  /**
   * Render output ports.
   */
  protected renderOutputPorts(): string {
    if (!this.definition.outputs.length) return '';

    const ports = this.definition.outputs.map(port => `
      <div class="archflow-node__port archflow-node__port--output"
           data-port-id="${port.id}"
           data-port-type="${port.dataType}"
           title="${port.label}${port.description ? ': ' + port.description : ''}">
        <span class="archflow-node__port-label">${port.label}</span>
        <div class="archflow-node__port-handle"></div>
      </div>
    `).join('');

    return `<div class="archflow-node__ports archflow-node__ports--outputs">${ports}</div>`;
  }

  /**
   * Render execution status indicator.
   */
  protected renderExecutionStatus(): string {
    if (!this.executionState || this.executionState === 'idle') return '';

    const statusIcons: Record<string, string> = {
      'running': `<div class="archflow-node__status archflow-node__status--running" title="Running"></div>`,
      'completed': `<div class="archflow-node__status archflow-node__status--completed" title="Completed">✓</div>`,
      'error': `<div class="archflow-node__status archflow-node__status--error" title="Error">✕</div>`,
      'skipped': `<div class="archflow-node__status archflow-node__status--skipped" title="Skipped">−</div>`,
      'waiting': `<div class="archflow-node__status archflow-node__status--waiting" title="Waiting">⋯</div>`
    };

    return statusIcons[this.executionState] || '';
  }

  /**
   * Render the node's body HTML.
   */
  protected renderBody(): string {
    return `
      <div class="archflow-node__header">
        <div class="archflow-node__icon">${this.getIcon()}</div>
        <span class="archflow-node__label">${this.definition.label}</span>
        ${this.renderExecutionStatus()}
      </div>
      <div class="archflow-node__content">
        ${this.renderContent()}
      </div>
    `;
  }

  /**
   * Render the node's content. Override in subclasses.
   */
  protected renderContent(): string {
    // Show key configuration values
    const content: string[] = [];

    for (const param of this.definition.parameters) {
      const value = this.node.config[param.name];
      if (value !== undefined && value !== '') {
        const displayValue = this.formatConfigValue(param.type, value);
        content.push(`<div class="archflow-node__param"><span class="archflow-node__param-label">${param.label}:</span> ${displayValue}</div>`);
      }
    }

    return content.join('');
  }

  /**
   * Format a configuration value for display.
   */
  protected formatConfigValue(type: string, value: unknown): string {
    switch (type) {
      case 'boolean':
        return value ? '✓' : '✕';
      case 'select':
        return String(value);
      case 'number':
        return String(value);
      default:
        const str = String(value);
        return str.length > 30 ? str.substring(0, 30) + '...' : str;
    }
  }

  /**
   * Render the complete node.
   */
  render(): string {
    return `
      <div class="${this.getClasses()}"
           data-node-id="${this.node.id}"
           data-node-type="${this.node.type}"
           style="--node-color: ${this.getBackgroundColor()}; --node-border: ${this.getBorderColor()}; --node-text: ${this.getTextColor()}">
        ${this.renderInputPorts()}
        ${this.renderBody()}
        ${this.renderOutputPorts()}
      </div>
    `;
  }

  /**
   * Validate the node's configuration.
   */
  validate(): boolean | string {
    return true; // Default: always valid
  }
}

// ==========================================================================
// Specialized Node Components
// ==========================================================================

/**
 * Input Node Component
 */
export class InputNodeComponent extends BaseNodeComponent {
  protected renderContent(): string {
    const schema = this.node.config['inputSchema'];
    return schema
      ? `<div class="archflow-node__param"><span class="archflow-node__param-label">Schema:</span> defined</div>`
      : '<div class="archflow-node__hint">No schema defined</div>';
  }
}

/**
 * Output Node Component
 */
export class OutputNodeComponent extends BaseNodeComponent {
  protected renderContent(): string {
    const path = this.node.config['outputPath'];
    return path
      ? `<div class="archflow-node__param"><span class="archflow-node__param-label">Path:</span> ${path}</div>`
      : '<div class="archflow-node__hint">Pass through all data</div>';
  }
}

/**
 * LLM Node Component
 */
export class LLMNodeComponent extends BaseNodeComponent {
  protected renderContent(): string {
    const model = this.node.config['model'] || 'gpt-4';
    const temperature = this.node.config['temperature'] ?? 0.7;
    const maxTokens = this.node.config['maxTokens'] ?? 2048;

    return `
      <div class="archflow-node__param"><span class="archflow-node__param-label">Model:</span> ${model}</div>
      <div class="archflow-node__params-row">
        <span class="archflow-node__param-badge">T: ${temperature}</span>
        <span class="archflow-node__param-badge">Max: ${maxTokens}</span>
      </div>
    `;
  }
}

/**
 * Agent Node Component
 */
export class AgentNodeComponent extends BaseNodeComponent {
  protected renderContent(): string {
    const agentType = this.node.config['agentType'] || 'react';
    const maxIterations = this.node.config['maxIterations'] ?? 10;

    return `
      <div class="archflow-node__param"><span class="archflow-node__param-label">Type:</span> ${agentType}</div>
      <div class="archflow-node__param"><span class="archflow-node__param-label">Max iterations:</span> ${maxIterations}</div>
    `;
  }
}

/**
 * Tool Node Component
 */
export class ToolNodeComponent extends BaseNodeComponent {
  protected renderContent(): string {
    const toolId = this.node.config['toolId'] || 'custom';

    const toolNames: Record<string, string> = {
      'search': '🔍 Search',
      'calculator': '🧮 Calculator',
      'weather': '🌤️ Weather',
      'custom': '🔧 Custom Tool'
    };

    const toolName = toolNames[toolId as string] || toolId;

    return `
      <div class="archflow-node__param"><span class="archflow-node__param-label">Tool:</span> ${toolName}</div>
    `;
  }
}

/**
 * Condition Node Component
 */
export class ConditionNodeComponent extends BaseNodeComponent {
  protected renderContent(): string {
    const operator = this.node.config['operator'] || 'equals';
    const compareValue = this.node.config['compareValue'];

    let content = `<div class="archflow-node__param"><span class="archflow-node__param-label">Operator:</span> ${operator}</div>`;

    if (compareValue) {
      content += `<div class="archflow-node__param"><span class="archflow-node__param-label">Value:</span> ${compareValue}</div>`;
    }

    return content;
  }

  protected renderBody(): string {
    return `
      <div class="archflow-node__header">
        <div class="archflow-node__icon">${this.getIcon()}</div>
        <span class="archflow-node__label">${this.definition.label}</span>
        ${this.renderExecutionStatus()}
      </div>
      <div class="archflow-node__content archflow-node__content--branch">
        <div class="archflow-node__branch archflow-node__branch--true">✓ True</div>
        <div class="archflow-node__branch archflow-node__branch--false">✕ False</div>
      </div>
    `;
  }
}

/**
 * Parallel Node Component
 */
export class ParallelNodeComponent extends BaseNodeComponent {
  protected renderBody(): string {
    const branchCount = (this.node.config['branchCount'] as number) ?? 2;

    const branches = Array.from({ length: branchCount }, (_, i) => `
      <div class="archflow-node__branch-indicator">${i + 1}</div>
    `).join('');

    return `
      <div class="archflow-node__header">
        <div class="archflow-node__icon">${this.getIcon()}</div>
        <span class="archflow-node__label">${this.definition.label}</span>
        ${this.renderExecutionStatus()}
      </div>
      <div class="archflow-node__content archflow-node__content--parallel">
        <div class="archflow-node__branches">${branches}</div>
      </div>
    `;
  }
}

/**
 * Loop Node Component
 */
export class LoopNodeComponent extends BaseNodeComponent {
  protected renderContent(): string {
    const maxIterations = this.node.config['maxIterations'] ?? 100;
    const variableName = this.node.config['variableName'] || 'item';

    return `
      <div class="archflow-node__param"><span class="archflow-node__param-label">Variable:</span> ${variableName}</div>
      <div class="archflow-node__param"><span class="archflow-node__param-label">Max:</span> ${maxIterations}</div>
    `;
  }
}

/**
 * Prompt Template Node Component
 */
export class PromptTemplateNodeComponent extends BaseNodeComponent {
  protected renderContent(): string {
    const template = this.node.config['template'] as string;

    if (!template) {
      return '<div class="archflow-node__hint">Add a template</div>';
    }

    // Extract variables from template
    const variables = template.match(/\{\{(\w+)\}\}/g)?.map(v => v.replace(/\{\{|\}\}/g, '')) || [];

    const preview = template.length > 50
      ? template.substring(0, 50) + '...'
      : template;

    let content = `<div class="archflow-node__template-preview">"${preview}"</div>`;

    if (variables.length > 0) {
      content += `<div class="archflow-node__variables">${variables.map(v =>
        `<span class="archflow-node__variable">{{${v}}}</span>`
      ).join('')}</div>`;
    }

    return content;
  }
}

/**
 * Vector Search Node Component
 */
export class VectorSearchNodeComponent extends BaseNodeComponent {
  protected renderContent(): string {
    const vectorStore = this.node.config['vectorStore'] || 'pinecone';
    const topK = this.node.config['topK'] ?? 5;
    const collection = this.node.config['collection'];

    return `
      <div class="archflow-node__param"><span class="archflow-node__param-label">Store:</span> ${vectorStore}</div>
      ${collection ? `<div class="archflow-node__param"><span class="archflow-node__param-label">Collection:</span> ${collection}</div>` : ''}
      <div class="archflow-node__param"><span class="archflow-node__param-label">Top K:</span> ${topK}</div>
    `;
  }
}

/**
 * Embedding Node Component
 */
export class EmbeddingNodeComponent extends BaseNodeComponent {
  protected renderContent(): string {
    const model = (this.node.config['model'] as string) || 'openai-text-embedding-ada-002';
    const shortModel = model.split('-').slice(-2).join('-');

    return `
      <div class="archflow-node__param"><span class="archflow-node__param-label">Model:</span> ${shortModel}</div>
    `;
  }
}

/**
 * Custom Node Component
 */
export class CustomNodeComponent extends BaseNodeComponent {
  protected renderContent(): string {
    const entries = Object.entries(this.node.config);

    if (entries.length === 0) {
      return '<div class="archflow-node__hint">Configure this node</div>';
    }

    return entries.slice(0, 3).map(([key, value]) => `
      <div class="archflow-node__param"><span class="archflow-node__param-label">${key}:</span> ${String(value)}</div>
    `).join('');
  }
}

// ==========================================================================
// Node Component Factory
// ==========================================================================

/**
 * Create a node component for a given node instance.
 */
export function createNodeComponent(
  node: NodeInstance,
  definition: NodeTypeDefinition,
  executionState?: NodeExecutionState
): NodeComponent {
  const ComponentClass = getComponentClassForType(node.type);
  // Type assertion via unknown to bypass abstract class check
  // We know ComponentClass is always concrete because getComponentClassForType
  // returns concrete classes or CustomNodeComponent as fallback
  return new (ComponentClass as unknown as new (
    node: NodeInstance,
    definition: NodeTypeDefinition,
    executionState?: NodeExecutionState
  ) => NodeComponent)(node, definition, executionState);
}

/**
 * Get the appropriate component class for a node type.
 */
function getComponentClassForType(nodeType: string): typeof BaseNodeComponent {
  const componentMap: Record<string, typeof BaseNodeComponent> = {
    'input': InputNodeComponent,
    'output': OutputNodeComponent,
    'llm-chat': LLMNodeComponent,
    'llm-streaming': LLMNodeComponent,
    'assistant': LLMNodeComponent,
    'agent': AgentNodeComponent,
    'tool': ToolNodeComponent,
    'function': ToolNodeComponent,
    'condition': ConditionNodeComponent,
    'parallel': ParallelNodeComponent,
    'loop': LoopNodeComponent,
    'prompt-template': PromptTemplateNodeComponent,
    'prompt-chunk': PromptTemplateNodeComponent,
    'vector-search': VectorSearchNodeComponent,
    'vector-store': VectorSearchNodeComponent,
    'embedding': EmbeddingNodeComponent
  };

  return componentMap[nodeType] || CustomNodeComponent;
}
