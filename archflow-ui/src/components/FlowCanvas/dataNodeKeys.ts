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
 * .DEFAULT_OPERATION_BY_NODE_TYPE; DesignerAdapterKeysConformanceTest confere os dois lados). Sem 'operation' no nó —
 * que o painel ainda não oferece —, a execução recusa: o Vector store porque nenhum adapter grava vetores (dar-lhe
 * "search" fazia o "Armazena embeddings" buscar e voltar com sucesso), a Memória porque guardar, ler e limpar são
 * escolhas do autor. O Executar exige o campo, e o copiloto não cria esses nós.
 */
export const NODE_TYPES_WITHOUT_DEFAULT_OPERATION: ReadonlySet<string> = new Set(['memory', 'vector-store'])

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
