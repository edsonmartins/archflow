package br.com.archflow.example.config;

import br.com.archflow.agent.Agent;
import br.com.archflow.agent.AgentExecutor;
import br.com.archflow.core.FlowEngine;
import br.com.archflow.core.Edge;
import br.com.archflow.core.LLMNode;
import br.com.archflow.core.Node;
import br.com.archflow.core.OutputNode;
import br.com.archflow.core.Workflow;
import br.com.archflow.langchain4j.ChatLanguageModel;
import br.com.archflow.langchain4j.openai.OpenAiChatModel;
import br.com.archflow.tool.Tool;
import br.com.archflow.tool.ToolResult;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

/**
 * Configuração do archflow para o exemplo Spring Boot.
 */
@Configuration
public class ArchflowConfig {

    @Value("${archflow.llm.api-key}")
    private String apiKey;

    /**
     * Configura o modelo de linguagem ChatLanguageModel.
     */
    @Bean
    public ChatLanguageModel chatLanguageModel() {
        return OpenAiChatModel.builder()
                .apiKey(apiKey)
                .modelName("gpt-4o")
                .temperature(0.7)
                .build();
    }

    /**
     * Configura o FlowEngine com um workflow de exemplo.
     */
    @Bean
    public FlowEngine flowEngine(ChatLanguageModel llm) {
        FlowEngine engine = new FlowEngine(llm);

        // Workflow de atendimento ao cliente
        Workflow customerSupport = Workflow.builder()
                .id("customer-support")
                .name("Atendimento ao Cliente")
                .description("Workflow automatizado para suporte ao cliente")
                .nodes(List.of(
                        // Nó de entrada
                        Node.builder()
                                .id("input")
                                .type("input")
                                .config(Map.of(
                                        "message", "string"
                                ))
                                .build(),

                        // Nó LLM para classificação
                        LLMNode.builder()
                                .id("classify")
                                .model("gpt-4o")
                                .prompt("""
                                    Classifique a solicitação do cliente em uma das categorias:
                                    - suporte_tecnico
                                    - duvida_produto
                                    - reclamacao
                                    - elogio

                                    Mensagem: {input.message}

                                    Responda apenas com a categoria.
                                    """)
                                .build(),

                        // Nó LLM para resposta
                        LLMNode.builder()
                                .id("respond")
                                .model("gpt-4o")
                                .prompt("""
                                    Como atendente ao cliente, responda à seguinte mensagem:
                                    {input.message}

                                    Categoria identificada: {classify.output}

                                    Seja cordial, profissional e conciso.
                                    """)
                                .build(),

                        // Nó de saída
                        OutputNode.builder()
                                .id("output")
                                .template("${respond.output}")
                                .build()
                ))
                .edges(List.of(
                        Edge.from("input").to("classify"),
                        Edge.from("classify").to("respond"),
                        Edge.from("respond").to("output")
                ))
                .build();

        engine.register(customerSupport);

        return engine;
    }

    /**
     * Configura um agente de exemplo.
     */
    @Bean
    public Agent customerServiceAgent(ChatLanguageModel llm) {
        return Agent.builder()
                .id("customer-service")
                .name("Agente de Atendimento")
                .llm(llm)
                .systemPrompt("""
                    Você é um agente de atendimento ao cliente útil e profissional.
                    Seus objetivos:
                    - Ajudar clientes com suas dúvidas
                    - Resolver problemas de forma eficiente
                    - Manter tom cordial e profissional

                    Use as ferramentas disponíveis quando necessário.
                    """)
                .tools(List.of(
                        new DateTimeTool(),
                        new GreetingTool()
                ))
                .build();
    }

    /**
     * Executor de agentes.
     */
    @Bean
    public AgentExecutor agentExecutor() {
        return new AgentExecutor();
    }

    // ==================== FERRAMENTAS ====================

    /**
     * Ferramenta para obter data/hora atual.
     */
    public static class DateTimeTool implements Tool {

        @Override
        public String getName() {
            return "datetime";
        }

        @Override
        public String getDescription() {
            return "Obtém a data e hora atual formatada";
        }

        @Override
        public ToolResult execute(Map<String, Object> input) {
            String format = input.getOrDefault("format", "dd/MM/yyyy HH:mm").toString();
            DateTimeFormatter formatter = DateTimeFormatter.ofPattern(format);
            String now = LocalDateTime.now().format(formatter);

            return ToolResult.success(Map.of(
                    "datetime", now
            ));
        }
    }

    /**
     * Ferramenta para gerar saudações personalizadas.
     */
    public static class GreetingTool implements Tool {

        @Override
        public String getName() {
            return "greet";
        }

        @Override
        public String getDescription() {
            return "Gera uma saudação personalizada para o cliente";
        }

        @Override
        public ToolResult execute(Map<String, Object> input) {
            String name = input.getOrDefault("name", "Cliente").toString();

            return ToolResult.success(Map.of(
                    "greeting", "Olá, " + name + "! Como posso ajudá-lo hoje?"
            ));
        }
    }
}
