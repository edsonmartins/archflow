package br.com.archflow.sdk;

import br.com.archflow.agent.ArchFlowAgent;
import br.com.archflow.agent.config.AgentConfig;
import br.com.archflow.agent.config.ResourceConfig;
import br.com.archflow.agent.config.RetryConfig;
import br.com.archflow.dsl.WorkflowDocument;
import br.com.archflow.model.flow.FlowResult;
import br.com.archflow.standalone.FlowSerializer;
import br.com.archflow.standalone.model.SerializableFlow;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * Executa no próprio processo um fluxo escrito na DSL — sem servidor.
 *
 * <pre>{@code
 * try (EmbeddedWorkflowRunner runner = EmbeddedWorkflowRunner.builder().build()) {
 *     FlowResult result = runner.run(doc, Map.of("input", "olá"));
 *     System.out.println(result.getStatus());
 * }
 * }</pre>
 *
 * <h2>Requer archflow-standalone no classpath</h2>
 * A dependência é {@code optional} no pom do SDK, de propósito: ela traz o motor
 * inteiro, e quem só publica por REST não deve herdar esse peso. Declare-a se
 * for usar esta classe — {@link #builder()} avisa com uma mensagem clara se ela
 * faltar.
 *
 * <h2>Plugins não são carregados por default</h2>
 * O {@link AgentConfig} tem {@code pluginsPath} default {@code "plugins"}, e o
 * agente varre esse diretório na primeira execução. Abrir um jar de plugin
 * <b>executa o {@code onLoad} dele</b> — código arbitrário, sem sandbox, com os
 * privilégios do processo. Uma biblioteca que faça isso porque existia uma pasta
 * chamada {@code plugins} ao lado do executável é uma armadilha, então aqui o
 * default é <b>não varrer nada</b>. Quem precisa chama
 * {@link Builder#pluginsPath(String)} e assume a escolha.
 *
 * <h2>Um runner, um agente</h2>
 * Cada instância cria um {@link ArchFlowAgent} próprio, com threads próprias.
 * Fechá-la ({@code close()}) desliga o agente; use try-with-resources ou uma só
 * instância de vida longa, não uma por execução.
 *
 * @since 1.1.0
 */
public final class EmbeddedWorkflowRunner implements AutoCloseable {

    private final ArchFlowAgent agent;
    private final FlowSerializer serializer = new FlowSerializer();
    private final Duration timeout;

    private EmbeddedWorkflowRunner(ArchFlowAgent agent, Duration timeout) {
        this.agent = agent;
        this.timeout = timeout;
    }

    public static Builder builder() {
        return new Builder();
    }

    /**
     * Executa o fluxo e espera o resultado.
     *
     * <p>Síncrono por fora, assíncrono por dentro: o motor executa em outras
     * threads e este método bloqueia até o fim ou até o timeout.
     *
     * @param document o fluxo, tal como a DSL o produziu
     * @param input    variáveis iniciais; {@code null} equivale a vazio
     * @throws ArchflowClientException se a execução falhar, estourar o timeout
     *                                 ou a thread for interrompida
     */
    public FlowResult run(WorkflowDocument document, Map<String, Object> input) {
        Objects.requireNonNull(document, "document");
        SerializableFlow flow;
        try {
            // Passa pelo JSON de propósito: é o mesmo caminho que um fluxo
            // carregado de arquivo percorre, então executar da DSL e executar
            // do arquivo exercitam a mesma desserialização.
            flow = serializer.deserialize(document.toJson());
        } catch (Exception e) {
            throw new ArchflowClientException(
                    "o documento do fluxo " + document.id() + " não pôde ser lido pelo motor", e);
        }

        try {
            return agent.executeFlow(flow, input == null ? Map.of() : new HashMap<>(input))
                    .get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ArchflowClientException(
                    "interrompido ao executar o fluxo " + document.id(), e);
        } catch (Exception e) {
            throw new ArchflowClientException(
                    "falha ao executar o fluxo " + document.id(), e);
        }
    }

    /** Executa sem variáveis de entrada. */
    public FlowResult run(WorkflowDocument document) {
        return run(document, Map.of());
    }

    /** Desliga o agente e libera as threads. */
    @Override
    public void close() {
        agent.close();
    }

    /** Construtor do runner. */
    public static final class Builder {

        private String agentId;
        /** {@code null} = não varrer diretório nenhum. Ver a nota de classe. */
        private String pluginsPath;
        private Duration timeout = Duration.ofMinutes(5);
        private int maxConcurrentFlows = 1;
        private int maxThreads = Runtime.getRuntime().availableProcessors();

        private Builder() {
        }

        public Builder agentId(String agentId) {
            this.agentId = agentId;
            return this;
        }

        /**
         * Diretório de plugins a carregar.
         *
         * <p>Cada jar ali tem o seu {@code onLoad} executado, sem isolamento —
         * aponte apenas para um diretório cujo conteúdo você controla.
         */
        public Builder pluginsPath(String pluginsPath) {
            this.pluginsPath = pluginsPath;
            return this;
        }

        /** Tempo máximo de uma execução. Default 5 minutos. */
        public Builder timeout(Duration timeout) {
            this.timeout = timeout;
            return this;
        }

        public Builder maxConcurrentFlows(int maxConcurrentFlows) {
            this.maxConcurrentFlows = maxConcurrentFlows;
            return this;
        }

        public Builder maxThreads(int maxThreads) {
            this.maxThreads = maxThreads;
            return this;
        }

        public EmbeddedWorkflowRunner build() {
            AgentConfig config = AgentConfig.builder()
                    .agentId(agentId != null ? agentId : "sdk-embedded")
                    // Explícito mesmo quando nulo: é o que desliga a varredura
                    // do diretório default "plugins".
                    .pluginsPath(pluginsPath)
                    .maxConcurrentFlows(maxConcurrentFlows)
                    .defaultFlowTimeout(timeout.toMillis())
                    .retryConfig(new RetryConfig(3, 1000, 2.0))
                    .resourceConfig(new ResourceConfig(maxThreads, Runtime.getRuntime().maxMemory()))
                    .build();
            try {
                return new EmbeddedWorkflowRunner(new ArchFlowAgent(config), timeout);
            } catch (NoClassDefFoundError e) {
                // A dependência é optional: sem ela o erro cru não diz o que fazer.
                throw new IllegalStateException(
                        "execução embarcada exige archflow-standalone no classpath — "
                                + "a dependência é optional no archflow-sdk-java para não impor "
                                + "o motor a quem só usa o cliente REST. Falta: " + e.getMessage(), e);
            }
        }
    }
}
