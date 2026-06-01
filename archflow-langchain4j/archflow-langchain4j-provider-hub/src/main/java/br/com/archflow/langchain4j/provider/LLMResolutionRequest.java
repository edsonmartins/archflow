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
 * @param tenantId        tenant atual (para resolução de chave); pode ser {@code null}
 * @param platformDefault config base totalmente preenchida (obrigatória)
 * @param tenantDefault   patch vindo da governança do tenant
 * @param flowPatch       patch no nível do fluxo
 * @param agentPatch      patch no nível do agente
 * @param stepPatch       patch no nível do passo (mais específico)
 * @since 1.0.0
 */
public record LLMResolutionRequest(
        String tenantId,
        ResolvedLLMConfig platformDefault,
        LLMConfigPatch tenantDefault,
        LLMConfigPatch flowPatch,
        LLMConfigPatch agentPatch,
        LLMConfigPatch stepPatch
) {
    public LLMResolutionRequest {
        if (platformDefault == null) {
            throw new IllegalArgumentException("platformDefault is required");
        }
        tenantDefault = tenantDefault == null ? LLMConfigPatch.empty() : tenantDefault;
        flowPatch = flowPatch == null ? LLMConfigPatch.empty() : flowPatch;
        agentPatch = agentPatch == null ? LLMConfigPatch.empty() : agentPatch;
        stepPatch = stepPatch == null ? LLMConfigPatch.empty() : stepPatch;
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

        public LLMResolutionRequest build() {
            return new LLMResolutionRequest(tenantId, platformDefault, tenantDefault, flowPatch, agentPatch, stepPatch);
        }
    }
}
