package br.com.archflow.langchain4j.provider;

import java.util.Map;
import java.util.Optional;

/**
 * SPI para traduzir um <b>tier</b> de LLM em um modelo concreto, por tenant.
 *
 * <p>Um tier ({@code LIGHT}, {@code STRONG}, …) é uma decisão de <i>política</i>:
 * quem aciona o agente sabe se a tarefa é barata ou difícil, e não deveria
 * precisar conhecer o nome do modelo de um provedor para dizer isso. Traduzir
 * tier em modelo é decisão de <i>infraestrutura</i>, e muda quando o catálogo
 * do provedor muda — sem que a política precise mudar junto.
 *
 * <p>Irmão do {@link TenantKeyResolver}, e pelo mesmo motivo: o archflow não
 * guarda nem chaves nem catálogo de modelos por tenant. Cada produto implementa
 * sobre o próprio storage (governança, tabela de config, arquivo).
 *
 * <p><b>Vazio é resposta legítima.</b> Um tenant sem mapa cai no comportamento
 * anterior — o modelo vem da cadeia de patches. O que não pode acontecer é
 * cair em silêncio: quem pediu um tier e não o recebeu precisa aparecer no log.
 * Ver {@link DefaultLLMConfigResolver}.
 *
 * @since 1.2.0
 */
@FunctionalInterface
public interface TierModelResolver {

    /**
     * O modelo para um (tenant, tier).
     *
     * @param tenantId tenant atual; pode ser {@code null} em contexto global
     * @param tier     identificador do tier, como veio de quem acionou
     *                 (ex.: {@code "LIGHT"}, {@code "STRONG"})
     * @return o id do modelo, ou {@link Optional#empty()} quando não há mapa
     *         para este tenant/tier
     */
    Optional<String> resolveModel(String tenantId, String tier);

    /** Resolver que nunca mapeia nada (default — produto sobrepõe). */
    TierModelResolver NOOP = (tenantId, tier) -> Optional.empty();

    /**
     * Mapa fixo {@code tier → modelo}, igual para todos os tenants.
     *
     * <p>Serve para deployment de tenant único e para teste. Um produto
     * multi-tenant implementa a interface sobre o próprio storage — este atalho
     * ignora o {@code tenantId} de propósito, e é por isso que ele não serve
     * para multi-tenant.
     *
     * <p>A comparação do tier é <b>case-insensitive</b>: quem aciona escreve
     * {@code "STRONG"} ou {@code "strong"} conforme a serialização do lado dele,
     * e uma política silenciosamente não aplicada por causa de caixa seria
     * exatamente o defeito que este mecanismo existe para eliminar.
     */
    static TierModelResolver fixed(Map<String, String> tierToModel) {
        Map<String, String> normalizado = tierToModel == null ? Map.of()
                : tierToModel.entrySet().stream()
                        .filter(e -> e.getKey() != null && e.getValue() != null)
                        .collect(java.util.stream.Collectors.toUnmodifiableMap(
                                e -> e.getKey().trim().toUpperCase(java.util.Locale.ROOT),
                                Map.Entry::getValue));
        return (tenantId, tier) -> tier == null || tier.isBlank()
                ? Optional.empty()
                : Optional.ofNullable(normalizado.get(tier.trim().toUpperCase(java.util.Locale.ROOT)));
    }
}
