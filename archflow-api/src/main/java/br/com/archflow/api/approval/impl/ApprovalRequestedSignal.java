package br.com.archflow.api.approval.impl;

import br.com.archflow.engine.lifecycle.FlowLifecycleListener;
import br.com.archflow.model.engine.ExecutionContext;
import br.com.archflow.model.flow.Flow;
import br.com.archflow.model.flow.FlowStep;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Avisa quem se inscreveu que um passo suspendeu a execução — hoje, um portão de aprovação que
 * acabou de abrir um pedido.
 *
 * <p>Existe para o {@link ApprovalTimeoutSweeper}: ele agendava a próxima varredura pelo prazo dos
 * pedidos que já existiam, e um pedido aberto depois (o caso comum) só era recusado no intervalo
 * seguinte — até 5 min depois do prazo, com a API dizendo EXPIRED e o registro da execução ainda
 * PAUSED (RCHFLW-126). O motor fotografa os ouvintes quando nasce, e o sweeper nasce depois dele
 * (depende da fila, que depende do motor): por isso este aviso, sem dependências, entra no motor e
 * o sweeper se inscreve nele depois.</p>
 *
 * <p>O callback roda na thread da travessia: quem se inscreve não pode bloquear nem decidir nada
 * aqui — o sweeper só põe uma varredura na fila dele.</p>
 */
public final class ApprovalRequestedSignal implements FlowLifecycleListener {

    private static final Logger log = LoggerFactory.getLogger(ApprovalRequestedSignal.class);

    private final CopyOnWriteArrayList<Runnable> subscribers = new CopyOnWriteArrayList<>();

    public void subscribe(Runnable subscriber) {
        subscribers.addIfAbsent(Objects.requireNonNull(subscriber, "subscriber"));
    }

    public void unsubscribe(Runnable subscriber) {
        subscribers.remove(subscriber);
    }

    @Override
    public void onStepSuspended(Flow flow, FlowStep step, ExecutionContext context, long durationMs) {
        for (Runnable subscriber : subscribers) {
            try {
                subscriber.run();
            } catch (RuntimeException e) {
                // Um aviso que falha não pode atrapalhar a suspensão: o sweeper ainda tem o intervalo.
                log.debug("Aviso de pedido de aprovação falhou: {}", e.getMessage());
            }
        }
    }
}
