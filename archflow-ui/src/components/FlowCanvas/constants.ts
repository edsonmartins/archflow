// ── Categorias com cores e metadados ────────────────────────────
// Colors aligned with ArchFlow_Workflow_v2.html CSS variables
export const NODE_CATEGORIES = {
  agent: {
    label:      'AI / Agents',
    color:      '#2563EB',
    colorLight: '#DBEAFE',
    colorDark:  '#1D4ED8',
    border:     '#2563EB',
  },
  control: {
    label:      'Control flow',
    color:      '#7C3AED',
    colorLight: '#EDE9FE',
    colorDark:  '#6D28D9',
    border:     '#7C3AED',
  },
  data: {
    label:      'Data',
    color:      '#0D9488',
    colorLight: '#CCFBF1',
    colorDark:  '#0F766E',
    border:     '#0D9488',
  },
  tool: {
    label:      'Tools / Functions',
    color:      '#DC4E26',
    colorLight: '#FEE2E2',
    colorDark:  '#B91C1C',
    border:     '#DC4E26',
  },
  vector: {
    label:      'Vector / Memory',
    color:      '#D97706',
    colorLight: '#FEF3C7',
    colorDark:  '#B45309',
    border:     '#D97706',
  },
  io: {
    label:      'I/O',
    color:      '#6B7280',
    colorLight: '#F3F4F6',
    colorDark:  '#4B5563',
    border:     '#6B7280',
  },
  knowledge: {
    label:      'Knowledge',
    color:      '#8B5CF6',
    colorLight: '#F5F3FF',
    colorDark:  '#7C3AED',
    border:     '#8B5CF6',
  },
  integration: {
    label:      'Integration',
    color:      '#059669',
    colorLight: '#D1FAE5',
    colorDark:  '#047857',
    border:     '#059669',
  },
  annotation: {
    label:      'Annotations',
    color:      '#D97706',
    colorLight: '#FEF3C7',
    colorDark:  '#B45309',
    border:     '#D97706',
  },
} as const

// ── Mapeamento de componentId → categoria ────────────────────────
// (usado pelo NodeRegistry do ArchFlow para categorizar os 23 tipos)
export const NODE_TYPE_TO_CATEGORY: Record<string, keyof typeof NODE_CATEGORIES> = {
  'agent':            'agent',
  'assistant':        'agent',
  'llm-chat':         'agent',
  'llm-streaming':    'agent',
  'orchestrate':      'agent',
  'condition':        'control',
  'parallel':         'control',
  'loop':             'control',
  'switch':           'control',
  'transform':        'data',
  'map':              'data',
  'filter':           'data',
  'reduce':           'data',
  'merge':            'data',
  'tool':             'tool',
  'function':         'tool',
  'custom':           'tool',
  'vector-search':    'vector',
  'vector-store':     'vector',
  'embedding':        'vector',
  'rag':              'vector',
  'memory':           'vector',
  'prompt-template':  'io',
  'prompt-chunk':     'io',
  'input':            'io',
  'output':           'io',
  // Knowledge / HITL / orchestration (backend already supports these)
  'skills':           'knowledge',
  'approval':         'control',
  'decision':         'control',
  'subflow':          'control',
  'mcp-tool':         'tool',
  // External messaging (Linktor)
  'linktor-send':     'integration',
  'linktor-escalate': 'integration',
  // Auxiliary canvas annotations
  'sticky-note':      'annotation',
  'group-frame':      'annotation',
  'section-divider':  'annotation',
}

/**
 * Anotações do canvas: só visuais. Ficam em steps[] para sobreviver a salvar/reabrir, mas o motor as
 * ignora (CanvasAnnotation.TYPES no archflow-model — um teste de espelho compara as duas listas).
 */
export const ANNOTATION_COMPONENT_IDS: ReadonlySet<string> = new Set(
  Object.entries(NODE_TYPE_TO_CATEGORY).filter(([, category]) => category === 'annotation').map(([id]) => id),
)

