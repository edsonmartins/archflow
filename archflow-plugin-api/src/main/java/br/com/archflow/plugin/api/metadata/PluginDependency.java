package br.com.archflow.plugin.api.metadata;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Define uma dependência do plugin.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({})
public @interface PluginDependency {
    /**
     * ID do plugin requerido.
     */
    String pluginId();

    /**
     * Versão mínima requerida.
     */
    String minVersion();

    /**
     * Versão máxima suportada.
     */
    String maxVersion() default "";

    /**
     * Se a dependência é opcional.
     */
    boolean optional() default false;
}