package br.com.archflow.api.agent.mcp;

import br.com.archflow.langchain4j.provider.ProviderEndpoint;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Busca no provedor o custo de uma geração que voltou sem ele.
 *
 * <h2>Por que existe</h2>
 *
 * <p>O custo de um turno no OpenRouter vem em {@code usage.cost}, no corpo da resposta ou no evento
 * final do stream. Quando não vem, o turno entra sem custo e o total da execução fica desconhecido
 * — correto, porque soma parcial não é total. Medido no VendaX em 13/11 (VEN-1044): com a resposta
 * do Agente da Diretoria por tool, mais turnos por execução, 10 a 14 de cada 120 execuções saíam
 * sem custo, e o gate de promoção, que trata desconhecido como reprovação, reprovava todas as
 * versões.</p>
 *
 * <p>O OpenRouter guarda o custo de cada geração e o devolve em {@code GET /generation?id=…}
 * ({@code data.total_cost}, em USD). A consulta só leva o id: nada do prompt nem da resposta.</p>
 *
 * <h2>Contrato</h2>
 *
 * <p>Nunca lança e nunca inventa: o futuro completa com o custo ou com {@code null}. A estatística
 * da geração pode demorar a existir logo depois da resposta (404), então a consulta repete dentro de
 * um prazo curto — e desiste nele.</p>
 */
@FunctionalInterface
public interface CustoDaGeracao {

    /**
     * @param endpoint     onde e com que chave o turno foi feito
     * @param idDaGeracao  o id que o provedor deu à geração
     * @return o custo em USD, ou {@code null} se não foi possível obtê-lo no prazo
     */
    CompletableFuture<Double> consultar(ProviderEndpoint endpoint, String idDaGeracao);

    /**
     * A consulta do OpenRouter, repetindo em 404/5xx até {@code prazo}.
     *
     * @param prazo tempo total da consulta, contando as repetições
     */
    static CustoDaGeracao openRouter(Duration prazo) {
        return new OpenRouter(HttpClient.newBuilder().connectTimeout(prazo).build(), prazo,
                Duration.ofMillis(400));
    }

    /** O cliente do OpenRouter. Pacote-visível para teste com intervalo curto. */
    final class OpenRouter implements CustoDaGeracao {

        private static final Logger log = LoggerFactory.getLogger(CustoDaGeracao.class);
        private static final ObjectMapper JSON = new ObjectMapper();

        private final HttpClient http;
        private final Duration prazo;
        private final Duration intervalo;

        OpenRouter(HttpClient http, Duration prazo, Duration intervalo) {
            this.http = http;
            this.prazo = prazo;
            this.intervalo = intervalo;
        }

        @Override
        public CompletableFuture<Double> consultar(ProviderEndpoint endpoint, String idDaGeracao) {
            if (endpoint == null || idDaGeracao == null || idDaGeracao.isBlank()
                    || endpoint.apiKey() == null || endpoint.apiKey().isBlank()) {
                return CompletableFuture.completedFuture(null);
            }
            URI uri;
            try {
                String base = endpoint.baseUrl().endsWith("/")
                        ? endpoint.baseUrl().substring(0, endpoint.baseUrl().length() - 1)
                        : endpoint.baseUrl();
                uri = URI.create(base + "/generation?id="
                        + URLEncoder.encode(idDaGeracao, StandardCharsets.UTF_8));
            } catch (IllegalArgumentException e) {
                return CompletableFuture.completedFuture(null);
            }
            long limite = System.nanoTime() + prazo.toNanos();
            return tentar(uri, endpoint.apiKey(), limite, 1)
                    .thenApply(custo -> {
                        if (custo != null) {
                            log.info("Custo da geração {} obtido pela consulta: {} USD", idDaGeracao, custo);
                        } else {
                            log.info("Custo da geração {} não obtido em {} ms — o turno segue sem custo",
                                    idDaGeracao, prazo.toMillis());
                        }
                        return custo;
                    })
                    .exceptionally(e -> null);
        }

        private CompletableFuture<Double> tentar(URI uri, String chave, long limite, int tentativa) {
            long resta = limite - System.nanoTime();
            if (resta <= 0) {
                return CompletableFuture.completedFuture(null);
            }
            HttpRequest pedido = HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofNanos(resta))
                    .header("Authorization", "Bearer " + chave)
                    .header("Accept", "application/json")
                    .GET()
                    .build();
            return http.sendAsync(pedido, HttpResponse.BodyHandlers.ofString())
                    .handle((resposta, erro) -> {
                        if (erro == null && resposta.statusCode() == 200) {
                            Double custo = custoDoCorpo(resposta.body());
                            if (custo != null) {
                                return CompletableFuture.completedFuture(custo);
                            }
                        }
                        if (!repetivel(resposta, erro)) {
                            log.debug("Consulta da geração desistiu (status={}, erro={})",
                                    resposta == null ? null : resposta.statusCode(),
                                    erro == null ? null : erro.toString());
                            return CompletableFuture.<Double>completedFuture(null);
                        }
                        long espera = Math.min(intervalo.toNanos(), limite - System.nanoTime());
                        if (espera <= 0) {
                            return CompletableFuture.<Double>completedFuture(null);
                        }
                        return CompletableFuture.supplyAsync(() -> null,
                                        CompletableFuture.delayedExecutor(espera, TimeUnit.NANOSECONDS))
                                .thenCompose(x -> tentar(uri, chave, limite, tentativa + 1));
                    })
                    .thenCompose(f -> f);
        }

        /**
         * 404 é o caso esperado: a estatística ainda não existe. 200 sem custo, 429 e 5xx também
         * passam com o tempo. 401/403 não: a chave não vai mudar no meio da consulta.
         */
        private static boolean repetivel(HttpResponse<String> resposta, Throwable erro) {
            if (erro != null) {
                return !(erro instanceof java.net.http.HttpTimeoutException)
                        && !(erro.getCause() instanceof java.net.http.HttpTimeoutException);
            }
            int s = resposta.statusCode();
            return s == 200 || s == 404 || s == 429 || s >= 500;
        }

        static Double custoDoCorpo(String corpo) {
            if (corpo == null || corpo.isBlank()) {
                return null;
            }
            try {
                JsonNode custo = JSON.readTree(corpo).path("data").path("total_cost");
                return custo.isNumber() && Double.isFinite(custo.doubleValue()) && custo.doubleValue() >= 0
                        ? custo.doubleValue() : null;
            } catch (Exception e) {
                return null;
            }
        }
    }
}
