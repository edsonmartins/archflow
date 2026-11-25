package br.com.archflow.api.linktor;

import br.com.archflow.api.linktor.dto.LinktorConfigDto;
import br.com.archflow.api.linktor.impl.LinktorConfigControllerImpl;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Um Linktor de mentira num servidor HTTP local: guarda cada requisição como chegou (método, caminho
 * com query, corpo, chave de API) e responde o que o teste combinou para {@code "MÉTODO /caminho"}.
 *
 * <p>Os testes do contrato leem o corpo cru que saiu do archflow — afirmar sobre o {@code Map} que o
 * cliente montou não provaria o que o Linktor recebe. As respostas combinadas seguem o envelope real do
 * Linktor ({@code {"success", "data"}}, {@code internal/api/handlers/response.go}).</p>
 */
public final class LinktorFalso implements AutoCloseable {

    public static final String CHAVE = "chave-de-teste";

    /** Uma requisição como o Linktor a recebeu. */
    public record Requisicao(String metodo, String caminho, String corpo, String chaveDeApi) {}

    private record Resposta(int status, String corpo) {}

    private final HttpServer server;
    private final List<Requisicao> recebidas = new CopyOnWriteArrayList<>();
    private final Map<String, Resposta> respostas = new ConcurrentHashMap<>();

    public LinktorFalso() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1", exchange -> {
            String caminho = exchange.getRequestURI().getRawPath().substring("/api/v1".length());
            String query = exchange.getRequestURI().getRawQuery();
            String completo = query == null ? caminho : caminho + "?" + query;
            String corpo = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            recebidas.add(new Requisicao(exchange.getRequestMethod(), completo, corpo,
                    exchange.getRequestHeaders().getFirst("X-API-Key")));
            Resposta r = respostas.getOrDefault(exchange.getRequestMethod() + " " + caminho,
                    new Resposta(404, "{\"success\":false,\"error\":{\"code\":\"NOT_FOUND\"}}"));
            byte[] bytes = r.corpo().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(r.status(), bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
    }

    /** Combina a resposta de {@code metodo caminho} (caminho sem a query). */
    public LinktorFalso responde(String metodo, String caminho, int status, String corpo) {
        respostas.put(metodo + " " + caminho, new Resposta(status, corpo));
        return this;
    }

    public List<Requisicao> recebidas() {
        return List.copyOf(recebidas);
    }

    public String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/api/v1";
    }

    /** Cliente real, com a integração ligada e autenticando por chave de API, como numa instalação. */
    public LinktorHttpClient cliente() {
        return new LinktorHttpClient(new LinktorConfigControllerImpl(
                new LinktorConfigDto(true, baseUrl(), CHAVE, "", "", 5)));
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
