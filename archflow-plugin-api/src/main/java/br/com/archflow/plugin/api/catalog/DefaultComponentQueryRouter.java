package br.com.archflow.plugin.api.catalog;

import br.com.archflow.model.ai.metadata.ComponentMetadata;
import br.com.archflow.model.ai.type.ComponentType;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Roteamento por pontuação determinística sobre os metadados do catálogo.
 *
 * <p>Sinais (do mais forte ao mais fraco): keywords {@literal >} capabilities
 * {@literal >} tags {@literal >} texto (nome/descrição). O score final é o maior
 * sinal ponderado, em [0.0, 1.0]. Determinístico e testável — sem ML.
 *
 * @since 1.0.0
 */
public final class DefaultComponentQueryRouter implements ComponentQueryRouter {

    private static final double W_CAPABILITY = 0.9;
    private static final double W_TAG = 0.7;
    private static final double W_TEXT = 0.6;

    private final ComponentCatalog catalog;

    public DefaultComponentQueryRouter(ComponentCatalog catalog) {
        this.catalog = catalog;
    }

    @Override
    public Optional<ScoredComponent> route(String query) {
        return route(query, null);
    }

    @Override
    public Optional<ScoredComponent> route(String query, ComponentType type) {
        return rank(query, type).stream().findFirst();
    }

    @Override
    public List<ScoredComponent> rank(String query) {
        return rank(query, null);
    }

    @Override
    public List<ScoredComponent> rank(String query, ComponentType type) {
        Set<String> queryTokens = tokenize(query);
        if (queryTokens.isEmpty()) {
            return List.of();
        }
        List<ScoredComponent> scored = new ArrayList<>();
        for (ComponentMetadata md : catalog.listComponents()) {
            if (type != null && md.type() != type) {
                continue;
            }
            double score = score(queryTokens, md);
            if (score > 0.0) {
                scored.add(new ScoredComponent(md.id(), md, score));
            }
        }
        // Maior score primeiro; desempate estável por id.
        scored.sort(Comparator.comparingDouble(ScoredComponent::score).reversed()
                .thenComparing(ScoredComponent::componentId));
        return List.copyOf(scored);
    }

    /** Score de um componente: melhor sinal ponderado, saturado em 1.0. */
    static double score(Set<String> queryTokens, ComponentMetadata md) {
        double kw = containedRatio(queryTokens, md.keywords());
        double cap = containedRatio(queryTokens, md.capabilities());
        double tag = containedRatio(queryTokens, md.tags());
        double txt = tokenOverlap(queryTokens, md.name() + " " + md.description());
        double best = Math.max(
                Math.max(kw, cap * W_CAPABILITY),
                Math.max(tag * W_TAG, txt * W_TEXT));
        return Math.min(1.0, best);
    }

    /** Fração das entradas do conjunto cujas palavras aparecem na query. */
    static double containedRatio(Set<String> queryTokens, Set<String> entries) {
        if (entries == null || entries.isEmpty()) {
            return 0.0;
        }
        int matched = 0;
        for (String entry : entries) {
            Set<String> entryTokens = tokenize(entry);
            if (!entryTokens.isEmpty() && entryTokens.stream().anyMatch(queryTokens::contains)) {
                matched++;
            }
        }
        return (double) matched / entries.size();
    }

    /** Fração dos tokens da query presentes no texto. */
    static double tokenOverlap(Set<String> queryTokens, String text) {
        Set<String> textTokens = tokenize(text);
        if (textTokens.isEmpty()) {
            return 0.0;
        }
        long matched = queryTokens.stream().filter(textTokens::contains).count();
        return (double) matched / queryTokens.size();
    }

    /** Minúsculas, split em não-alfanumérico, descarta tokens de 1 caractere. */
    static Set<String> tokenize(String s) {
        if (s == null || s.isBlank()) {
            return Set.of();
        }
        Set<String> out = new HashSet<>();
        for (String part : s.toLowerCase(Locale.ROOT).split("[^a-z0-9]+")) {
            if (part.length() >= 2) {
                out.add(part);
            }
        }
        return out;
    }
}