/** O passo é anotação pelo componentId ou pelo type (o designer grava o type em maiúsculas). */
export function isAnnotationStep(step: { componentId?: unknown; type?: unknown } | null | undefined): boolean {
  if (!step) return false
  return [step.componentId, step.type].some(
    k => typeof k === 'string' && ANNOTATION_COMPONENT_IDS.has(k.trim().toLowerCase()),
  )
}

/**
 * Tipos da paleta que o motor ainda não executa (RCHFLW-11). O nó continua podendo ser adicionado e salvo —
 * fluxos que já os têm não quebram —, mas a paleta e o nó dizem "ainda não suportado na execução", o
 * Executar do editor é impedido nomeando os nós e o copiloto não os cria. No servidor a execução é recusada
 * no início, antes de qualquer passo.
 *
 * <p>Espelho de NodesWithoutExecution.TYPES no archflow-api: o NodesWithoutExecutionMirrorTest compara as duas
 * listas e confere no factory de verdade que estes não executam e os outros tipos da paleta executam. Quando
 * um tipo ganhar execução, sai daqui e de lá.</p>
 *
 * <p>A lista decide sozinha, nos dois lados: o designer não conhece os plugins carregados no servidor, então o
 * motor também não isenta um componente do catálogo com um destes ids — são ids reservados para plugins.</p>
 */
export const NOT_EXECUTABLE_NODE_TYPES: ReadonlySet<string> = new Set([
  'agent', 'assistant',
  'parallel', 'loop', 'subflow',
  'transform', 'map', 'filter', 'reduce', 'merge',
  'tool', 'function', 'custom', 'mcp-tool',
  'prompt-template', 'prompt-chunk',
  'skills', 'linktor-send', 'linktor-escalate',
])

/**
 * O nó é de um tipo sem execução. Vale o componentId e, sem ele, o type — a mesma regra do motor
 * (DefaultFlowStepFactory.kindOf): um passo escrito à mão com type "agent" e componentId de um plugin roda.
 */
export function isNotExecutableNode(node: { componentId?: unknown; nodeType?: unknown; type?: unknown }): boolean {
  const raw = [node.componentId, node.nodeType, node.type].find(k => typeof k === 'string' && k.trim() !== '')
  return typeof raw === 'string' && NOT_EXECUTABLE_NODE_TYPES.has(raw.trim().toLowerCase())
}

