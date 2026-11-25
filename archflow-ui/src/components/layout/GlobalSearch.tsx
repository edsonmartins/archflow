import { useEffect, useMemo, useState } from 'react';
import { Spotlight, type SpotlightActionGroupData } from '@mantine/spotlight';
import { IconMessageCircle, IconPlayerPlay, IconSearch, IconTemplate, IconTopologyRing } from '@tabler/icons-react';
import { useNavigate } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { executionApi, type ExecutionSummary } from '../../services/api';
import { conversationApi, type Conversation } from '../../services/conversation-api';
import { templateApi } from '../../services/template-api';
import type { WorkflowTemplate } from '../../data/templates';
import { useWorkflowStore } from '../../stores/workflow-store';
import { useTenantStore } from '../../stores/useTenantStore';

/**
 * Mesmo tamanho de página que a lista de Conversas pede com busca (ConversationsListPage): a busca
 * global não inventa um corte próprio para o que o servidor já filtrou.
 */
const CONVERSATION_PAGE_SIZE = 100;

/** Espera o usuário parar de digitar antes de pedir conversas ao servidor (uma busca por tecla seria ruído). */
const CONVERSATION_SEARCH_DELAY_MS = 250;

/** O tenant em que as requisições estão saindo agora (o personificado, ou o próprio quando nenhum). */
const currentTenant = () => useTenantStore.getState().impersonating?.id ?? null;

interface GlobalSearchProps {
    /** Páginas do menu, já montadas pelo layout (que é quem conhece o menu e o papel do usuário). */
    pageActions: SpotlightActionGroupData[];
}

/**
 * "Buscar em tudo" (⌘K) — RCHFLW-91. Antes só listava as páginas do menu e os 30 primeiros fluxos da
 * memória do navegador: execuções, conversas e modelos nunca apareciam, e o 31º fluxo era inalcançável.
 *
 * <p>Usa só os endpoints de lista que o app já chama (não há endpoint de busca no servidor):</p>
 * <ul>
 *   <li>fluxos — a lista inteira do store, sem corte;</li>
 *   <li>execuções — {@code GET /executions} sem limite, como o Histórico, recarregado a cada abertura
 *       (uma execução disparada há pouco precisa aparecer);</li>
 *   <li>conversas — {@code GET /conversations?search=}, a busca do servidor que a lista de Conversas
 *       usa: ela casa id, usuário, canal, persona, título e última mensagem, não só a primeira página;</li>
 *   <li>modelos — o catálogo de {@code templateApi.list}, o mesmo da galeria.</li>
 * </ul>
 *
 * <p>Cada fonte falha sozinha: o grupo dela fica vazio e o resto da busca continua funcionando.
 * Execuções, conversas e modelos só entram quando há texto digitado — com a busca vazia a lista
 * mostraria todas as execuções do tenant de uma vez.</p>
 *
 * <p>Execuções, conversas e modelos são do tenant da requisição, como fluxos (RCHFLW-73): ao trocar de
 * personificação o que está aqui é do tenant anterior e é descartado, e uma resposta pedida antes da
 * troca que chega depois dela é ignorada — senão listaria itens que no tenant novo respondem 404.</p>
 */
