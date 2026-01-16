# @archflow/component

> Framework-agnostic visual AI workflow builder Web Component

[![npm version](https://badge.fury.io/js/%40archflow%2Fcomponent.svg)](https://www.npmjs.com/package/@archflow/component)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](https://opensource.org/licenses/Apache-2.0)

**Archflow Designer** is a visual workflow builder for AI applications that works with any frontend framework - React, Vue, Angular, Svelte, or vanilla JavaScript.

## Features

- 🎨 **Framework-Agnostic** - Works with any framework or no framework at all
- 🧩 **Web Component** - Built with native Web Components and Shadow DOM
- 🔧 **TypeScript** - Full TypeScript support with type definitions
- 🎯 **Easy Integration** - Single import, zero configuration
- 🌙 **Theme Support** - Built-in light and dark themes
- 📱 **Responsive** - Works on any screen size

## Installation

```bash
npm install @archflow/component
# or
yarn add @archflow/component
# or
pnpm add @archflow/component
```

## Quick Start

### HTML / Vanilla JavaScript

```html
<!DOCTYPE html>
<html>
<head>
  <script type="module">
    import { registerArchflowDesigner } from '@archflow/component';
    registerArchflowDesigner();
  </script>
</head>
<body>
  <archflow-designer
    workflow-id="my-workflow"
    api-base="http://localhost:8080/api"
    theme="light">
  </archflow-designer>
</body>
</html>
```

### React

```tsx
import { useEffect } from 'react';
import { registerArchflowDesigner } from '@archflow/component';

function App() {
  useEffect(() => {
    registerArchflowDesigner();
  }, []);

  return (
    <archflow-designer
      workflow-id="my-workflow"
      api-base="http://localhost:8080/api"
      theme="dark"
      style={{ width: '100%', height: '600px' }}
    />
  );
}
```

### Vue 3

```vue
<script setup>
import { onMounted } from 'vue';
import { registerArchflowDesigner } from '@archflow/component';

onMounted(() => {
  registerArchflowDesigner();
});
</script>

<template>
  <archflow-designer
    workflow-id="my-workflow"
    api-base="http://localhost:8080/api"
    theme="light"
  />
</template>
```

### Angular

```typescript
import { Component, OnInit } from '@angular/core';
import { registerArchflowDesigner } from '@archflow/component';

@Component({
  selector: 'app-root',
  template: `
    <archflow-designer
      [attr.workflow-id]="workflowId"
      [attr.api-base]="apiBase"
      [attr.theme]="theme">
    </archflow-designer>
  `
})
export class AppComponent implements OnInit {
  workflowId = 'my-workflow';
  apiBase = 'http://localhost:8080/api';
  theme = 'light';

  ngOnInit() {
    registerArchflowDesigner();
  }
}
```

## API Reference

### Attributes

| Attribute | Type | Default | Description |
|-----------|------|---------|-------------|
| `workflow-id` | `string` | - | Workflow ID to load |
| `api-base` | `string` | `/api` | API base URL |
| `theme` | `'light' \| 'dark'` | `'light'` | Theme mode |
| `readonly` | `boolean` | `false` | Disable editing |
| `width` | `string` | `'100%'` | Component width |
| `height` | `string` | `'600px'` | Component height |

### Methods

```typescript
// Load workflow by ID
await designer.loadWorkflow('workflow-id');

// Set workflow programmatically
designer.setWorkflow(workflowObject);

// Save current workflow
const saved = await designer.saveWorkflow();

// Execute workflow
const result = await designer.executeWorkflow({ input: 'value' });

// Select nodes
designer.selectNodes(['node-id-1', 'node-id-2']);

// Clear selection
designer.clearSelection();

// Reset to empty state
designer.reset();

// Get workflow JSON
const json = designer.getWorkflowJson();
```

### Events

| Event | Detail | Description |
|-------|--------|-------------|
| `connected` | `{ component, version }` | Component initialized |
| `workflow-loaded` | `{ workflow }` | Workflow loaded |
| `workflow-saved` | `{ workflow, timestamp }` | Workflow saved |
| `workflow-executed` | `{ result, timestamp }` | Workflow executed |
| `node-selected` | `{ nodeId, timestamp }` | Node selected |
| `error` | `{ type, message, timestamp }` | Error occurred |

```typescript
designer.addEventListener('workflow-saved', (event) => {
  console.log('Workflow saved:', event.detail.workflow);
});
```

## Workflow Schema

```typescript
interface Flow {
  id: string;
  metadata: {
    name: string;
    description?: string;
    version: string;
  };
  steps: FlowStep[];
}

interface FlowStep {
  id: string;
  type: 'input' | 'output' | 'llm-chat' | 'llm-complete' | 'tool' | 'agent';
  name: string;
  config: Record<string, unknown>;
}
```

## Development

```bash
# Install dependencies
npm install

# Run dev server
npm run dev

# Run type check
npm run typecheck

# Run tests
npm run test

# Build component
npm run build:component
```

## License

Apache-2.0 © [Archflow Team](https://github.com/archflow)

## Links

- [GitHub](https://github.com/archflow/archflow)
- [Documentation](https://github.com/archflow/archflow/docs)
- [Issues](https://github.com/archflow/archflow/issues)
