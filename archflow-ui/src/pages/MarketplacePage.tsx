import { useEffect, useMemo, useState } from 'react'
import { Link } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import {
    Badge, Button, Card, Group, Modal, Skeleton, Stack, Text, TextInput, Title,
    Tabs, Tooltip,
} from '@mantine/core'
import { notifications } from '@mantine/notifications'
import { marketplaceApi, type Extension } from '../services/marketplace-api'
import { ApiError } from '../services/api'
import { confirmAction } from '../lib/confirm'

/**
 * Lists, searches and installs/uninstalls marketplace extensions.
 *
 * <p>The page is intentionally simple — the backend
 * {@code MarketplaceControllerImpl} already handles dependency and
 * signature checks; the UI's job is to expose the catalog so admins
 * can discover and manage extensions without curl.</p>
 */
export default function MarketplacePage() {
    const { t } = useTranslation()
    const [items, setItems]       = useState<Extension[]>([])
    const [query, setQuery]       = useState('')
    const [typeFilter, setType]   = useState<string | undefined>()
    const [loading, setLoading]   = useState(true)
    const [installing, setInst]   = useState<string | null>(null)
    const [installOpen, setOpen]  = useState(false)
    const [manifestUrl, setUrl]   = useState('')

    const reload = async () => {
        setLoading(true)
        try {
            const hits = query || typeFilter
                ? await marketplaceApi.search(query, typeFilter)
                : await marketplaceApi.list()
            setItems(hits)
        } catch (err) {
            notifications.show({ color: 'red', title: t('marketplace.title'),
                message: err instanceof ApiError ? err.message : t('marketplace.loadFailed') })
        } finally {
            setLoading(false)
        }
    }

    useEffect(() => { reload() }, []) // eslint-disable-line react-hooks/exhaustive-deps

    const types = useMemo(() => {
        const s = new Set<string>()
        items.forEach(i => { if (i.type) s.add(i.type) })
        return Array.from(s)
    }, [items])

    const handleInstall = async () => {
        if (!manifestUrl.trim()) {
            notifications.show({ color: 'yellow', title: t('marketplace.installTitle'),
                message: t('marketplace.manifestRequired') })
            return
        }
        try {
            const ext = await marketplaceApi.install({ manifestUrl: manifestUrl.trim() })
            notifications.show({ color: 'green', title: t('marketplace.installedOk'),
                message: t('marketplace.installedOkMsg', { name: ext.displayName }) })
            setOpen(false); setUrl('')
            await reload()
        } catch (err) {
            notifications.show({ color: 'red', title: t('marketplace.installFailed'),
                message: err instanceof ApiError ? err.message : String(err) })
        }
    }

    const handleUninstall = (ext: Extension) => {
        confirmAction({
            title: t('confirmations.uninstallTitle'),
            message: t('confirmations.uninstallMessage', { name: ext.displayName }),
            confirmLabel: t('confirmations.uninstall'),
            onConfirm: async () => {
                setInst(ext.id)
                try {
                    await marketplaceApi.uninstall(ext.id)
                    notifications.show({ color: 'green', title: t('marketplace.uninstallOk'),
                        message: t('marketplace.uninstallOkMsg', { name: ext.displayName }) })
                    await reload()
                } catch (err) {
                    notifications.show({ color: 'red', title: t('marketplace.uninstallFailed'),
                        message: err instanceof ApiError ? err.message : String(err) })
                } finally {
                    setInst(null)
                }
            },
        })
    }

    return (
        <Stack gap="md" p="md" data-testid="marketplace-page">
            <Group justify="space-between">
                <Title order={2}>{t('marketplace.title')}</Title>
                <Button onClick={() => setOpen(true)} data-testid="marketplace-install-btn">
                    {t('marketplace.installExt')}
                </Button>
            </Group>

            <Group>
                <TextInput
                    placeholder={t('marketplace.searchPlaceholder')}
                    value={query}
                    onChange={e => setQuery(e.currentTarget.value)}
                    onKeyDown={e => { if (e.key === 'Enter') reload() }}
                    style={{ flex: 1 }}
                    data-testid="marketplace-search"
                />
                <Button variant="default" onClick={reload}>{t('marketplace.searchBtn')}</Button>
            </Group>

            <Tabs value={typeFilter ?? 'all'}
                  onChange={v => { setType(v === 'all' ? undefined : (v ?? undefined)); setTimeout(reload, 0) }}>
                <Tabs.List>
                    <Tabs.Tab value="all">{t('marketplace.allTab', { count: items.length })}</Tabs.Tab>
                    {types.map(ty => (
                        <Tabs.Tab key={ty} value={ty}>{ty}</Tabs.Tab>
                    ))}
                </Tabs.List>
            </Tabs>

            {loading && (
                <Stack gap="sm" aria-hidden>
                    {[0, 1, 2].map(i => <Skeleton key={i} height={104} radius="md" />)}
                </Stack>
            )}
            {!loading && items.length === 0 && (
                <Stack align="center" gap="xs" py={40}>
                    <Text c="dimmed" ta="center">{t('marketplace.empty')}</Text>
                    {(query || typeFilter) && (
                        <Button
                            variant="light"
                            size="xs"
                            onClick={() => { setQuery(''); setType(undefined); setTimeout(reload, 0) }}
                        >
                            {t('marketplace.clearSearch')}
                        </Button>
                    )}
                </Stack>
            )}

            <Stack gap="sm">
                {items.map(ext => (
                    <Card key={ext.id} withBorder padding="md" data-testid={`extension-${ext.id}`}>
                        <Group justify="space-between" align="flex-start">
                            <Stack gap={4} style={{ flex: 1 }}>
                                <Group gap="xs">
                                    <Link to={`/marketplace/${encodeURIComponent(ext.id)}`}>
                                        <Text fw={600}>{ext.displayName}</Text>
                                    </Link>
                                    <Badge variant="light">{ext.type}</Badge>
                                    <Text size="xs" c="dimmed">v{ext.version}</Text>
                                    {ext.installed && <Badge color="green" size="sm">{t('marketplace.installed')}</Badge>}
                                </Group>
                                <Text size="sm" c="dimmed">{ext.description}</Text>
                                <Text size="xs" c="dimmed">{t('marketplace.authorBy', { author: ext.author })}</Text>
                            </Stack>
                            <Group>
                                {ext.installed ? (
                                    <Tooltip label={t('marketplace.uninstallHint')}>
                                        <Button variant="outline" color="red"
                                                onClick={() => handleUninstall(ext)}
                                                loading={installing === ext.id}
                                                data-testid={`uninstall-${ext.id}`}>
                                            {t('marketplace.uninstall')}
                                        </Button>
                                    </Tooltip>
                                ) : (
                                    <Tooltip label={t('marketplace.installHint')}>
                                        <Button
                                            variant="light"
                                            onClick={() => {
                                                // Catalog items don't carry a manifest URL yet, so
                                                // pre-fill the conventional path and let the admin
                                                // confirm/adjust in the install modal.
                                                setUrl(`/opt/archflow/extensions/${ext.id}/manifest.json`)
                                                setOpen(true)
                                            }}
                                            data-testid={`install-${ext.id}`}
                                        >
                                            {t('marketplace.install')}
                                        </Button>
                                    </Tooltip>
                                )}
                            </Group>
                        </Group>
                    </Card>
                ))}
            </Stack>

            <Modal opened={installOpen} onClose={() => setOpen(false)} title={t('marketplace.installTitle')}
                   centered data-testid="install-modal">
                <Stack>
                    <TextInput
                        label={t('marketplace.manifestPath')}
                        description={t('marketplace.manifestHint')}
                        placeholder="/opt/archflow/extensions/my-ext/manifest.json"
                        value={manifestUrl}
                        onChange={e => setUrl(e.currentTarget.value)}
                        data-testid="manifest-path"
                    />
                    <Group justify="flex-end">
                        <Button variant="default" onClick={() => setOpen(false)}>{t('common.cancel')}</Button>
                        <Button onClick={handleInstall} data-testid="install-submit">{t('marketplace.submitInstall')}</Button>
                    </Group>
                </Stack>
            </Modal>
        </Stack>
    )
}
