// ── Categorias com cores e metadados ────────────────────────────
export const NODE_CATEGORIES = {
  agent: {
    label:      'AI / Agents',
    color:      '#378ADD',
    colorLight: '#E6F1FB',
    colorDark:  '#0C447C',
    border:     '#185FA5',
  },
  control: {
    label:      'Control flow',
    color:      '#7F77DD',
    colorLight: '#EEEDFE',
    colorDark:  '#3C3489',
    border:     '#534AB7',
  },
  data: {
    label:      'Data',
    color:      '#1D9E75',
    colorLight: '#E1F5EE',
    colorDark:  '#085041',
    border:     '#0F6E56',
  },
  tool: {
    label:      'Tools / Functions',
    color:      '#D85A30',
    colorLight: '#FAECE7',
    colorDark:  '#712B13',
    border:     '#993C1D',
  },
  vector: {
    label:      'Vector / Memory',
    color:      '#BA7517',
    colorLight: '#FAEEDA',
    colorDark:  '#633806',
    border:     '#854F0B',
  },
  io: {
    label:      'I/O',
    color:      '#888780',
    colorLight: '#F1EFE8',
    colorDark:  '#444441',
    border:     '#5F5E5A',
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
  'prompt-template':  'io',
  'prompt-chunk':     'io',
  'input':            'io',
  'output':           'io',
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
  // I/O
  { componentId: 'prompt-template', label: 'Prompt template', description: 'Parameterized prompt', category: 'io'      as const, icon: '✎' },
  { componentId: 'prompt-chunk',  label: 'Prompt chunk',   description: 'Partial prompt block',     category: 'io'      as const, icon: '¶' },
  { componentId: 'input',         label: 'Input',          description: 'Workflow entry point',     category: 'io'      as const, icon: '→' },
  { componentId: 'output',        label: 'Output',         description: 'Workflow exit point',      category: 'io'      as const, icon: '←' },
] as const

// ── Status de execução ───────────────────────────────────────────
export const EXECUTION_STATUS_COLORS = {
  idle:    { border: 'var(--color-border-secondary)', bg: 'transparent',                    text: 'var(--color-text-tertiary)'  },
  running: { border: '#BA7517',                        bg: '#FAEEDA',                        text: '#854F0B'                     },
  success: { border: '#0F6E56',                        bg: '#E1F5EE',                        text: '#085041'                     },
  error:   { border: '#993C1D',                        bg: '#FAECE7',                        text: '#712B13'                     },
} as const
