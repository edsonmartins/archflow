import type { WorkflowConnection, WorkflowStep } from '../components/FlowCanvas/types';

/**
 * Catalog of pre-built workflow templates that users can clone with one
 * click. Inspired by PraisonAI's `examples/` directory but presented as
 * a discoverable in-app gallery.
 *
 * Each template includes the full canvas content (steps + connections),
 * sample prompt variables and a category for filtering. The templates are
 * fully declarative — no backend roundtrip is needed to render the
 * gallery, only when the user clicks "Use this template" we POST to
 * `/workflows` to materialize a copy in their workspace.
 */

export type TemplateCategory =
    | 'customer_support'
    | 'rag'
    | 'content'
    | 'code'
    | 'research'
    | 'productivity';

export interface WorkflowTemplate {
    id: string;
    name: string;
    icon: string;
    /** Short one-liner shown in the card. */
    summary: string;
    /** Long-form markdown description shown in the detail page. */
    description: string;
    category: TemplateCategory;
    tags: string[];
    /** Estimated time-to-first-response (cosmetic). */
    complexity: 'starter' | 'intermediate' | 'advanced';
    /** Steps and connections that will populate the canvas after clone. */
    steps: WorkflowStep[];
    connections: WorkflowConnection[];
    /** Optional default configuration variables exposed by the template. */
    variables?: Record<string, string>;
}

