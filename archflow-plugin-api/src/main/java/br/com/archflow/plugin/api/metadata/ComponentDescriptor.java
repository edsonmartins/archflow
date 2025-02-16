package br.com.archflow.plugin.api.metadata;

import br.com.archflow.model.ai.type.ComponentType;

import java.lang.annotation.*;

/**
 * Descritor de um componente de IA.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
@Documented
public @interface ComponentDescriptor {
    /**
     * Identificador único do componente
     */
    String id();

    /**
     * Nome do componente
     */
    String name();

    /**
     * Descrição do componente
     */
    String description() default "";

    /**
     * Tipo do componente
     */
    ComponentType type();

    /**
     * Versão do componente
     */
    String version();

    /**
     * URL ou base64 do ícone
     */
    String icon() default "";

    /**
     * Operações suportadas
     */
    Operation[] operations() default {};

    /**
     * Propriedades de configuração
     */
    Property[] properties() default {};
}