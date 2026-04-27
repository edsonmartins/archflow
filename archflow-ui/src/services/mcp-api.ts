import { api } from './api'

export interface McpTool {
    name: string
    description: string
    inputSchema: Record<string, unknown>
}

export interface McpPromptArgument {
    name: string
    description?: string
    required: boolean
}

export interface McpPrompt {
    name: string
    description?: string
    arguments: McpPromptArgument[]
}

export interface McpResource {
    uri?: string
    name: string
    description?: string
    mimeType?: string
}

export interface McpIntrospection {
    serverName: string
    connected: boolean
    tools: McpTool[]
    prompts: McpPrompt[]
    resources: McpResource[]
    error?: string
}

export const mcpApi = {
    listServers: () => api.get<string[]>('/admin/mcp/servers'),
    introspect:  (name: string) =>
        api.get<McpIntrospection>(`/admin/mcp/servers/${encodeURIComponent(name)}`),
}
