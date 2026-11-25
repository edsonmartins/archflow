package br.com.archflow.model.tenant;

/**
 * O id canônico do tenant padrão — o tenant de quem não declarou nenhum.
 *
 * <p>Existiam três valores para "sem tenant": {@code "SYSTEM"} no motor (contexto e repositórios de
 * estado), {@code "__default__"} na requisição HTTP ({@code TenantContext}) e {@code "default"} na UI.
 * O produtor de uma aprovação (o motor) e o consumidor (a fila, filtrada pelo tenant da requisição)
 * nunca concordavam, e a aprovação de uma execução disparada pela UI não aparecia na fila de ninguém
 * (RCHFLW-13). Agora há um valor só, definido aqui porque o {@code archflow-model} é o módulo que
 * todos os leitores enxergam — motor, API e repositórios.
 *
 * <p>Dados gravados antes com {@code "SYSTEM"} são migrados para este id pelas migrations
 * {@code V1_3_1} (flow_states/audit_logs, archflow-core) e {@code V6_12_1} (mcp_agent_states,
 * archflow-api); ver {@link #LEGACY_SYSTEM}.
 */
public final class DefaultTenant {

    /** Id canônico do tenant padrão. Igual ao {@code TenantContext.DEFAULT_TENANT} da API. */
    public static final String ID = "__default__";

    /**
     * Valor que o motor gravava como tenant padrão até a unificação. Só existe para as migrations e
     * para quem precisar reconhecer dado antigo — nenhum código novo deve gravá-lo.
     */
    public static final String LEGACY_SYSTEM = "SYSTEM";

    private DefaultTenant() {
    }

    /**
     * @return o próprio tenant quando informado; {@link #ID} quando nulo ou em branco. Nunca nulo.
     */
    public static String orDefault(String tenantId) {
        return tenantId == null || tenantId.isBlank() ? ID : tenantId;
    }
}
