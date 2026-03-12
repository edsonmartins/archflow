---
title: "Web Component API"
sidebar_position: 1
slug: web-component
---

# Web Component API

Referencia da API do Web Component `<archflow-designer>` para integracao do visual workflow designer em qualquer aplicacao web.

## Visao Geral

O archflow disponibiliza um Web Component padrao (Custom Element) que pode ser usado em qualquer framework frontend:

```html
<archflow-designer
  workflow-id="flow-789"
  theme="light"
  width="100%"
  height="600px"
/>
```

## Instalacao

### Via NPM

```bash
npm install @archflow/designer
```

```javascript
import '@archflow/designer';
```

### Via CDN

```html
<script type="module" src="https://unpkg.com/@archflow/designer/dist/archflow-designer.js"></script>
```

### Via build local

```bash
cd archflow-ui
npm run build
# Artefato gerado em dist/archflow-designer.js
```

## Tag: `<archflow-designer>`

O componente principal para o designer visual de workflows.

## Attributes

Atributos HTML que podem ser definidos diretamente na tag:

| Atributo | Tipo | Default | Descricao |
|----------|------|---------|-----------|
| `workflow-id` | string | `null` | ID do workflow a carregar (via API) |
| `theme` | `"light"` \| `"dark"` | `"light"` | Tema visual |
| `width` | string | `"100%"` | Largura do componente (CSS) |
| `height` | string | `"600px"` | Altura do componente (CSS) |
| `readonly` | boolean | `false` | Modo somente leitura (desabilita edicao) |
| `show-minimap` | boolean | `true` | Exibir minimapa de navegacao |
| `show-grid` | boolean | `true` | Exibir grid de fundo |
| `api-url` | string | `"/api"` | URL base da API REST |
| `locale` | string | `"pt-BR"` | Idioma da interface |

### Exemplos

```html
<!-- Designer completo -->
<archflow-designer
  workflow-id="flow-789"
  theme="dark"
  width="100%"
  height="800px"
  show-minimap="true"
  show-grid="true"
/>

<!-- Modo visualizacao (readonly) -->
<archflow-designer
  workflow-id="flow-789"
  readonly="true"
  show-minimap="false"
  height="400px"
/>

<!-- Novo workflow (sem id) -->
<archflow-designer
  theme="light"
  height="100vh"
/>
```

## Properties (JavaScript)

Propriedades acessiveis via JavaScript para controle programatico:

### `setWorkflow(data)`

Carrega um workflow no designer a partir de um objeto JSON.

```javascript
const designer = document.querySelector('archflow-designer');

designer.setWorkflow({
  metadata: {
    name: "My Workflow",
    description: "Workflow de exemplo",
    version: "1.0.0"
  },
  steps: [
    {
      id: "step-1",
      type: "ACTION",
      componentId: "tech-support-assistant",
      operation: "analyze",
      position: { x: 100, y: 200 },
      configuration: {
        model: "claude-3-sonnet"
      }
    },
    {
      id: "step-2",
      type: "TOOL_CALL",
      componentId: "calculator-tool",
      operation: "calculate",
      position: { x: 400, y: 200 }
    }
  ],
  connections: [
    {
      sourceId: "step-1",
      targetId: "step-2",
      isErrorPath: false
    }
  ]
});
```

### `getWorkflow()`

Retorna o workflow atual como objeto JSON.

```javascript
const workflow = designer.getWorkflow();
console.log(workflow.metadata.name);
console.log(workflow.steps.length);

// Salvar via API
fetch('/api/workflows', {
  method: 'POST',
  headers: {
    'Authorization': 'Bearer ' + token,
    'Content-Type': 'application/json'
  },
  body: JSON.stringify(workflow)
});
```

### `addNode(nodeInfo)`

Adiciona um novo no (step) ao canvas.

```javascript
designer.addNode({
  type: "ACTION",
  componentId: "tech-support-assistant",
  operation: "analyze",
  position: { x: 300, y: 150 },
  configuration: {
    model: "claude-3-sonnet",
    temperature: 0.7
  }
});
```

### `clearCanvas()`

Remove todos os nos e conexoes do canvas.

```javascript
designer.clearCanvas();
```

### `zoomTo(level)`

Define o nivel de zoom do canvas.

```javascript
designer.zoomTo(1.0);   // 100%
designer.zoomTo(0.5);   // 50%
designer.zoomTo(1.5);   // 150%
```

### `fitView()`

Ajusta o zoom para mostrar todos os nos.

