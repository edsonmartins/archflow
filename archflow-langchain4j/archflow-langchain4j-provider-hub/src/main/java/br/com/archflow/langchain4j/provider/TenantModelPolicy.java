package br.com.archflow.langchain4j.provider;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * Quais modelos um tenant pode usar — a restrição {@code allowedModels} do cadastro de tenants,
 * aplicada na <b>execução</b> (RCHFLW-79).
 *
 * <p>O seletor do designer já mostra só o catálogo ativo ∩ os permitidos do tenant, mas o documento
 * do fluxo também chega por YAML, pela API e por fluxos antigos: uma restrição que só o painel
 * respeita se contorna escrevendo outro id no JSON. Por isso os dois caminhos de LLM consultam esta
 * política antes de criar o modelo — o dos agentes ({@code DefaultLLMConfigResolver}) e a ponte dos
 * nós de chat ({@code LangChainAdapterComponent}).
 *
 * <p>Regras:
 * <ul>
 *   <li><b>Vazio = sem restrição.</b> Um tenant sem lista (ou com a lista vazia) usa o catálogo
 *       inteiro e pode digitar um id livre — é o comportamento histórico.</li>
 *   <li><b>"Nenhum tenant" não é restringido.</b> {@code SYSTEM}/{@code __default__}/{@code __global__}
 *       ({@link TenantKeyResolver#isNoTenant}) são a plataforma, que não tem cadastro de tenant.</li>
 *   <li><b>Sem modelo, com restrição, falha.</b> O modelo que iria seria o default do adapter, que
 *       ninguém escolheu e ninguém conferiu contra a lista.</li>
 *   <li>A comparação é pelo id exato (aparado): {@code openai/gpt-4o} e {@code gpt-4o} são modelos de
 *       providers diferentes.</li>
 * </ul>
 *
 * @since 1.0.0
 */
@FunctionalInterface
public interface TenantModelPolicy {

    /** Sem restrição para ninguém — o default de testes e de hosts que não têm cadastro de tenants. */
    TenantModelPolicy UNRESTRICTED = tenantId -> Optional.empty();

    /**
     * Os modelos permitidos ao tenant.
     *
     * @param tenantId tenant da execução (já se sabe que não é sentinela de "nenhum tenant")
     * @return a lista; vazio (ou um conjunto vazio) quando o tenant não tem restrição
     */
    Optional<? extends Collection<String>> allowedModels(String tenantId);

    /**
     * Falha quando o tenant tem restrição e algum dos modelos não está nela.
     *
     * @param tenantId tenant da execução (pode ser {@code null} ou sentinela: aí não há restrição)
     * @param provider provider do modelo, só para a mensagem
     * @param models   modelos que a chamada vai usar (o principal e, quando há, o de fallback); um
     *                 {@code null} é "o nó não disse o modelo"
     * @throws IllegalStateException com prefixo {@code [LLM-MODELO]}, antes de qualquer requisição
     */
    default void check(String tenantId, String provider, List<String> models) {
        if (TenantKeyResolver.isNoTenant(tenantId)) {
            return;
        }
        Set<String> allowed = new TreeSet<>();
        allowedModels(tenantId).ifPresent(list -> list.stream()
                .filter(m -> m != null && !m.isBlank())
                .map(String::trim)
                .forEach(allowed::add));
        if (allowed.isEmpty()) {
            return;
        }
        for (String model : models) {
            String id = model == null ? null : model.trim();
            if (id == null || id.isEmpty()) {
                throw new IllegalStateException("[LLM-MODELO] O tenant '" + tenantId + "' só permite os modelos "
                        + allowed + ", mas a chamada ao provider '" + provider + "' não diz o modelo (iria o "
                        + "default do adapter). Escolha um dos permitidos no nó ou no padrão do fluxo.");
            }
            if (!allowed.contains(id)) {
                throw new IllegalStateException("[LLM-MODELO] O modelo '" + id + "' (provider '" + provider
                        + "') não está entre os permitidos do tenant '" + tenantId + "': " + allowed
                        + ". Escolha um deles no nó ou peça ao administrador da plataforma para liberá-lo.");
            }
        }
    }

    /** Atalho para uma chamada com um modelo só. */
    default void check(String tenantId, String provider, String model) {
        check(tenantId, provider, java.util.Collections.singletonList(model));
    }
}
