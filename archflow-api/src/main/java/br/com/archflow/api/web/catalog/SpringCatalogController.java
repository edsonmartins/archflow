package br.com.archflow.api.web.catalog;

import br.com.archflow.api.catalog.CatalogController;
import br.com.archflow.api.catalog.dto.CatalogItemDto;
import br.com.archflow.api.catalog.dto.RoutedComponentDto;
import br.com.archflow.model.ai.type.ComponentType;
import br.com.archflow.plugin.api.catalog.ComponentQueryRouter;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Locale;

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
    private final ComponentQueryRouter router;

    public SpringCatalogController(CatalogController delegate, ComponentQueryRouter router) {
        this.delegate = delegate;
        this.router = router;
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

    /**
     * Roteamento por query em linguagem natural: devolve os componentes mais
     * adequados, pontuados pelo {@link ComponentQueryRouter}.
     *
     * @param query texto da query (obrigatório)
     * @param type  filtro opcional: "agent" | "assistant" | "tool"
     * @param limit máximo de resultados (default 5)
     */
    @GetMapping("/route")
    public List<RoutedComponentDto> route(
            @RequestParam("q") String query,
            @RequestParam(value = "type", required = false) String type,
            @RequestParam(value = "limit", required = false, defaultValue = "5") int limit) {
        ComponentType ct = parseType(type);
        return router.rank(query, ct).stream()
                .limit(Math.max(1, limit))
                .map(sc -> new RoutedComponentDto(
                        sc.componentId(),
                        sc.metadata().name() != null ? sc.metadata().name() : sc.componentId(),
                        sc.metadata().type() != null ? sc.metadata().type().name().toLowerCase(Locale.ROOT) : null,
                        sc.score(),
                        sc.metadata().capabilities() == null ? List.of() : List.copyOf(sc.metadata().capabilities()),
                        sc.metadata().keywords() == null ? List.of() : List.copyOf(sc.metadata().keywords())))
                .toList();
    }

    private static ComponentType parseType(String type) {
        if (type == null || type.isBlank()) {
            return null;
        }
        try {
            return ComponentType.valueOf(type.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
