package br.com.archflow.plugin.api.metadata;

import java.lang.annotation.*;

/**
 * Define uma propriedade.
 */
@Target({})
@Retention(RetentionPolicy.RUNTIME)
public @interface Property {
    /**
     * Identificador da propriedade
     */
    String id();

    /**
     * Nome da propriedade
     */
    String name();

    /**
     * Descrição da propriedade
     */
    String description() default "";

    /**
     * Tipo da propriedade
     */
    String type() default "string";

    /**
     * Se a propriedade é obrigatória
     */
    boolean required() default false;

    /**
     * Valor padrão
     */
    String defaultValue() default "";

    /**
     * Grupo para organização na UI
     */
    String group() default "default";
}