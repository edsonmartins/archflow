package br.com.archflow.sdk;

import br.com.archflow.dsl.WorkflowDocument;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Cliente do servidor archflow: leva ao servidor os fluxos escritos na DSL.
 *
 * <pre>{@code
 * ArchflowClient client = ArchflowClient.builder()
 *         .baseUrl("http://localhost:8080")
 *         .apiKey(System.getenv("ARCHFLOW_API_KEY"))
 *         .build();
 *
 * PublishResult r = client.publish(doc);
 * if (r.idChanged()) {
 *     // o servidor criou com outro id — guarde-o para atualizar depois
 * }
 * Map<String, Object> execucao = client.execute(r.assignedId(), Map.of("input", "olá"));
 * }</pre>
 *
 * <p>Sem biblioteca HTTP de terceiro: usa o {@link HttpClient} do JDK, para não
 * impor uma versão de OkHttp/Apache ao classpath de quem usa o SDK.
 *
 * <p>Thread-safe: o {@link HttpClient} subjacente é, e esta classe é imutável
 * depois de construída.
 *
 * @since 1.1.0
 */
public final class ArchflowClient {

    private static final TypeReference<LinkedHashMap<String, Object>> MAP =
            new TypeReference<>() { };
    private static final TypeReference<List<Map<String, Object>>> LIST =
            new TypeReference<>() { };

    private final HttpClient http;
    private final ObjectMapper mapper = new ObjectMapper();
    private final URI baseUri;
    private final String authHeader;
    private final String authValue;
    private final Duration requestTimeout;

    private ArchflowClient(Builder builder) {
        this.baseUri = URI.create(builder.baseUrl.replaceAll("/+$", ""));
        this.authHeader = builder.authHeader;
        this.authValue = builder.authValue;
        this.requestTimeout = builder.requestTimeout;
        this.http = builder.httpClient != null ? builder.httpClient
                : HttpClient.newBuilder().connectTimeout(builder.connectTimeout).build();
    }

    public static Builder builder() {
        return new Builder();
    }

    // ------------------------------------------------------------- workflows

    /**
     * Publica o fluxo.
     *
     * <p>Tenta primeiro atualizar o fluxo cujo id a DSL declarou
     * ({@code PUT /api/workflows/{id}}). Se não existir, cria
     * ({@code POST /api/workflows}) — e aí <b>o servidor atribui um id novo</b>,
     * descartando o declarado. Ver {@link PublishResult}, que expõe os dois.
     *
     * <p>Este vai-e-vem existe porque a API não oferece upsert por id do
     * cliente. Escondê-lo atrás de um retorno {@code String} faria toda
     * republicação criar uma cópia sem que ninguém percebesse.
     */
    public PublishResult publish(WorkflowDocument document) {
        Objects.requireNonNull(document, "document");
        String declaredId = document.id();
        String payload = document.toJson();

        HttpResponse<String> update = send(request("/api/workflows/" + encode(declaredId))
                .PUT(HttpRequest.BodyPublishers.ofString(payload))
                .header("Content-Type", "application/json")
                .build());

        if (update.statusCode() == 200) {
            Map<String, Object> body = parseMap(update.body());
            return new PublishResult(declaredId, String.valueOf(body.getOrDefault("id", declaredId)),
                    false, body);
        }
        if (update.statusCode() != 404) {
            throw new ArchflowClientException(
                    "falha ao atualizar o fluxo " + declaredId, update.statusCode(), update.body());
        }

        HttpResponse<String> create = send(request("/api/workflows")
                .POST(HttpRequest.BodyPublishers.ofString(payload))
                .header("Content-Type", "application/json")
                .build());
        if (create.statusCode() != 201 && create.statusCode() != 200) {
            throw new ArchflowClientException(
                    "falha ao criar o fluxo " + declaredId, create.statusCode(), create.body());
        }
        Map<String, Object> body = parseMap(create.body());
        Object assigned = body.get("id");
        if (assigned == null) {
            throw new ArchflowClientException(
                    "o servidor criou o fluxo mas não devolveu id; sem ele não há como atualizá-lo depois",
                    create.statusCode(), create.body());
        }
        return new PublishResult(declaredId, String.valueOf(assigned), true, body);
    }

    /** O fluxo gravado, ou {@code null} se não existir. */
    public Map<String, Object> get(String workflowId) {
        HttpResponse<String> response = send(request("/api/workflows/" + encode(workflowId))
                .GET().build());
        if (response.statusCode() == 404) {
            return null;
        }
        requireOk(response, "falha ao ler o fluxo " + workflowId);
        return parseMap(response.body());
    }

    /** Todos os fluxos do servidor. */
    public List<Map<String, Object>> list() {
        HttpResponse<String> response = send(request("/api/workflows").GET().build());
        requireOk(response, "falha ao listar os fluxos");
        try {
            return mapper.readValue(response.body(), LIST);
        } catch (IOException e) {
            throw new ArchflowClientException("resposta ilegível ao listar os fluxos", e);
        }
    }

