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

    /**
     * Esperas antes de cada reentrega do result: 1 s, 3 s, 9 s — a primeira tentativa e mais três.
     *
     * <p>Medido em 22/09 no VendaX: um pico de carga esgotou o pool de conexões do Core por ~3 s, e 5
     * de 9 results chegaram, receberam 5xx e se perderam sem rastro — sentimento, cotação,
     * narração. A espera total (13 s) cobre um pico desse tamanho com folga. Mais tentativas
     * seguraria a thread do agente por um Core que está fora, não em pico.</p>
     */
    static final Duration[] ESPERAS_DO_RESULT = {
            Duration.ofSeconds(1), Duration.ofSeconds(3), Duration.ofSeconds(9)};

    /** Divisor das esperas do result — só para teste, que não pode dormir 13 s. */
    private volatile long escalaDasEsperasDoResult = 1;

    public VendaxResultSender(String coreBaseUrl, String secret) {
        this.coreBaseUrl = coreBaseUrl;
        this.secret = secret;
    }

    public boolean isConfigured() {
        return coreBaseUrl != null && !coreBaseUrl.isBlank();
    }

    /** Envia o resultado. Não lança: falhar aqui não pode derrubar a execução já concluída. */
    public void send(VendaxResult result) {
        send(result, null);
    }

    /**
     * Envia o resultado, reentregando em falha de rede e em 5xx. Não lança.
     *
     * <p>Repetir é seguro: o Core processa cada result pela {@code idempotencyKey} — a cotação tem a
     * guarda por mensagem, o CS fecha a leitura pelo trace, o uso conta uma vez por
     * {@code execucaoId}. 4xx não é reentregue: 401 é assinatura errada e 400 é corpo que o Core
     * não aceita, e nenhum dos dois passa a caber na tentativa seguinte.</p>
     *
     * <p>Roda na thread do executor do agente, que é limitado ({@code max-concurrency}): com o Core
     * fora, cada result segura a thread pelas esperas, a fila enche e novos invokes são recusados —
     * o outbox do Core é quem os guarda. Com o Core em pico, é o que dá a ele os segundos que
     * faltam. Não afeta o prazo da classe interativa: o prazo vale para a execução do agente, que
     * a esta altura já terminou; o que fazer com um result atrasado é decisão do Core.</p>
     *
     * @param traceId o do invoke, para quem procurar no Core o que se perdeu; pode ser nulo
     */
    public void send(VendaxResult result, String traceId) {
        if (!isConfigured()) {
            log.warn("Resultado do agente {} descartado: archflow.vendax.core.base-url não configurada",
                    result.agent());
            return;
        }
        String body;
        try {
            body = mapper.writeValueAsString(result);
        } catch (Exception e) {
            log.error("Resultado do agente {} não serializou (trace={}, chave={}): {}",
                    result.agent(), traceId, result.idempotencyKey(), e.getMessage());
            return;
        }
        String rotulo = "resultado do agente " + result.agent();
        Entrega entrega = entregar("/api/v1/public/archflow/result", body, rotulo,
                esperasDoResult());
        switch (entrega) {
            case ENTREGUE -> log.info("Resultado do agente {} entregue ao Core (conv={})",
                    result.agent(), result.conversationId());
            // Com o traceId e a chave: é o que o Core precisa para achar o que se perdeu.
            case RECUSADA, ESGOTADA -> log.error("Resultado do agente {} PERDIDO ({}; trace={}, "
                            + "chave={}, status={})", result.agent(),
                    entrega == Entrega.RECUSADA ? "recusado pelo Core, sem nova tentativa"
                            : "Core indisponível em todas as tentativas",
                    traceId, result.idempotencyKey(), result.status());
            case INTERROMPIDA -> log.warn("Envio do resultado interrompido (agente={}, trace={})",
                    result.agent(), traceId);
        }
    }

    private Duration[] esperasDoResult() {
        Duration[] esperas = new Duration[ESPERAS_DO_RESULT.length];
        for (int i = 0; i < esperas.length; i++) {
            esperas[i] = ESPERAS_DO_RESULT[i].dividedBy(escalaDasEsperasDoResult);
        }
        return esperas;
    }

    /** O que aconteceu com uma entrega ao Core. */
    enum Entrega { ENTREGUE, RECUSADA, ESGOTADA, INTERROMPIDA }

    /**
     * POST assinado com reentrega: falha de rede e 5xx tentam de novo, 4xx desiste na hora.
     *
     * <p>Um só laço para o result e o relato de uso. Eram dois, e só o do relato reentregava — o
     * controller do Core prometia "devolve 5xx para o ArchFlow reentregar" a um lado que nunca
     * reentregou o result. A assinatura é refeita a cada tentativa, com o timestamp novo.</p>
     *
     * @param esperas a espera antes de cada reentrega; o número de tentativas é {@code esperas + 1}
     */
    private Entrega entregar(String caminho, String body, String rotulo, Duration[] esperas) {
        int tentativas = esperas.length + 1;
        for (int tentativa = 1; tentativa <= tentativas; tentativa++) {
            try {
                HttpResponse<String> response = postarAssinado(caminho, body);
                int status = response.statusCode();
                if (status / 100 == 2) {
                    return Entrega.ENTREGUE;
                }
                if (status / 100 == 4) {
                    log.error("Core recusou o {} (HTTP {}), sem nova tentativa: {}",
                            rotulo, status, response.body());
                    return Entrega.RECUSADA;
                }
                log.warn("Core falhou ao receber o {} (HTTP {}), tentativa {}/{}",
                        rotulo, status, tentativa, tentativas);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return Entrega.INTERROMPIDA;
            } catch (Exception e) {
                log.warn("Falha de rede ao entregar o {} (tentativa {}/{}): {}",
                        rotulo, tentativa, tentativas, e.getMessage());
            }
            if (tentativa < tentativas && !esperar(esperas[tentativa - 1])) {
                return Entrega.INTERROMPIDA;
            }
        }
        return Entrega.ESGOTADA;
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
        Duration[] esperas = new Duration[TENTATIVAS_DO_RELATO - 1];
        for (int i = 0; i < esperas.length; i++) {
            esperas[i] = esperaEntreTentativas.multipliedBy(i + 1);
        }
        switch (entregar("/api/v1/public/archflow/uso", body,
                "relato de uso " + relato.motivo() + " do agente " + relato.agent(), esperas)) {
            case ENTREGUE -> log.info("Uso {} do agente {} relatado ao Core (execucao={}, tokens={})",
                    relato.motivo(), relato.agent(), relato.uso().execucaoId(), relato.uso().tokens());
            case RECUSADA -> { } // já registrado, com o corpo da resposta
            case ESGOTADA -> log.error("Relato de uso {} do agente {} perdido após {} tentativas "
                            + "(execucao={}, tokens={})", relato.motivo(), relato.agent(),
                    TENTATIVAS_DO_RELATO, relato.uso().execucaoId(), relato.uso().tokens());
            case INTERROMPIDA -> log.warn("Relato de uso {} interrompido (agente={})",
                    relato.motivo(), relato.agent());
        }
    }

    /** Espera antes da reentrega; {@code false} se a thread foi interrompida. */
    private boolean esperar(Duration espera) {
        try {
            Thread.sleep(espera.toMillis());
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

    /** Para teste: as esperas do result divididas por {@code divisor} (1 s, 3 s, 9 s → ms). */
    void setEscalaDasEsperasDoResult(long divisor) {
        this.escalaDasEsperasDoResult = divisor;
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
