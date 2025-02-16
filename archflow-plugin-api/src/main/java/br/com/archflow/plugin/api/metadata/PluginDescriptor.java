package br.com.archflow.plugin.api.metadata;

import java.lang.annotation.*;

/**
 * Descritor principal de um plugin do archflow.
 * Fornece metadados essenciais para o catálogo de plugins.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
@Documented
public @interface PluginDescriptor {
    /**
     * Identificador único do plugin.
     * Deve seguir o padrão: [vendor].[categoria].[nome]
     */
    String id();

    /**
     * Nome de exibição do plugin.
     */
    String name();

    /**
     * Descrição detalhada do plugin.
     */
    String description();

    /**
     * Versão do plugin seguindo SemVer.
     */
    String version();

    /**
     * Categorias do plugin (ex: llm, database, api).
     */
    String[] categories();

    /**
     * Tags para busca e filtro.
     */
    String[] tags() default {};

    /**
     * URL do ícone do plugin.
     */
    String icon() default "";

    /**
     * Informações do desenvolvedor/vendor.
     */
    PluginVendor vendor();

    /**
     * Operações suportadas pelo plugin.
     */
    PluginOperation[] operations();

    /**
     * Dependências requeridas.
     */
    PluginDependency[] dependencies() default {};

    /**
     * Configurações do plugin.
     */
    PluginConfiguration[] configurations() default {};
}