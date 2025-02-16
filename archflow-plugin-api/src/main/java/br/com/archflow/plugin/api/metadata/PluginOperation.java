package br.com.archflow.plugin.api.metadata;

import br.com.archflow.plugin.api.type.OperationType;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Descreve uma operação específica do plugin.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({})
public @interface PluginOperation {
    /**
     * Identificador único da operação.
     */
    String id();

    /**
     * Nome de exibição da operação.
     */
    String name();

    /**
     * Descrição da operação.
     */
    String description();

    /**
     * Tipo de operação (sync, async, stream).
     */
    OperationType type() default OperationType.SYNC;

    /**
     * Parâmetros de entrada.
     */
    PluginParameter[] inputs();

    /**
     * Parâmetros de saída.
     */
    PluginParameter[] outputs();

    /**
     * Exemplo de uso.
     */
    String example() default "";
}