import { useEffect, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Badge, Button, Card, Group, Stack, Switch, Text, Title } from '@mantine/core'
import { notifications } from '@mantine/notifications'
import { skillsApi, type Skill } from '../services/skills-api'
import { ApiError } from '../services/api'

/**
 * Admin surface for the skills adapter. Lists every skill that was
 * loaded (either from a configured file-system directory or by code)
 * and lets the admin toggle the active flag per skill.
 *
 * <p>When no skills are configured (no directory set in
 * {@code archflow.skills.directory}), the page renders a hint instead
 * of a misleading empty list.</p>
 */
export default function SkillsPage() {
    const { t } = useTranslation()
    const [skills, setSkills]   = useState<Skill[]>([])
    const [busy, setBusy]       = useState<string | null>(null)
    const [loading, setLoading] = useState(true)

    const reload = async () => {
        setLoading(true)
        try {
            setSkills(await skillsApi.list())
        } catch (e) {
            notifications.show({ color: 'red', title: 'Skills',
                message: e instanceof ApiError ? e.message : String(e) })
        } finally {
            setLoading(false)
        }
    }

    useEffect(() => { reload() }, [])

    const toggle = async (s: Skill, active: boolean) => {
        setBusy(s.name)
        try {
            if (active) await skillsApi.activate(s.name)
            else        await skillsApi.deactivate(s.name)
            await reload()
        } catch (e) {
            notifications.show({ color: 'red',
                title: active ? t('skills.activateFailed') : t('skills.deactivateFailed'),
                message: e instanceof ApiError ? e.message : String(e) })
        } finally {
            setBusy(null)
        }
    }

    return (
        <Stack gap="md" style={{ padding: 24 }} data-testid="skills-page">
            <Group justify="space-between">
                <Title order={2}>{t('skills.title')}</Title>
                <Button variant="default" onClick={reload}>{t('common.refresh')}</Button>
            </Group>

            {loading && <Text c="dimmed">{t('triggers.loading')}</Text>}
            {!loading && skills.length === 0 && (
                <Card withBorder><Stack>
                    <Text c="dimmed">{t('skills.emptyTitle')}</Text>
                    <Text size="sm" c="dimmed">
                        {t('skills.emptyHint').split(/<code>|<\/code>/).map((seg, i) =>
                            i % 2 === 1 ? <code key={i}>{seg}</code> : <span key={i}>{seg}</span>)}
                    </Text>
                </Stack></Card>
            )}

            {skills.map(s => (
                <Card key={s.name} withBorder data-testid={`skill-${s.name}`}>
                    <Group justify="space-between" align="flex-start">
                        <Stack gap={4} style={{ flex: 1 }}>
                            <Group gap="xs">
                                <Text fw={600}>{s.name}</Text>
                                {s.active && <Badge color="green">{t('skills.badgeActive')}</Badge>}
                                {s.resources.length > 0 && (
                                    <Badge variant="light">{t('skills.resources', { count: s.resources.length })}</Badge>
                                )}
                            </Group>
                            <Text size="sm" c="dimmed">{s.description}</Text>
                            {s.resources.length > 0 && (
                                <Group gap={4} mt={4}>
                                    {s.resources.map(r => (
                                        <Badge key={r.name} size="sm" variant="outline">{r.name}</Badge>
                                    ))}
                                </Group>
                            )}
                        </Stack>
                        <Switch
                            checked={s.active}
                            onChange={e => toggle(s, e.currentTarget.checked)}
                            disabled={busy === s.name}
                            data-testid={`skill-toggle-${s.name}`}
                            label={s.active ? t('skills.active') : t('skills.inactive')}
                        />
                    </Group>
                </Card>
            ))}
        </Stack>
    )
}
