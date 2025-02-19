package br.com.archflow.langchain4j.adapter;

import dev.ai4j.openai4j.chat.JsonSchemaElement;

public class JsonBooleanSchema extends JsonSchemaElement {
    private final String description;

    private JsonBooleanSchema(Builder builder) {
        super("boolean");
        this.description = builder.description;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String description;

        public Builder description(String description) {
            this.description = description;
            return this;
        }

        public JsonBooleanSchema build() {
            return new JsonBooleanSchema(this);
        }
    }
}