package br.com.archflow.api.agent.mcp;

import br.com.archflow.model.engine.ExecutionContext;
import br.com.archflow.model.engine.ExecutionKeys;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Para onde o laço do agente manda o que produz <b>enquanto produz</b>: pedaços de texto, avisos de
 * tool e o sinal de cancelamento que vem de fora.
 *
 * <p>Sem uma saída, o laço é o de sempre — chama o modelo, espera a resposta inteira, devolve. Com
 * uma, e se {@link #transmite()}, o laço usa o modelo em streaming e entrega o texto da resposta
 * final por {@link #delta}. O que o laço <b>não</b> faz é interpretar o texto: não corta, não filtra,
 * não confere. Isso é de quem o recebe.</p>
 *
 * <p>Os {@code delta} são só os da resposta final. Quando o catálogo tem tools, um turno só se
 * mostra final quando termina sem pedir tool; então o texto desse turno fica retido até lá. Sem
 * tools, o texto sai à medida que chega.</p>
 *
 * <p>Implementações precisam ser thread-safe: o laço roda numa thread e o cancelamento chega noutra.</p>
 */
public interface SaidaDeTexto {

    /** Onde a saída viaja no contexto do fluxo; transitória, nunca vai para o estado durável. */
    String CONTEXT_KEY = ExecutionKeys.TRANSIENT_PREFIX + "saidaDeTexto";

    /**
     * Se o texto deve ser transmitido. Falso mantém o modelo bloqueante e só serve ao cancelamento
     * e aos avisos de tool — é o caso de um fluxo cuja saída não é texto.
     */
    default boolean transmite() {
        return true;
    }

    /** Um pedaço do texto da resposta final. */
    void delta(String texto);

    /** Um nó chamou uma tool. {@code duracaoMs} só acompanha o fim. */
    void tool(String nome, boolean inicio, Long duracaoMs);

    /** Completa quando alguém pediu para parar. O laço desiste de esperar o modelo na hora. */
    CompletableFuture<Void> cancelamento();

    default boolean cancelada() {
        return cancelamento().isDone();
    }

    /** A mesma saída sem o texto: cancelamento e avisos de tool, para os nós que não são o final. */
    default SaidaDeTexto semTexto() {
        SaidaDeTexto origem = this;
        return new SaidaDeTexto() {
            @Override
            public boolean transmite() {
                return false;
            }

            @Override
            public void delta(String texto) {
            }

            @Override
            public void tool(String nome, boolean inicio, Long duracaoMs) {
                origem.tool(nome, inicio, duracaoMs);
            }

            @Override
            public CompletableFuture<Void> cancelamento() {
                return origem.cancelamento();
            }
        };
    }

    /** Só o cancelamento — para quem quer poder parar uma execução que não transmite nada. */
    static SaidaDeTexto soCancelamento(CompletableFuture<Void> sinal) {
        return new SaidaDeTexto() {
            @Override
            public boolean transmite() {
                return false;
            }

            @Override
            public void delta(String texto) {
            }

            @Override
            public void tool(String nome, boolean inicio, Long duracaoMs) {
            }

            @Override
            public CompletableFuture<Void> cancelamento() {
                return sinal;
            }
        };
    }

    static void injetar(ExecutionContext context, SaidaDeTexto saida) {
        if (context != null && saida != null) {
            context.set(CONTEXT_KEY, saida);
        }
    }

    static Optional<SaidaDeTexto> de(ExecutionContext context) {
        if (context == null) {
            return Optional.empty();
        }
        return context.get(CONTEXT_KEY)
                .filter(SaidaDeTexto.class::isInstance)
                .map(SaidaDeTexto.class::cast);
    }
}
