import { useEffect, useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import { Badge, Button, Card, Group, Skeleton, Stack, Text, Title } from '@mantine/core'
import { notifications } from '@mantine/notifications'
import { marketplaceApi, type Extension } from '../services/marketplace-api'
import { ApiError } from '../services/api'
import { confirmAction } from '../lib/confirm'

export default function MarketplaceDetailPage() {
    const { t } = useTranslation()
    const { id = '' } = useParams()
    const [ext, setExt] = useState<Extension | null>(null)
    const [err, setErr] = useState<string | null>(null)

    useEffect(() => {
        marketplaceApi.get(id)
            .then(setExt)
            .catch(e => setErr(e instanceof ApiError ? e.message : String(e)))
    }, [id])

    const uninstall = () => {
        if (!ext) return
        confirmAction({
            title: t('confirmations.uninstallTitle'),
            message: t('confirmations.uninstallMessage', { name: ext.displayName }),
            confirmLabel: t('confirmations.uninstall'),
            onConfirm: async () => {
                try {
                    await marketplaceApi.uninstall(ext.id)
                    notifications.show({ color: 'green', title: t('marketplace.uninstallOk'),
                        message: t('marketplace.uninstallOkMsg', { name: ext.displayName }) })
                    setExt({ ...ext, installed: false })
                } catch (e) {
                    notifications.show({ color: 'red', title: t('marketplace.uninstallFailed'),
                        message: e instanceof ApiError ? e.message : String(e) })
                }
            },
        })
    }

    if (err) return (
        <Stack gap="md" p="md">
            <Text c="red">{err}</Text>
            <Link to="/marketplace"><Button variant="default">{t('marketplace.back')}</Button></Link>
        </Stack>
    )
    if (!ext) return (
        <Stack gap="sm" p="md" aria-hidden>
            <Skeleton height={36} width={320} radius="md" />
            <Skeleton height={140} radius="md" />
        </Stack>
    )

    return (
        <Stack gap="md" p="md" data-testid="marketplace-detail">
            <Group justify="space-between">
                <Group align="baseline">
                    <Title order={2}>{ext.displayName}</Title>
                    <Badge variant="light">{ext.type}</Badge>
                    <Text size="sm" c="dimmed">v{ext.version}</Text>
                    {ext.installed && <Badge color="green">{t('marketplace.installed')}</Badge>}
                </Group>
                <Link to="/marketplace"><Button variant="default">{t('marketplace.back')}</Button></Link>
            </Group>
            <Card withBorder>
                <Stack gap="sm">
                    <Text>{ext.description}</Text>
                    <Text size="sm" c="dimmed">{t('marketplace.by')}: {ext.author}</Text>
                    <Text size="sm" c="dimmed">{t('marketplace.idLabel')}: <code>{ext.id}</code></Text>
                    {ext.permissions?.length > 0 && (
                        <Group gap={4} align="center">
                            <Text size="sm" c="dimmed">{t('marketplace.permissions')}</Text>
                            {ext.permissions.map(p => <Badge key={p} size="sm" variant="outline">{p}</Badge>)}
                        </Group>
                    )}
                </Stack>
            </Card>
            {ext.installed && (
                <Group>
                    <Button color="red" variant="outline" onClick={uninstall} data-testid="uninstall-detail">
                        {t('marketplace.uninstall')}
                    </Button>
                </Group>
            )}
        </Stack>
    )
}
