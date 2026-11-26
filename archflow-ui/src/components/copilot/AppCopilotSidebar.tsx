import { useMemo } from 'react';
import {
    CopilotModalHeader,
    CopilotSidebar,
    type CopilotChatLabels,
    type CopilotModalHeaderProps,
} from '@copilotkit/react-core/v2';
import type { TFunction } from 'i18next';
import { useTranslation } from 'react-i18next';
import { usePermissions } from '../../lib/permissions';

/**
 * Todos os textos visíveis do chat do CopilotKit, vindos do i18n (RCHFLW-82). Sem a prop {@code labels}
 * a biblioteca usa os padrões dela, em inglês ("How can I help you today?", "Type a message...", "Open
 * chat"), no meio de uma interface em português.
 *
 * <p>O tipo de retorno é o {@code CopilotChatLabels} completo, não {@code Partial}: se uma versão nova da
 * biblioteca acrescentar um rótulo, o tsc acusa aqui em vez de o texto novo aparecer em inglês.</p>
 */
function copilotChatLabels(t: TFunction): CopilotChatLabels {
    return {
        chatInputPlaceholder: t('copilot.chat.inputPlaceholder'),
        chatInputToolbarStartTranscribeButtonLabel: t('copilot.chat.startTranscribe'),
        chatInputToolbarCancelTranscribeButtonLabel: t('copilot.chat.cancelTranscribe'),
        chatInputToolbarFinishTranscribeButtonLabel: t('copilot.chat.finishTranscribe'),
        chatInputToolbarAddButtonLabel: t('copilot.chat.addAttachments'),
        chatInputToolbarToolsButtonLabel: t('copilot.chat.tools'),
        assistantMessageToolbarCopyCodeLabel: t('copilot.chat.copyCode'),
        assistantMessageToolbarCopyCodeCopiedLabel: t('copilot.chat.copyCodeCopied'),
        assistantMessageToolbarCopyMessageLabel: t('copilot.chat.copyMessage'),
        assistantMessageToolbarThumbsUpLabel: t('copilot.chat.thumbsUp'),
        assistantMessageToolbarThumbsDownLabel: t('copilot.chat.thumbsDown'),
        assistantMessageToolbarReadAloudLabel: t('copilot.chat.readAloud'),
        assistantMessageToolbarRegenerateLabel: t('copilot.chat.regenerate'),
        userMessageToolbarCopyMessageLabel: t('copilot.chat.copyMessage'),
        userMessageToolbarEditMessageLabel: t('copilot.chat.editMessage'),
        chatDisclaimerText: t('copilot.chat.disclaimer'),
        chatToggleOpenLabel: t('copilot.chat.toggleOpen'),
        chatToggleCloseLabel: t('copilot.chat.toggleClose'),
        modalHeaderTitle: t('copilot.chat.headerTitle'),
        welcomeMessageText: t('copilot.chat.welcome'),
    };
}

/**
 * O cabeçalho da própria biblioteca, com os dois botões que ficam fora de {@code labels}: o X de fechar
 * e o abridor de conversas (só no celular) trazem aria-label fixo em inglês ("Close", "Open threads").
 * Os slots {@code closeButton}/{@code drawerLauncher} aceitam props, e a biblioteca espalha essas props
 * depois do aria-label padrão — então traduzir é só passá-lo, sem trocar componente nenhum.
 *
 * <p>Vai como componente (identidade estável no módulo), não como objeto: o CopilotSidebar remonta a
 * barra inteira quando a prop {@code header} muda de identidade, e um objeto refeito a cada troca de
 * idioma reabriria a barra no estado inicial (fechada), perdendo o que o usuário tinha digitado.</p>
 */
function TranslatedHeaderView(props: CopilotModalHeaderProps) {
    const { t } = useTranslation();
    return (
        <CopilotModalHeader
            {...props}
            closeButton={{ 'aria-label': t('copilot.chat.toggleClose') }}
            drawerLauncher={{ 'aria-label': t('copilot.chat.openThreads') }}
        />
    );
}

// O slot tipa o componente como typeof CopilotModalHeader, com os subcomponentes estáticos; leva os da
// própria biblioteca, como ela mesma faz com a vista da barra (Object.assign no CopilotSidebar).
const TranslatedHeader = Object.assign(TranslatedHeaderView, {
    displayName: 'TranslatedCopilotModalHeader',
    Title: CopilotModalHeader.Title,
    CloseButton: CopilotModalHeader.CloseButton,
    DrawerLauncher: CopilotModalHeader.DrawerLauncher,
});

/**
 * A barra lateral do copilot montada no layout, com os rótulos no idioma atual da interface.
 *
 * <p>Só para quem tem {@code workflow:execute}: cada mensagem é um {@code POST /ag-ui/agent}, que o servidor
 * recusa (403) sem ela (QA2 RCHFLW-158). Um VIEWER via o chat, digitava e recebia erro a cada envio.</p>
 */
export default function AppCopilotSidebar() {
    // O react-i18next troca a identidade de t quando o idioma muda: trocar pelo seletor refaz os
    // rótulos sem remontar o chat (e sem perder a conversa aberta).
    const { t } = useTranslation();
    const labels = useMemo(() => copilotChatLabels(t), [t]);
    const canRunAgent = usePermissions()('workflow:execute');
    if (!canRunAgent) return null;
    return <CopilotSidebar agentId="archflow" defaultOpen={false} labels={labels} header={TranslatedHeader} />;
}
