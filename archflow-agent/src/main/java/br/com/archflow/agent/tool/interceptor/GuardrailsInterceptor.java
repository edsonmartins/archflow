package br.com.archflow.agent.tool.interceptor;

import br.com.archflow.agent.tool.ToolContext;
import br.com.archflow.agent.tool.ToolInterceptor;
import br.com.archflow.agent.tool.ToolInterceptorException;
import br.com.archflow.agent.tool.ToolResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * Interceptor que implementa guardrails (validações) para execução de tools.
 *
 * <p>Guardrails permitem:
 * <ul>
 *   <li>Validar input antes da execução</li>
 *   <li>Validar output após a execução</li>
 *   <li>Bloquear execução baseado em regras de negócio</li>
 *   <li>Sanitizar dados sensíveis</li>
 * </ul>
 */
public class GuardrailsInterceptor implements ToolInterceptor {

    private static final Logger log = LoggerFactory.getLogger(GuardrailsInterceptor.class);

    private final List<InputGuardrail> inputGuardrails;
    private final List<OutputGuardrail> outputGuardrails;

    public GuardrailsInterceptor() {
        this.inputGuardrails = new ArrayList<>();
        this.outputGuardrails = new ArrayList<>();
    }

    @Override
    public void beforeExecute(ToolContext context) throws ToolInterceptorException {
        Object input = context.getInput();

        for (InputGuardrail guardrail : inputGuardrails) {
            if (!guardrail.test(context)) {
                String message = String.format(
                        "Guardrail %s bloqueou execução da tool %s: %s",
                        guardrail.getName(),
                        context.getToolName(),
                        guardrail.getViolationMessage(context)
                );
                log.warn("[{}] {}", context.getExecutionId(), message);
                throw new ToolInterceptorException(
                        guardrail.getName(),
                        context.getExecutionId(),
                        message
                );
            }
        }
    }

    @Override
    public ToolResult afterExecute(ToolContext context, ToolResult result) throws ToolInterceptorException {
        if (!result.isSuccess()) {
            return result;
        }

        Object output = result.getData().orElse(null);

        for (OutputGuardrail guardrail : outputGuardrails) {
            if (!guardrail.test(context, output)) {
                String message = String.format(
                        "Output guardrail %s violado para tool %s: %s",
                        guardrail.getName(),
                        context.getToolName(),
                        guardrail.getViolationMessage(context, output)
                );
                log.warn("[{}] {}", context.getExecutionId(), message);

                // Por padrão, retorna erro, mas pode ser configurado para modificar/sanitizar
                return ToolResult.error(message, new GuardrailViolationException(message));
            }
        }

        return result;
    }

    @Override
    public int order() {
        // Guardrails devem executar antes da tool (validar input)
        // e depois (validar output), mas depois de logging
        return 10;
    }

    @Override
    public String getName() {
        return "GuardrailsInterceptor";
    }

    /**
     * Adiciona um guardrail de input.
     */
    public GuardrailsInterceptor addInputGuardrail(InputGuardrail guardrail) {
        this.inputGuardrails.add(guardrail);
        return this;
    }

    /**
     * Adiciona um guardrail de output.
     */
    public GuardrailsInterceptor addOutputGuardrail(OutputGuardrail guardrail) {
        this.outputGuardrails.add(guardrail);
        return this;
    }

    /**
     * Remove todos os guardrails.
     */
    public void clearGuardrails() {
        inputGuardrails.clear();
        outputGuardrails.clear();
    }

    /**
     * Interface para guardrails de input.
     */
    @FunctionalInterface
    public interface InputGuardrail {
        /**
         * Testa se o input é válido.
         *
         * @param context Contexto da execução
         * @return true se válido, false se deve bloquear
         */
        boolean test(ToolContext context);

        /**
         * Retorna o nome deste guardrail.
         */
        default String getName() {
            return getClass().getSimpleName();
        }