export default function GlobalSearch({ pageActions }: GlobalSearchProps) {
    const { t } = useTranslation();
    const navigate = useNavigate();
    const workflows = useWorkflowStore((s) => s.workflows);
    const fetchWorkflows = useWorkflowStore((s) => s.fetchWorkflows);
    const [query, setQuery] = useState('');
    const [executions, setExecutions] = useState<ExecutionSummary[]>([]);
    const [templates, setTemplates] = useState<WorkflowTemplate[]>([]);
    const [conversations, setConversations] = useState<Conversation[]>([]);
    const tenant = useTenantStore((s) => s.impersonating?.id ?? null);

    useEffect(() => { fetchWorkflows().catch(() => { /* opportunistic */ }); }, [fetchWorkflows]);

    // Troca de tenant: nada do anterior fica na busca até a próxima abertura recarregar.
    useEffect(() => {
        setExecutions([]);
        setTemplates([]);
        setConversations([]);
    }, [tenant]);

    const loadOnOpen = () => {
        const requestedIn = currentTenant();
        const keep = <T,>(set: (items: T[]) => void) => (items: T[]) => {
            if (currentTenant() === requestedIn) set(items);
        };
        executionApi.list()
            .then(keep(setExecutions))
            .catch(() => keep(setExecutions)([]));
        templateApi.list()
            .then(keep(setTemplates))
            .catch(() => keep(setTemplates)([]));
    };

    const trimmed = query.trim();

    useEffect(() => {
        if (!trimmed) {
            setConversations([]);
            return;
        }
        // Resposta de uma busca anterior (ou de antes de uma troca de tenant) que chega depois da atual
        // não pode sobrescrevê-la.
        let stale = false;
        const requestedIn = currentTenant();
        const isStale = () => stale || currentTenant() !== requestedIn;
        const timer = setTimeout(() => {
            conversationApi.listConversations({ search: trimmed, pageSize: CONVERSATION_PAGE_SIZE })
                .then((page) => { if (!isStale()) setConversations(page.items); })
                .catch(() => { if (!isStale()) setConversations([]); });
        }, CONVERSATION_SEARCH_DELAY_MS);
        return () => {
            stale = true;
            clearTimeout(timer);
        };
    }, [trimmed, tenant]);

    const actions = useMemo<SpotlightActionGroupData[]>(() => {
        const groups = [...pageActions];
        const push = (group: SpotlightActionGroupData) => {
            if (group.actions.length > 0) groups.push(group);
        };
        push({
            group: t('nav.workflows'),
            actions: workflows.map((w) => ({
                id: `search-wf-${w.id}`,
                label: w.name,
                description: w.description || undefined,
                keywords: [w.id],
                onClick: () => navigate(`/editor/${w.id}`),
                leftSection: <IconTopologyRing size={18} />,
            })),
        });
        if (!trimmed) return groups;
        push({
            group: t('nav.executions'),
            actions: executions.map((e) => ({
                id: `search-exec-${e.id}`,
                label: e.id,
                description: e.workflowName || e.workflowId,
                keywords: [e.workflowId, e.workflowName, e.status].filter(Boolean),
                onClick: () => navigate(`/executions/${e.id}`),
                leftSection: <IconPlayerPlay size={18} />,
            })),
        });
        push({
            group: t('nav.conversations'),
            actions: conversations.map((c) => ({
                id: `search-conv-${c.id}`,
                // Título quando há, senão o id — a mesma regra da lista de Conversas.
                label: c.title || c.id,
                description: c.title ? c.id : c.lastMessage || undefined,
                // O servidor já filtrou por estes campos; repeti-los aqui impede o filtro local do
                // Spotlight de esconder uma conversa que casou, por exemplo, pela última mensagem.
                keywords: [c.id, c.workflowId, c.tenantId, c.userId, c.channel, c.persona, c.title, c.lastMessage]
                    .filter((v): v is string => Boolean(v)),
                onClick: () => navigate(`/conversations/${c.id}`),
                leftSection: <IconMessageCircle size={18} />,
            })),
        });
        push({
            group: t('nav.templates'),
            actions: templates.map((tpl) => ({
                id: `search-tpl-${tpl.id}`,
                label: tpl.name,
                description: tpl.summary || undefined,
                keywords: [tpl.id, ...(tpl.tags ?? [])],
                onClick: () => navigate(`/templates/${tpl.id}`),
                leftSection: <IconTemplate size={18} />,
            })),
        });
        return groups;
    }, [pageActions, workflows, executions, conversations, templates, trimmed, t, navigate]);

    return (
        <Spotlight
            actions={actions}
            query={query}
            onQueryChange={setQuery}
            onSpotlightOpen={loadOnOpen}
            shortcut="mod + k"
            highlightQuery
            searchProps={{
                leftSection: <IconSearch size={18} />,
                placeholder: t('nav.searchPlaceholder'),
            }}
            nothingFound={t('nav.searchNothing')}
        />
    );
}
