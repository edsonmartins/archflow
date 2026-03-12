import { api } from './api';

export interface ConversationMessage {
    id: string;
    role: 'user' | 'assistant' | 'system' | 'tool';
    content: string;
    timestamp: string;
    formData?: FormDataType;
}

export interface FormField {
    id: string;
    label: string;
    type: 'TEXT' | 'EMAIL' | 'NUMBER' | 'SELECT' | 'CHECKBOX' | 'TEXTAREA' | 'FILE' | 'DATE' | 'PASSWORD' | 'PHONE' | 'URL' | 'HIDDEN';
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

export interface Conversation {
    id: string;
    workflowId: string;
    status: 'ACTIVE' | 'SUSPENDED' | 'COMPLETED' | 'CANCELLED';
    resumeToken?: string;
    formData?: FormDataType;
    createdAt: string;
    updatedAt: string;
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

export const conversationApi = {
    suspendConversation: (conversationId: string, workflowId: string, formId: string) =>
        api.post<SuspendResponse>('/conversations/suspend', { conversationId, workflowId, formId }),

    resumeConversation: (resumeToken: string, formData: Record<string, unknown>) =>
        api.post<ResumeResponse>('/conversations/resume', { resumeToken, formData }),

    getConversation: (conversationId: string) =>
        api.get<Conversation>(`/conversations/${conversationId}`),

    getMessages: (conversationId: string) =>
        api.get<ConversationMessage[]>(`/conversations/${conversationId}/messages`),

    cancelConversation: (conversationId: string) =>
        api.delete<void>(`/conversations/${conversationId}`),
};
