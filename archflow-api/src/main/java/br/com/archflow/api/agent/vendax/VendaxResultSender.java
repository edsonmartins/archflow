package br.com.archflow.api.agent.vendax;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;

/**
 * Devolve o resultado do agente ao VendaX Core, em {@code POST /api/v1/public/archflow/result}.
 *
 * <p>É a metade de volta do par: o Core entrega o invoke e não espera na conexão, porque a execução
 * de um agente (LLM + tools) leva dezenas de segundos. Autenticado por HMAC-SHA256 sobre
 * {@code timestamp + "." + body}, como o webhook do Linktor — o Core recusa sem assinatura.</p>
 *
 * <p>Só faz conexões de saída, de propósito: assim o ArchFlow roda igualmente na nuvem ou como jar
 * local atrás de NAT, sem precisar ser alcançável de fora.</p>
 */
public class VendaxResultSender {

    private static final Logger log = LoggerFactory.getLogger(VendaxResultSender.class);

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private final ObjectMapper mapper = new ObjectMapper();

    private final String coreBaseUrl;
    private final String secret;

    public VendaxResultSender(String coreBaseUrl, String secret) {
        this.coreBaseUrl = coreBaseUrl;
        this.secret = secret;
    }

    public boolean isConfigured() {
        return coreBaseUrl != null && !coreBaseUrl.isBlank();
    }

    /** Envia o resultado. Não lança: falhar aqui não pode derrubar a execução já concluída. */
    public void send(VendaxResult result) {
        if (!isConfigured()) {
            log.warn("Resultado do agente {} descartado: archflow.vendax.core.base-url não configurada",
                    result.agent());
            return;
        }
        try {
            String body = mapper.writeValueAsString(result);
            String timestamp = String.valueOf(Instant.now().getEpochSecond());
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(coreBaseUrl.replaceAll("/+$", "")
                            + "/api/v1/public/archflow/result"))
                    .timeout(Duration.ofSeconds(30))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
            if (secret != null && !secret.isBlank()) {
                builder.header("X-Archflow-Timestamp", timestamp)
                       .header("X-Archflow-Signature", hmacHex(secret, timestamp + "." + body));
            }
            HttpResponse<String> response =
                    http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                log.warn("Core recusou o resultado do agente {} (HTTP {}): {}",
                        result.agent(), response.statusCode(), response.body());
                return;
            }
            log.info("Resultado do agente {} entregue ao Core (conv={})",
                    result.agent(), result.conversationId());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Envio do resultado interrompido (agente={})", result.agent());
        } catch (Exception e) {
            log.error("Falha ao entregar o resultado do agente {} ao Core: {}",
                    result.agent(), e.getMessage(), e);
        }
    }

    private String hmacHex(String key, String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] digest = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (Exception e) {
            throw new IllegalStateException("Falha ao assinar o resultado para o VendaX Core", e);
        }
    }
}
