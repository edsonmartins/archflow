package br.com.archflow.langchain4j.adapter;

import br.com.archflow.model.engine.ExecutionContext;
import br.com.archflow.model.ai.domain.*;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.service.tool.ToolExecutor;

import java.util.List;
import java.util.Map;

/**
 * Adaptador base para agentes do LangChain4j.
 */
public abstract class AgentAdapter implements LangChainAdapter {
    private AgentService agentService;
    private ChatLanguageModel model;
    private Map<ToolSpecification, ToolExecutor> tools;

    @Override
    public void configure(Map<String, Object> properties) {
        validate(properties);

        // Configura o modelo e ferramentas
        this.model = createLanguageModel(properties);
        this.tools = loadTools(properties);

        // Cria o serviço de agente
        this.agentService = createAgentService();
    }

    @Override
    public Object execute(String operation, Object input, ExecutionContext context) throws Exception {
        switch (operation) {
            case "executeTask":
                return executeTask((Task) input, context);
            case "planActions":
                return planActions((Goal) input, context);
            case "makeDecision":
                return makeDecision(input, context);
            default:
                throw new IllegalArgumentException("Invalid operation for agent: " + operation);
        }
    }

    protected Result executeTask(Task task, ExecutionContext context) throws Exception {
        try {
            String response = agentService.process(task.parameters());
            return new Result(
                    true,          // success
                    response,      // output
                    Map.of(),      // metadata (vazio neste caso)
                    List.of()      // messages (vazio neste caso)
            );
        } catch (Exception e) {
            return new Result(
                    false,         // success
                    null,          // output
                    Map.of(        // metadata com informação do erro
                            "error", e.getMessage(),
                            "errorType", e.getClass().getSimpleName()
                    ),
                    List.of(       // mensagens de erro
                            "Task execution failed: " + e.getMessage()
                    )
            );
        }
    }

    protected List<Action> planActions(Goal goal, ExecutionContext context) throws Exception {
        String plan = agentService.plan(goal.description());
        return parsePlanToActions(plan);
    }

    protected Decision makeDecision(Object input, ExecutionContext context) throws Exception {
        String result = agentService.decide(input.toString());
        return parseDecision(result);
    }

    /**
     * Cria o serviço de agente usando AiServices do Langchain4j
     */
    private AgentService createAgentService() {
        return AiServices.builder(AgentService.class)
                .chatLanguageModel(model)
                .tools(tools)
                .chatMemory(createChatMemory())
                .build();
    }

    /**
     * Interface do serviço de agente
     */
    private interface AgentService {
        @UserMessage("{{it}}")
        String process(Map<String, Object> input);

        @UserMessage("Plan the following goal: {{it}}")
        String plan(String goal);

        @UserMessage("Make a decision about: {{it}}")
        String decide(String input);
    }

    /**
     * Cria o modelo de linguagem
     */
    protected abstract ChatLanguageModel createLanguageModel(Map<String, Object> properties);

    /**
     * Carrega as ferramentas disponíveis
     */
    protected abstract Map<ToolSpecification, ToolExecutor> loadTools(Map<String, Object> properties);

    /**
     * Cria a memória do chat
     */
    protected abstract ChatMemory createChatMemory();

    /**
     * Converte um plano em string para lista de ações
     */
    protected abstract List<Action> parsePlanToActions(String plan);

    /**
     * Converte uma resposta em string para decisão
     */
    protected abstract Decision parseDecision(String result);
}