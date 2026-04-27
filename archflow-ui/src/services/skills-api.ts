import { api } from './api'

export interface SkillResource {
    name: string
    mimeType: string
    content?: string
}

export interface Skill {
    name: string
    description: string
    content?: string
    resources: SkillResource[]
    active: boolean
}

/** Wraps {@code /api/admin/skills/*}. */
export const skillsApi = {
    list:       () => api.get<Skill[]>('/admin/skills'),
    active:     () => api.get<Skill[]>('/admin/skills/active'),
    activate:   (name: string) => api.post<Skill>(`/admin/skills/${encodeURIComponent(name)}/activate`),
    deactivate: (name: string) => api.delete<void>(`/admin/skills/${encodeURIComponent(name)}/activate`),
    resource:   (skillName: string, resourceName: string) =>
        api.get<SkillResource>(
            `/admin/skills/${encodeURIComponent(skillName)}/resources/${encodeURIComponent(resourceName)}`),
}
