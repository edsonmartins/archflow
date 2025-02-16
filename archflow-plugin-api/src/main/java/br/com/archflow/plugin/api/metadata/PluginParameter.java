package br.com.archflow.plugin.api.metadata;

import br.com.archflow.plugin.api.type.ParameterType;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Define um parâmetro de configuração ou operação.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.FIELD, ElementType.METHOD})
public @interface PluginParameter {
    /**
     * Identificador do parâmetro.
     */
    String id();

    /**
     * Nome de exibição.
     */
    String name();

    /**
     * Descrição do parâmetro.
     */
    String description();

    /**
     * Tipo do parâmetro.
     */
    ParameterType type();

    /**
     * Se o parâmetro é obrigatório.
     */
    boolean required() default false;

    /**
     * Valor padrão.
     */
    String defaultValue() default "";

    /**
     * Valores possíveis para tipos enum.
     */
    String[] allowedValues() default {};

    /**
     * Expressão de validação.
     */
    String validation() default "";
}