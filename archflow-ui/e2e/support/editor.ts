import type { Page } from '@playwright/test';
import { adminUser, authHandlers, fulfillJson, installApiRouter } from './api';

export type EditorConnection = {
    sourceId: string;
    targetId: string;
    isErrorPath: boolean;
};

export type EditorStep = {
    id: string;
    type: string;
    componentId: string;
    operation: string;
    position: { x: number; y: number };
    configuration: Record<string, unknown>;
    connections: EditorConnection[];
};

export type EditorWorkflow = {
    id: string;
    metadata: {
        name: string;
        description: string;
        version: string;
        category: string;
        tags: string[];
    };
    steps: EditorStep[];
    configuration: Record<string, unknown>;
};

export const editorProviders = [
    {
        id: 'anthropic',
        displayName: 'Anthropic',
        requiresApiKey: true,
        supportsStreaming: true,
        group: 'Cloud',
        models: [
            { id: 'claude-sonnet-4-6', name: 'Claude Sonnet 4.6', contextWindow: 200000, maxTemperature: 1.0 },
            { id: 'claude-opus-4-6', name: 'Claude Opus 4.6', contextWindow: 200000, maxTemperature: 1.0 },
        ],
    },
    {
        id: 'openai',
        displayName: 'OpenAI',
        requiresApiKey: true,
        supportsStreaming: true,
        group: 'Cloud',
        models: [
            { id: 'gpt-4o', name: 'GPT-4o', contextWindow: 128000, maxTemperature: 2.0 },
            { id: 'gpt-4o-mini', name: 'GPT-4o Mini', contextWindow: 128000, maxTemperature: 2.0 },
        ],
    },
];

export const editorPatterns = [
    { id: 'react', label: 'ReAct (Reason + Act)', description: 'Iterative loop' },
    { id: 'plan-execute', label: 'Plan and Execute', description: 'Plan then execute' },
    { id: 'rewoo', label: 'ReWOO (Reasoning Without Observation)', description: 'Plan upfront' },
    { id: 'chain-of-thought', label: 'Chain of Thought', description: 'Multi-path vote' },
];

export const editorPersonas = [
    { id: 'order_tracking', label: 'Order Tracking', description: 'Tracks orders and deliveries', promptId: 'p/orders' },
    { id: 'customer_support', label: 'Customer Support', description: 'Handles customer queries with empathy', promptId: 'p/support' },
];

export const editorGovernanceProfiles = [
    {
        id: 'default',
        name: 'Default',
        systemPrompt: 'You are helpful.',
        enabledTools: [],
        disabledTools: [],
        escalationThreshold: 0.4,
        maxToolExecutions: 10,
        customInstructions: '',
    },
    {
        id: 'strict',
        name: 'Strict',
        systemPrompt: 'Be very careful.',
        enabledTools: ['crm_lookup'],
        disabledTools: ['execute_code'],
        escalationThreshold: 0.75,
        maxToolExecutions: 5,
        customInstructions: 'No PII disclosure.',
    },
];

export type EditorMockState<TWorkflow extends EditorWorkflow> = {
    getWorkflow: () => TWorkflow;
    getYaml: () => string;
    getLastPut: () => TWorkflow | null;
    getPutCount: () => number;
};

export function renderWorkflowYaml(workflow: EditorWorkflow): string {
    const stepsYaml = workflow.steps
        .map((step) => {
            const configLines = Object.entries(step.configuration)
                .map(([key, value]) => `      ${key}: ${JSON.stringify(value)}`)
                .join('\n');
            const connectionsYaml = step.connections.length
                ? `    connections:\n${step.connections
                    .map(
                        (connection) =>
                            `      - { sourceId: ${connection.sourceId}, targetId: ${connection.targetId}, isErrorPath: ${connection.isErrorPath} }`,
                    )
                    .join('\n')}`
                : '';

            return `  - id: ${step.id}\n    type: ${step.type}\n    componentId: ${step.componentId}\n    operation: ${step.operation}\n    configuration:\n${configLines}\n${connectionsYaml}`;
        })
        .join('\n');

    return `id: ${workflow.id}\nmetadata:\n  name: ${workflow.metadata.name}\n  version: ${workflow.metadata.version}\nsteps:\n${stepsYaml}\n`;
}

