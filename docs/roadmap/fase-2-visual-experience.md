# Fase 2: Visual Experience - Especificação Detalhada

**Duração**: 6-8 semanas
**Objetivo**: Web Component designer disruptivo zero frontend lock-in
**Status**: Planejamento

---

## 🔬 Decisão Arquitetural: React 19 + Web Component

**Análise completa:** [docs/analysis/react-to-web-component-analysis.md](../analysis/react-to-web-component-analysis.md)

### Por que React 19?

- ✅ **Lançado em 05/12/2024** com suporte NATIVO a Web Components
- ✅ **Zero overhead** - sem bibliotecas de conversão necessárias
- ✅ **Suporte oficial** - mantido pelo time React
- ✅ **Time já conhece React** - nenhuma curva de aprendizado

### Estratégia

```
React 19 apenas CONSOME o Web Component
├── Web Component implementado em TypeScript puro
├── Sem React runtime dentro do WC
└── Shadow DOM para isolamento CSS
```

### Exemplo de Uso

```tsx
// No React 19 - funciona direto!
<archflow-designer
  workflow-id="customer-support"
  api-base="http://localhost:8080"
  theme="dark"
  onWorkflowSaved={(e) => console.log(e.detail)} />
```

### Problemas Conhecidos e Mitigações

| Problema | Mitigação |
|----------|-----------|
| Attributes vs Properties | Implementar ambos no WC |
| Sem Declarative Shadow DOM | Client-side rendering |

### Fontes

