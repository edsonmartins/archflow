/**
 * Chaves dos nós de dados que o painel, o canvas e o Executar leem juntos (RCHFLW-55, RCHFLW-56). Módulo puro: o
 * card do canvas não pode puxar a API de conhecimento só para saber se o nó tem uma base.
 */

/**
 * A chave que o motor lê (KnowledgeNodeComponent.BASE_KEY). Os templates antigos gravavam vectorStoreId, que o motor
 * ainda aceita nos fluxos já instalados.
 */
export const KNOWLEDGE_BASE_KEY = 'knowledgeBaseId'
export const LEGACY_KNOWLEDGE_BASE_KEY = 'vectorStoreId'

/** Os tipos de nó que aceitam uma base (KnowledgeNodeComponent.NODE_TYPES). */
export const KNOWLEDGE_NODE_TYPES: ReadonlySet<string> = new Set(['vector-search', 'rag'])

/** A base que o nó escolheu (a chave atual, senão a dos templates antigos), ou null. */
export function knowledgeBaseOf(config: Record<string, unknown> | undefined): string | null {
  for (const key of [KNOWLEDGE_BASE_KEY, LEGACY_KNOWLEDGE_BASE_KEY]) {
    const v = config?.[key]
    if (typeof v === 'string' && v.trim() !== '') return v.trim()
  }
  return null
}

/**
 * Tipos de nó de dados sem operação padrão no motor (o complemento de LangChainAdapterComponent
 * .DEFAULT_OPERATION_BY_NODE_TYPE; DesignerAdapterKeysConformanceTest confere os dois lados). Sem 'operation' no nó,
 * a execução recusa: o Vector store porque nenhum adapter grava vetores (dar-lhe "search" fazia o "Armazena
 * embeddings" buscar e voltar com sucesso), a Memória porque guardar, ler e limpar são escolhas do autor. O Executar
 * exige o campo; o copiloto só cria os que têm a Operação no painel ({@link PANEL_OPERATIONS}).
 */
export const NODE_TYPES_WITHOUT_DEFAULT_OPERATION: ReadonlySet<string> = new Set(['memory', 'vector-store'])

/**
 * As operações dos adapters de memória (LangChainAdapterComponent.MEMORY_OPERATIONS — RedisMemoryAdapter e
 * JdbcMemoryAdapter aceitam estas e nenhuma outra): as opções do campo Operação do nó Memória (RCHFLW-115).
 */
export const MEMORY_OPERATIONS = ['add', 'get', 'clear'] as const

/**
 * De quem é a mensagem que o add guarda (LangChainAdapterComponent.MEMORY_ROLES). Os adapters só aceitam um
 * ChatMessage, e a entrada de um passo é texto ou JSON: sem o papel, o motor não tem mensagem a montar.
 */
export const MEMORY_ROLES = ['user', 'ai', 'system'] as const

/** Onde o motor lê o papel quando o nó é escrito à mão (DesignerAdapterKeys: memoryRole → memory.role). */
export const MEMORY_ROLE_NATIVE_KEY = 'memory.role'

/** Os tipos sem operação padrão que já têm o campo Operação no painel, com as opções que ele oferece. */
export const PANEL_OPERATIONS: Readonly<Record<string, readonly string[]>> = { memory: MEMORY_OPERATIONS }

/**
 * Campo do nó onde se escolhe a conexão do servidor, por backend (DesignerAdapterKeys.CONNECTION_FIELD_BY_PROVIDER
 * no archflow-api). O nó guarda só o NOME; URL, usuário, senha e chave ficam no servidor, em
 * archflow.adapters.<backend>.<nome>.* — o painel tinha campos de URL do Redis e chave do Pinecone que gravavam a
 * credencial no JSON do fluxo, e o motor nem os lia.
 */
export const CONNECTION_FIELD_BY_BACKEND: Readonly<Record<string, string>> = {
  pgvector: 'pgDatasource',
  redis: 'redisConnection',
  pinecone: 'pineconeConnection',
}

/** Onde o servidor declara a conexão de um backend — o texto da dica do campo. */
export function serverConnectionPrefix(backend: string): string {
  return `archflow.adapters.${backend}.<nome>.*`
}
