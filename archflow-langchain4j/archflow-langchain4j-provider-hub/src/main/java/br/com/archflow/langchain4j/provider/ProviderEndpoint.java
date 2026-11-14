package br.com.archflow.langchain4j.provider;

import java.util.Objects;

/**
 * Onde e com que credencial o provider resolvido para uma requisição é chamado.
 *
 * <p>Existe para quem precisa falar com o <b>mesmo</b> provider fora do {@code ChatModel} — hoje, o
 * laço de agente buscando no OpenRouter o custo de uma geração que voltou sem {@code usage.cost}.
 * A chave vem pela mesma cadeia do modelo (tenant, depois inline); resolver outra seria cobrar a
 * consulta de uma conta e o turno de outra.</p>
 *
 * <p>{@link #toString()} omite a chave: o record vai parar em log de depuração cedo ou tarde.</p>
 *
 * @param provider id do provider ({@code openrouter})
 * @param baseUrl  URL base efetiva — a sobrescrita da config ou a padrão do provider
 * @param apiKey   chave resolvida; {@code null} quando não há
 */
public record ProviderEndpoint(String provider, String baseUrl, String apiKey) {

    public ProviderEndpoint {
        Objects.requireNonNull(provider, "provider");
        Objects.requireNonNull(baseUrl, "baseUrl");
    }

    @Override
    public String toString() {
        return "ProviderEndpoint[provider=" + provider + ", baseUrl=" + baseUrl
                + ", apiKey=" + (apiKey == null ? "null" : "***") + "]";
    }
}