- [React v19 Announcement](https://react.dev/blog/2024/12/05/react-19)
- [React 19 and Web Component Examples](https://frontendmasters.com/blog/react-19-and-web-component-examples/)

---

## Overview

Esta fase cria o componente visual de drag-and-drop para design de workflows AI. O diferencial crítico é distribuir como **Web Component**, funcionando em qualquer framework frontend (React, Vue, Angular, vanilla).

**Stack escolhida:** React 19 para desenvolvimento + Web Component para distribuição.

### Deliverables

- [ ] `<archflow-designer>` Web Component
- [ ] `<archflow-chat-panel>` Web Component
- [ ] Node System (LLM, Tool, Input, Output, etc.)
- [ ] Canvas com drag-and-drop
- [ ] Workflow execution via component
- [ ] Publicação no npm como `@archflow/component`

---

## Arquitetura Frontend

```
archflow-ui/
├── packages/
│   ├── archflow-component/           # Web Component (TypeScript puro)
│   │   ├── src/
│   │   │   ├── ArchflowDesigner.ts    # HTMLElement principal
│   │   │   ├── ArchflowChat.ts        # Chat panel HTMLElement
│   │   │   ├── ArchflowFlowView.ts    # Visualizador de execução
│   │   │   ├── components/            # Componentes internos (TS)
│   │   │   │   ├── Canvas.ts
│   │   │   │   ├── Node.ts
│   │   │   │   ├── Edge.ts
│   │   │   │   ├── NodePalette.ts
│   │   │   │   ├── PropertiesPanel.ts
│   │   │   │   └── Toolbar.ts
│   │   │   ├── nodes/                 # Implementações de nodes
│   │   │   │   ├── LLMNode.ts
│   │   │   │   ├── ToolNode.ts
│   │   │   │   ├── InputNode.ts
│   │   │   │   ├── OutputNode.ts
│   │   │   │   ├── VectorSearchNode.ts
│   │   │   │   ├── ConditionNode.ts
│   │   │   │   └── ParallelNode.ts
│   │   │   ├── styles/                # CSS isolado
│   │   │   │   ├── designer.css
│   │   │   │   ├── canvas.css
│   │   │   │   └── nodes.css
│   │   │   └── types/                 # TypeScript types
│   │   ├── package.json
│   │   ├── vite.config.ts
│   │   └── tsconfig.json
│   │
│   ├── archflow-react-adapter/       # Adapter React opcional
│   │   ├── src/
│   │   │   ├── ArchflowDesigner.tsx   # React wrapper
│   │   │   └── index.ts
│   │   └── package.json
│   │
│   └── examples/                     # Exemplos de uso
│       ├── react/                     # React 19 example
│       │   ├── App.tsx
│       │   └── main.tsx
│       ├── vue/                       # Vue example
│       └── angular/                   # Angular example
```

### Nota: Por que TypeScript puro para o Web Component?

```typescript
// ✅ CORRIDO - Web Component em TypeScript puro
class ArchflowDesigner extends HTMLElement {
  connectedCallback() {
    this.attachShadow({ mode: 'open' });
    this.render();
  }
}

// ❌ EVITAR - Não incluir React dentro do Web Component
// Isso aumentaria o bundle desnecessariamente
```

**O React 19 apenas CONSOME o Web Component, não o compila.**

---

## Sprint 5: Web Component Core (1.5 semanas)

### Objetivo

Criar o Web Component base que pode ser usado em qualquer framework.

### Especificação: ArchflowDesigner

**Arquivo**: `archflow-ui/packages/archflow-component/src/ArchflowDesigner.ts`

```typescript
/**
 * archflow-designer Web Component
 *
 * Uso:
 * <archflow-designer
 *   workflow-id="customer-support"
 *   api-base="http://localhost:8080"
 *   theme="dark"
 *   readonly="false">
 * </archflow-designer>
 *
 * Events:
 * - workflow-saved: Disparado quando workflow é salvo
 * - workflow-executed: Disparado quando workflow é executado
 * - node-selected: Disparado quando um nó é selecionado
 * - validation-error: Disparado quando há erro de validação
 */

import { ArchflowDesignerSvelte } from './ArchflowDesigner.svelte';

export class ArchflowDesigner extends HTMLElement {
  // Shadow DOM para isolamento CSS
  private shadow: ShadowRoot;

  // Svelte component instance
  private component: ArchflowDesignerSvelte;

  // Props observáveis
  private _workflowId: string;
  private _apiBase: string;
  private _theme: 'light' | 'dark';
  private _readonly: boolean;
  private _workflow?: Workflow;

  // Event callbacks
  private _onSave?: (workflow: Workflow) => void;
  private _onExecute?: (result: ExecutionResult) => void;
  private _onNodeSelected?: (node: Node) => void;

  constructor() {
    super();
    this.attachShadow({ mode: 'open' });
    this.shadow.adoptedStyleSheets.forEach(sheet => {
      // Clona styles para shadow DOM
    });
  }

  static get observedAttributes() {
    return ['workflow-id', 'api-base', 'theme', 'readonly'];
  }

  connectedCallback() {
    this.render();
    this.attachEventListeners();
  }

  disconnectedCallback() {
    if (this.component) {
      this.component.$destroy();
    }
  }

  attributeChangedCallback(name: string, oldValue: string, newValue: string) {
    if (oldValue === newValue) return;

    switch (name) {
      case 'workflow-id':
        this._workflowId = newValue;
        break;
      case 'api-base':
        this._apiBase = newValue;
        break;
      case 'theme':
        this._theme = newValue as 'light' | 'dark';
        break;
      case 'readonly':
        this._readonly = newValue !== null;
        break;
    }

    // Atualiza componente Svelte se já existe
    if (this.component) {
      this.component.$set({
        workflowId: this._workflowId,
        apiBase: this._apiBase,
        theme: this._theme,
        readonly: this._readonly
      });
    }
  }

  private render() {
    // Props para o componente Svelte
    const props = {
      workflowId: this._workflowId,
      apiBase: this._apiBase || this.getAttribute('api-base') || '/api',
      theme: this._theme || this.getAttribute('theme') || 'light',
      readonly: this._readonly || this.getAttribute('readonly') === 'true',
      workflow: this._workflow,
      onSave: (workflow: Workflow) => {
        this._workflow = workflow;
        this.dispatchEvent(new CustomEvent('workflow-saved', {
          detail: { workflow },
          bubbles: true,
          composed: true
        }));
        this._onSave?.(workflow);
      },
      onExecute: (result: ExecutionResult) => {
        this.dispatchEvent(new CustomEvent('workflow-executed', {
          detail: { result },
          bubbles: true,
          composed: true
        }));
        this._onExecute?.(result);
      },
      onNodeSelected: (node: Node) => {
        this.dispatchEvent(new CustomEvent('node-selected', {
          detail: { node },
          bubbles: true,
          composed: true
        }));
        this._onNodeSelected?.(node);
      }
    };

    // Cria componente Svelte dentro do Shadow DOM
    this.component = new ArchflowDesignerSvelte({
      target: this.shadow,
      props
    });
  }

  private attachEventListeners() {
    // Opcional: escuta eventos externos
    this.addEventListener('load-workflow', (e: CustomEvent) => {
      const workflowId = e.detail.workflowId;
      this.loadWorkflow(workflowId);
    });

    this.addEventListener('save-workflow', () => {
      this.saveWorkflow();
    });

    this.addEventListener('execute-workflow', () => {
      this.executeWorkflow();
    });
  }

  /**
   * API pública para carregar um workflow
   */
  public async loadWorkflow(workflowId: string): Promise<void> {
    const response = await fetch(`${this._apiBase}/workflows/${workflowId}`);
    const workflow = await response.json();

    this._workflow = workflow;
    this.component.$set({ workflow });
  }

  /**
   * API pública para salvar o workflow atual
   */
  public async saveWorkflow(): Promise<void> {
    const response = await fetch(`${this._apiBase}/workflows`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(this._workflow)
    });

    if (!response.ok) {
      throw new Error('Failed to save workflow');
    }
  }

  /**
   * API pública para executar o workflow atual
   */
  public async executeWorkflow(input?: Record<string, unknown>): Promise<void> {
    const response = await fetch(`${this._apiBase}/workflows/execute`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        workflowId: this._workflowId,
        input: input || this._workflow.defaultInput
      })
    });

    if (!response.ok) {
      throw new Error('Failed to execute workflow');
    }
  }

  /**
   * API pública para validar o workflow atual
   */
  public async validateWorkflow(): Promise<ValidationResult> {
    // Validação client-side primeiro
    const clientErrors = this.validateClientSide();
    if (clientErrors.length > 0) {
      return { valid: false, errors: clientErrors };
    }

    // Validação server-side
    const response = await fetch(`${this._apiBase}/workflows/validate`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(this._workflow)
    });

    return await response.json();
  }

  private validateClientSide(): ValidationError[] {
    const errors: ValidationError[] = [];

    // Valida nós órfãos
    const orphanNodes = this.findOrphanNodes();
    if (orphanNodes.length > 0) {
      errors.push({
        type: 'orphan-nodes',
        message: 'Nodes without connections',
        nodes: orphanNodes
      });
    }

    // Valida ciclos
    const cycles = this.detectCycles();
    if (cycles.length > 0) {
      errors.push({
        type: 'cycles',
        message: 'Cycles detected in workflow',
        cycles
      });
    }

    // Valida configuração de cada nó
    for (const node of this._workflow.nodes) {
      const nodeErrors = this.validateNode(node);
      errors.push(...nodeErrors);
    }

    return errors;
  }
}

// Registro do Web Component
customElements.define('archflow-designer', ArchflowDesigner);
```

### Especificação: Types

**Arquivo**: `archflow-ui/packages/archflow-component/src/types/workflow.ts`

```typescript
/**
 * Estrutura de dados para Workflow
 */
export interface Workflow {
  id: string;
  name: string;
  description?: string;
  version: string;
  createdAt: string;
  updatedAt: string;

  // Nós do workflow
  nodes: WorkflowNode[];

  // Conexões entre nós
  edges: WorkflowEdge[];

  // Configuração global
  config: WorkflowConfig;

  // Input padrão
  defaultInput?: Record<string, unknown>;
}

export interface WorkflowNode {
  id: string;
  type: NodeType;
  position: Position;
  data: NodeData;
}

export type NodeType =
  | 'input'
  | 'output'
  | 'llm'
  | 'tool'
  | 'agent'
  | 'condition'
  | 'loop'
  | 'parallel'
  | 'delay'
  | 'transform'
  | 'rag'
  | 'custom';

export interface Position {
  x: number;
  y: number;
}

export interface NodeData {
  label: string;
  config: Record<string, unknown>;
  // Configuração específica por tipo
  [key: string]: unknown;
}

export interface WorkflowEdge {
  id: string;
  source: string;  // node ID
  target: string;  // node ID
  sourceHandle?: string;
  targetHandle?: string;
  label?: string;
  condition?: string;  // Para nós de condição
}

export interface WorkflowConfig {
  timeout?: number;        // em segundos
  retryPolicy?: RetryPolicy;
  maxConcurrent?: number;
  enableCaching?: boolean;
  enableMetrics?: boolean;
}

export interface RetryPolicy {
  maxAttempts: number;
  backoffMs: number;
  multiplier: number;
}

/**
 * Estrutura de dados para execução
 */
export interface ExecutionResult {
  executionId: string;
  status: 'running' | 'completed' | 'failed' | 'cancelled';
  startTime: string;
  endTime?: string;
  output?: Record<string, unknown>;
  error?: string;
  steps: ExecutionStep[];
  metrics: ExecutionMetrics;
}

export interface ExecutionStep {
  stepId: string;
  nodeName: string;
  status: 'pending' | 'running' | 'completed' | 'failed';
  input?: Record<string, unknown>;
  output?: Record<string, unknown>;
  error?: string;
  startTime: string;
  endTime?: string;
  duration?: number;
}

export interface ExecutionMetrics {
  totalDuration: number;
  tokenUsage: TokenUsage;
  costUsd?: number;
}

export interface TokenUsage {
  promptTokens: number;
  completionTokens: number;
  totalTokens: number;
}

/**
 * Validação
 */
export interface ValidationResult {
  valid: boolean;
  errors?: ValidationError[];
  warnings?: ValidationWarning[];
}

export interface ValidationError {
  type: string;
  message: string;
  nodeId?: string;
  nodes?: string[];
  cycles?: string[][];
}

export interface ValidationWarning {
  type: string;
  message: string;
  nodeId?: string;
}
```

### Especificação: Svelte Component Base

**Arquivo**: `archflow-ui/packages/archflow-designer/src/ArchflowDesigner.svelte`

```svelte
<script lang="ts">
  import { onMount, setContext } from 'svelte';
  import { writable, derived } from 'svelte/store';

  // Components
  import Canvas from './components/Canvas.svelte';
  import NodePalette from './components/NodePalette.svelte';
  import PropertiesPanel from './components/PropertiesPanel.svelte';
  import Toolbar from './components/Toolbar.svelte';

  // Stores
  import { workflowStore, type WorkflowStore } from './store/workflowStore';
  import { canvasStore } from './store/canvasStore';

  // Props
  export let workflowId: string;
  export let apiBase: string = '/api';
  export let theme: 'light' | 'dark' = 'light';
  export let readonly: boolean = false;
  export let workflow: Workflow;

  // Callbacks
  export let onSave: (workflow: Workflow) => void;
  export let onExecute: (result: ExecutionResult) => void;
  export let onNodeSelected: (node: Node) => void;

  // Inicializa stores
  const store = workflowStore(apiBase);
  const canvas = canvasStore();

  // Estado local
  let selectedNode: WorkflowNode | null = $canvas.selectedNode;
  let isExecuting = false;
  let executionResult: ExecutionResult | null = null;

  // Derived state
  const canExecute = derived(
    [store.workflow, canvas.selectedNode],
    ([$workflow, $selectedNode]) => {
      return $workflow && $workflow.nodes.length > 0;
    }
  );

  // Actions
  async function saveWorkflow() {
    const validation = await store.validate();
    if (!validation.valid) {
      dispatch('validation-error', validation);
      return;
    }

    await store.save();
    onSave?.(store.getWorkflow());
  }

  async function executeWorkflow() {
    isExecuting = true;

    try {
      const result = await store.execute(workflow.defaultInput);
      executionResult = result;
      onExecute?.(result);
    } catch (error) {
      console.error('Execution error:', error);
    } finally {
      isExecuting = false;
    }
  }

  function handleNodeSelect(node: WorkflowNode) {
    canvas.selectNode(node);
    selectedNode = node;
    onNodeSelected?.(node);
  }

  // Theme CSS injection
  $: theme === 'dark' ? darkStyles : lightStyles;
</script>

<style>
  :host {
    display: block;
    width: 100%;
    height: 100vh;
    font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;
  }

  :host([theme="dark"]) {
    --bg-primary: #1a1a1a;
    --bg-secondary: #2d2d2d;
    --text-primary: #e0e0e0;
    --text-secondary: #a0a0a0;
    --border-color: #404040;
    --accent: #4a9eff;
  }

  :host([theme="light"]) {
    --bg-primary: #ffffff;
    --bg-secondary: #f5f5f5;
    --text-primary: #1a1a1a;
    --text-secondary: #666666;
    --border-color: #e0e0e0;
    --accent: #0066cc;
  }
</style>

<div class="archflow-designer" class={theme}>
  <Toolbar
    {readonly}
    {canExecute}
    on:save={saveWorkflow}
    on:execute={executeWorkflow}
  />

  <div class="main-content">
    <NodePalette {readonly} />

    <Canvas
      {workflow}
      {readonly}
      on:nodeSelect={handleNodeSelect}
    />

    <PropertiesPanel
      {selectedNode}
      {readonly}
    />
  </div>

  {#if isExecuting}
    <ExecutionOverlay result={executionResult} />
  {/if}
</div>
```

### Especificação: Build Configuration

**Arquivo**: `archflow-ui/package.json` (root)

```json
{
  "name": "@archflow/component",
  "version": "1.0.0",
  "type": "module",
  "private": true,
  "workspaces": [
    "packages/*"
  ],
  "scripts": {
    "build": "pnpm -r --filter ./archflow-component build",
    "dev": "pnpm --filter ./archflow-example-react dev",
    "preview": "pnpm --filter ./archflow-example-react preview",
    "test": "vitest",
    "lint": "eslint .",
    "typecheck": "tsc --noEmit"
  }
}
```

**Arquivo**: `archflow-ui/packages/archflow-component/vite.config.ts`

```typescript
import { defineConfig } from 'vite';
import { svelte } from '@sveltejs/vite-plugin-svelte';
import { resolve } from 'path';

export default defineConfig({
  plugins: [
    svelte({
      // Compila Svelte para Web Component
      compilerOptions: {
        customElement: true
      }
    })
  ],
  build: {
    lib: {
      entry: resolve(__dirname, 'src/ArchflowDesigner.ts'),
      name: 'ArchflowComponent',
      formats: ['es', 'umd']
    },
    rollupOptions: {
      external: ['svelte', 'svelte/store', 'svelte/internal'],
      output: {
        globals: {
          'svelte': 'Svelte',
          'svelte/store': 'SvelteStore',
          'svelte/internal': 'SvelteInternal'
        }
      }
    }
  },
  define: {
    ARCHFLOW_VERSION: JSON.stringify(process.env.npm_package_version)
  }
});
```

### Critérios de Aceite

- [ ] `<archflow-designer>` Web Component funcionando
- [ ] Shadow DOM isolado (CSS não vaza)
- [ ] Props reativas (workflowId, apiBase, theme, readonly)
- [ ] Custom events disparados (workflow-saved, workflow-executed)
- [ ] API pública (loadWorkflow, saveWorkflow, executeWorkflow)
- [ ] Build para ESM e UMD

---

## Sprint 6: Node System (1.5 semanas)

### Objetivo

Implementar o sistema de nós com tipos básicos e API para nós customizados.

### Tipos de Nós

```
┌─────────────────────────────────────────────────────────────┐
│                    Nós Básicos                             │
├─────────────────────────────────────────────────────────────┤
│  INPUT                  OUTPUT                               │
│  ┌────────┐              ┌────────┐                          │
│  │ Start  │              │  End   │                          │
│  └────────┘              └────────┘                          │
│                                                              │
│  PROCESSING                                                  │
│  ┌─────────┐    ┌─────────┐    ┌─────────┐    ┌──────┐  │
│  │   LLM   │    │  Tool   │    │   RAG   │    │Loop  │  │
│  └─────────┘    └─────────┘    └─────────┘    └──────┘  │
│                                                              │
│  LOGIC                                                       │
│  ┌─────────┐    ┌─────────┐                               │
│  │Condition│    │Parallel│                               │
│  └─────────┘    └─────────┘                               │
└─────────────────────────────────────────────────────────────┘
```

### Especificação: NodeRegistry

**Arquivo**: `archflow-ui/packages/archflow-designer/src/nodes/NodeRegistry.ts`

```typescript
/**
 * Registro de tipos de nós disponíveis no designer.
 */
export class NodeRegistry {
  private nodeTypes = new Map<string, NodeTypeDefinition>();

  register(type: NodeTypeDefinition) {
    this.nodeTypes.set(type.type, type);
  }

  get(type: string): NodeTypeDefinition | undefined {
    return this.nodeTypes.get(type);
  }

  getAll(): NodeTypeDefinition[] {
    return Array.from(this.nodeTypes.values());
  }

  getByCategory(category: string): NodeTypeDefinition[] {
    return this.getAll().filter(n => n.category === category);
  }
}

export interface NodeTypeDefinition {
  // Identificador
  type: string;

  // Categoria para agrupamento na paleta
  category: 'input-output' | 'ai' | 'tools' | 'logic' | 'data' | 'custom';

  // Visual
  label: string;
  icon: string;
  color: string;

  // Configuração
  configSchema: ConfigSchema;

  // Comportamento
  multipleInputs?: boolean;
  multipleOutputs?: boolean;
  configurable?: boolean;

  // Componente Svelte para renderização
  component?: any;
}

export interface ConfigSchema {
  type: 'object';
  properties: Record<string, ConfigProperty>;
  required?: string[];
}

export interface ConfigProperty {
  type: 'string' | 'number' | 'boolean' | 'array' | 'object' | 'enum';
  title?: string;
  description?: string;
  default?: unknown;
  enum?: unknown[];
  properties?: Record<string, ConfigProperty>;
  items?: ConfigProperty;
}

/**
 * Registro inicial com nós básicos
 */
export const defaultNodeRegistry = new NodeRegistry();

// Nós Input/Output
defaultNodeRegistry.register({
  type: 'input',
  category: 'input-output',
  label: 'Input',
  icon: 'play',
  color: '#10b981',
  configSchema: {
    type: 'object',
    properties: {
      label: {
        type: 'string',
        title: 'Label',
        default: 'Start'
      },
      schema: {
        type: 'object',
        title: 'Input Schema',
        description: 'JSON Schema do input esperado'
      }
    }
  },
  multipleInputs: false,
  multipleOutputs: true
});

defaultNodeRegistry.register({
  type: 'output',
  category: 'input-output',
  label: 'Output',
  icon: 'stop',
  color: '#ef4444',
  configSchema: {
    type: 'object',
    properties: {
      label: {
        type: 'string',
        title: 'Label',
        default: 'End'
      },
      schema: {
        type: 'object',
        title: 'Output Schema',
        description: 'JSON Schema do output retornado'
      }
    }
  },
  multipleInputs: true,
  multipleOutputs: false
});

// Nó LLM
defaultNodeRegistry.register({
  type: 'llm',
  category: 'ai',
  label: 'LLM',
  icon: 'brain',
  color: '#8b5cf6',
  configSchema: {
    type: 'object',
    required: ['model'],
    properties: {
      model: {
        type: 'string',
        title: 'Model',
        enum: ['gpt-4.1', 'gpt-4o-mini', 'claude-3.5-sonnet', 'claude-3.7-sonnet'],
        default: 'gpt-4.1'
      },
      systemMessage: {
        type: 'string',
        title: 'System Message',
        multiline: true
      },
      temperature: {
        type: 'number',
        title: 'Temperature',
        minimum: 0,
        maximum: 2,
        default: 0.7
      },
      maxTokens: {
        type: 'integer',
        title: 'Max Tokens',
        minimum: 1,
        default: 2048
      },
      enableStreaming: {
        type: 'boolean',
        title: 'Enable Streaming',
        default: true
      }
    }
  }
});

// Nó Tool
defaultNodeRegistry.register({
  type: 'tool',
  category: 'tools',
  label: 'Tool',
  icon: 'wrench',
  color: '#f59e0b',
  configSchema: {
    type: 'object',
    required: ['tool'],
    properties: {
      tool: {
        type: 'string',
        title: 'Tool Name',
        description: 'Nome da tool registrada'
      },
      timeout: {
        type: 'integer',
        title: 'Timeout (ms)',
        default: 30000
      }
    }
  }
});

// Nó RAG
defaultNodeRegistry.register({
  type: 'rag',
  category: 'ai',
  label: 'RAG',
  icon: 'database',
  color: '#06b6d4',
  configSchema: {
    type: 'object',
    required: ['knowledgeBase', 'embeddingModel'],
    properties: {
      knowledgeBase: {
        type: 'string',
        title: 'Knowledge Base ID'
      },
      embeddingModel: {
        type: 'string',
        title: 'Embedding Model',
        default: 'text-embedding-3-small'
      },
      maxResults: {
        type: 'integer',
        title: 'Max Results',
        default: 5
      },
      minScore: {
        type: 'number',
        title: 'Min Score',
        minimum: 0,
        maximum: 1,
        default: 0.7
      }
    }
  }
});

// Nó Condition
defaultNodeRegistry.register({
  type: 'condition',
  category: 'logic',
  label: 'Condition',
  icon: 'git-branch',
  color: '#ec4899',
  configSchema: {
    type: 'object',
    required: ['expression'],
    properties: {
      expression: {
        type: 'string',
        title: 'Expression',
        description: 'Ex: {{input.value}} > 100'
      }
    }
  },
  multipleInputs: true,
  multipleOutputs: 2
});

// Nó Loop
defaultNodeRegistry.register({
  type: 'loop',
  category: 'logic',
  label: 'Loop',
  icon: 'repeat',
  color: '#f97316',
  configSchema: {
    type: 'object',
    required: ['condition'],
    properties: {
      condition: {
        type: 'string',
        title: 'Loop Condition',
        description: 'Ex: {{items.length}} > 0'
      },
      maxIterations: {
        type: 'integer',
        title: 'Max Iterations',
        default: 10
      }
    }
  }
});

// Nó Parallel
defaultNodeRegistry.register({
  type: 'parallel',
  category: 'logic',
  label: 'Parallel',
  icon: 'git-branch',
  color: '#6366f1',
  configSchema: {
    type: 'object',
    required: ['branches'],
    properties: {
      branches: {
        type: 'array',
        title: 'Number of Branches',
        minItems: 2,
        maxItems: 10
      }
    }
  },
  multipleInputs: true,
  multipleOutputs: true
});

/**
 * API para registrar nós customizados por usuários
 */
export function registerCustomNodeType(definition: NodeTypeDefinition) {
  // Validação
  if (!definition.type.startsWith('custom.')) {
    throw new Error('Custom node types must start with "custom."');
  }

  // Registra
  defaultNodeRegistry.register(definition);

  // Disponibiliza globalmente para uso
  if (typeof window !== 'undefined') {
    (window as any).archflowCustomNodes = (window as any).archflowCustomNodes || {};
    (window as any).archflowCustomNodes[definition.type] = definition;
  }
}
```

### Especificação: LLMNode Component

**Arquivo**: `archflow-ui/packages/archflow-designer/src/nodes/nodes/LLMNode.svelte`

```svelte
<script lang="ts">
  import { onMount } from 'svelte';
  import type { WorkflowNode } from '@/types/workflow';

  export let node: WorkflowNode;
  export let readonly = false;

  // Estados
  let models: string[] = [];
  let testing = false;
  let testResult = '';

  onMount(async () => {
    // Carrega modelos disponíveis
    const response = await fetch('/api/llm/models');
    models = await response.json();
  });

  async function testModel() {
    testing = true;
    testResult = 'Testing...';

    try {
      const response = await fetch('/api/llm/test', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(node.data.config)
      });

      const result = await response.json();
      testResult = JSON.stringify(result, null, 2);
    } catch (error) {
      testResult = 'Error: ' + error.message;
    } finally {
      testing = false;
    }
  }
</script>

<div class="llm-node">
  <header class="node-header">
    <span class="icon">🧠</span>
    <span class="label">{node.data.label || 'LLM'}</span>
  </header>

  <div class="config">
    <label>
      Model
      <select
        bind:value={node.data.config.model}
        disabled={readonly}
      >
        {#each models as model}
          <option value={model}>{model}</option>
        {/each}
      </select>
    </label>

    <label>
      System Message
      <textarea
        bind:value={node.data.config.systemMessage}
        rows={3}
        disabled={readonly}
      />
    </label>

    <div class="row">
      <label>
        Temperature
        <input
          type="number"
          bind:value={node.data.config.temperature}
          min="0"
          max="2"
          step="0.1"
          disabled={readonly}
        />
      </label>

      <label>
        Max Tokens
        <input
          type="number"
          bind:value={node.data.config.maxTokens}
          min="1"
          disabled={readonly}
        />
      </label>
    </div>

    <label class="checkbox">
      <input
        type="checkbox"
        bind:checked={node.data.config.enableStreaming}
        disabled={readonly}
      />
      Enable Streaming
    </label>

    {#if !readonly}
      <button class="test-btn" on:click={testModel} disabled={testing}>
        {testing ? 'Testing...' : 'Test Model'}
      </button>
    {/if}

    {#if testResult}
      <pre class="test-result">{testResult}</pre>
    {/if}
  </div>
</div>

<style>
  .llm-node {
    padding: 1rem;
    background: var(--bg-secondary);
    border-radius: 8px;
  }

  .node-header {
    display: flex;
    align-items: center;
    gap: 0.5rem;
    font-weight: 600;
    margin-bottom: 1rem;
  }

  .icon {
    font-size: 1.2rem;
  }

  label {
    display: flex;
    flex-direction: column;
    gap: 0.25rem;
    margin-bottom: 0.75rem;
  }

  textarea, input, select {
    width: 100%;
    padding: 0.5rem;
    background: var(--bg-primary);
    border: 1px solid var(--border-color);
    border-radius: 4px;
    color: var(--text-primary);
  }

  .row {
    display: flex;
    gap: 1rem;
  }

  .row label {
    flex: 1;
  }

  .checkbox {
    flex-direction: row;
    align-items: center;
  }

  .checkbox input {
    width: auto;
  }

  .test-btn {
    width: 100%;
    padding: 0.5rem;
    background: var(--accent);
    border: none;
    border-radius: 4px;
    color: white;
    font-weight: 600;
    cursor: pointer;
  }

  .test-btn:disabled {
    opacity: 0.6;
  }

  .test-result {
    margin-top: 0.5rem;
    padding: 0.5rem;
    background: var(--bg-primary);
    border-radius: 4px;
    font-size: 0.75rem;
    overflow: auto;
  }
</style>
```

### Critérios de Aceite

- [ ] NodeRegistry funcionando
- [ ] 8+ tipos de nós implementados
- [ ] API para nós customizados
- [ ] ConfigSchema para cada tipo
- [ ] Validação de configuração

---

## Sprint 7: Canvas & Connections (2 semanas)

### Objetivo

Implementar canvas com drag-and-drop, connections entre nós e validação visual.

### Especificação: Canvas Component

**Arquivo**: `archflow-ui/packages/archflow-designer/src/components/Canvas.svelte`

```svelte
<script lang="ts">
  import { onMount, onDestroy } from 'svelte';
  import type { WorkflowNode, WorkflowEdge } from '@/types/workflow';

  export let workflow: Workflow;
  export let readonly: boolean;

  // Biblioteca de canvas (pode usar ReactFlow ou implementação própria)
  // Para este exemplo, assumimos implementação própria com SVG

  let container: HTMLElement;
  let svgContainer: SVGElement;

  // Estado
  let nodes = $state(workflow.nodes);
  let edges = $state(workflow.edges);

  // Drag state
  let draggingNode: WorkflowNode | null = null;
  let dragOffset = { x: 0, y: 0 };

  // Connection state
  let connectingFrom: { nodeId: string; handle: string } | null = null;
  let mousePosition = { x: 0, y: 0 };

  // Viewport
  let transform = { x: 0, y: 0, scale: 1 };

  // Grid
  const gridSize = 20;

  onMount(() => {
    if (container) {
      initCanvas();
    }
  });

  function initCanvas() {
    // Event listeners para drag-and-drop
    container.addEventListener('mousedown', handleMouseDown);
    window.addEventListener('mousemove', handleMouseMove);
    window.addEventListener('mouseup', handleMouseUp);

    // Zoom/Pan
    container.addEventListener('wheel', handleWheel, { passive: false });
  }

  onDestroy(() => {
    window.removeEventListener('mousemove', handleMouseMove);
    window.removeEventListener('mouseup', handleMouseUp);
  });

  function handleMouseDown(e: MouseEvent) {
    const target = e.target as HTMLElement;
    const nodeEl = target.closest('.workflow-node');

    if (nodeEl && !readonly) {
      // Começa a arrastar nó
      const nodeId = nodeEl.dataset.nodeId;
      draggingNode = nodes.find(n => n.id === nodeId);
      if (draggingNode) {
        const rect = nodeEl.getBoundingClientRect();
        dragOffset = {
          x: e.clientX - rect.left,
          y: e.clientY - rect.top
        };
      }
    } else if (target.classList.contains('output-handle')) {
      // Começa a criar conexão
      const nodeId = target.dataset.nodeId!;
      connectingFrom = { nodeId, handle: 'output' };
    }
  }

  function handleMouseMove(e: MouseEvent) {
    mousePosition = {
      x: (e.clientX - container.getBoundingClientRect().left - transform.x) / transform.scale,
      y: (e.clientY - container.getBoundingClientRect().top - transform.y) / transform.scale
    };

    if (draggingNode) {
      draggingNode.position = {
        x: mousePosition.x - dragOffset.x / transform.scale,
        y: mousePosition.y - dragOffset.y / transform.scale
      };
    }
  }

  function handleMouseUp(e: MouseEvent) {
    const target = e.target as HTMLElement;
    const nodeEl = target.closest('.workflow-node');

    if (connectingFrom && nodeEl) {
      // Completa conexão
      const targetNodeId = nodeEl.dataset.nodeId!;
      const targetHandle = target.classList.contains('input-handle') ? 'input' : 'output';

      if (targetNodeId !== connectingFrom.nodeId) {
        const edge: WorkflowEdge = {
          id: `edge_${Date.now()}`,
          source: connectingFrom.nodeId,
          target: targetNodeId,
          sourceHandle: connectingFrom.handle,
          targetHandle: targetHandle
        };

        edges = [...edges, edge];
        workflow.edges = edges;
        dispatch('edge-created', edge);
      }
    }

    // Finaliza drag
    if (draggingNode) {
      dispatch('node-moved', draggingNode);
    }

    draggingNode = null;
    connectingFrom = null;
  }

  function handleWheel(e: WheelEvent) {
    if (e.ctrlKey || e.metaKey) {
      e.preventDefault();
      const zoomIntensity = 0.001;
      const delta = -e.deltaY * zoomIntensity;
      const newScale = Math.min(Math.max(0.1, transform.scale + delta), 4);

      // Zoom em direção ao mouse
      const rect = container.getBoundingClientRect();
      const mouseX = e.clientX - rect.left;
      const mouseY = e.clientY - rect.top;

      transform.x -= (mouseX - transform.x) * (newScale / transform.scale - 1);
      transform.y -= (mouseY - transform.y) * (newScale / transform.scale - 1);
      transform.scale = newScale;
    }
  }

  // Snap to grid
  function snapToGrid(value: number): number {
    return Math.round(value / gridSize) * gridSize;
  }
</script>

<div class="canvas-container" bind:this={container}>
  <div
    class="canvas"
    style="transform: translate({transform.x}px, {transform.y}px) scale({transform.scale})"
  >
    <!-- Grid -->
    <svg class="grid" width="100%" height="100%">
      <defs>
        <pattern
          id="grid"
          width="{gridSize}"
          height="{gridSize}"
          patternUnits="userSpaceOnUse"
        >
          <path
            d="M {gridSize} 0 L 0 0 0 {gridSize}"
            fill="none"
            stroke="var(--border-color)"
            stroke-width="0.5"
          />
        </pattern>
      </defs>
      <rect width="100%" height="100%" fill="url(#grid)" />
    </svg>

    <!-- Conexões (SVG) -->
    <svg
      bind:this={svgContainer}
      class="connections"
      width="100%"
      height="100%"
      style="position: absolute; top: 0; left: 0; pointer-events: none;"
    >
      {#each edges as edge}
        <Edge
          {edge}
          {nodes}
          on:delete={() => deleteEdge(edge.id)}
        />
      {/each}

      <!-- Connection line sendo criada -->
      {#if connectingFrom}
        <line
          x1={getNodePosition(connectingFrom.nodeId).x + 100}
          y1={getNodePosition(connectingFrom.nodeId).y + 30}
          x2={mousePosition.x}
          y2={mousePosition.y}
          stroke="var(--accent)"
          stroke-width="2"
          stroke-dasharray="5,5"
        />
      {/if}
    </svg>

    <!-- Nós -->
    {#each nodes as node}
      <div
        class="workflow-node"
        data-node-id={node.id}
        style="position: absolute; left: {node.position.x}px; top: {node.position.y}px;"
        class:node.data.type}
      >
        <NodeComponent
          {node}
          {readonly}
          on:select={(n) => dispatch('node-selected', n)}
        />
      </div>
    {/each}
  </div>
</div>

<style>
  .canvas-container {
    width: 100%;
    height: 100%;
    overflow: hidden;
    background: var(--bg-primary);
    position: relative;
    cursor: grab;
  }

  .canvas-container:active {
    cursor: grabbing;
  }

  .connections {
    z-index: 0;
  }

  .workflow-node {
    z-index: 1;
    position: absolute;
  }
</style>
```

### Especificação: Edge Component

**Arquivo**: `archflow-ui/packages/archflow-designer/src/components/Edge.svelte`

```svelte
<script lang="ts">
  import { getBezierPath } from '@/utils/path';

  export let edge: WorkflowEdge;
  export let nodes: WorkflowNode[];

  const sourceNode = $derived(nodes.find(n => n.id === edge.source));
  const targetNode = $derived(nodes.find(n => n.id === edge.target));

  const pathData = $derived(getBezierPath(
    sourceNode.position,
    targetNode.position,
    edge.sourceHandle,
    edge.targetHandle
  ));
</script>

<path
  d={pathData}
  stroke="var(--text-secondary)"
  stroke-width="2"
  fill="none"
  marker-end="url(#arrowhead)"
/>

{#if edge.label}
  <text
    x="midpoint(pathData).x"
    y="midpoint(pathData).y - 5"
    text-anchor="middle"
    fill="var(--text-secondary)"
    font-size="12"
  >
    {edge.label}
  </text>
{/if}

<style>
  path {
    transition: stroke 0.2s;
  }

  path:hover {
    stroke: var(--accent);
  }
</style>
```

### Especificação: Validação Visual

**Arquivo**: `archflow-ui/packages/archflow-designer/src/utils/validation.ts`

```typescript
/**
 * Validações visuais no canvas
 */
export class CanvasValidator {
  /**
   * Detecta nós órfãos (sem conexões)
   */
  static findOrphanNodes(nodes: WorkflowNode[], edges: WorkflowEdge[]): WorkflowNode[] {
    const connectedNodeIds = new Set<string>();

    for (const edge of edges) {
      connectedNodeIds.add(edge.source);
      connectedNodeIds.add(edge.target);
    }

    return nodes.filter(n => !connectedNodeIds.has(n.id));
  }

  /**
   * Detecta ciclos no DAG
   */
  static detectCycles(nodes: WorkflowNode[], edges: WorkflowEdge[]): string[][] {
    const adj = new Map<string, string[]>();

    // Constrói lista de adjacência
    for (const node of nodes) {
      adj.set(node.id, []);
    }

    for (const edge of edges) {
      adj.get(edge.source)?.push(edge.target);
    }

    // Detecta ciclos usando DFS
    const cycles: string[][] = [];
    const visited = new Set<string>();
    const recursionStack = new Set<string>();

    function dfs(nodeId: string, path: string[]) {
      if (recursionStack.has(nodeId)) {
        // Ciclo encontrado!
        const cycleStart = path.indexOf(nodeId);
        cycles.push([...path.slice(cycleStart), nodeId]);
        return;
      }

      if (visited.has(nodeId)) return;

      visited.add(nodeId);
      recursionStack.add(nodeId);

      for (const neighbor of adj.get(nodeId) || []) {
        dfs(neighbor, [...path, nodeId]);
      }

      recursionStack.delete(nodeId);
    }

    for (const nodeId of adj.keys()) {
      dfs(nodeId, []);
    }

    return cycles;
  }

  /**
   * Valida que conexões são válidas
   */
  static validateEdges(nodes: WorkflowNode[], edges: WorkflowEdge[]): ValidationError[] {
    const errors: ValidationError[] = [];

    // Valida que source e target existem
    for (const edge of edges) {
      const sourceExists = nodes.some(n => n.id === edge.source);
      const targetExists = nodes.some(n => n.id === edge.target);

      if (!sourceExists) {
        errors.push({
          type: 'invalid-edge',
          message: `Source node ${edge.source} not found`,
          edgeId: edge.id
        });
      }

      if (!targetExists) {
        errors.push({
          type: 'invalid-edge',
          message: `Target node ${edge.target} not found`,
          edgeId: edge.id
        });
      }
    }

    return errors;
  }

  /**
   * Valida nós de condicional têm branches configurados
   */
  static validateConditionals(nodes: WorkflowNode[]): ValidationError[] {
    return nodes
      .filter(n => n.type === 'condition')
      .filter(n => !n.data.config.expression)
      .map(n => ({
        type: 'missing-config',
        message: 'Condition node requires expression',
        nodeId: n.id
      }));
  }
}
```

### Critérios de Aceite

- [ ] Canvas com drag-and-drop funcionando
- [ ] Grid snap para alinhamento
- [ ] Pan e zoom (Ctrl + scroll)
- [ ] Conexões com curvas bezier
- [ ] Visualização de erros (nós órfãos, ciclos)
- [ ] Validação em tempo real
- [ ] Undo/redo básico

---

## Sprint 8: Workflow Execution via Component (2 semanas)

### Objetivo

Executar workflows diretamente do Web Component com visualização do progresso.

### Especificação: ExecutionStore

**Arquivo**: `archflow-ui/packages/archflow-designer/src/store/executionStore.ts`

```typescript
import { writable, derived } from 'svelte/store';
import type { ExecutionResult, ExecutionStep } from '@/types/workflow';

interface ExecutionState {
  isExecuting: boolean;
  executionId: string | null;
  currentStepIndex: number;
  result: ExecutionResult | null;
  error: string | null;
}

function createExecutionStore(apiBase: string) {
  const { subscribe, set, update } = writable<ExecutionState>({
    isExecuting: false,
    executionId: null,
    currentStepIndex: -1,
    result: null,
    error: null
  });

  return {
    subscribe,
    set,
    update,

    async execute(workflowId: string, input?: Record<string, unknown>) {
      set({ isExecuting: true, executionId: null, currentStepIndex: -1 });

      try {
        // Inicia execução SSE
        const response = await fetch(`${apiBase}/workflows/execute`, {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ workflowId, input })
        });

        if (!response.ok) {
          throw new Error('Failed to start execution');
        }

        const reader = response.body!.getReader();
        const decoder = new TextDecoder();

        // Processa stream de eventos
        while (true) {
          const { done, value } = await reader.read();
          if (done) break;

          const chunk = decoder.decode(value);
          const lines = chunk.split('\n').filter(Boolean);

          for (const line of lines) {
            if (line.startsWith('data: ')) {
              const json = JSON.parse(line.slice(5));

              // Processa evento baseado no domain
              switch (json.envelope.domain) {
                case 'AUDIT':
                  handleAuditEvent(json);
                  break;
                case 'CHAT':
                  // Eventos de chat são para o chat panel
                  break;
                case 'TOOL':
                  handleToolEvent(json);
                  break;
                case 'INTERACTION':
                  handleInteractionEvent(json);
                  break;
              }
            }
          }
        }

      } catch (error) {
        update(state => ({
          isExecuting: false,
          error: error.message
        }));
      }
    },

    handleAuditEvent(event: any) {
      // Atualiza estado de execução
      update(state => {
        if (event.data.executionId && !state.executionId) {
          state.executionId = event.data.executionId;
        }
      });
    },

    handleToolEvent(event: any) {
      const { toolName, action, input, output } = event.data;

      if (action === 'start') {
        // Mostra nó sendo executado
        update(state => ({
          currentStepIndex: state.currentStepIndex + 1
        }));
      } else if (action === 'complete') {
        // Nó completado
        update(state => ({
          currentStepIndex: state.currentStepIndex + 1
        }));
      }
    },

    cancel: async () => {
      const { executionId } = get();

      if (executionId) {
        await fetch(`${apiBase}/executions/${executionId}/cancel`, {
          method: 'POST'
        });

        set({ isExecuting: false, executionId: null });
      }
    }
  };
}

function createExecutionStore(apiBase: string) {
  // Implementação real
  return {
    subscribe: () => () => {},
    execute: async () => {},
    cancel: async () => {}
  };
}
```

### Especificação: ExecutionOverlay

**Arquivo**: `archflow-ui/packages/archflow-designer/src/components/ExecutionOverlay.svelte`

```svelte
<script lang="ts">
  import { onMount } from 'svelte';

  export let result: ExecutionResult | null = null;

  let visible = $state(false);

  function show() {
    visible = true;
  }

  function hide() {
    visible = false;
  }

  // Expose ao pai
  export function showOverlay(r: ExecutionResult) {
    result = r;
    show();
  }
</script>

{#if visible && result}
  <div class="overlay">
    <div class="overlay-content">
      <header>
        <h2>Execution Result</h2>
        <button on:click={hide}>✕</button>
      </header>

      <div class="status">
        <span class="status-{result.status}">
          {result.status}
        </span>
        <span class="duration">
          {result.metrics.totalDuration}ms
        </span>
      </div>

      <div class="steps">
        <h3>Execution Steps</h3>
        {#each result.steps as step}
          <div class="step status-{step.status}">
            <div class="step-header">
              <span class="step-name">{step.nodeName}</span>
              <span class="step-time">{step.duration}ms</span>
            </div>

            {#if step.input}
              <div class="step-io">
                <strong>Input:</strong>
                <pre>{JSON.stringify(step.input, null, 2)}</pre>
              </div>
            {/if}

            {#if step.output}
              <div class="step-io">
                <strong>Output:</strong>
                <pre>{JSON.stringify(step.output, null, 2)}</pre>
              </div>
            {/if}

            {#if step.error}
              <div class="step-error">
                <strong>Error:</strong>
                <code>{step.error}</code>
              </div>
            {/if}
          </div>
        {/each}
      </div>

      <div class="metrics">
        <h3>Metrics</h3>
        <dl>
          <dt>Tokens</dt>
          <dd>{result.metrics.tokenUsage.totalTokens}</dd>

          <dt>Prompt Tokens</dt>
          <dd>{result.metrics.tokenUsage.promptTokens}</dd>

          <dt>Completion Tokens</dt>
          <dd>{result.metrics.tokenUsage.completionTokens}</dd>

          <dt>Cost</dt>
          <dd>${result.metrics.costUsd?.toFixed(4) || 'N/A'}</dd>
        </dl>
      </div>
    </div>
  </div>
{/if}

<style>
  .overlay {
    position: fixed;
    inset: 0;
    background: rgba(0, 0, 0, 0.5);
    display: flex;
    align-items: center;
    justify-content: center;
    z-index: 1000;
  }

  .overlay-content {
    background: var(--bg-secondary);
    border-radius: 8px;
    max-width: 600px;
    max-height: 80vh;
    overflow-y: auto;
    padding: 1.5rem;
  }

  .status {
    display: flex;
    gap: 1rem;
    margin-bottom: 1rem;
    font-weight: 600;
  }

  .step {
    border-left: 3px solid var(--border-color);
    padding-left: 1rem;
    margin-bottom: 1rem;
  }

  .step-completed {
    border-color: #10b981;
  }

  .step-failed {
    border-color: #ef4444;
  }

  .step-error {
    color: #ef4444;
    margin-top: 0.5rem;
  }
</style>
```

### Critérios de Aceite

- [ ] Execução de workflows via Web Component
- [ ] Status updates em tempo real
- [ ] Visualização de steps do fluxo
- [ ] Métricas de execução
- [ ] Cancelamento de execução
- [ ] Overlay com resultados

---

## Critérios de Sucesso da Fase 2

- [ ] `<archflow-designer>` publicado no npm
- [ ] Funciona em React (example confirmado)
- [ ] Funciona em Vue (example confirmado)
- [ ] Funciona em Angular (example confirmado)
- [ ] 8+ tipos de nós implementados
- [ ] Canvas com drag-and-drop completo
- [ ] Validação visual de erros
- [ ] Execução via component funcionando
- [ ] Documentação de integração

---

## Próximos Passos Após Fase 2

1. **Publish no npm**: `@archflow/component v1.0.0-alpha1`
2. **Demo videos**: Showcasing React/Vue/Angular integration
3. **Blog post**: "Zero Frontend Lock-in com Web Components"
4. **Iniciar Fase 3**: Enterprise Capabilities
