package br.com.archflow.plugin.api.model;

/**
 * Informação de dependência.
 */
public record DependencyInfo(
    String pluginId,
    String minVersion,
    String maxVersion,
    boolean optional
) {}