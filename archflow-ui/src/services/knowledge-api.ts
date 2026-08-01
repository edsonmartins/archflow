import { api, authFetch } from './api'

export interface KnowledgeBase {
    id: string
    tenantId: string
    workspaceId: string | null
    name: string
    createdAt: string
}

export interface StoredFile {
    id: string
    originalName: string
    contentType: string
    size: number
    createdAt: string
}

export interface KnowledgeDocument {
    id: string
    knowledgeBaseId: string
    fileId: string
    status: 'QUEUED' | 'PROCESSING' | 'READY' | 'FAILED'
    chunkCount: number
    error: string | null
    createdAt: string
    updatedAt: string
}

export interface Job {
    id: string
    status: 'QUEUED' | 'RUNNING' | 'SUCCEEDED' | 'FAILED' | 'CANCELLED'
    progress: number
    message: string | null
    error: string | null
}

export interface IndexVersion {
    id: string
    baseId: string
    basedOnVersionId: string | null
    status: 'BUILDING' | 'ACTIVE' | 'INACTIVE'
    createdAt: string
    activatedAt: string | null
}

export interface SearchHit {
    chunkId: string
    documentId: string
    content: string
    score: number
}

export interface IngestionSubmission {
    document: KnowledgeDocument
    job: Job
}

export const knowledgeApi = {
    listBases: () => api.get<KnowledgeBase[]>('/knowledge-bases'),
    createBase: (name: string, workspaceId?: string) =>
        api.post<KnowledgeBase>('/knowledge-bases', { name, workspaceId }),
    listDocuments: (baseId: string) =>
        api.get<KnowledgeDocument[]>(`/knowledge-bases/${encodeURIComponent(baseId)}/documents`),
    getFile: (fileId: string) =>
        api.get<StoredFile>(`/files/${encodeURIComponent(fileId)}`),
    upload: async (file: File, workspaceId?: string): Promise<StoredFile> => {
        const form = new FormData()
        form.append('file', file)
        if (workspaceId) form.append('workspaceId', workspaceId)
        const response = await authFetch('/api/files', { method: 'POST', body: form })
        if (!response.ok) throw new Error((await response.text()) || response.statusText)
        return response.json()
    },
    ingest: (baseId: string, fileId: string) =>
        api.post<IngestionSubmission>(
            `/knowledge-bases/${encodeURIComponent(baseId)}/documents`,
            { fileId },
        ),
    getJob: (jobId: string) => api.get<Job>(`/jobs/${encodeURIComponent(jobId)}`),
    search: (baseId: string, query: string, versionId?: string, limit = 8) =>
        api.post<SearchHit[]>(`/knowledge-bases/${encodeURIComponent(baseId)}/search`, {
            query,
            versionId,
            limit,
            minScore: 0,
        }),
    versions: (baseId: string) =>
        api.get<IndexVersion[]>(
            `/knowledge-bases/${encodeURIComponent(baseId)}/index/versions`,
        ),
    rollback: (baseId: string, versionId: string) =>
        api.put<IndexVersion>(
            `/knowledge-bases/${encodeURIComponent(baseId)}/index/active`,
            { versionId },
        ),
}
