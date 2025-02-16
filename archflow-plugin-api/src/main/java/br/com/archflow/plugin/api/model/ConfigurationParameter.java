package br.com.archflow.plugin.api.model;

import br.com.archflow.plugin.api.type.ParameterType;
import java.util.List;

/**
 * Representa um parâmetro de configuração de um plugin.
 * Usado para definir as configurações que um plugin pode receber.
 */
public record ConfigurationParameter(
    /**
     * Identificador único do parâmetro
     */
    String id,

    /**
     * Nome para exibição do parâmetro
     */
    String displayName,

    /**
     * Descrição detalhada do parâmetro
     */
    String description,

    /**
     * Grupo do parâmetro para organização na UI
     */
    String group,

    /**
     * Tipo do parâmetro
     */
    ParameterType type,

    /**
     * Se o parâmetro é obrigatório
     */
    boolean required,

    /**
     * Valor padrão do parâmetro
     */
    String defaultValue,

    /**
     * Se o valor deve ser tratado como secreto (ex: senhas)
     */
    boolean secret,

    /**
     * Valores permitidos (para tipos enum)
     */
    List<String> allowedValues,

    /**
     * Validação do valor (ex: regex, range)
     */
    String validation,

    /**
     * Ordem de exibição do parâmetro
     */
    int order
) {
    /**
     * Builder para criar instâncias de ConfigurationParameter
     */
    public static class Builder {
        private String id;
        private String displayName;
        private String description;
        private String group = "general";
        private ParameterType type;
        private boolean required = false;
        private String defaultValue;
        private boolean secret = false;
        private List<String> allowedValues = List.of();
        private String validation;
        private int order = 0;

        public Builder id(String id) {
            this.id = id;
            return this;
        }

        public Builder displayName(String displayName) {
            this.displayName = displayName;
            return this;
        }

        public Builder description(String description) {
            this.description = description;
            return this;
        }

        public Builder group(String group) {
            this.group = group;
            return this;
        }

        public Builder type(ParameterType type) {
            this.type = type;
            return this;
        }

        public Builder required(boolean required) {
            this.required = required;
            return this;
        }

        public Builder defaultValue(String defaultValue) {
            this.defaultValue = defaultValue;
            return this;
        }

        public Builder secret(boolean secret) {
            this.secret = secret;
            return this;
        }

        public Builder allowedValues(List<String> allowedValues) {
            this.allowedValues = allowedValues;
            return this;
        }

        public Builder validation(String validation) {
            this.validation = validation;
            return this;
        }

        public Builder order(int order) {
            this.order = order;
            return this;
        }

        public ConfigurationParameter build() {
            if (id == null || id.trim().isEmpty()) {
                throw new IllegalArgumentException("id is required");
            }
            if (displayName == null || displayName.trim().isEmpty()) {
                throw new IllegalArgumentException("displayName is required");
            }
            if (type == null) {
                throw new IllegalArgumentException("type is required");
            }

            return new ConfigurationParameter(
                id,
                displayName,
                description,
                group,
                type,
                required,
                defaultValue,
                secret,
                allowedValues,
                validation,
                order
            );
        }
    }

    /**
     * Cria um novo builder para ConfigurationParameter
     */
    public static Builder builder() {
        return new Builder();
    }
}