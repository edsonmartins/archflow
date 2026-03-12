# Archflow React Customer Support Example

A simple React demo that connects to the archflow backend to list, design, and execute customer support workflows.

## Prerequisites

- Node.js 18+
- Archflow backend running at `http://localhost:8080`

## Running

```bash
npm install
npm run dev
```

Open http://localhost:3000. The Vite dev server proxies `/api` requests to the backend at `:8080`.

## What it demonstrates

1. **Authentication** - Login form that obtains a JWT token via `/api/auth/login`
2. **Workflow listing** - Fetches available workflows from `/api/workflows`
3. **Visual designer** - Embeds the `<archflow-designer>` web component for the selected workflow
4. **Execution** - Triggers workflow execution via REST and polls for status
