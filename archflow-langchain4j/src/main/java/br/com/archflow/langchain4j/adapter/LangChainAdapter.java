package br.com.archflow.langchain4j.adapter;

import br.com.archflow.model.engine.ExecutionContext;

import java.util.Map;

public interface LangChainAdapter {
    /**
     * Configura o adapter com as propriedades fornecidas
     */
    void configure(Map<String, Object> properties);

    /**
     * Executa uma operação usando o componente do LangChain4j
     */
    Object execute(String operation, Object input, ExecutionContext context) throws Exception;

    /**
     * Valida a configuração do adapter
     */
    void validate(Map<String, Object> properties);

    /**
     * Libera recursos quando o adapter não é mais necessário
     */
    void shutdown();
}