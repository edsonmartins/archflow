package br.com.archflow.langchain4j.provider;

import br.com.archflow.model.ai.AIComponent;
import br.com.archflow.model.config.LLMConfigPatch;
import br.com.archflow.model.config.ResolvedLLMConfig;
import br.com.archflow.model.flow.Flow;
import br.com.archflow.model.flow.FlowStep;

/**
 * Requisição de resolução de configuração de LLM. Carrega o default da plataforma
 * e os patches de cada nível da cadeia de herança.
 *
 * <p>Precedência (mais específico vence):
 * {@code step > agent > flow > tenant > platform}.
 *
 * <p>O {@code tier} entra <b>abaixo</b> dessa cadeia: ele é política de quem
 * aciona ("esta tarefa é difícil"), e qualquer patch mais específico continua
 * vencendo. Quando isso acontece — ou quando não há mapa para o tier —
 * {@link DefaultLLMConfigResolver} <b>loga</b>, porque um tier pedido e não
 * honrado em silêncio é o defeito que ele existe para eliminar.
 *
 * @param tenantId        tenant atual (para resolução de chave); pode ser {@code null}
 * @param platformDefault config base totalmente preenchida (obrigatória)
 * @param tenantDefault   patch vindo da governança do tenant
 * @param flowPatch       patch no nível do fluxo
 * @param agentPatch      patch no nível do agente
 * @param stepPatch       patch no nível do passo (mais específico)
 * @param tier            tier de LLM pedido por quem acionou ({@code LIGHT}/{@code STRONG});
 *                        {@code null} mantém o comportamento anterior
 * @since 1.0.0
 */
public record LLMResolutionRequest(
        String tenantId,
        ResolvedLLMConfig platformDefault,
        LLMConfigPatch tenantDefault,
        LLMConfigPatch flowPatch,
        LLMConfigPatch agentPatch,
        LLMConfigPatch stepPatch,
        String tier
) {
    public LLMResolutionRequest {
        if (platformDefault == null) {
            throw new IllegalArgumentException("platformDefault is required");
        }
        tenantDefault = tenantDefault == null ? LLMConfigPatch.empty() : tenantDefault;
        flowPatch = flowPatch == null ? LLMConfigPatch.empty() : flowPatch;
        agentPatch = agentPatch == null ? LLMConfigPatch.empty() : agentPatch;
        stepPatch = stepPatch == null ? LLMConfigPatch.empty() : stepPatch;
        tier = tier == null || tier.isBlank() ? null : tier.trim();
    }

    /**
     * Compat: requisição sem tier — o comportamento de sempre.
     *
     * <p>Existe para não obrigar todo chamador e todo teste a conhecer um campo
     * que só o caminho de agente usa. Um record de contrato que ganha campo sem
     * construtor de compatibilidade quebra quem não tem nada a ver com a mudança.
     */
    public LLMResolutionRequest(String tenantId, ResolvedLLMConfig platformDefault,
                                LLMConfigPatch tenantDefault, LLMConfigPatch flowPatch,
                                LLMConfigPatch agentPatch, LLMConfigPatch stepPatch) {
        this(tenantId, platformDefault, tenantDefault, flowPatch, agentPatch, stepPatch, null);
    }

    public static Builder builder(ResolvedLLMConfig platformDefault) {
        return new Builder(platformDefault);
    }

    /**
     * Monta a requisição para um passo de um fluxo, preenchendo os tiers
     * {@code flow} (de {@link Flow#getConfiguration()}) e {@code step}
     * (de {@link FlowStep#getLLMPatch()}). O tier {@code agent} fica vazio
     * (ainda não há abstração de agente — D1).
     *
     * @param platformDefault default da plataforma (obrigatório)
     * @param tenantId        tenant atual
     * @param tenantDefault   patch da governança do tenant (pode ser {@code null})
     * @param flow            fluxo em execução (pode ser {@code null})
     * @param step            passo atual (pode ser {@code null})
     */
    public static LLMResolutionRequest forStep(ResolvedLLMConfig platformDefault,
                                               String tenantId,
                                               LLMConfigPatch tenantDefault,
                                               Flow flow,
                                               FlowStep step) {
        return forStep(platformDefault, tenantId, tenantDefault, flow, step, null);
    }

    /**
     * Como {@link #forStep(ResolvedLLMConfig, String, LLMConfigPatch, Flow, FlowStep)},
     * mas também preenche o tier {@code agent} a partir da config declarada do
     * componente ({@code AIComponent.getMetadata().properties()}). Precedência
     * efetiva: step {@literal >} agent {@literal >} flow {@literal >} tenant
     * {@literal >} platform.
     *
     * @param agent componente do passo (pode ser {@code null} → tier agent vazio)
     */
    public static LLMResolutionRequest forStep(ResolvedLLMConfig platformDefault,
                                               String tenantId,
                                               LLMConfigPatch tenantDefault,
                                               Flow flow,
                                               FlowStep step,
                                               AIComponent agent) {
        LLMConfigPatch flowPatch = (flow != null && flow.getConfiguration() != null)
                ? flow.getConfiguration().getLLMPatch()
                : LLMConfigPatch.empty();
        LLMConfigPatch stepPatch = step != null ? step.getLLMPatch() : LLMConfigPatch.empty();
        LLMConfigPatch agentPatch = (agent != null && agent.getMetadata() != null)
                ? LLMConfigPatch.fromMap(agent.getMetadata().properties())
                : LLMConfigPatch.empty();
        return builder(platformDefault)
                .tenantId(tenantId)
                .tenantDefault(tenantDefault)
                .flowPatch(flowPatch)
                .agentPatch(agentPatch)
                .stepPatch(stepPatch)
                .build();
    }

    public static class Builder {
        private final ResolvedLLMConfig platformDefault;
        private String tenantId;
        private LLMConfigPatch tenantDefault = LLMConfigPatch.empty();
        private LLMConfigPatch flowPatch = LLMConfigPatch.empty();
        private LLMConfigPatch agentPatch = LLMConfigPatch.empty();
        private LLMConfigPatch stepPatch = LLMConfigPatch.empty();
        private String tier;

        private Builder(ResolvedLLMConfig platformDefault) {
            this.platformDefault = platformDefault;
        }

        public Builder tenantId(String tenantId) {
            this.tenantId = tenantId;
            return this;
        }

        public Builder tenantDefault(LLMConfigPatch patch) {
            this.tenantDefault = patch;
            return this;
        }

        public Builder flowPatch(LLMConfigPatch patch) {
            this.flowPatch = patch;
            return this;
        }

        public Builder agentPatch(LLMConfigPatch patch) {
            this.agentPatch = patch;
            return this;
        }

        public Builder stepPatch(LLMConfigPatch patch) {
            this.stepPatch = patch;
            return this;
        }

        /** Tier pedido por quem acionou ({@code LIGHT}/{@code STRONG}); {@code null} = nenhum. */
        public Builder tier(String tier) {
            this.tier = tier;
            return this;
        }

        public LLMResolutionRequest build() {
            return new LLMResolutionRequest(tenantId, platformDefault, tenantDefault, flowPatch,
                    agentPatch, stepPatch, tier);
        }
    }
}
