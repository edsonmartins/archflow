package br.com.archflow.api.catalog;

import br.com.archflow.api.catalog.dto.CatalogItemDto;

import java.util.List;

/**
 * Catálogo agregado de componentes disponíveis para montar um workflow.
 *
 * <p>Existe para que o frontend possa popular dropdowns e paleta do
 * editor sem hardcode. A fonte de verdade é {@code ComponentCatalog}
 * (agents/assistants/tools registrados via plugin) combinada com
 * {@code LangChainRegistry.getProvidersOfType} (embedding/memory/
 * vectorstore/chain/chat provider descobertos via SPI).
 *
 * <p>Cada endpoint devolve {@link CatalogItemDto} uniforme; o campo
 * {@code kind} discrimina a categoria.
 */
public interface CatalogController {

    List<CatalogItemDto> listAgents();

    List<CatalogItemDto> listAssistants();

    List<CatalogItemDto> listTools();

    /** LLM chat providers descobertos via SPI + ProviderHub. */
    List<CatalogItemDto> listChatProviders();

    List<CatalogItemDto> listEmbeddings();

    List<CatalogItemDto> listMemories();

    List<CatalogItemDto> listVectorStores();

    List<CatalogItemDto> listChains();

    /** Retorna tudo agrupado por {@code kind} — útil no boot do editor. */
    List<CatalogItemDto> listAll();
}
