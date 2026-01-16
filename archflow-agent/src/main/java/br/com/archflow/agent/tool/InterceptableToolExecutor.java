package br.com.archflow.agent.tool;

import br.com.archflow.model.engine.ExecutionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Executor de tools com suporte a interceptores e rastreamento.
 *
 * <p>Este executor integra o ExecutionTracker e ToolInterceptorChain
 * para fornecer rastreamento completo e interceptação da execução de tools.
 */
public class InterceptableToolExecutor {

    private static final Logger log = LoggerFactory.getLogger(InterceptableToolExecutor.class);

    private final ExecutionTracker tracker;
    private final ToolInterceptorChain interceptorChain;
    private final Map<String, ToolFunction> tools;

    private InterceptableToolExecutor(Builder builder) {
        this.tracker = builder.tracker != null ? builder.tracker : new ExecutionTracker();
        this.interceptorChain = builder.interceptorChain != null
                ? builder.interceptorChain
                : ToolInterceptorChain.builder().build();
        this.tools = new ConcurrentHashMap<>(builder.tools);
    }

    /**
     * Executa uma tool com rastreamento e interceptação.
     *
     * @param toolName Nome da tool
     * @param input    Input da tool
     * @param context  Contexto de execução
     * @return Resultado da execução
     * @throws Exception Se ocorrer erro
     */
    public ToolResult<?> execute(String toolName, Object input, ExecutionContext context) throws Exception {
        // Inicia rastreamento
        ExecutionId executionId = tracker.startRoot(ExecutionId.ExecutionType.TOOL);

        log.debug("[{}] Iniciando execução da tool {}", executionId.getId(), toolName);

        try {
            // Cria contexto da tool
            ToolContext toolContext = ToolContext.builder()
                    .executionId(executionId.getId())
                    .toolName(toolName)
                    .input(input)
                    .executionContext(context)
                    .build();

            // Cria executor que invoca a tool real
            ToolInterceptorChain.ToolExecutor toolExecutor = tc -> {
                ToolFunction tool = tools.get(toolName);
                if (tool == null) {
                    throw new IllegalArgumentException("Tool not found: " + toolName);
                }
                return tool.apply(tc.getInput(), context);
            };

            // Executa através da cadeia de interceptores
            ToolResult<?> result = ToolInterceptorChain.builder()
                    .addInterceptors(interceptorChain.getInterceptors())
                    .toolExecutor(toolExecutor)
                    .build()
                    .execute(toolContext);

            // Marca como completo
            tracker.complete(executionId.getId(), result);

            return result;

        } catch (Exception e) {
            tracker.fail(executionId.getId(), e);
            throw e;
        }
    }

    /**
     * Executa uma tool aninhada dentro de outra execução.
     *
     * @param parentId ID da execução pai
     * @param toolName Nome da tool
     * @param input    Input da tool
     * @param context  Contexto de execução
     * @return Resultado da execução
     * @throws Exception Se ocorrer erro
     */
    public ToolResult<?> executeChild(String parentId, String toolName, Object input, ExecutionContext context) throws Exception {
        // Inicia rastreamento como filho
        ExecutionId executionId = tracker.startChild(parentId, ExecutionId.ExecutionType.TOOL);

        log.debug("[{}] Iniciando execução da tool {} (filha de {})",
                executionId.getId(), toolName, parentId);

        try {
            ToolContext toolContext = ToolContext.builder()
                    .executionId(executionId.getId())
                    .toolName(toolName)
                    .input(input)
                    .executionContext(context)
                    .build();

            ToolInterceptorChain.ToolExecutor toolExecutor = tc -> {
                ToolFunction tool = tools.get(toolName);
                if (tool == null) {
                    throw new IllegalArgumentException("Tool not found: " + toolName);
                }
                return tool.apply(tc.getInput(), context);
            };

            ToolResult<?> result = ToolInterceptorChain.builder()
                    .addInterceptors(interceptorChain.getInterceptors())
                    .toolExecutor(toolExecutor)
                    .build()
                    .execute(toolContext);

            tracker.complete(executionId.getId(), result);

            return result;

        } catch (Exception e) {
            tracker.fail(executionId.getId(), e);
            throw e;
        }
    }

    /**
     * Registra uma tool.
     *
     * @param name Nome da tool
     * @param tool Função da tool
     * @return Esta instância para chaining
     */
    public InterceptableToolExecutor registerTool(String name, ToolFunction tool) {
        tools.put(name, tool);
        log.debug("Tool registrada: {}", name);
        return this;
    }

    /**
     * Remove uma tool.
     *
     * @param name Nome da tool
     * @return Tool removida, ou null se não existia
     */
    public ToolFunction unregisterTool(String name) {
        ToolFunction removed = tools.remove(name);
        if (removed != null) {
            log.debug("Tool removida: {}", name);
        }
        return removed;
    }

    /**
     * Verifica se uma tool está registrada.
     *
     * @param name Nome da tool
     * @return true se registrada
     */
    public boolean hasTool(String name) {
        return tools.containsKey(name);
    }

    /**
     * Retorna o tracker.
     *
     * @return ExecutionTracker
     */
    public ExecutionTracker getTracker() {
        return tracker;
    }

    /**
     * Retorna a cadeia de interceptores.
     *
     * @return ToolInterceptorChain
     */
    public ToolInterceptorChain getInterceptorChain() {
        return interceptorChain;
    }

    /**
     * Limpa execuções antigas do tracker.
     *
     * @param olderThan Idade mínima para manter
     */
    public void cleanup(java.time.Instant olderThan) {
        tracker.cleanup(olderThan);
    }

    /**
     * Encerra o executor e limpa recursos.
     */
    public void shutdown() {
        log.info("Encerrando InterceptableToolExecutor...");
        tracker.cleanup(java.time.Instant.now());
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private ExecutionTracker tracker;
        private ToolInterceptorChain interceptorChain;
        private final Map<String, ToolFunction> tools = new ConcurrentHashMap<>();

        public Builder tracker(ExecutionTracker tracker) {
            this.tracker = tracker;
            return this;
        }

        public Builder interceptorChain(ToolInterceptorChain interceptorChain) {
            this.interceptorChain = interceptorChain;
            return this;
        }

        public Builder addInterceptor(br.com.archflow.agent.tool.ToolInterceptor interceptor) {
            if (this.interceptorChain == null) {
                this.interceptorChain = ToolInterceptorChain.builder()
                        .addInterceptor(interceptor)
                        .build();
            } else {
                this.interceptorChain.addInterceptor(interceptor);
            }
            return this;
        }

        public Builder tool(String name, ToolFunction tool) {
            this.tools.put(name, tool);
            return this;
        }

        public InterceptableToolExecutor build() {
            return new InterceptableToolExecutor(this);
        }
    }

    /**
     * Interface funcional para execução de tools.
     */
    @FunctionalInterface
    public interface ToolFunction {
        /**
         * Aplica a tool ao input.
         *
         * @param input  Input da tool
         * @param context Contexto de execução
         * @return Resultado da execução
         * @throws Exception Se ocorrer erro
         */
        ToolResult<?> apply(Object input, ExecutionContext context) throws Exception;
    }
}
