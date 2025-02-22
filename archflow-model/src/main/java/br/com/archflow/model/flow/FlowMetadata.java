package br.com.archflow.model.flow;

import java.util.List;

/**
 * Metadados associados a um fluxo.
 * Contém informações descritivas e de categorização do fluxo.
 */
public record FlowMetadata(
    /** Nome do fluxo */
    String name,

    /** Descrição detalhada */
    String description,

    /** Versão do fluxo */
    String version,

    /** Autor do fluxo */
    String author,

    /** Categoria do fluxo */
    String category,

    /** Tags para categorização */
    List<String> tags
) {
    /**
     * Construtor com validação
     */
    public FlowMetadata {
        if (name == null || name.trim().isEmpty()) {
            throw new IllegalArgumentException("Name is required");
        }
        if (version == null || version.trim().isEmpty()) {
            throw new IllegalArgumentException("Version is required");
        }
        if (tags == null) {
            tags = List.of();
        }
    }

    /**
     * Builder para facilitar a criação
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Builder para construção fluente
     */
    public static class Builder {
        private String name;
        private String description;
        private String version;
        private String author;
        private String category;
        private List<String> tags;

        public Builder name(String name) {
            this.name = name;
            return this;
        }

        public Builder description(String description) {
            this.description = description;
            return this;
        }

        public Builder version(String version) {
            this.version = version;
            return this;
        }

        public Builder author(String author) {
            this.author = author;
            return this;
        }

        public Builder category(String category) {
            this.category = category;
            return this;
        }

        public Builder tags(List<String> tags) {
            this.tags = tags;
            return this;
        }

        public FlowMetadata build() {
            return new FlowMetadata(name, description, version, author, category, tags);
        }
    }
}