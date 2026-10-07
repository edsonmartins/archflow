package br.com.archflow.api.agent.stream;

import br.com.archflow.api.agent.mcp.SaidaDeTexto;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.function.LongSupplier;

/**
 * Uma conexão SSE de resposta em fluxo: escreve os eventos numerando-os, detecta que o cliente
 * saiu e vira o {@link SaidaDeTexto} do laço do agente.
 *
 * <p>Fechar a conexão cancela: a primeira escrita que falha completa o {@link #cancelamento()}.
 * Há batimento (um comentário SSE) quando nada foi escrito por um intervalo — é ele que revela uma
 * conexão morta enquanto o modelo está calado, e que impede proxy de fechar a conexão ociosa.</p>
 */
public final class SessaoSse implements SaidaDeTexto {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final SseEmitter emitter;
    private final CompletableFuture<Void> cancelamento = new CompletableFuture<>();
    private final boolean transmiteTexto;
    private final LongSupplier relogioNanos;
    private final StringBuilder texto = new StringBuilder();
    private long seq;
    private boolean aberta = true;
    private boolean fimEntregue;
    private long ultimoEnvioNanos;

    public SessaoSse(SseEmitter emitter, boolean transmiteTexto) {
        this(emitter, transmiteTexto, System::nanoTime);
    }

    SessaoSse(SseEmitter emitter, boolean transmiteTexto, LongSupplier relogioNanos) {
        this.emitter = emitter;
        this.transmiteTexto = transmiteTexto;
        this.relogioNanos = relogioNanos;
        this.ultimoEnvioNanos = relogioNanos.getAsLong();
        // O cliente saiu (ou o container desistiu da conexão): é um pedido de parar.
        emitter.onCompletion(this::clienteSaiu);
        emitter.onError(e -> clienteSaiu());
        emitter.onTimeout(this::clienteSaiu);
    }

    private void clienteSaiu() {
        synchronized (this) {
            aberta = false;
        }
        cancelamento.complete(null);
    }

    // ── SaidaDeTexto ─────────────────────────────────────────────────

    @Override
    public boolean transmite() {
        return transmiteTexto;
    }

    @Override
    public void delta(String pedaco) {
        synchronized (this) {
            texto.append(pedaco);
        }
        enviar("delta", node -> node.put("texto", pedaco));
    }

    @Override
    public void tool(String nome, boolean inicio, Long duracaoMs) {
        enviar("tool", node -> {
            node.put("nome", nome);
            node.put("fase", inicio ? "INICIO" : "FIM");
            if (!inicio && duracaoMs != null) {
                node.put("duracaoMs", duracaoMs);
            }
        });
    }

    @Override
    public CompletableFuture<Void> cancelamento() {
        return cancelamento;
    }

    // ── Eventos do protocolo ────────────────────────────────────────

    public boolean inicio(String idempotencyKey, String traceId) {
        return enviar("inicio", node -> {
            node.put("idempotencyKey", idempotencyKey);
            if (traceId != null) {
                node.put("traceId", traceId);
            }
        });
    }

    /** O texto que já saiu em {@code delta}. */
    public synchronized String textoEmitido() {
        return texto.toString();
    }

    /**
     * O {@code fim}: o resultado completo, com {@code seq}. {@code true} quando a escrita foi
     * aceita pela conexão — e então não há webhook.
     */
    public boolean fim(Object resultado) {
        boolean ok = enviar("fim", node -> node.setAll((ObjectNode) JSON.valueToTree(resultado)));
        synchronized (this) {
            fimEntregue = ok;
        }
        if (ok) {
            encerrar();
        }
        return ok;
    }

    /** {@code erro}: encerra a conexão. O resultado de erro vai pelo webhook. */
    public void erro(String mensagem, boolean recuperavel) {
        enviar("erro", node -> {
            node.put("error", mensagem);
            node.put("recuperavel", recuperavel);
        });
        encerrar();
    }

    public synchronized boolean fimEntregue() {
        return fimEntregue;
    }

    /** Escreve um comentário SSE se nada foi escrito há {@code intervaloNanos}. */
    public void batimento(long intervaloNanos) {
        synchronized (this) {
            if (!aberta || relogioNanos.getAsLong() - ultimoEnvioNanos < intervaloNanos) {
                return;
            }
            try {
                emitter.send(SseEmitter.event().comment("batimento"));
                ultimoEnvioNanos = relogioNanos.getAsLong();
            } catch (IOException | RuntimeException e) {
                aberta = false;
            }
        }
        if (!aberta) {
            cancelamento.complete(null);
        }
    }

    public synchronized boolean aberta() {
        return aberta;
    }

    public void encerrar() {
        synchronized (this) {
            if (!aberta) {
                return;
            }
            aberta = false;
        }
        try {
            emitter.complete();
        } catch (RuntimeException ignorado) {
            // já encerrada pelo container
        }
    }

    private boolean enviar(String evento, java.util.function.Consumer<ObjectNode> campos) {
        boolean falhou = false;
        synchronized (this) {
            if (!aberta) {
                return false;
            }
            try {
                ObjectNode dados = JSON.createObjectNode();
                dados.put("seq", seq);
                campos.accept(dados);
                emitter.send(SseEmitter.event().name(evento).data(JSON.writeValueAsString(dados)));
                seq++;
                ultimoEnvioNanos = relogioNanos.getAsLong();
                return true;
            } catch (IOException | RuntimeException e) {
                aberta = false;
                falhou = true;
            }
        }
        if (falhou) {
            // A escrita falhou: o cliente foi embora. Fechar a conexão cancela.
            cancelamento.complete(null);
        }
        return false;
    }
}
