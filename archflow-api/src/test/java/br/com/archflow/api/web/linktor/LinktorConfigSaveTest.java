package br.com.archflow.api.web.linktor;

import br.com.archflow.api.config.GlobalExceptionHandler;
import br.com.archflow.api.linktor.dto.LinktorConfigDto;
import br.com.archflow.api.linktor.impl.LinktorConfigControllerImpl;
import br.com.archflow.langchain4j.mcp.transport.McpCommandPolicy;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * Salvar a configuração do Linktor pela API, como a tela faz: GET, edita, PUT do mesmo corpo.
 *
 * <ul>
 *   <li>RCHFLW-69: o comando sugerido pela tela ({@code linktor-mcp}) está fora da allow-list padrão de
 *   comandos MCP. A {@code SecurityException} da política não tinha handler e o save respondia 500 com o
 *   erro padrão do Spring. Agora é 400 com a frase da política, que diz o que fazer.</li>
 *   <li>RCHFLW-70: o segredo de até 8 caracteres volta mascarado como {@code ***}, e o PUT desse corpo
 *   gravava o literal {@code ***} como a nova chave — o MCP do Linktor subia com
 *   {@code LINKTOR_API_KEY=***}.</li>
 * </ul>
 */
@DisplayName("Linktor — salvar a configuração")
class LinktorConfigSaveTest {

    private final ObjectMapper json = new ObjectMapper();
    private LinktorConfigControllerImpl controller;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        // A frase depende da allow-list padrão: a propriedade venceria a variável, então é limpa; se a
        // máquina tiver a variável definida, o caso não é o da instalação padrão e o teste não se aplica.
        System.clearProperty(McpCommandPolicy.ALLOWED_COMMANDS_PROPERTY);
        assumeTrue(System.getenv(McpCommandPolicy.ALLOWED_COMMANDS_ENV) == null,
                "ARCHFLOW_MCP_ALLOWED_COMMANDS definida no ambiente");
        mvc = build(new LinktorConfigDto(false, "http://localhost:8081/api/v1", "", "", "", 30));
    }

    @AfterEach
    void tearDown() {
        System.clearProperty(McpCommandPolicy.ALLOWED_COMMANDS_PROPERTY);
    }

    private MockMvc build(LinktorConfigDto initial) {
        controller = new LinktorConfigControllerImpl(initial);
        return MockMvcBuilders.standaloneSetup(new SpringLinktorConfigController(controller))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    private MvcResult putConfig(Object body) throws Exception {
        return mvc.perform(put("/api/admin/linktor")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(body)))
                .andReturn();
    }

    private ObjectNode getConfig() throws Exception {
        MvcResult r = mvc.perform(get("/api/admin/linktor")).andReturn();
        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        return (ObjectNode) json.readTree(r.getResponse().getContentAsString());
    }

    @Test
    @DisplayName("comando fora da allow-list: 400 com frase que nomeia os launchers e a variável, nada salvo")
    void comandoForaDaAllowList() throws Exception {
        ObjectNode body = getConfig();
        body.put("enabled", true);
        body.put("mcpCommand", "linktor-mcp");

        MvcResult r = putConfig(body);

        assertThat(r.getResponse().getStatus()).isEqualTo(400);
        JsonNode erro = json.readTree(r.getResponse().getContentAsString());
        assertThat(erro.path("error").asText())
                .contains("'linktor-mcp' is not on the allow-list")
                .contains("npx").contains("node")
                .contains(McpCommandPolicy.ALLOWED_COMMANDS_ENV);
        assertThat(getConfig().path("enabled").asBoolean()).isFalse();
        assertThat(getConfig().path("mcpCommand").asText()).isEmpty();
    }

    @Test
    @DisplayName("comando com launcher permitido: salva")
    void comandoPermitido() throws Exception {
        ObjectNode body = getConfig();
        body.put("enabled", true);
        body.put("mcpCommand", "node /opt/linktor-mcp-server/dist/index.js");

        MvcResult r = putConfig(body);

        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        assertThat(getConfig().path("mcpCommand").asText()).isEqualTo("node /opt/linktor-mcp-server/dist/index.js");
    }

    @ParameterizedTest(name = "segredo de {0} caractere(s)")
    @ValueSource(ints = {1, 3, 8, 9, 32})
    @DisplayName("GET → PUT do mesmo corpo mantém a chave e o token originais, qualquer que seja o tamanho")
    void segredoMascaradoMantido(int tamanho) throws Exception {
        String chave = "k".repeat(tamanho - 1) + "1";
        String token = "t".repeat(tamanho - 1) + "2";
        mvc = build(new LinktorConfigDto(true, "http://localhost:8081/api/v1", chave, token,
                "npx linktor", 30));

        ObjectNode body = getConfig();
        assertThat(body.path("apiKey").asText()).isNotEqualTo(chave);
        body.put("timeoutSeconds", 45);
        MvcResult r = putConfig(body);

        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        LinktorConfigDto gravada = controller.rawSnapshot();
        assertThat(gravada.apiKey()).isEqualTo(chave);
        assertThat(gravada.accessToken()).isEqualTo(token);
        assertThat(gravada.timeoutSeconds()).isEqualTo(45);
    }

    @Test
    @DisplayName("segredo novo digitado substitui o guardado")
    void segredoNovoSubstitui() throws Exception {
        mvc = build(new LinktorConfigDto(false, "http://localhost:8081/api/v1", "abc", "", "", 30));

        ObjectNode body = getConfig();
        body.put("apiKey", "nova-chave-123");
        putConfig(body);

        assertThat(controller.rawSnapshot().apiKey()).isEqualTo("nova-chave-123");
    }

    @Test
    @DisplayName("campo apagado (em branco) remove a chave e o token guardados — antes só um restart os tirava")
    void segredoEmBrancoRemove() throws Exception {
        // RCHFLW-193: o vazio também significava "manter", e o segredo gravado ficava irremovível.
        mvc = build(new LinktorConfigDto(true, "http://localhost:8081/api/v1", "qa2-fake-linktor-key-123456",
                "qa2-fake-access-token-654321", "npx linktor", 30));

        ObjectNode body = getConfig();
        body.put("apiKey", "");
        body.put("accessToken", "  ");
        MvcResult r = putConfig(body);

        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        assertThat(controller.rawSnapshot().apiKey()).isEmpty();
        assertThat(controller.rawSnapshot().accessToken()).isEmpty();
        assertThat(getConfig().path("apiKey").asText()).isEmpty();
    }

    @Test
    @DisplayName("campo ausente (null) mantém o segredo: o cliente que só mexe nos outros campos não o apaga")
    void segredoAusenteMantido() throws Exception {
        mvc = build(new LinktorConfigDto(true, "http://localhost:8081/api/v1", "qa2-fake-linktor-key-123456",
                "", "npx linktor", 30));

        ObjectNode body = getConfig();
        body.remove("apiKey");
        body.putNull("accessToken");
        body.put("timeoutSeconds", 60);
        putConfig(body);

        assertThat(controller.rawSnapshot().apiKey()).isEqualTo("qa2-fake-linktor-key-123456");
        assertThat(controller.rawSnapshot().timeoutSeconds()).isEqualTo(60);
    }
}
