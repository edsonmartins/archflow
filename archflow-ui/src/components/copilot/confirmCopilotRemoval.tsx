import { Text } from '@mantine/core'
import { modals } from '@mantine/modals'

type Translate = (key: string, options?: Record<string, unknown>) => string

/**
 * Pergunta ao usuário se o copiloto pode remover um nó que ele NÃO criou (um nó que já estava no fluxo).
 *
 * <p>Por que perguntar, e não só avisar: no "Gerar com IA" a barra do copiloto está fechada, os toasts
 * "Removed …" passam entre dezenas de outros e o fluxo é salvo sem o nó se ninguém apertar Ctrl+Z. O agente
 * pode tomar um nó do usuário por "errado" — ou o objetivo pode trazer um texto que o mande apagar —, e um nó
 * do usuário só sai com o OK do usuário. Os nós que o próprio copiloto criou saem sem pergunta: corrigir o que
 * acabou de montar é o uso normal da ferramenta.</p>
 *
 * <p>Fechar o modal (Esc, clique fora) conta como "não": a remoção é a ação que precisa de consentimento.</p>
 */
export function confirmCopilotNodeRemoval(t: Translate, node: { id: string; label: string }): Promise<boolean> {
    return new Promise(resolve => {
        let settled = false
        // O ConfirmModal chama onConfirm e DEPOIS fecha (onClose): só a primeira resposta vale.
        const answer = (ok: boolean) => {
            if (settled) return
            settled = true
            resolve(ok)
        }
        modals.openConfirmModal({
            title: t('copilot.confirmRemoveNode.title'),
            children: (
                <Text size="sm">{t('copilot.confirmRemoveNode.message', { label: node.label, id: node.id })}</Text>
            ),
            labels: { confirm: t('copilot.confirmRemoveNode.confirm'), cancel: t('copilot.confirmRemoveNode.cancel') },
            confirmProps: { color: 'red' },
            onConfirm: () => answer(true),
            onCancel: () => answer(false),
            onClose: () => answer(false),
        })
    })
}
