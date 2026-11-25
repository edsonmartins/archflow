import { useEffect, useState } from 'react'
import { catalogApi, type CatalogItem } from '../../services/catalog-api'

/**
 * Providers que têm adapter de chat registrado no servidor (GET /api/catalog/chat-providers, lido do
 * LangChainRegistry). É o que um nó llm-chat / llm-streaming consegue executar: a lista de
 * /workflow/providers vale para os agentes (provider-hub, 16 providers), não para os nós de chat — e
 * oferecê-la inteira deixava escolher Gemini/Ollama, que falhavam só na execução (RCHFLW-43).
 *
 * `null` enquanto carrega ou quando o servidor não responde: aí o painel não filtra, em vez de
 * inventar uma lista fixa que pode não ser a do servidor.
 */
let cache: Promise<CatalogItem[] | null> | null = null

function fetchChatProviders(): Promise<CatalogItem[] | null> {
  if (!cache) {
    cache = catalogApi.listProviders().catch(() => null)
  }
  return cache
}

/** Zera o cache de módulo (testes, hot-reload). */
export function resetChatProvidersCache() {
  cache = null
}

export function useChatProviders(): CatalogItem[] | null {
  const [items, setItems] = useState<CatalogItem[] | null>(null)
  useEffect(() => {
    let cancelled = false
    fetchChatProviders().then(list => {
      if (!cancelled) setItems(Array.isArray(list) ? list : null)
    })
    return () => { cancelled = true }
  }, [])
  return items
}
