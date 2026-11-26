import { useEffect, useState } from 'react'
import { useTranslation } from 'react-i18next'
import {
    Alert, Badge, Button, Group, Paper, SimpleGrid, Skeleton, Stack, Switch, Text, ThemeIcon,
} from '@mantine/core'
import {
    IconAlertCircle, IconBook2, IconCircleCheck, IconCircleDashed, IconFile, IconPuzzle, IconRefresh,
} from '@tabler/icons-react'
import { notifications } from '@mantine/notifications'
import { skillsApi, type Skill } from '../services/skills-api'
import { ApiError } from '../services/api'
import { PageHeader } from '../components/PageHeader'
import { SectionCard } from '../components/SectionCard'
import { StatCard } from '../components/admin/StatCard'
import EmptyState from '../components/EmptyState'
import { usePermissions } from '../lib/permissions'
import { PermissionTooltip } from '../components/PermissionTooltip'

const renderRich = (text: string) =>
    text.split(/<code>|<\/code>/).map((seg, i) =>
        i % 2 === 1 ? <code key={i}>{seg}</code> : <span key={i}>{seg}</span>)

/**
 * Admin surface for the skills adapter. Lists every skill that was
 * loaded (either from a configured file-system directory or by code)
 * and lets the admin toggle the active flag per skill.
 *
 * <p>When no skills are configured (no directory set in
 * {@code archflow.skills.directory}), the page renders a hint instead
 * of a misleading empty list.</p>
 *
 * <p>Each skill is a row with a Switch — a short form, not a data table —
 * so it stays a Mantine list rather than a VTable (the canvas cannot host
 * an interactive switch).</p>
 */