    /** O fluxo em YAML — o mesmo texto que a aba "Code" do designer mostra. */
    public String getYaml(String workflowId) {
        HttpResponse<String> response =
                send(request("/api/workflows/" + encode(workflowId) + "/yaml").GET().build());
        requireOk(response, "falha ao ler o YAML do fluxo " + workflowId);
        Object yaml = parseMap(response.body()).get("yaml");
        return yaml == null ? null : yaml.toString();
    }

    /** Remove o fluxo. {@code false} se ele já não existia. */
    public boolean delete(String workflowId) {
        HttpResponse<String> response =
                send(request("/api/workflows/" + encode(workflowId)).DELETE().build());
        if (response.statusCode() == 404) {
            return false;
        }
        requireOk(response, "falha ao remover o fluxo " + workflowId);
        return true;
    }

    // ------------------------------------------------------------- execução

    /**
     * Dispara uma execução e devolve o registro criado pelo servidor (que traz
     * o id da execução).
     *
     * <p>A execução é <b>assíncrona</b> no servidor: o retorno significa "aceita
     * e enfileirada", não "concluída". Um fluxo com um passo de aprovação, por
     * exemplo, vai ficar suspenso esperando uma pessoa.
     */
    public Map<String, Object> execute(String workflowId, Map<String, Object> input) {
        String payload = writeJson(input == null ? Map.of() : input);
        HttpResponse<String> response =
                send(request("/api/workflows/" + encode(workflowId) + "/execute")
                        .POST(HttpRequest.BodyPublishers.ofString(payload))
                        .header("Content-Type", "application/json")
                        .build());
        requireOk(response, "falha ao executar o fluxo " + workflowId);
        return parseMap(response.body());
    }

    // ------------------------------------------------------------- interno

    private HttpRequest.Builder request(String path) {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(baseUri.resolve(path))
                .timeout(requestTimeout)
                .header("Accept", "application/json");
        if (authHeader != null) {
            builder.header(authHeader, authValue);
        }
        return builder;
    }

    private HttpResponse<String> send(HttpRequest request) {
        try {
            return http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new ArchflowClientException("falha de comunicação com " + request.uri(), e);
        } catch (InterruptedException e) {
            // Restaurar a flag: engolir a interrupção deixaria o chamador sem
            // saber que alguém pediu para parar.
            Thread.currentThread().interrupt();
            throw new ArchflowClientException("interrompido ao chamar " + request.uri(), e);
        }
    }

    private static void requireOk(HttpResponse<String> response, String message) {
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new ArchflowClientException(message, response.statusCode(), response.body());
        }
    }

    private Map<String, Object> parseMap(String json) {
        try {
            return mapper.readValue(json, MAP);
        } catch (IOException e) {
            throw new ArchflowClientException("resposta do servidor não é JSON de objeto", e);
        }
    }

    private String writeJson(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (IOException e) {
            throw new ArchflowClientException("falha ao serializar o corpo da requisição", e);
        }
    }

    private static String encode(String pathSegment) {
        return java.net.URLEncoder.encode(pathSegment, java.nio.charset.StandardCharsets.UTF_8)
                // URLEncoder é de formulário: espaço vira '+', que num caminho é
                // um '+' literal e não um espaço.
                .replace("+", "%20");
    }

    /** Construtor do cliente. */
    public static final class Builder {

        private String baseUrl;
        private String authHeader;
        private String authValue;
        private Duration connectTimeout = Duration.ofSeconds(10);
        private Duration requestTimeout = Duration.ofSeconds(30);
        private HttpClient httpClient;

        private Builder() {
        }

        /** Ex.: {@code http://localhost:8080}. Barras finais são ignoradas. */
        public Builder baseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
            return this;
        }

        /** Autentica com {@code X-API-Key}. */
        public Builder apiKey(String apiKey) {
            this.authHeader = "X-API-Key";
            this.authValue = apiKey;
            return this;
        }

        /** Autentica com {@code Authorization: Bearer <token>}. */
        public Builder bearerToken(String token) {
            this.authHeader = "Authorization";
            this.authValue = "Bearer " + token;
            return this;
        }

        public Builder connectTimeout(Duration timeout) {
            this.connectTimeout = timeout;
            return this;
        }

        public Builder requestTimeout(Duration timeout) {
            this.requestTimeout = timeout;
            return this;
        }

        /** Injeta um {@link HttpClient} próprio (proxy, TLS, executor dedicado). */
        public Builder httpClient(HttpClient httpClient) {
            this.httpClient = httpClient;
            return this;
        }

        public ArchflowClient build() {
            if (baseUrl == null || baseUrl.isBlank()) {
                throw new IllegalArgumentException("baseUrl é obrigatório");
            }
            return new ArchflowClient(this);
        }
    }
}
