import { api } from './api';
import type { ToolCallView } from '../components/chat/ToolCallBlock';
import type { Citation } from '../components/chat/CitationList';

export interface ConversationMessage {
    id: string;
    role: 'user' | 'assistant' | 'system' | 'tool';
    content: string;
    timestamp: string;
    formData?: FormDataType;
    /** Tool calls attached to this message (assistant turns). */
    toolCalls?: ToolCallView[];
    /** Citations attached to this message (RAG hits). */
    citations?: Citation[];
    /** Persona id active when this message was produced. */
    personaId?: string;
    personaIcon?: string;
}

export interface FormField {
    id: string;
    label: string;
    type:
        | 'TEXT'
        | 'EMAIL'
        | 'NUMBER'
        | 'SELECT'
        | 'CHECKBOX'
        | 'TEXTAREA'
        | 'FILE'
        | 'DATE'
        | 'PASSWORD'
        | 'PHONE'
        | 'URL'
        | 'HIDDEN';
    required?: boolean;
    placeholder?: string;
    defaultValue?: string;
    options?: { value: string; label: string }[];
    description?: string;
}

export interface FormDataType {
    id: string;
    title: string;
    description?: string;
    fields: FormField[];
    submitLabel?: string;
    cancelLabel?: string;
}

export type ConversationStatus =
    | 'ACTIVE'
    | 'AWAITING_HUMAN'
    | 'SUSPENDED'
    | 'COMPLETED'
    | 'CANCELLED'
    | 'ESCALATED'
    | 'CLOSED';

export interface Conversation {
    id: string;
    workflowId?: string;
    tenantId?: string;
    userId?: string;
    channel?: string;
    status: ConversationStatus;
    persona?: string;
    title?: string;
    resumeToken?: string;
    formData?: FormDataType;
    createdAt: string;
    updatedAt: string;
    messageCount?: number;
    lastMessage?: string;
}

export interface ConversationListParams {
    status?: ConversationStatus | 'ALL';
    search?: string;
    /** ISO timestamp — only conversations updated after this time. */
    since?: string;
    page?: number;
    pageSize?: number;
}

export interface PagedResult<T> {
    items: T[];
    page: number;
    pageSize: number;
    total: number;
}

export interface SuspendResponse {
    conversationId: string;
    resumeToken: string;
    formData: FormDataType;
}

export interface ResumeResponse {
    conversationId: string;
    status: string;
    messages: ConversationMessage[];
}

function buildQuery(params: Record<string, string | number | undefined>): string {
    const entries = Object.entries(params).filter(([, v]) => v !== undefined && v !== '');
    if (entries.length === 0) return '';
    const search = new URLSearchParams();
    for (const [k, v] of entries) search.set(k, String(v));
    return `?${search.toString()}`;
}

export const conversationApi = {
    listConversations: (params?: ConversationListParams) =>
        api.get<PagedResult<Conversation>>(
            `/conversations${buildQuery({
                status: params?.status,
                search: params?.search,
                since: params?.since,
                page: params?.page,
                pageSize: params?.pageSize,
            })}`,
        ),

    suspendConversation: (conversationId: string, workflowId: string, formId: string) =>
        api.post<SuspendResponse>('/conversations/suspend', {
            conversationId,
            workflowId,
            formId,
        }),

    resumeConversation: (resumeToken: string, formData: Record<string, unknown>) =>
        api.post<ResumeResponse>('/conversations/resume', { resumeToken, formData }),

    getConversation: (conversationId: string) =>
        api.get<Conversation>(`/conversations/${conversationId}`),

    getMessages: (conversationId: string, params?: { page?: number; pageSize?: number }) =>
        api.get<ConversationMessage[]>(
            `/conversations/${conversationId}/messages${buildQuery({
                page: params?.page,
                pageSize: params?.pageSize,
            })}`,
        ),

    sendMessage: (conversationId: string, content: string) =>
        api.post<{ messageId: string }>(`/conversations/${conversationId}/messages`, { content }),

    cancelConversation: (conversationId: string) =>
        api.delete<void>(`/conversations/${conversationId}`),
};
