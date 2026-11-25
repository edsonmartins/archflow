package br.com.archflow.plugin.api.catalog;

import br.com.archflow.model.ai.metadata.ComponentMetadata;
import br.com.archflow.model.ai.type.ComponentType;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Roteamento por pontuação determinística sobre os metadados do catálogo.
 *
 * <p>Sinais (do mais forte ao mais fraco): keywords {@literal >} capabilities
 * {@literal >} tags {@literal >} texto (nome/descrição). O score final é o maior
 * sinal ponderado, em [0.0, 1.0]. Determinístico e testável — sem ML.
 *
 * <p>A query e os metadados passam pelo mesmo {@link #tokenize(String)}, e é só nele que se decide o
 * que é um token (RCHFLW-109): minúsculas, acentos dobrados antes de quebrar em tokens, tokens de 1
 * caractere e {@linkplain #FUNCTION_WORDS palavras funcionais} descartados. Sem a dobra, "análise"
 * virava "an" e "lise", e nenhuma subtarefa em português casava com um termo acentuado. Sem descartar
 * as palavras funcionais, "de" e "com" casavam qualquer subtarefa em português com qualquer descrição
 * que as tivesse — era assim que o mcp-agent (descrição em português) ganhava quase todas, com scores
 * entre 0,05 e 0,2.</p>
 *
 * @since 1.0.0
 */
public final class DefaultComponentQueryRouter implements ComponentQueryRouter {

    private static final double W_CAPABILITY = 0.9;
    private static final double W_TAG = 0.7;
    private static final double W_TEXT = 0.6;

    /** Marcas combinantes que a decomposição NFD separa da letra ("á" → "a" + U+0301). */
    private static final Pattern COMBINING_MARKS = Pattern.compile("\\p{M}+");

    /**
     * Palavras funcionais (artigos, preposições e suas contrações, conjunções, pronomes e determinantes)
     * em pt-BR e em inglês, já sem acento — a forma que {@link #tokenize(String)} compara. Não dizem o que
     * uma subtarefa pede nem o que um componente faz; casando, só criam score entre textos que não têm
     * nada em comum. As em inglês entram pelo mesmo motivo ("the", "of" e "for" estão nas descrições dos
     * agentes embutidos) e não mudam o topo de nenhuma consulta em inglês dos testes. Tokens de 1
     * caractere ("a", "o", "e", "à", "é") já caem pelo tamanho. Lista fechada de propósito: não é um
     * filtro de frequência, e um termo de domínio ("dados", "custo", "total") nunca entra aqui.
     */
    static final Set<String> FUNCTION_WORDS = Set.of(
            // pt-BR: artigos, preposições e contrações
            "as", "os", "um", "uma", "uns", "umas", "de", "da", "do", "das", "dos", "em", "na", "no", "nas",
            "nos", "num", "numa", "ao", "aos", "pelo", "pela", "pelos", "pelas", "por", "para", "pra", "com",
            "sem", "entre", "sobre", "ate", "apos", "desde", "contra",
            // pt-BR: conjunções, pronomes e determinantes
            "ou", "mas", "nem", "que", "se", "como", "seu", "sua", "seus", "suas", "meu", "minha", "nosso",
            "nossa", "este", "esta", "estes", "estas", "esse", "essa", "esses", "essas", "isto", "isso",
            "aquele", "aquela", "aquilo", "qual", "quais", "cada", "ele", "ela", "eles", "elas", "lhe",
            // en: artigos, preposições, conjunções, pronomes e determinantes
            "an", "the", "of", "to", "in", "on", "at", "by", "for", "with", "from", "into", "about", "and",
            "or", "but", "nor", "is", "are", "be", "it", "its", "this", "that", "these", "those", "each",
            "my", "your", "our", "their", "via");

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

    /**
     * Minúsculas, acentos dobrados, split em não-alfanumérico; descarta tokens de 1 caractere e
     * {@link #FUNCTION_WORDS}. A dobra vem antes do split porque o split só conhece {@code [a-z0-9]}: uma
     * letra acentuada era separador.
     */
    static Set<String> tokenize(String s) {
        if (s == null || s.isBlank()) {
            return Set.of();
        }
        String folded = COMBINING_MARKS.matcher(Normalizer.normalize(s, Normalizer.Form.NFD)).replaceAll("");
        Set<String> out = new HashSet<>();
        for (String part : folded.toLowerCase(Locale.ROOT).split("[^a-z0-9]+")) {
            if (part.length() >= 2 && !FUNCTION_WORDS.contains(part)) {
                out.add(part);
            }
        }
        return out;
    }
}
