package br.com.archflow.api.web.catalog;

import br.com.archflow.api.catalog.CatalogController;
import br.com.archflow.api.catalog.dto.CatalogItemDto;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * HTTP binding for {@link CatalogController}.
 *
 * <p>Exposes {@code /api/catalog/*} so the frontend can populate
 * PropertyPanel dropdowns and node palette without hardcoded arrays.
 */
@RestController
@RequestMapping("/api/catalog")
public class SpringCatalogController {

    private final CatalogController delegate;

    public SpringCatalogController(CatalogController delegate) {
        this.delegate = delegate;
    }

    @GetMapping("/agents")
    public List<CatalogItemDto> agents() { return delegate.listAgents(); }

    @GetMapping("/assistants")
    public List<CatalogItemDto> assistants() { return delegate.listAssistants(); }

    @GetMapping("/tools")
    public List<CatalogItemDto> tools() { return delegate.listTools(); }

    @GetMapping("/chat-providers")
    public List<CatalogItemDto> chatProviders() { return delegate.listChatProviders(); }

    @GetMapping("/embeddings")
    public List<CatalogItemDto> embeddings() { return delegate.listEmbeddings(); }

    @GetMapping("/memories")
    public List<CatalogItemDto> memories() { return delegate.listMemories(); }

    @GetMapping("/vectorstores")
    public List<CatalogItemDto> vectorStores() { return delegate.listVectorStores(); }

    @GetMapping("/chains")
    public List<CatalogItemDto> chains() { return delegate.listChains(); }

    @GetMapping
    public List<CatalogItemDto> all() { return delegate.listAll(); }
}