```javascript
designer.fitView();
```

## Events

Eventos emitidos pelo componente. Use `addEventListener` para captura-los:

### `node-selected`

Disparado quando um no e selecionado no canvas.

```javascript
designer.addEventListener('node-selected', (event) => {
  const { nodeId, nodeType, componentId } = event.detail;
  console.log(`Node selecionado: ${nodeId} (${nodeType})`);
});
```

### `selection-cleared`

Disparado quando a selecao e limpa (clique no canvas vazio).

```javascript
designer.addEventListener('selection-cleared', () => {
  // Fechar painel de propriedades
});
```

### `workflow-saved`

Disparado quando o workflow e salvo (via botao ou programaticamente).

```javascript
designer.addEventListener('workflow-saved', (event) => {
  const { workflowId, version } = event.detail;
  console.log(`Workflow salvo: ${workflowId} v${version}`);
});
```

### `workflow-executed`

Disparado quando uma execucao de workflow e iniciada.

```javascript
designer.addEventListener('workflow-executed', (event) => {
  const { executionId, status } = event.detail;
  console.log(`Execucao iniciada: ${executionId} - ${status}`);
});
```

### `node-added`

Disparado quando um novo no e adicionado ao canvas.

```javascript
designer.addEventListener('node-added', (event) => {
  const { nodeId, nodeType, position } = event.detail;
  console.log(`Novo no: ${nodeId} em (${position.x}, ${position.y})`);
});
```

### `node-removed`

Disparado quando um no e removido do canvas.

```javascript
designer.addEventListener('node-removed', (event) => {
  const { nodeId } = event.detail;
  console.log(`No removido: ${nodeId}`);
});
```

### `connection-added`

Disparado quando uma nova conexao e criada entre nos.

```javascript
designer.addEventListener('connection-added', (event) => {
  const { sourceId, targetId, isErrorPath } = event.detail;
  console.log(`Conexao: ${sourceId} → ${targetId} (error: ${isErrorPath})`);
});
```

## CSS Custom Properties

Customize a aparencia do designer com CSS custom properties:

```css
archflow-designer {
  /* Cores principais */
  --archflow-bg: #ffffff;
  --archflow-bg-secondary: #f8f9fa;
  --archflow-text: #212529;
  --archflow-text-secondary: #6c757d;
  --archflow-border: #dee2e6;

  /* Cores de destaque */
  --archflow-primary: #228be6;
  --archflow-primary-light: #e7f5ff;
  --archflow-success: #40c057;
  --archflow-warning: #fab005;
  --archflow-error: #fa5252;

  /* Nos */
  --archflow-node-bg: #ffffff;
  --archflow-node-border: #dee2e6;
  --archflow-node-selected: #228be6;
  --archflow-node-shadow: 0 2px 8px rgba(0, 0, 0, 0.1);
  --archflow-node-radius: 8px;

  /* Conexoes */
  --archflow-edge-color: #adb5bd;
  --archflow-edge-selected: #228be6;
  --archflow-edge-error: #fa5252;
  --archflow-edge-width: 2px;

  /* Grid */
  --archflow-grid-color: #f1f3f5;
  --archflow-grid-size: 20px;

  /* Minimap */
  --archflow-minimap-bg: rgba(255, 255, 255, 0.9);
  --archflow-minimap-border: #dee2e6;

  /* Fontes */
  --archflow-font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', sans-serif;
  --archflow-font-size: 14px;
}
```

### Tema escuro

```css
archflow-designer[theme="dark"] {
  --archflow-bg: #1a1b1e;
  --archflow-bg-secondary: #25262b;
  --archflow-text: #c1c2c5;
  --archflow-text-secondary: #909296;
  --archflow-border: #373a40;
  --archflow-node-bg: #25262b;
  --archflow-node-border: #373a40;
  --archflow-grid-color: #2c2e33;
}
```

## Integracao com Frameworks

### React