        /**
         * Retorna mensagem de violação.
         */
        default String getViolationMessage(ToolContext context) {
            return "Input inválido";
        }
    }

    /**
     * Interface para guardrails de output.
     */
    @FunctionalInterface
    public interface OutputGuardrail {
        /**
         * Testa se o output é válido.
         *
         * @param context Contexto da execução
         * @param output  Output da tool
         * @return true se válido, false se deve bloquear
         */
        boolean test(ToolContext context, Object output);

        /**
         * Retorna o nome deste guardrail.
         */
        default String getName() {
            return getClass().getSimpleName();
        }

        /**
         * Retorna mensagem de violação.
         */
        default String getViolationMessage(ToolContext context, Object output) {
            return "Output inválido";
        }
    }

    /**
     * Exceção lançada quando um guardrail é violado.
     */
    public static class GuardrailViolationException extends RuntimeException {
        public GuardrailViolationException(String message) {
            super(message);
        }
    }

    // Guardrails comuns

    /**
     * Cria um guardrail que valida o tamanho máximo do input.
     */
    public static InputGuardrail maxInputSize(int maxSize) {
        return new InputGuardrail() {
            @Override
            public boolean test(ToolContext context) {
                Object input = context.getInput();
                if (input == null) {
                    return true;
                }
                return input.toString().length() <= maxSize;
            }

            @Override
            public String getName() {
                return "MaxInputSize";
            }

            @Override
            public String getViolationMessage(ToolContext context) {
                int actualSize = context.getInput() != null
                        ? context.getInput().toString().length()
                        : 0;
                return String.format("Input excede tamanho máximo de %d caracteres (atual: %d)",
                        maxSize, actualSize);
            }
        };
    }

    /**
     * Cria um guardrail que bloqueia tools específicas.
     */
    public static InputGuardrail blockTools(String... blockedToolNames) {
        return new InputGuardrail() {
            @Override
            public boolean test(ToolContext context) {
                for (String blocked : blockedToolNames) {
                    if (context.getToolName().equals(blocked)) {
                        return false;
                    }
                }
                return true;
            }

            @Override
            public String getName() {
                return "BlockedTools";
            }

            @Override
            public String getViolationMessage(ToolContext context) {
                return "Tool " + context.getToolName() + " está bloqueada";
            }
        };
    }

    /**
     * Cria um guardrail que valida que o output não é vazio.
     */
    public static OutputGuardrail nonEmptyOutput() {
        return new OutputGuardrail() {
            @Override
            public boolean test(ToolContext context, Object output) {
                if (output == null) {
                    return false;
                }
                if (output instanceof String && ((String) output).isEmpty()) {
                    return false;
                }
                return true;
            }

            @Override
            public String getName() {
                return "NonEmptyOutput";
            }

            @Override
            public String getViolationMessage(ToolContext context, Object output) {
                return "Output não pode ser vazio";
            }
        };
    }

    /**
     * Cria um guardrail customizado a partir de um Predicate.
     */
    public static InputGuardrail customInput(String name, Predicate<ToolContext> predicate, String message) {
        return new InputGuardrail() {
            @Override
            public boolean test(ToolContext context) {
                return predicate.test(context);
            }

            @Override
            public String getName() {
                return name;
            }

            @Override
            public String getViolationMessage(ToolContext context) {
                return message;
            }
        };
    }

    /**
     * Cria um guardrail customizado a partir de um Predicate.
     */
    public static OutputGuardrail customOutput(String name, java.util.function.BiPredicate<ToolContext, Object> predicate, String message) {
        return new OutputGuardrail() {
            @Override
            public boolean test(ToolContext context, Object output) {
                return predicate.test(context, output);
            }

            @Override
            public String getName() {
                return name;
            }

            @Override
            public String getViolationMessage(ToolContext context, Object output) {
                return message;
            }
        };
    }

    public static GuardrailsInterceptor create() {
        return new GuardrailsInterceptor();
    }
}
