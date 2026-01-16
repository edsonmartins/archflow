package br.com.archflow.agent.tool;

import br.com.archflow.model.engine.ExecutionContext;

/**
 * Interface para interceptores de execução de tools.
 *
 * <p>Interceptores permitem executar lógica antes e depois da execução de uma tool,
 * bem como tratar erros de forma centralizada.
 *
 * <p>Exemplos de uso:
 * <ul>
 *   <li>Logging de execução</li>
 *   <li>Caching de resultados</li>
 *   <li>Métricas e monitoramento</li>
 *   <li>Validação (guardrails)</li>
 *   <li>Modificação de inputs/outputs</li>
 * </ul>
 *
 * @see ToolContext
 * @see ToolResult
 */
public interface ToolInterceptor {

    /**
     * Invocado antes da execução da tool.
     *
     * <p>Use este método para:
     * <ul>
     *   <li>Validar input</li>
     *   <li>Logar início da execução</li>
     *   <li>Coletar métricas de início</li>
     *   <li>Verificar cache</li>
     * </ul>
     *
     * @param context Contexto da execução da tool
     * @throws ToolInterceptorException Se a execução não deve prosseguir
     */
    default void beforeExecute(ToolContext context) throws ToolInterceptorException {
        // Default: não faz nada
    }

    /**
     * Invocado após a execução bem-sucedida da tool.
     *
     * <p>Use este método para:
     * <ul>
     *   <li>Logar resultado</li>
     *   <li>Armazenar em cache</li>
     *   <li>Coletar métricas de fim</li>
     *   <li>Modificar o resultado</li>
     * </ul>
     *
     * @param context Contexto da execução da tool
     * @param result Resultado retornado pela tool
     * @return Resultado possivelmente modificado
     * @throws ToolInterceptorException Se ocorrer erro no pós-processamento
     */
    default ToolResult afterExecute(ToolContext context, ToolResult result) throws ToolInterceptorException {
        return result; // Default: retorna resultado sem modificação
    }

    /**
     * Invocado quando a execução da tool lança uma exceção.
     *
     * <p>Use este método para:
     * <ul>
     *   <li>Logar erro</li>
     *   <li>Coletar métricas de erro</li>
     *   <li>Tentar recuperar com fallback</li>
     *   <li>Lançar exceção customizada</li>
     * </ul>
     *
     * @param context Contexto da execução da tool
     * @param error Exceção lançada pela tool
     * @throws Exception Se o erro deve ser propagado
     */
    default void onError(ToolContext context, Throwable error) throws Exception {
        // Default: propaga o erro
        if (error instanceof Exception) {
            throw (Exception) error;
        }
        throw new RuntimeException(error);
    }

    /**
     * Retorna a ordem de execução deste interceptor.
     *
     * <p>Interceptores com menor valor são executados primeiro.
     *
     * @return Ordem de execução (menor = antes)
     */
    default int order() {
        return 100;
    }

    /**
     * Retorna o nome deste interceptor para fins de logging.
     *
     * @return Nome do interceptor
     */
    default String getName() {
        return this.getClass().getSimpleName();
    }
}
