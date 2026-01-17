package br.com.archflow.agent.tool;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Cadeia de interceptores para execução de tools.
 *
 * <p>Esta classe gerencia a execução ordenada de interceptores antes,
 * durante e após a execução de uma tool.
 */
public class ToolInterceptorChain {

    private static final Logger log = LoggerFactory.getLogger(ToolInterceptorChain.class);

    private final List<ToolInterceptor> interceptors;
    private final ToolExecutor toolExecutor;

    private ToolInterceptorChain(Builder builder) {
        this.interceptors = new CopyOnWriteArrayList<>(builder.interceptors);
        this.toolExecutor = builder.toolExecutor != null ? builder.toolExecutor : new DefaultToolExecutor();
        // Ordena interceptores pela ordem
        this.interceptors.sort(Comparator.comparingInt(ToolInterceptor::order));
    }

    /**
     * Executa a tool através da cadeia de interceptores.
     *
     * @param context Contexto da execução
     * @return Resultado da execução
     * @throws Exception Se ocorrer erro na execução
     */
    public ToolResult<?> execute(ToolContext context) throws Exception {
        String executionId = context.getExecutionId();
        String toolName = context.getToolName();

        log.debug("[{}] Executando tool {} através de {} interceptores",
                executionId, toolName, interceptors.size());

        // Fase 1: Before execute
        for (ToolInterceptor interceptor : interceptors) {
            try {
                log.trace("[{}] Invocando beforeExecute em {}", executionId, interceptor.getName());
                interceptor.beforeExecute(context);
            } catch (ToolInterceptorException e) {
                if (e.shouldAbort()) {
                    log.warn("[{}] Interceptor {} abortou execução: {}",
                            executionId, interceptor.getName(), e.getMessage());
                    return handleInterceptorError(context, interceptor, e);
                }
            } catch (Exception e) {
                log.error("[{}] Erro inesperado em beforeExecute do interceptor {}",
                        executionId, interceptor.getName(), e);
                return handleInterceptorError(context, interceptor, e);
            }
        }

        // Fase 2: Execute tool
        ToolResult<?> result;
        try {
            log.trace("[{}] Executando tool {}", executionId, toolName);
            result = toolExecutor.execute(context);
            context.setResult(result);
        } catch (Exception e) {
            log.error("[{}] Erro na execução da tool {}", executionId, toolName, e);
            context.setError(e);

            // Fase 3a: On error
            for (ToolInterceptor interceptor : interceptors) {
                try {
                    interceptor.onError(context, e);
                } catch (Exception onErrorEx) {
                    log.error("[{}] Erro no onError do interceptor {}",
                            executionId, interceptor.getName(), onErrorEx);
                }
            }
            throw e;
        } finally {
            context.setEndTime(java.time.Instant.now());
        }

        // Fase 3b: After execute
        for (ToolInterceptor interceptor : interceptors) {
            try {
                log.trace("[{}] Invocando afterExecute em {}", executionId, interceptor.getName());
                ToolResult<?> modifiedResult = interceptor.afterExecute(context, result);
                if (modifiedResult != null) {
                    result = modifiedResult;
                }
            } catch (ToolInterceptorException e) {
                log.warn("[{}] Erro no afterExecute do interceptor {}: {}",
                        executionId, interceptor.getName(), e.getMessage());
            } catch (Exception e) {
                log.error("[{}] Erro inesperado no afterExecute do interceptor {}",
                        executionId, interceptor.getName(), e);
            }
        }

        log.debug("[{}] Tool {} executada em {}ms com status {}",
                executionId, toolName, context.getDurationMillis(), result.getStatus());

        return result;
    }

    private ToolResult<?> handleInterceptorError(ToolContext context, ToolInterceptor interceptor, Exception e) {
        return ToolResult.error(
                String.format("Interceptor %s abortou execução: %s",
                        interceptor.getName(), e.getMessage()),
                e
        );
    }

    /**
     * Adiciona um interceptor à cadeia.
     *
     * @param interceptor Interceptor a adicionar
     * @return Esta instância para chaining
     */
    public ToolInterceptorChain addInterceptor(ToolInterceptor interceptor) {
        this.interceptors.add(interceptor);
        this.interceptors.sort(Comparator.comparingInt(ToolInterceptor::order));
        return this;
    }