export default function SkillsPage() {
    const { t } = useTranslation()
    // Ativar/desativar muda o que os agentes do tenant fazem: o servidor exige agent:manage (QA2 RCHFLW-158).
    const canManage = usePermissions()('agent:manage')
    const [skills, setSkills]   = useState<Skill[]>([])
    const [busy, setBusy]       = useState<string | null>(null)
    const [loading, setLoading] = useState(true)
    const [error, setError]     = useState<string | null>(null)

    const reload = async () => {
        setLoading(true)
        setError(null)
        try {
            setSkills(await skillsApi.list())
        } catch (e) {
            setError(e instanceof ApiError ? e.message : String(e))
        } finally {
            setLoading(false)
        }
    }

    useEffect(() => { reload() }, [])

    const toggle = async (s: Skill, active: boolean) => {
        // O switch desabilitado já não muda; a guarda cobre quem dispara o change mesmo assim.
        if (!canManage) return
        setBusy(s.name)
        try {
            if (active) await skillsApi.activate(s.name)
            else        await skillsApi.deactivate(s.name)
            notifications.show({ color: 'green',
                title: active ? t('skills.badgeActive') : t('skills.inactive'),
                message: s.name })
            await reload()
        } catch (e) {
            notifications.show({ color: 'red',
                title: active ? t('skills.activateFailed') : t('skills.deactivateFailed'),
                message: e instanceof ApiError ? e.message : String(e) })
        } finally {
            setBusy(null)
        }
    }

    const firstLoad = loading && skills.length === 0
    const activeCount = skills.filter(s => s.active).length
    const resourceCount = skills.reduce((n, s) => n + s.resources.length, 0)

    return (
        <Stack gap="lg" p="md" data-testid="skills-page">
            <PageHeader
                icon={IconBook2}
                accent="slate"
                title={t('skills.title')}
                subtitle={t('skills.subtitle')}
                actions={
                    <Button variant="default" leftSection={<IconRefresh size={16} />} onClick={reload}>
                        {t('common.refresh')}
                    </Button>
                }
            />

            <SimpleGrid cols={{ base: 1, xs: 2, md: 4 }} spacing="md">
                <StatCard label={t('skills.stats.total')} value={firstLoad ? '—' : skills.length}
                          icon={IconPuzzle} accent="blue" hint={t('skills.stats.totalHint')} />
                <StatCard label={t('skills.stats.active')} value={firstLoad ? '—' : activeCount}
                          icon={IconCircleCheck} accent="teal" hint={t('skills.stats.activeHint')} />
                <StatCard label={t('skills.stats.inactive')} value={firstLoad ? '—' : skills.length - activeCount}
                          icon={IconCircleDashed} accent="slate" hint={t('skills.stats.inactiveHint')} />
                <StatCard label={t('skills.stats.resources')} value={firstLoad ? '—' : resourceCount}
                          icon={IconFile} accent="violet" hint={t('skills.stats.resourcesHint')} />
            </SimpleGrid>

            <SectionCard
                icon={IconPuzzle}
                accent="slate"
                title={t('skills.listTitle')}
                description={t('skills.listHint')}
            >
                {error && (
                    <Alert color="red" variant="light" icon={<IconAlertCircle size={16} />} mb="md">
                        <Group justify="space-between" wrap="nowrap" gap="sm">
                            <Text size="sm">{error}</Text>
                            <Button size="xs" variant="white" color="red" onClick={reload}>{t('common.refresh')}</Button>
                        </Group>
                    </Alert>
                )}

                {firstLoad && (
                    <Stack gap="sm" aria-hidden>
                        {[0, 1, 2].map(i => <Skeleton key={i} height={76} radius="md" />)}
                    </Stack>
                )}

                {!loading && !error && skills.length === 0 && (
                    <EmptyState
                        compact
                        accent="slate"
                        icon={<IconBook2 />}
                        title={t('skills.emptyTitle')}
                        description={renderRich(t('skills.emptyHint'))}
                    />
                )}

                {skills.length > 0 && (
                    <Stack gap="sm">
                        {skills.map(s => (
                            <Paper key={s.name} withBorder p="md" data-testid={`skill-${s.name}`}>
                                <Group justify="space-between" align="center" wrap="nowrap" gap="md">
                                    <Group gap="md" wrap="nowrap" align="flex-start" style={{ flex: 1, minWidth: 0 }}>
                                        <ThemeIcon size={38} radius="md" variant="light" color={s.active ? 'teal' : 'gray'}>
                                            <IconPuzzle size={18} stroke={1.8} />
                                        </ThemeIcon>
                                        <Stack gap={4} style={{ flex: 1, minWidth: 0 }}>
                                            <Group gap="xs">
                                                <Text fw={600}>{s.name}</Text>
                                                {s.active && <Badge color="teal" variant="light">{t('skills.badgeActive')}</Badge>}
                                                {s.resources.length > 0 && (
                                                    <Badge variant="light" color="violet">
                                                        {t('skills.resources', { count: s.resources.length })}
                                                    </Badge>
                                                )}
                                            </Group>
                                            <Text size="sm" c="dimmed">{s.description}</Text>
                                            {s.resources.length > 0 && (
                                                <Group gap={4} mt={2}>
                                                    {s.resources.map(r => (
                                                        <Badge key={r.name} size="sm" variant="outline" color="gray"
                                                               leftSection={<IconFile size={11} />}>
                                                            {r.name}
                                                        </Badge>
                                                    ))}
                                                </Group>
                                            )}
                                        </Stack>
                                    </Group>
                                    <PermissionTooltip missing={canManage ? null : 'agent:manage'}>
                                        <Switch
                                            checked={s.active}
                                            onChange={e => toggle(s, e.currentTarget.checked)}
                                            disabled={busy === s.name || !canManage}
                                            color="teal"
                                            data-testid={`skill-toggle-${s.name}`}
                                            label={s.active ? t('skills.active') : t('skills.inactive')}
                                        />
                                    </PermissionTooltip>
                                </Group>
                            </Paper>
                        ))}
                    </Stack>
                )}
            </SectionCard>
        </Stack>
    )
}
