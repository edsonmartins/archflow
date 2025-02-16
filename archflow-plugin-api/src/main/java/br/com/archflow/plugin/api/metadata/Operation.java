package br.com.archflow.plugin.api.metadata;

import java.lang.annotation.*;

/**
 * Define uma operação do componente.
 */
@Target({})
@Retention(RetentionPolicy.RUNTIME)
public @interface Operation {
    /**
     * Identificador da operação
     */
    String id();

    /**
     * Nome da operação
     */
    String name();

    /**
     * Descrição da operação
     */
    String description() default "";

    /**
     * Propriedades de entrada
     */
    Property[] inputs() default {};

    /**
     * Propriedades de saída
     */
    Property[] outputs() default {};
}