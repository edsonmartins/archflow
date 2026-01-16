package br.com.archflow.agent.tool.interceptor;

import br.com.archflow.agent.tool.ToolContext;
import br.com.archflow.agent.tool.ToolInterceptor;
import br.com.archflow.agent.tool.ToolResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Interceptor que faz logging da execução de tools.
 *
 * <p>Registra:
 * <ul>
 *   <li>Início da execução (beforeExecute)</li>
 *   <li>Fim da execução com duração (afterExecute)</li>
 *   <li>Erros (onError)</li>
 * </ul>
 */
public class LoggingInterceptor implements ToolInterceptor {

    private static final Logger log = LoggerFactory.getLogger(LoggingInterceptor.class);
    private final boolean logInput;
    private final boolean logOutput;
    private final boolean logStackTrace;

    public LoggingInterceptor() {
        this(true, false, false);
    }

    public LoggingInterceptor(boolean logInput, boolean logOutput, boolean logStackTrace) {
        this.logInput = logInput;
        this.logOutput = logOutput;
        this.logStackTrace = logStackTrace;
    }

    @Override
    public void beforeExecute(ToolContext context) {
        String executionId = context.getExecutionId();
        String toolName = context.getToolName();

        if (logInput && context.getInput() != null) {
            log.info("[{}] Iniciando execução da tool {} com input: {}",
                    executionId, toolName, sanitizeInput(context.getInput()));
        } else {
            log.info("[{}] Iniciando execução da tool {}", executionId, toolName);
        }
    }

    @Override
    public ToolResult afterExecute(ToolContext context, ToolResult result) {
        String executionId = context.getExecutionId();
        String toolName = context.getToolName();
        long duration = context.getDurationMillis();

        if (result.isSuccess()) {
            if (logOutput && result.getData().isPresent()) {
                log.info("[{}] Tool {} executada com sucesso em {}ms. Output: {}",
                        executionId, toolName, duration, sanitizeOutput(result.getData().get()));
            } else {
                log.info("[{}] Tool {} executada com sucesso em {}ms",
                        executionId, toolName, duration);
            }
        } else {
            log.error("[{}] Tool {} executada com erro em {}ms: {}",
                    executionId, toolName, duration,
                    result.getMessage().orElse("Erro desconhecido"));
        }

        return result;
    }

    @Override
    public void onError(ToolContext context, Throwable error) {
        String executionId = context.getExecutionId();
        String toolName = context.getToolName();

        if (logStackTrace) {
            log.error("[{}] Erro na execução da tool {} após {}ms",
                    executionId, toolName, context.getDurationMillis(), error);
        } else {
            log.error("[{}] Erro na execução da tool {} após {}ms: {}",
                    executionId, toolName, context.getDurationMillis(),
                    error.getMessage());
        }
    }

    @Override
    public int order() {
        // Logging deve ser o primeiro a executar
        return Integer.MIN_VALUE + 100;
    }

    @Override
    public String getName() {
        return "LoggingInterceptor";
    }

    /**
     * Sanitiza o input para logging (evita logs muito grandes).
     */
    private Object sanitizeInput(Object input) {
        if (input == null) {
            return null;
        }
        String str = input.toString();
        if (str.length() > 500) {
            return str.substring(0, 500) + "... (truncado)";
        }
        return str;
    }

    /**
     * Sanitiza o output para logging.
     */
    private Object sanitizeOutput(Object output) {
        if (output == null) {
            return null;
        }
        String str = output.toString();
        if (str.length() > 500) {
            return str.substring(0, 500) + "... (truncado)";
        }
        return str;
    }

    public static LoggingInterceptor create() {
        return new LoggingInterceptor();
    }

    public static LoggingInterceptor create(boolean logInput, boolean logOutput, boolean logStackTrace) {
        return new LoggingInterceptor(logInput, logOutput, logStackTrace);
    }
}
