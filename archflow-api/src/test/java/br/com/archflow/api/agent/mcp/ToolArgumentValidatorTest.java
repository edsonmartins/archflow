package br.com.archflow.api.agent.mcp;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * O caso que faltava: JSON <b>bem formado</b> e errado. Modelos com
 * tool-calling fraco erram assim — campo obrigatório ausente, número mandado
 * como string, valor fora do enum — e isso chegava à tool.
 */
@DisplayName("ToolArgumentValidator")
class ToolArgumentValidatorTest {

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "required", List.of("servico"),
            "properties", Map.of(
                    "servico", Map.of("type", "string"),
                    "linhas", Map.of("type", "integer"),
                    "desde", Map.of("type", "number"),
                    "seguir", Map.of("type", "boolean"),
                    "nivel", Map.of("enum", List.of("INFO", "WARN", "ERROR")),
                    "tags", Map.of("type", "array", "items", Map.of("type", "string")),
                    "filtro", Map.of(
                            "type", "object",
                            "required", List.of("campo"),
                            "properties", Map.of("campo", Map.of("type", "string")))));

    @Test
    @DisplayName("argumentos corretos passam")
    void validArguments() {
        assertThat(ToolArgumentValidator.validate(SCHEMA, Map.of(
                "servico", "traefik",
                "linhas", 100,
                "seguir", true,
                "nivel", "ERROR",
                "tags", List.of("prod", "edge"),
                "filtro", Map.of("campo", "msg")))).isEmpty();
    }

    @Test
    @DisplayName("campo obrigatório ausente é apontado pelo nome")
    void missingRequiredField() {
        assertThat(ToolArgumentValidator.validate(SCHEMA, Map.of("linhas", 10)))
                .singleElement().asString().contains("servico");
    }

    @Test
    @DisplayName("obrigatório presente mas nulo conta como ausente")
    void nullRequiredField() {
        Map<String, Object> args = new java.util.HashMap<>();
        args.put("servico", null);

        assertThat(ToolArgumentValidator.validate(SCHEMA, args))
                .singleElement().asString().contains("servico");
    }

    @Test
    @DisplayName("número mandado como string é recusado")
    void wrongScalarType() {
        assertThat(ToolArgumentValidator.validate(SCHEMA,
                Map.of("servico", "traefik", "linhas", "100")))
                .singleElement().asString().contains("linhas").contains("integer");
    }

    @Test
    @DisplayName("integer aceita um Number inteiro (JSON não distingue 100 de 100.0)")
    void integerAcceptsWholeNumbers() {
        assertThat(ToolArgumentValidator.validate(SCHEMA,
                Map.of("servico", "traefik", "linhas", 100.0))).isEmpty();
        assertThat(ToolArgumentValidator.validate(SCHEMA,
                Map.of("servico", "traefik", "linhas", 100.5)))
                .singleElement().asString().contains("integer");
    }

    @Test
    @DisplayName("valor fora do enum é recusado, listando os aceitos")
    void valueOutsideEnum() {
        assertThat(ToolArgumentValidator.validate(SCHEMA,
                Map.of("servico", "traefik", "nivel", "TRACE")))
                .singleElement().asString().contains("nivel").contains("INFO");
    }

    @Test
    @DisplayName("item de array com tipo errado é apontado com o índice")
    void wrongArrayItemType() {
        assertThat(ToolArgumentValidator.validate(SCHEMA,
                Map.of("servico", "traefik", "tags", List.of("prod", 42))))
                .singleElement().asString().contains("tags[1]");
    }

    @Test
    @DisplayName("objeto aninhado tem seus obrigatórios conferidos")
    void nestedObjectRequired() {
        assertThat(ToolArgumentValidator.validate(SCHEMA,
                Map.of("servico", "traefik", "filtro", Map.of("outro", "x"))))
                .singleElement().asString().contains("filtro.campo");
    }

    @Test
    @DisplayName("propriedade não declarada é aceita — recusá-la quebraria servers legítimos")
    void unknownPropertyIsAccepted() {
        assertThat(ToolArgumentValidator.validate(SCHEMA,
                Map.of("servico", "traefik", "extra", "algo"))).isEmpty();
    }

    @Test
    @DisplayName("schema ausente ou vazio não recusa nada")
    void missingSchemaIsPermissive() {
        assertThat(ToolArgumentValidator.validate(null, Map.of("x", 1))).isEmpty();
        assertThat(ToolArgumentValidator.validate(Map.of(), Map.of("x", 1))).isEmpty();
    }

    @Test
    @DisplayName("acumula todas as violações, não só a primeira")
    void reportsEveryViolation() {
        assertThat(ToolArgumentValidator.validate(SCHEMA,
                Map.of("linhas", "dez", "nivel", "TRACE"))).hasSize(3);
    }
}