// ── Palette completa (23 tipos do NodeRegistry) ──────────────────
export const PALETTE_NODES = [
  // AI / Agents
  { componentId: 'agent',         label: 'Agent',          description: 'Autonomous AI agent',     category: 'agent'   as const },
  { componentId: 'assistant',     label: 'Assistant',      description: 'Interactive assistant',    category: 'agent'   as const },
  { componentId: 'llm-chat',      label: 'LLM Chat',       description: 'Language model call',      category: 'agent'   as const },
  // Sem 'llm-streaming': ele executa como o LLM Chat, sem streaming (RCHFLW-57). Fluxos que já o têm continuam
  // rodando (alias no AdapterNodeTypes) e o painel diz isso; só deixa de ser oferecido.
  { componentId: 'orchestrate',   label: 'Orchestrate',    description: 'Dynamic multi-agent workflow', category: 'agent' as const },
  // Control flow
  { componentId: 'condition',     label: 'Condition',      description: 'Branch on condition',      category: 'control' as const },
  { componentId: 'parallel',      label: 'Parallel',       description: 'Run branches in parallel', category: 'control' as const },
  { componentId: 'loop',          label: 'Loop',           description: 'Iterate over items',       category: 'control' as const },
  { componentId: 'switch',        label: 'Switch',         description: 'Multi-branch switch',      category: 'control' as const },
  // Data
  { componentId: 'transform',     label: 'Transform',      description: 'Map and reshape data',     category: 'data'    as const },
  { componentId: 'map',           label: 'Map',            description: 'Map over collection',      category: 'data'    as const },
  { componentId: 'filter',        label: 'Filter',         description: 'Filter by condition',      category: 'data'    as const },
  { componentId: 'reduce',        label: 'Reduce',         description: 'Reduce to single value',   category: 'data'    as const },
  { componentId: 'merge',         label: 'Merge',          description: 'Merge multiple inputs',    category: 'data'    as const },
  // Tools
  { componentId: 'tool',          label: 'Tool',           description: 'Execute a tool call',      category: 'tool'    as const },
  { componentId: 'function',      label: 'Function',       description: 'Custom code function',     category: 'tool'    as const },
  { componentId: 'custom',        label: 'Custom',         description: 'Custom component',         category: 'tool'    as const },
  // Vector / Memory
  { componentId: 'vector-search', label: 'Vector search',  description: 'Semantic retrieval',       category: 'vector'  as const },
  { componentId: 'vector-store',  label: 'Vector store',   description: 'Store embeddings',         category: 'vector'  as const },
  { componentId: 'embedding',     label: 'Embedding',      description: 'Generate embeddings',      category: 'vector'  as const },
  { componentId: 'rag',           label: 'RAG chain',      description: 'Retrieval-augmented generation', category: 'vector' as const },
  { componentId: 'memory',        label: 'Memory',         description: 'Conversational memory store', category: 'vector' as const },
  // I/O
  { componentId: 'prompt-template', label: 'Prompt template', description: 'Parameterized prompt', category: 'io'      as const },
  { componentId: 'prompt-chunk',  label: 'Prompt chunk',   description: 'Partial prompt block',     category: 'io'      as const },
  { componentId: 'input',         label: 'Input',          description: 'Workflow entry point',     category: 'io'      as const },
  { componentId: 'output',        label: 'Output',         description: 'Workflow exit point',      category: 'io'      as const },
  // Knowledge / orchestration / HITL (backend-backed)
  { componentId: 'skills',        label: 'Skills',         description: 'Activate and read playbook skills',  category: 'knowledge'   as const },
  { componentId: 'approval',      label: 'Human approval', description: 'Pause for a human decision (HITL)',  category: 'control'     as const },
  { componentId: 'decision',      label: 'Decision',       description: 'Typed decision with confidence and route', category: 'control' as const },
  { componentId: 'subflow',       label: 'Subflow',        description: 'Invoke another workflow as a step',  category: 'control'     as const },
  { componentId: 'mcp-tool',      label: 'MCP tool',       description: 'Call a tool on a registered MCP server', category: 'tool'    as const },
  // External messaging (Linktor)
  { componentId: 'linktor-send',     label: 'Linktor send',     description: 'Post a message to a Linktor conversation', category: 'integration' as const },
  { componentId: 'linktor-escalate', label: 'Linktor escalate', description: 'Hand a conversation off to a human agent', category: 'integration' as const },
  // Auxiliary canvas annotations (n8n-style)
  { componentId: 'sticky-note',      label: 'Sticky note',      description: 'Yellow comment / annotation card',         category: 'annotation' as const },
  { componentId: 'group-frame',      label: 'Group frame',      description: 'Bounding box that visually groups nodes',   category: 'annotation' as const },
  { componentId: 'section-divider',  label: 'Section divider',  description: 'Labeled horizontal divider',                category: 'annotation' as const },
] as const

// ── Status de execução ───────────────────────────────────────────
// Semantic tokens from App.css — light AND dark variants are defined
// there, so the badges stay readable in both color schemes.
export const EXECUTION_STATUS_COLORS = {
  idle:    { border: 'var(--border2)', bg: 'transparent',    text: 'var(--text4)' },
  running: { border: 'var(--amber)',   bg: 'var(--amber-l)', text: 'var(--amber-text)' },
  success: { border: 'var(--green)',   bg: 'var(--green-l)', text: 'var(--green-text)' },
  error:   { border: 'var(--red)',     bg: 'var(--red-l)',   text: 'var(--red-text)' },
  skipped: { border: 'var(--gray)',    bg: 'var(--gray-l)',  text: 'var(--text3)' },
  // Portão de aprovação à espera de uma pessoa: nem concluído (verde) nem rodando (âmbar).
  awaiting: { border: 'var(--purple)', bg: 'var(--purple-l)', text: 'var(--purple-text)' },
} as const
