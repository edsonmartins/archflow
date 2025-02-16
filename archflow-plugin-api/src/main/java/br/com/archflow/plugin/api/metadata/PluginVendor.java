package br.com.archflow.plugin.api.metadata;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Informações do desenvolvedor/vendor do plugin.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({})
public @interface PluginVendor {
    /**
     * Nome do vendor.
     */
    String name();

    /**
     * URL do website.
     */
    String url() default "";

    /**
     * Email de contato.
     */
    String email() default "";

    /**
     * Tipo de licença.
     */
    String license() default "Apache-2.0";
}