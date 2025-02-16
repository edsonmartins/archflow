package br.com.archflow.plugin.api.metadata;

import java.lang.annotation.*;

/**
 * Anotação para definir uma configuração de plugin.
 * Usada dentro do PluginDescriptor para especificar 
 * as configurações disponíveis do plugin.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({})  // Será usada apenas dentro de PluginDescriptor
public @interface PluginConfiguration {
    /**
     * Identificador único da configuração
     */
    String id();

    /**
     * Nome para exibição da configuração
     */
    String name();

    /**
     * Descrição da configuração
     */
    String description() default "";

    /**
     * Tipo da configuração
     */
    String type();

    /**
     * Se a configuração é obrigatória
     */
    boolean required() default false;

    /**
     * Grupo da configuração para organização na UI
     */
    String group() default "general";

    /**
     * Valor padrão da configuração
     */
    String defaultValue() default "";

    /**
     * Se o valor é secreto (ex: senha)
     */
    boolean secret() default false;

    /**
     * Valores possíveis para tipos enum
     */
    String[] allowedValues() default {};

    /**
     * Validação do valor (ex: regex)
     */
    String validation() default "";

    /**
     * Ordem de exibição na UI
     */
    int order() default 0;
}