```tsx
import '@archflow/designer';
import { useRef, useEffect } from 'react';

// Declaracao de tipo para o Web Component
declare global {
  namespace JSX {
    interface IntrinsicElements {
      'archflow-designer': React.DetailedHTMLProps<
        React.HTMLAttributes<HTMLElement> & {
          'workflow-id'?: string;
          theme?: 'light' | 'dark';
          width?: string;
          height?: string;
          readonly?: boolean;
          'show-minimap'?: boolean;
          'show-grid'?: boolean;
        },
        HTMLElement
      >;
    }
  }
}

function WorkflowEditor({ workflowId }: { workflowId: string }) {
  const designerRef = useRef<HTMLElement>(null);

  useEffect(() => {
    const designer = designerRef.current;
    if (!designer) return;

    const handleNodeSelected = (e: CustomEvent) => {
      console.log('Node selecionado:', e.detail);
    };

    designer.addEventListener('node-selected', handleNodeSelected);
    return () => designer.removeEventListener('node-selected', handleNodeSelected);
  }, []);

  const handleSave = () => {
    const designer = designerRef.current as any;
    const workflow = designer.getWorkflow();
    console.log('Salvando:', workflow);
  };

  return (
    <div>
      <button onClick={handleSave}>Salvar</button>
      <archflow-designer
        ref={designerRef}
        workflow-id={workflowId}
        theme="light"
        height="600px"
      />
    </div>
  );
}
```

### Vue

```vue
<template>
  <div>
    <button @click="saveWorkflow">Salvar</button>
    <archflow-designer
      ref="designer"
      :workflow-id="workflowId"
      theme="light"
      height="600px"
      @node-selected="onNodeSelected"
      @workflow-saved="onWorkflowSaved"
    />
  </div>
</template>

<script setup lang="ts">
import '@archflow/designer';
import { ref } from 'vue';

const props = defineProps<{ workflowId: string }>();
const designer = ref<HTMLElement | null>(null);

function onNodeSelected(event: CustomEvent) {
  console.log('Node selecionado:', event.detail);
}

function onWorkflowSaved(event: CustomEvent) {
  console.log('Workflow salvo:', event.detail);
}

function saveWorkflow() {
  const el = designer.value as any;
  const workflow = el.getWorkflow();
  console.log('Salvando:', workflow);
}
</script>
```

### Angular

```typescript
// app.module.ts
import { CUSTOM_ELEMENTS_SCHEMA, NgModule } from '@angular/core';

@NgModule({
  schemas: [CUSTOM_ELEMENTS_SCHEMA],
  // ...
})
export class AppModule {}
```

```typescript
// workflow-editor.component.ts
import { Component, ElementRef, ViewChild, AfterViewInit } from '@angular/core';
import '@archflow/designer';

@Component({
  selector: 'app-workflow-editor',
  template: `
    <button (click)="saveWorkflow()">Salvar</button>
    <archflow-designer
      #designer
      [attr.workflow-id]="workflowId"
      theme="light"
      height="600px"
    ></archflow-designer>
  `
})
export class WorkflowEditorComponent implements AfterViewInit {
  @ViewChild('designer') designerRef!: ElementRef;
  workflowId = 'flow-789';

  ngAfterViewInit() {
    const designer = this.designerRef.nativeElement;

    designer.addEventListener('node-selected', (e: CustomEvent) => {
      console.log('Node selecionado:', e.detail);
    });

    designer.addEventListener('workflow-saved', (e: CustomEvent) => {
      console.log('Workflow salvo:', e.detail);
    });
  }

  saveWorkflow() {
    const designer = this.designerRef.nativeElement;
    const workflow = designer.getWorkflow();
    console.log('Salvando:', workflow);
  }
}
```

### Vanilla JavaScript

```html
<!DOCTYPE html>
<html lang="pt-BR">
<head>
  <script type="module" src="https://unpkg.com/@archflow/designer/dist/archflow-designer.js"></script>
  <style>
    archflow-designer {
      --archflow-primary: #7c3aed;
      --archflow-node-radius: 12px;
    }
  </style>
</head>
<body>
  <archflow-designer
    id="designer"
    theme="light"
    width="100%"
    height="100vh"
    show-minimap="true"
  ></archflow-designer>

  <script>
    const designer = document.getElementById('designer');

    designer.addEventListener('node-selected', (e) => {
      console.log('Node:', e.detail);
    });

    designer.addEventListener('workflow-saved', (e) => {
      console.log('Salvo:', e.detail);
    });

    // Carregar workflow programaticamente
    fetch('/api/workflows/flow-789', {
      headers: { 'Authorization': 'Bearer ' + token }
    })
    .then(r => r.json())
    .then(data => designer.setWorkflow(data));
  </script>
</body>
</html>
```

## Proximos passos

- [REST API Reference](./rest-endpoints) -- Endpoints para gerenciar workflows
- [Building Workflows](../guias/building-workflows) -- Criar workflows via API
- [Conceitos: Workflows](../conceitos/workflows) -- Arquitetura de workflows
