package br.com.archflow.plugin.api.catalog;

import br.com.archflow.model.ai.type.ComponentType;

import java.util.HashSet;
import java.util.Set;

/**
 * Critérios para busca de componentes.
 */
public record ComponentSearchCriteria(
    ComponentType type,
    Set<String> capabilities,
    String textSearch
) {
    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private ComponentType type;
        private Set<String> capabilities = new HashSet<>();
        private String textSearch;

        public Builder type(ComponentType type) {
            this.type = type;
            return this;
        }

        public Builder capabilities(Set<String> capabilities) {
            this.capabilities = capabilities;
            return this;
        }

        public Builder textSearch(String textSearch) {
            this.textSearch = textSearch;
            return this;
        }

        public ComponentSearchCriteria build() {
            return new ComponentSearchCriteria(type, capabilities, textSearch);
        }
    }
}