export async function installEditorApi<TWorkflow extends EditorWorkflow>(
    page: Page,
    options: {
        workflowId: string;
        initialWorkflow: () => TWorkflow;
        initialYaml?: string | ((workflow: TWorkflow) => string);
        workflowSummary?: (workflow: TWorkflow) => Record<string, unknown>;
    },
): Promise<EditorMockState<TWorkflow>> {
    let storedWorkflow = options.initialWorkflow();
    let storedYaml =
        typeof options.initialYaml === 'function'
            ? options.initialYaml(storedWorkflow)
            : options.initialYaml ?? renderWorkflowYaml(storedWorkflow);
    let putCount = 0;
    let lastPut: TWorkflow | null = null;

    const workflowSummary =
        options.workflowSummary ??
        ((workflow: TWorkflow) => ({
            id: workflow.id,
            name: workflow.metadata.name,
            description: workflow.metadata.description,
            version: workflow.metadata.version,
            status: 'draft',
            updatedAt: new Date().toISOString(),
            stepCount: workflow.steps.length,
        }));

    await installApiRouter(page, [
        ...authHandlers(adminUser, {
            workflows: [],
            pendingApprovals: [],
            loginShape: 'token',
            includeWorkflowList: false,
        }),
        async ({ path, method, route }) => {
            if (path !== '/workflow/providers' || method !== 'GET') return false;
            await fulfillJson(route, editorProviders);
            return true;
        },
        async ({ path, method, route }) => {
            if (path !== '/workflow/agent-patterns' || method !== 'GET') return false;
            await fulfillJson(route, editorPatterns);
            return true;
        },
        async ({ path, method, route }) => {
            if (path !== '/workflow/personas' || method !== 'GET') return false;
            await fulfillJson(route, editorPersonas);
            return true;
        },
        async ({ path, method, route }) => {
            if (path !== '/workflow/governance-profiles' || method !== 'GET') return false;
            await fulfillJson(route, editorGovernanceProfiles);
            return true;
        },
        async ({ path, method, route }) => {
            if (path !== '/workflow/mcp-servers' || method !== 'GET') return false;
            await fulfillJson(route, []);
            return true;
        },
        // Catalog endpoints — return empty arrays so PropertyPanel falls
        // into the "no catalog" branch (TextInput "Tool ID" instead of
        // Select "Tool"). Without this the useCatalog hook applies its
        // baked-in fallback list, which would cause the editor-journey
        // spec to see a populated Select where it expects a free-text
        // Tool ID input.
        async ({ path, method, route }) => {
            if (!['/catalog/agents', '/catalog/assistants', '/catalog/tools',
                  '/catalog/embeddings', '/catalog/memories',
                  '/catalog/vectorstores', '/catalog/chains'].includes(path)) return false;
            if (method !== 'GET') return false;
            await fulfillJson(route, []);
            return true;
        },
        async ({ path, method, route }) => {
            if (path !== '/executions' || method !== 'GET') return false;
            await fulfillJson(route, []);
            return true;
        },
        async ({ path, method, route }) => {
            if (path !== '/workflows' || method !== 'GET') return false;
            await fulfillJson(route, [workflowSummary(storedWorkflow)]);
            return true;
        },
        async ({ path, method, route }) => {
            if (path !== `/workflows/${options.workflowId}` || method !== 'GET') return false;
            await fulfillJson(route, storedWorkflow);
            return true;
        },
        async ({ path, method, request, route }) => {
            if (path !== `/workflows/${options.workflowId}` || method !== 'PUT') return false;
            putCount += 1;
            lastPut = request.postDataJSON() as TWorkflow;
            storedWorkflow = lastPut;
            storedYaml = renderWorkflowYaml(storedWorkflow);
            await fulfillJson(route, storedWorkflow);
            return true;
        },
        async ({ path, method, route }) => {
            if (path !== `/workflows/${options.workflowId}/yaml` || method !== 'GET') return false;
            await fulfillJson(route, {
                id: options.workflowId,
                yaml: storedYaml,
                version: storedWorkflow.metadata.version,
            });
            return true;
        },
    ]);

    return {
        getWorkflow: () => storedWorkflow,
        getYaml: () => storedYaml,
        getLastPut: () => lastPut,
        getPutCount: () => putCount,
    };
}
