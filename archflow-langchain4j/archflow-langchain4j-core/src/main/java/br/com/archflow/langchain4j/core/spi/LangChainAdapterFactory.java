package br.com.archflow.langchain4j.core.spi;

import java.util.Map;

/**
 * Factory para criação de adapters via SPI
 */
public interface LangChainAdapterFactory {
    /**
     * Retorna o identificador do provider
     */
    String getProvider();
    
    /**
     * Cria uma nova instância do adapter
     */
    LangChainAdapter createAdapter(Map<String, Object> properties);
    
    /**
     * Verifica se o factory suporta o tipo especificado
     */
    boolean supports(String type);
}