const TEMPLATES: WorkflowTemplate[] = [
    // ── 1. SAC simples ────────────────────────────────────────────
    {
        id: 'sac-basic',
        name: 'Customer support agent',
        icon: '🎧',
        summary: 'Conversational SAC with intent classification, persona routing and tool calls.',
        description:
            'A starter customer support agent that classifies user intent, routes to a specialized persona ' +
            '(order tracking, complaints, FAQ), runs guardrails for PII and identification, and finally calls ' +
            'a tool to fetch the answer. Mirrors the SAC pattern documented in archflow-conversation.',
        category: 'customer_support',
        tags: ['sac', 'conversational', 'persona', 'guardrails'],
        complexity: 'intermediate',
        steps: [
            { id: 's1', type: 'input', componentId: 'input', label: 'Inbound message', category: 'io', position: { x: 60, y: 200 }, config: {} },
            { id: 's2', type: 'condition', componentId: 'condition', label: 'Intent router', category: 'control', position: { x: 280, y: 200 }, config: { strategy: 'llm' } },
            { id: 's3', type: 'agent', componentId: 'agent', label: 'Tracking agent', category: 'agent', position: { x: 520, y: 80 }, config: { persona: 'order_tracking' } },
            { id: 's4', type: 'agent', componentId: 'agent', label: 'Complaint agent', category: 'agent', position: { x: 520, y: 200 }, config: { persona: 'complaint' } },
            { id: 's5', type: 'agent', componentId: 'agent', label: 'FAQ agent', category: 'agent', position: { x: 520, y: 320 }, config: { persona: 'faq' } },
            { id: 's6', type: 'tool', componentId: 'tool', label: 'CRM lookup', category: 'tool', position: { x: 760, y: 200 }, config: { name: 'crm_lookup' } },
            { id: 's7', type: 'output', componentId: 'output', label: 'Reply to user', category: 'io', position: { x: 1000, y: 200 }, config: {} },
        ],
        connections: [
            { id: 'c1', sourceId: 's1', targetId: 's2', isErrorPath: false },
            { id: 'c2', sourceId: 's2', targetId: 's3', isErrorPath: false },
            { id: 'c3', sourceId: 's2', targetId: 's4', isErrorPath: false },
            { id: 'c4', sourceId: 's2', targetId: 's5', isErrorPath: false },
            { id: 'c5', sourceId: 's3', targetId: 's6', isErrorPath: false },
            { id: 'c6', sourceId: 's4', targetId: 's6', isErrorPath: false },
            { id: 'c7', sourceId: 's5', targetId: 's7', isErrorPath: false },
            { id: 'c8', sourceId: 's6', targetId: 's7', isErrorPath: false },
        ],
        variables: { company_name: 'Acme', default_persona: 'order_tracking' },
    },

    // ── 2. RAG / doc Q&A ──────────────────────────────────────────
    {
        id: 'rag-doc-qa',
        name: 'Document Q&A (RAG)',
        icon: '📚',
        summary: 'Retrieval-augmented assistant that answers questions citing your knowledge base.',
        description:
            'Receives a question, runs a vector search against your knowledge base, builds a prompt with the ' +
            'retrieved chunks and asks an LLM to synthesize an answer. Citations are returned alongside the reply ' +
            'so the UI can render them with the CitationList component.',
        category: 'rag',
        tags: ['rag', 'vector', 'embeddings', 'citations'],
        complexity: 'starter',
        steps: [
            { id: 's1', type: 'input', componentId: 'input', label: 'Question', category: 'io', position: { x: 60, y: 200 }, config: {} },
            { id: 's2', type: 'embedding', componentId: 'embedding', label: 'Embed question', category: 'vector', position: { x: 280, y: 200 }, config: { model: 'text-embedding-3-small' } },
            { id: 's3', type: 'vector-search', componentId: 'vector-search', label: 'Retrieve chunks', category: 'vector', position: { x: 500, y: 200 }, config: { topK: 6 } },
            { id: 's4', type: 'prompt-template', componentId: 'prompt-template', label: 'Build prompt', category: 'io', position: { x: 720, y: 200 }, config: { template: 'rag.qa' } },
            { id: 's5', type: 'llm-streaming', componentId: 'llm-streaming', label: 'Synthesize', category: 'agent', position: { x: 940, y: 200 }, config: { model: 'gpt-4o' } },
            { id: 's6', type: 'output', componentId: 'output', label: 'Answer', category: 'io', position: { x: 1160, y: 200 }, config: {} },
        ],
        connections: [
            { id: 'c1', sourceId: 's1', targetId: 's2', isErrorPath: false },
            { id: 'c2', sourceId: 's2', targetId: 's3', isErrorPath: false },
            { id: 'c3', sourceId: 's3', targetId: 's4', isErrorPath: false },
            { id: 'c4', sourceId: 's4', targetId: 's5', isErrorPath: false },
            { id: 'c5', sourceId: 's5', targetId: 's6', isErrorPath: false },
        ],
        variables: { kb_collection: 'docs', max_chunks: '6' },
    },

    // ── 3. Code reviewer ──────────────────────────────────────────
    {
        id: 'code-reviewer',
        name: 'Code reviewer',
        icon: '🧑‍💻',
        summary: 'Reviews a pull request diff, flags issues and suggests improvements.',
        description:
            'Pulls a diff from the input, runs static checks via tools, asks an LLM to review the code with ' +
            'a structured rubric, and outputs a markdown comment ready to be posted to GitHub.',
        category: 'code',
        tags: ['code-review', 'pr', 'static-analysis'],
        complexity: 'intermediate',
        steps: [
            { id: 's1', type: 'input', componentId: 'input', label: 'PR diff', category: 'io', position: { x: 60, y: 220 }, config: {} },
            { id: 's2', type: 'tool', componentId: 'tool', label: 'Static lint', category: 'tool', position: { x: 280, y: 120 }, config: { name: 'lint_diff' } },
            { id: 's3', type: 'tool', componentId: 'tool', label: 'Security scan', category: 'tool', position: { x: 280, y: 320 }, config: { name: 'sast_diff' } },
            { id: 's4', type: 'merge', componentId: 'merge', label: 'Merge findings', category: 'data', position: { x: 520, y: 220 }, config: {} },
            { id: 's5', type: 'llm-chat', componentId: 'llm-chat', label: 'LLM review', category: 'agent', position: { x: 740, y: 220 }, config: { model: 'gpt-4o', rubric: 'code_review' } },
            { id: 's6', type: 'output', componentId: 'output', label: 'Markdown comment', category: 'io', position: { x: 960, y: 220 }, config: {} },
        ],
        connections: [
            { id: 'c1', sourceId: 's1', targetId: 's2', isErrorPath: false },
            { id: 'c2', sourceId: 's1', targetId: 's3', isErrorPath: false },
            { id: 'c3', sourceId: 's2', targetId: 's4', isErrorPath: false },
            { id: 'c4', sourceId: 's3', targetId: 's4', isErrorPath: false },
            { id: 'c5', sourceId: 's4', targetId: 's5', isErrorPath: false },
            { id: 'c6', sourceId: 's5', targetId: 's6', isErrorPath: false },
        ],
    },

    // ── 4. Content writer ─────────────────────────────────────────
    {
        id: 'content-writer',
        name: 'Long-form content writer',
        icon: '✍️',
        summary: 'Outlines, drafts and polishes a long-form article from a brief.',
        description:
            'Three-stage agent pipeline: an outline generator builds the structure, a drafter writes each ' +
            'section in parallel, and a final editor polishes tone and consistency. Useful for blog posts, ' +
            'newsletters and product descriptions.',
        category: 'content',
        tags: ['writing', 'long-form', 'multi-agent'],
        complexity: 'intermediate',
        steps: [
            { id: 's1', type: 'input', componentId: 'input', label: 'Brief', category: 'io', position: { x: 60, y: 220 }, config: {} },
            { id: 's2', type: 'agent', componentId: 'agent', label: 'Outliner', category: 'agent', position: { x: 280, y: 220 }, config: { role: 'outliner' } },
            { id: 's3', type: 'parallel', componentId: 'parallel', label: 'Draft sections', category: 'control', position: { x: 520, y: 220 }, config: {} },
            { id: 's4', type: 'agent', componentId: 'agent', label: 'Drafter', category: 'agent', position: { x: 760, y: 220 }, config: { role: 'drafter' } },
            { id: 's5', type: 'agent', componentId: 'agent', label: 'Editor', category: 'agent', position: { x: 1000, y: 220 }, config: { role: 'editor' } },
            { id: 's6', type: 'output', componentId: 'output', label: 'Article', category: 'io', position: { x: 1240, y: 220 }, config: {} },
        ],
        connections: [
            { id: 'c1', sourceId: 's1', targetId: 's2', isErrorPath: false },
            { id: 'c2', sourceId: 's2', targetId: 's3', isErrorPath: false },
            { id: 'c3', sourceId: 's3', targetId: 's4', isErrorPath: false },
            { id: 'c4', sourceId: 's4', targetId: 's5', isErrorPath: false },
            { id: 'c5', sourceId: 's5', targetId: 's6', isErrorPath: false },
        ],
        variables: { tone: 'professional', word_target: '1500' },
    },

    // ── 5. Web research → report ──────────────────────────────────
    {
        id: 'web-research',
        name: 'Web research → report',
        icon: '🔎',
        summary: 'Searches the web, scrapes top results and summarizes them into a structured report.',
        description:
            'Issues a web search, fetches the top results in parallel, runs an extractor to get clean text, ' +
            'and uses an LLM to compile a report with sources. Citations propagate to the chat surface for ' +
            'verification.',
        category: 'research',
        tags: ['web-search', 'scraping', 'report'],
        complexity: 'advanced',
        steps: [
            { id: 's1', type: 'input', componentId: 'input', label: 'Topic', category: 'io', position: { x: 60, y: 220 }, config: {} },
            { id: 's2', type: 'tool', componentId: 'tool', label: 'Web search', category: 'tool', position: { x: 280, y: 220 }, config: { name: 'web_search' } },
            { id: 's3', type: 'map', componentId: 'map', label: 'Fetch top N', category: 'data', position: { x: 520, y: 220 }, config: { topN: 5 } },
            { id: 's4', type: 'tool', componentId: 'tool', label: 'Extract text', category: 'tool', position: { x: 760, y: 220 }, config: { name: 'extract_text' } },
            { id: 's5', type: 'llm-chat', componentId: 'llm-chat', label: 'Compile report', category: 'agent', position: { x: 1000, y: 220 }, config: { model: 'gpt-4o', format: 'markdown' } },
            { id: 's6', type: 'output', componentId: 'output', label: 'Report', category: 'io', position: { x: 1240, y: 220 }, config: {} },
        ],
        connections: [
            { id: 'c1', sourceId: 's1', targetId: 's2', isErrorPath: false },
            { id: 'c2', sourceId: 's2', targetId: 's3', isErrorPath: false },
            { id: 'c3', sourceId: 's3', targetId: 's4', isErrorPath: false },
            { id: 'c4', sourceId: 's4', targetId: 's5', isErrorPath: false },
            { id: 'c5', sourceId: 's5', targetId: 's6', isErrorPath: false },
        ],
    },

    // ── 6. Email triage ───────────────────────────────────────────
    {
        id: 'email-triage',
        name: 'Email triage assistant',
        icon: '📥',
        summary: 'Classifies incoming emails, drafts replies and routes high-priority ones to a human.',
        description:
            'Pulls a new email, classifies it (sales, support, spam, urgent), drafts a reply via LLM, and ' +
            'sends low-confidence cases to the human approval queue. Demonstrates the human-in-the-loop pattern.',
        category: 'productivity',
        tags: ['email', 'classification', 'human-in-the-loop'],
        complexity: 'intermediate',
        steps: [
            { id: 's1', type: 'input', componentId: 'input', label: 'New email', category: 'io', position: { x: 60, y: 220 }, config: {} },
            { id: 's2', type: 'llm-chat', componentId: 'llm-chat', label: 'Classify', category: 'agent', position: { x: 280, y: 220 }, config: { task: 'classify' } },
            { id: 's3', type: 'switch', componentId: 'switch', label: 'Route', category: 'control', position: { x: 520, y: 220 }, config: { branches: ['sales', 'support', 'urgent', 'spam'] } },
            { id: 's4', type: 'agent', componentId: 'agent', label: 'Draft reply', category: 'agent', position: { x: 760, y: 140 }, config: { role: 'replier' } },
            { id: 's5', type: 'tool', componentId: 'tool', label: 'Send', category: 'tool', position: { x: 1000, y: 140 }, config: { name: 'send_email' } },
            { id: 's6', type: 'tool', componentId: 'tool', label: 'Escalate to human', category: 'tool', position: { x: 760, y: 320 }, config: { name: 'request_approval' } },
        ],
        connections: [
            { id: 'c1', sourceId: 's1', targetId: 's2', isErrorPath: false },
            { id: 'c2', sourceId: 's2', targetId: 's3', isErrorPath: false },
            { id: 'c3', sourceId: 's3', targetId: 's4', isErrorPath: false },
            { id: 'c4', sourceId: 's4', targetId: 's5', isErrorPath: false },
            { id: 'c5', sourceId: 's3', targetId: 's6', isErrorPath: true },
        ],
    },
];

export const TEMPLATES_BY_ID: Record<string, WorkflowTemplate> = TEMPLATES.reduce(
    (acc, t) => ({ ...acc, [t.id]: t }),
    {},
);

export const TEMPLATE_CATEGORIES: Record<TemplateCategory, { label: string; color: string }> = {
    customer_support: { label: 'Customer Support', color: 'blue' },
    rag: { label: 'RAG / Knowledge', color: 'grape' },
    content: { label: 'Content', color: 'pink' },
    code: { label: 'Code', color: 'teal' },
    research: { label: 'Research', color: 'orange' },
    productivity: { label: 'Productivity', color: 'cyan' },
};

export function listTemplates(): WorkflowTemplate[] {
    return TEMPLATES;
}

export function getTemplate(id: string): WorkflowTemplate | undefined {
    return TEMPLATES_BY_ID[id];
}
