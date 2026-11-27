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
    /**
     * Tipos de nó deste template que o motor ainda não executa (a execução falharia neles com
     * "component not found"). Vazio = tudo roda; ausente = não verificado (sem backend).
     */
    unsupportedNodeTypes?: string[];
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
    // Refeito só com nós que executam (RCHFLW-202). O de antes passava por 'embedding' sem provider (cujo vetor
    // virava o texto da busca) e por 'prompt-template', que não tem executor e citava um template 'rag.qa' que não
    // existe: o fluxo era recusado antes do primeiro passo. O nó RAG busca na base e responde com a pergunta que
    // recebeu (KnowledgeNodeComponent), então a pergunta não se perde no caminho. A base fica para o usuário escolher
    // no nó: o id é do workspace dele, e inventar um daria "base não existe" na execução.
    // Por que não Busca vetorial → LLM Chat: sem base, o vector-search cai num componente que não existe, e o
    // POST /templates/unsupported-node-types marcava o template na galeria como "Nós sem executor", mandando
    // trocar o nó que só precisava da base. O RAG sem base tem executor (o RagChainAdapter), e o Executar do
    // editor pede a base (ou os providers) antes de rodar (runReadiness CONDITIONAL_REQUIRED_FIELDS.rag).
    {
        id: 'rag-doc-qa',
        name: 'Document Q&A (RAG)',
        icon: '📚',
        summary: 'Retrieval-augmented assistant that answers questions from your knowledge base.',
        description:
            'Receives a question, searches the knowledge base chosen on the Answer from knowledge base node and asks ' +
            'the LLM to answer from the retrieved excerpts. Choose the knowledge base on that node before running.',
        category: 'rag',
        tags: ['rag', 'knowledge-base'],
        complexity: 'starter',
        steps: [
            { id: 's1', type: 'input', componentId: 'input', label: 'Question', category: 'io', position: { x: 60, y: 200 }, config: {} },
            { id: 's2', type: 'rag', componentId: 'rag', label: 'Answer from knowledge base', category: 'vector', position: { x: 320, y: 200 }, config: { retrieverMaxResults: 6 } },
            { id: 's3', type: 'output', componentId: 'output', label: 'Answer', category: 'io', position: { x: 580, y: 200 }, config: {} },
        ],
        connections: [
            { id: 'c1', sourceId: 's1', targetId: 's2', isErrorPath: false },
            { id: 'c2', sourceId: 's2', targetId: 's3', isErrorPath: false },
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
            { id: 's5', type: 'llm-chat', componentId: 'llm-chat', label: 'LLM review', category: 'agent', position: { x: 740, y: 220 }, config: { rubric: 'code_review' } },
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
    // Refeito como cadeia de LLM Chat (RCHFLW-202): 'agent' e 'parallel' não têm executor, e a chave 'role' não era
    // lida por nada — com os tipos trocados, o "Outliner" escrevia o artigo inteiro e o "Editor" pedia o texto. Cada
    // passo diz o papel no systemPrompt e recebe o briefing (${s1}) junto com a saída do anterior pela userMessage;
    // sem isso só o primeiro passo via o briefing. A redação por seção em paralelo saiu da descrição: não há nó que a faça.
    {
        id: 'content-writer',
        name: 'Long-form content writer',
        icon: '✍️',
        summary: 'Outlines, drafts and polishes a long-form article from a brief.',
        description:
            'Three sequential LLM steps: an outliner turns the brief into a section structure, a drafter writes the ' +
            'full article from the brief and the outline, and an editor revises the draft for clarity, consistency ' +
            'and fidelity to the brief. Each step receives the original brief alongside the previous step\'s output. ' +
            'Useful for blog posts, newsletters and product descriptions.',
        category: 'content',
        tags: ['writing', 'long-form', 'prompt-chain'],
        complexity: 'intermediate',
        steps: [
            { id: 's1', type: 'input', componentId: 'input', label: 'Brief', category: 'io', position: { x: 60, y: 220 }, config: {} },
            {
                id: 's2', type: 'llm-chat', componentId: 'llm-chat', label: 'Outline', category: 'agent', position: { x: 300, y: 220 },
                config: {
                    systemPrompt:
                        'Você é o redator responsável pelo esboço de um artigo longo. A partir do briefing, escreva ' +
                        'somente a estrutura: título provisório e a lista de seções, cada uma com uma ou duas frases ' +
                        'sobre o que ela deve cobrir. Não escreva o artigo.',
                    userMessage: 'Briefing: ${s1}',
                },
            },
            {
                id: 's3', type: 'llm-chat', componentId: 'llm-chat', label: 'Draft', category: 'agent', position: { x: 540, y: 220 },
                config: {
                    systemPrompt:
                        'Você é o redator do primeiro rascunho. Escreva o artigo completo seguindo o esboço seção por ' +
                        'seção e respeitando o briefing (público, tom e extensão, quando o briefing os indicar). ' +
                        'Devolva apenas o texto do artigo.',
                    userMessage: 'Briefing: ${s1}\n\nEsboço: ${s2}',
                },
            },
            {
                id: 's4', type: 'llm-chat', componentId: 'llm-chat', label: 'Edit', category: 'agent', position: { x: 780, y: 220 },
                config: {
                    systemPrompt:
                        'Você é o editor final. Revise o rascunho: corrija erros, melhore a clareza e a fluidez, deixe ' +
                        'o tom consistente e confira se o texto cumpre o briefing. Devolva apenas a versão final do ' +
                        'artigo, sem comentários sobre as mudanças.',
                    userMessage: 'Briefing: ${s1}\n\nRascunho: ${s3}',
                },
            },
            { id: 's5', type: 'output', componentId: 'output', label: 'Article', category: 'io', position: { x: 1020, y: 220 }, config: {} },
        ],
        connections: [
            { id: 'c1', sourceId: 's1', targetId: 's2', isErrorPath: false },
            { id: 'c2', sourceId: 's2', targetId: 's3', isErrorPath: false },
            { id: 'c3', sourceId: 's3', targetId: 's4', isErrorPath: false },
            { id: 'c4', sourceId: 's4', targetId: 's5', isErrorPath: false },
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
            { id: 's5', type: 'llm-chat', componentId: 'llm-chat', label: 'Compile report', category: 'agent', position: { x: 1000, y: 220 }, config: { format: 'markdown' } },
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
