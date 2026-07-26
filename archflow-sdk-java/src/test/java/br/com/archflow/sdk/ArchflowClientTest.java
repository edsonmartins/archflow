package br.com.archflow.sdk;

import br.com.archflow.dsl.Nodes;
import br.com.archflow.dsl.WorkflowDocument;
import br.com.archflow.dsl.Workflows;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * O cliente contra um servidor HTTP de verdade — o {@code HttpServer} do JDK,
 * sem dependência nova.
 *
 * <p>Um mock do {@code HttpClient} testaria que o cliente chama o que eu mandei
 * chamar, que é uma tautologia. Aqui as requisições saem pela rede local: o
 * caminho, o método, os cabeçalhos e o corpo são os que um servidor recebe.
 * O que <b>não</b> está testado é se o archflow-api responde como este esboço —
 * as rotas e os códigos abaixo foram lidos do {@code SpringWorkflowCrudController},
 * mas nada aqui prova que ele não mudou.
 */
@DisplayName("ArchflowClient")
class ArchflowClientTest {

    private HttpServer server;
    private ArchflowClient client;

    /** Requisições recebidas, para conferir cabeçalhos e corpo. */
    private final List<Recorded> received = new ArrayList<>();
    /** Fluxos "gravados", para simular existe/não existe. */
    private final Map<String, String> stored = new ConcurrentHashMap<>();

    private record Recorded(String method, String path, Map<String, String> headers, String body) {
    }

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/workflows", this::handle);
        server.start();

        client = ArchflowClient.builder()
                .baseUrl("http://127.0.0.1:" + server.getAddress().getPort())
                .apiKey("chave-de-teste")
                .build();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private void handle(HttpExchange exchange) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        String path = exchange.getRequestURI().getPath();
        Map<String, String> headers = new java.util.LinkedHashMap<>();
        exchange.getRequestHeaders().forEach((k, v) -> headers.put(k, String.join(",", v)));
        received.add(new Recorded(exchange.getRequestMethod(), path, headers, body));

        String id = path.replaceFirst("^/api/workflows/?", "").replaceFirst("/.*$", "");
        String response;
        int status;

        if ("POST".equals(exchange.getRequestMethod()) && path.endsWith("/execute")) {
            status = 200;
            response = "{\"id\":\"exec-1\",\"workflowId\":\"" + id + "\",\"status\":\"running\"}";
        } else if ("PUT".equals(exchange.getRequestMethod())) {
            // Espelha o servidor real: PUT devolve 404 se o fluxo não existe.
            if (stored.containsKey(id)) {
                stored.put(id, body);
                status = 200;
                response = "{\"id\":\"" + id + "\",\"status\":\"draft\"}";
            } else {
                status = 404;
                response = "";
            }
        } else if ("POST".equals(exchange.getRequestMethod())) {
            // ...e que POST IGNORA o id do corpo e cria um novo.
            String novo = "wf-abc123";
            stored.put(novo, body);
            status = 201;
            response = "{\"id\":\"" + novo + "\",\"status\":\"draft\"}";
        } else if ("DELETE".equals(exchange.getRequestMethod())) {
            status = stored.remove(id) != null ? 204 : 404;
            response = "";
        } else if (path.endsWith("/yaml")) {
            status = 200;
            response = "{\"yaml\":\"id: " + id + "\\n\"}";
        } else if (id.isEmpty()) {
            status = 200;
            response = "[{\"id\":\"wf-1\"},{\"id\":\"wf-2\"}]";
        } else if (stored.containsKey(id)) {
            status = 200;
            response = "{\"id\":\"" + id + "\"}";
        } else {
            status = 404;
            response = "";
        }

        byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
        if (bytes.length > 0) {
            exchange.getResponseBody().write(bytes);
        }
        exchange.close();
    }

    private static WorkflowDocument doc(String id) {
        return Workflows.define(id)
                .step("a", Nodes.component("input"))
                .step("b", Nodes.component("output"))
                .edge("a", "b")
                .build();
    }

    /**
     * O comportamento que o {@link PublishResult} existe para tornar visível: o
     * fluxo não existia, o POST criou com OUTRO id, e quem publicou precisa
     * saber disso — senão a próxima publicação cria outra cópia.
     */
    @Test
    @DisplayName("publicar um fluxo novo revela que o servidor trocou o id")
    void publishNewExposesTheServerAssignedId() {
        PublishResult result = client.publish(doc("meu-fluxo"));

        assertThat(result.created()).isTrue();
        assertThat(result.requestedId()).isEqualTo("meu-fluxo");
        assertThat(result.assignedId()).isEqualTo("wf-abc123");
        assertThat(result.idChanged())
                .as("sem isto, republicar criaria uma copia a cada vez, em silencio")
                .isTrue();

        assertThat(received).extracting(Recorded::method).containsExactly("PUT", "POST");
    }

    @Test
    @DisplayName("publicar um fluxo existente atualiza e preserva o id")
    void publishExistingUpdates() {
        stored.put("meu-fluxo", "{}");

        PublishResult result = client.publish(doc("meu-fluxo"));

        assertThat(result.created()).isFalse();
        assertThat(result.idChanged()).isFalse();
        assertThat(result.assignedId()).isEqualTo("meu-fluxo");
        assertThat(received).extracting(Recorded::method)
                .as("existindo, nao ha POST — nao se cria uma segunda copia")
                .containsExactly("PUT");
    }

    @Test
    @DisplayName("o corpo enviado é o documento canônico da DSL")
    void sendsTheCanonicalDocument() {
        client.publish(doc("f"));

        String enviado = received.get(0).body();
        assertThat(enviado).contains("\"steps\"").contains("\"connections\"");
        assertThat(enviado)
                .as("as arestas vao dentro do passo; um connections no topo seria a forma da UI")
                .doesNotContain("\"connections\" : [ {\n    \"sourceId\"");
    }

    @Test
    @DisplayName("a chave de API vai no cabeçalho X-API-Key")
    void sendsApiKeyHeader() {
        client.list();

        assertThat(received.get(0).headers())
                .containsEntry("X-api-key", "chave-de-teste");
    }

    @Test
    @DisplayName("bearer token vai em Authorization")
    void sendsBearerToken() {
        ArchflowClient comToken = ArchflowClient.builder()
                .baseUrl("http://127.0.0.1:" + server.getAddress().getPort())
                .bearerToken("jwt-abc")
                .build();

        comToken.list();

        assertThat(received.get(0).headers()).containsEntry("Authorization", "Bearer jwt-abc");
    }

    @Test
    @DisplayName("get devolve null quando o fluxo não existe, sem exceção")
    void getReturnsNullWhenAbsent() {
        assertThat(client.get("fantasma")).isNull();
    }

    @Test
    @DisplayName("get devolve o fluxo quando existe")
    void getReturnsWorkflow() {
        stored.put("existe", "{}");

        assertThat(client.get("existe")).containsEntry("id", "existe");
    }

    @Test
    @DisplayName("list devolve todos os fluxos")
    void listReturnsAll() {
        assertThat(client.list()).extracting(m -> m.get("id")).containsExactly("wf-1", "wf-2");
    }

    @Test
    @DisplayName("delete distingue removido de inexistente")
    void deleteDistinguishes() {
        stored.put("existe", "{}");

        assertThat(client.delete("existe")).isTrue();
        assertThat(client.delete("fantasma")).isFalse();
    }

    @Test
    @DisplayName("getYaml extrai o campo yaml da resposta")
    void getYamlUnwraps() {
        assertThat(client.getYaml("f")).isEqualTo("id: f\n");
    }

    @Test
    @DisplayName("execute envia o input e devolve o registro da execução")
    void executeSendsInput() {
        Map<String, Object> execucao = client.execute("f", Map.of("pergunta", "olá"));

        assertThat(execucao).containsEntry("id", "exec-1");
        assertThat(received.get(0).path()).isEqualTo("/api/workflows/f/execute");
        assertThat(received.get(0).body()).contains("pergunta").contains("olá");
    }

    @Test
    @DisplayName("erro do servidor vira exceção com status e corpo")
    void serverErrorCarriesStatus() {
        server.removeContext("/api/workflows");
        server.createContext("/api/workflows", exchange -> {
            byte[] b = "{\"error\":\"explodiu\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(500, b.length);
            exchange.getResponseBody().write(b);
            exchange.close();
        });

        assertThatThrownBy(() -> client.list())
                .isInstanceOf(ArchflowClientException.class)
                .hasMessageContaining("500")
                .hasMessageContaining("explodiu")
                .satisfies(e -> assertThat(((ArchflowClientException) e).getStatusCode()).isEqualTo(500));
    }

    @Test
    @DisplayName("servidor fora do ar vira ArchflowClientException, não IOException crua")
    void connectionFailure() {
        ArchflowClient morto = ArchflowClient.builder()
                .baseUrl("http://127.0.0.1:1")
                .build();

        assertThatThrownBy(morto::list)
                .isInstanceOf(ArchflowClientException.class)
                .hasMessageContaining("falha de comunicação");
    }

    @Test
    @DisplayName("baseUrl com barra final não gera caminho com barra dupla")
    void trailingSlashIsTrimmed() {
        ArchflowClient comBarra = ArchflowClient.builder()
                .baseUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/")
                .build();

        comBarra.list();

        assertThat(received.get(0).path()).isEqualTo("/api/workflows");
    }

    @Test
    @DisplayName("baseUrl é obrigatório")
    void baseUrlRequired() {
        assertThatThrownBy(() -> ArchflowClient.builder().build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("baseUrl");
    }
}
