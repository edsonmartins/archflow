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

    /** Tentativas do relato de uso: a primeira e mais duas. */
    static final int TENTATIVAS_DO_RELATO = 3;
    private volatile Duration esperaEntreTentativas = Duration.ofMillis(500);

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
            HttpResponse<String> response = postarAssinado("/api/v1/public/archflow/result",
                    mapper.writeValueAsString(result));
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

    /**
     * Relata o consumo de uma execução que não vai num result. Não lança.
     *
     * <p>Reentrega em falha de rede e 5xx — o Core conta cada (execução, motivo) uma vez, então
     * repetir é seguro. Não reentrega em 4xx: 400 é um relato que nunca vai caber (motivo
     * desconhecido, sem {@code execucaoId}), e 401 é assinatura errada, que repetir não conserta.</p>
     *
     * <p>As tentativas são poucas e curtas porque rodam na thread de quem executou o agente.</p>
     */
    public void relatarUso(VendaxUsoRelato relato) {
        if (!isConfigured()) {
            log.warn("Relato de uso ({}) do agente {} descartado: archflow.vendax.core.base-url não configurada",
                    relato.motivo(), relato.agent());
            return;
        }
        String body;
        try {
            body = mapper.writeValueAsString(relato);
        } catch (Exception e) {
            log.error("Relato de uso do agente {} não serializou: {}", relato.agent(), e.getMessage());
            return;
        }
        for (int tentativa = 1; tentativa <= TENTATIVAS_DO_RELATO; tentativa++) {
            try {
                HttpResponse<String> response = postarAssinado("/api/v1/public/archflow/uso", body);
                int status = response.statusCode();
                if (status / 100 == 2) {
                    log.info("Uso {} do agente {} relatado ao Core (execucao={}, tokens={})",
                            relato.motivo(), relato.agent(), relato.uso().execucaoId(),
                            relato.uso().tokens());
                    return;
                }
                if (status / 100 == 4) {
                    log.error("Core recusou o relato de uso {} do agente {} (HTTP {}), sem nova tentativa: {}",
                            relato.motivo(), relato.agent(), status, response.body());
                    return;
                }
                log.warn("Core falhou ao gravar o relato de uso {} (HTTP {}), tentativa {}/{}",
                        relato.motivo(), status, tentativa, TENTATIVAS_DO_RELATO);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("Relato de uso {} interrompido (agente={})", relato.motivo(), relato.agent());
                return;
            } catch (Exception e) {
                log.warn("Falha de rede no relato de uso {} (tentativa {}/{}): {}",
                        relato.motivo(), tentativa, TENTATIVAS_DO_RELATO, e.getMessage());
            }
            if (tentativa < TENTATIVAS_DO_RELATO && !esperar(tentativa)) {
                return;
            }
        }
        log.error("Relato de uso {} do agente {} perdido após {} tentativas (execucao={}, tokens={})",
                relato.motivo(), relato.agent(), TENTATIVAS_DO_RELATO,
                relato.uso().execucaoId(), relato.uso().tokens());
    }

    /** Espera crescente entre tentativas; {@code false} se a thread foi interrompida. */
    private boolean esperar(int tentativa) {
        try {
            Thread.sleep(esperaEntreTentativas.toMillis() * tentativa);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /** Para teste: sem espera real entre tentativas. */
    void setEsperaEntreTentativas(Duration espera) {
        this.esperaEntreTentativas = espera;
    }

    /** POST com a assinatura do webhook: HMAC-SHA256 de {@code timestamp + "." + body}. */
    private HttpResponse<String> postarAssinado(String caminho, String body)
            throws java.io.IOException, InterruptedException {
        String timestamp = String.valueOf(Instant.now().getEpochSecond());
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(coreBaseUrl.replaceAll("/+$", "") + caminho))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        if (secret != null && !secret.isBlank()) {
            builder.header("X-Archflow-Timestamp", timestamp)
                   .header("X-Archflow-Signature", hmacHex(secret, timestamp + "." + body));
        }
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
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