    /**
     * Remove um interceptor da cadeia.
     *
     * @param interceptor Interceptor a remover
     * @return true se foi removido
     */
    public boolean removeInterceptor(ToolInterceptor interceptor) {
        return this.interceptors.remove(interceptor);
    }

    /**
     * Remove um interceptor pelo nome/classe.
     *
     * @param interceptorClass Classe do interceptor
     * @return true se foi removido
     */
    public boolean removeInterceptor(Class<? extends ToolInterceptor> interceptorClass) {
        return interceptors.removeIf(i -> i.getClass().equals(interceptorClass));
    }

    /**
     * Retorna a lista de interceptores.
     *
     * @return Lista imutável de interceptores
     */
    public List<ToolInterceptor> getInterceptors() {
        return new ArrayList<>(interceptors);
    }

    /**
     * Verifica se a cadeia está vazia.
     *
     * @return true se não há interceptores
     */
    public boolean isEmpty() {
        return interceptors.isEmpty();
    }

    /**
     * Retorna o número de interceptores na cadeia.
     *
     * @return Número de interceptores
     */
    public int size() {
        return interceptors.size();
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private final List<ToolInterceptor> interceptors = new ArrayList<>();
        private ToolExecutor toolExecutor;

        public Builder addInterceptor(ToolInterceptor interceptor) {
            this.interceptors.add(interceptor);
            return this;
        }

        public Builder addInterceptors(List<ToolInterceptor> interceptors) {
            this.interceptors.addAll(interceptors);
            return this;
        }

        public Builder toolExecutor(ToolExecutor toolExecutor) {
            this.toolExecutor = toolExecutor;
            return this;
        }

        public ToolInterceptorChain build() {
            return new ToolInterceptorChain(this);
        }
    }

    /**
     * Interface para execução da tool.
     */
    @FunctionalInterface
    public interface ToolExecutor {
        /**
         * Executa a tool.
         *
         * @param context Contexto da execução
         * @return Resultado da execução
         * @throws Exception Se ocorrer erro
         */
        ToolResult<?> execute(ToolContext context) throws Exception;
    }

    /**
     * Executor padrão que invoca a tool através do mapa de tools no contexto.
     *
     * <p>Para usar este executor, o ToolContext deve conter o atributo
     * {@link ToolContext#TOOLS_MAP_KEY} com o mapa de tools registradas.
     */
    private static class DefaultToolExecutor implements ToolExecutor {
        @Override
        @SuppressWarnings("unchecked")
        public ToolResult<?> execute(ToolContext context) throws Exception {
            // Busca o mapa de tools no contexto
            Object toolsMapObj = context.getAttribute(ToolContext.TOOLS_MAP_KEY);

            if (toolsMapObj == null) {
                throw new IllegalStateException(
                        "Nenhuma tool registrada encontrada no contexto. " +
                        "Configure um ToolExecutor explicito ou registre as tools usando " +
                        "InterceptableToolExecutor.registerTool(), e garanta que o mapa seja " +
                        "definido como atributo '" + ToolContext.TOOLS_MAP_KEY + "' no ToolContext."
                );
            }

            if (!(toolsMapObj instanceof Map)) {
                throw new IllegalStateException(
                        "O atributo '" + ToolContext.TOOLS_MAP_KEY + "' deve ser um Map<String, ToolFunction>"
                );
            }

            Map<String, Object> toolsMap = (Map<String, Object>) toolsMapObj;
            Object tool = toolsMap.get(context.getToolName());

            if (tool == null) {
                throw new IllegalArgumentException(
                        "Tool not found: " + context.getToolName() + ". " +
                        "Available tools: " + toolsMap.keySet()
                );
            }

            // Invoca a tool (suporta both ToolFunction interface and simple reflection)
            if (tool instanceof InterceptableToolExecutor.ToolFunction) {
                return ((InterceptableToolExecutor.ToolFunction) tool).apply(
                        context.getInput(),
                        context.getExecutionContext()
                );
            }

            // Fallback para tool genérica com método execute/apply
            try {
                if (tool instanceof java.util.function.Function) {
                    Object result = ((java.util.function.Function<Object, ?>) tool).apply(context.getInput());
                    return ToolResult.success(result);
                }
            } catch (Exception e) {
                return ToolResult.error("Erro ao executar tool: " + e.getMessage(), e);
            }

            throw new IllegalArgumentException(
                    "Tool '" + context.getToolName() + "' não implementa uma interface suportada. " +
                    "Use InterceptableToolExecutor.ToolFunction."
            );
        }
    }
}
