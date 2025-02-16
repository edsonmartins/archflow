package br.com.archflow.plugin.api.catalog;

import br.com.archflow.plugin.api.type.OperationType;

import java.util.HashSet;
import java.util.Set;

/**
 * Critérios para busca de plugins.
 */
public record PluginSearchCriteria(
    Set<String> categories,
    Set<String> tags,
    String textSearch,
    String vendor,
    OperationType operationType
) {
    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private Set<String> categories = new HashSet<>();
        private Set<String> tags = new HashSet<>();
        private String textSearch;
        private String vendor;
        private OperationType operationType;

        public Builder categories(Set<String> categories) {
            this.categories = categories;
            return this;
        }

        public Builder tags(Set<String> tags) {
            this.tags = tags;
            return this;
        }

        public Builder textSearch(String textSearch) {
            this.textSearch = textSearch;
            return this;
        }

        public Builder vendor(String vendor) {
            this.vendor = vendor;
            return this;
        }

        public Builder operationType(OperationType operationType) {
            this.operationType = operationType;
            return this;
        }

        public PluginSearchCriteria build() {
            return new PluginSearchCriteria(
                categories,
                tags,
                textSearch,
                vendor,
                operationType
            );
        }
    }
}