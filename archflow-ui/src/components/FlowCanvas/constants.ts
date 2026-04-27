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

// ── Palette completa (23 tipos do NodeRegistry) ──────────────────
export const PALETTE_NODES = [
  // AI / Agents
  { componentId: 'agent',         label: 'Agent',          description: 'Autonomous AI agent',     category: 'agent'   as const, icon: '🤖' },
  { componentId: 'assistant',     label: 'Assistant',      description: 'Interactive assistant',    category: 'agent'   as const, icon: '🧠' },
  { componentId: 'llm-chat',      label: 'LLM Chat',       description: 'Language model call',      category: 'agent'   as const, icon: '💬' },
  { componentId: 'llm-streaming', label: 'LLM Streaming',  description: 'Streaming LLM response',  category: 'agent'   as const, icon: '⚡' },
  // Control flow
  { componentId: 'condition',     label: 'Condition',      description: 'Branch on condition',      category: 'control' as const, icon: '⑂' },
  { componentId: 'parallel',      label: 'Parallel',       description: 'Run branches in parallel', category: 'control' as const, icon: '⫶' },
  { componentId: 'loop',          label: 'Loop',           description: 'Iterate over items',       category: 'control' as const, icon: '↻' },
  { componentId: 'switch',        label: 'Switch',         description: 'Multi-branch switch',      category: 'control' as const, icon: '⋮' },
  // Data
  { componentId: 'transform',     label: 'Transform',      description: 'Map and reshape data',     category: 'data'    as const, icon: '⇄' },
  { componentId: 'map',           label: 'Map',            description: 'Map over collection',      category: 'data'    as const, icon: '⊞' },
  { componentId: 'filter',        label: 'Filter',         description: 'Filter by condition',      category: 'data'    as const, icon: '▽' },
  { componentId: 'reduce',        label: 'Reduce',         description: 'Reduce to single value',   category: 'data'    as const, icon: '⊃' },
  { componentId: 'merge',         label: 'Merge',          description: 'Merge multiple inputs',    category: 'data'    as const, icon: '⊕' },
  // Tools
  { componentId: 'tool',          label: 'Tool',           description: 'Execute a tool call',      category: 'tool'    as const, icon: '⚙' },
  { componentId: 'function',      label: 'Function',       description: 'Custom code function',     category: 'tool'    as const, icon: '⟨⟩' },
  { componentId: 'custom',        label: 'Custom',         description: 'Custom component',         category: 'tool'    as const, icon: '✦' },
  // Vector / Memory
  { componentId: 'vector-search', label: 'Vector search',  description: 'Semantic retrieval',       category: 'vector'  as const, icon: '◎' },
  { componentId: 'vector-store',  label: 'Vector store',   description: 'Store embeddings',         category: 'vector'  as const, icon: '⊟' },
  { componentId: 'embedding',     label: 'Embedding',      description: 'Generate embeddings',      category: 'vector'  as const, icon: '∿' },
  { componentId: 'rag',           label: 'RAG chain',      description: 'Retrieval-augmented generation', category: 'vector' as const, icon: '📚' },
  { componentId: 'memory',        label: 'Memory',         description: 'Conversational memory store', category: 'vector' as const, icon: '🧠' },
  // I/O
  { componentId: 'prompt-template', label: 'Prompt template', description: 'Parameterized prompt', category: 'io'      as const, icon: '✎' },
  { componentId: 'prompt-chunk',  label: 'Prompt chunk',   description: 'Partial prompt block',     category: 'io'      as const, icon: '¶' },
  { componentId: 'input',         label: 'Input',          description: 'Workflow entry point',     category: 'io'      as const, icon: '→' },
  { componentId: 'output',        label: 'Output',         description: 'Workflow exit point',      category: 'io'      as const, icon: '←' },
  // Knowledge / orchestration / HITL (backend-backed)
  { componentId: 'skills',        label: 'Skills',         description: 'Activate and read playbook skills',  category: 'knowledge'   as const, icon: '📖' },
  { componentId: 'approval',      label: 'Human approval', description: 'Pause for a human decision (HITL)',  category: 'control'     as const, icon: '✋' },
  { componentId: 'subflow',       label: 'Subflow',        description: 'Invoke another workflow as a step',  category: 'control'     as const, icon: '⇲' },
  { componentId: 'mcp-tool',      label: 'MCP tool',       description: 'Call a tool on a registered MCP server', category: 'tool'    as const, icon: '🔌' },
  // External messaging (Linktor)
  { componentId: 'linktor-send',     label: 'Linktor send',     description: 'Post a message to a Linktor conversation', category: 'integration' as const, icon: '✉' },
  { componentId: 'linktor-escalate', label: 'Linktor escalate', description: 'Hand a conversation off to a human agent', category: 'integration' as const, icon: '🛎' },
  // Auxiliary canvas annotations (n8n-style)
  { componentId: 'sticky-note',      label: 'Sticky note',      description: 'Yellow comment / annotation card',         category: 'annotation' as const, icon: '✎' },
  { componentId: 'group-frame',      label: 'Group frame',      description: 'Bounding box that visually groups nodes',   category: 'annotation' as const, icon: '▣' },
  { componentId: 'section-divider',  label: 'Section divider',  description: 'Labeled horizontal divider',                category: 'annotation' as const, icon: '─' },
] as const

// ── Status de execução ───────────────────────────────────────────
// Aligned with HTML reference exec-badge colors
export const EXECUTION_STATUS_COLORS = {
  idle:    { border: 'var(--border2)',  bg: 'transparent',  text: 'var(--text4)' },
  running: { border: '#D97706',        bg: '#FFFBEB',       text: '#92400E'      },
  success: { border: '#059669',        bg: '#ECFDF5',       text: '#065F46'      },
  error:   { border: '#DC2626',        bg: '#FEF2F2',       text: '#991B1B'      },
} as const
