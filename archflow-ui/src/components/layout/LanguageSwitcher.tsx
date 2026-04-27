import { ActionIcon, Menu, Tooltip } from '@mantine/core'
import { IconLanguage } from '@tabler/icons-react'
import { useTranslation } from 'react-i18next'
import { SUPPORTED_LANGUAGES } from '../../i18n'

/**
 * Compact language picker rendered in the app header. Persists the
 * choice through i18next's localStorage detector so the selection
 * survives reloads.
 */
export default function LanguageSwitcher() {
    const { t, i18n } = useTranslation()
    const active = i18n.resolvedLanguage ?? i18n.language

    return (
        <Menu shadow="md" width={180} position="bottom-end">
            <Menu.Target>
                <Tooltip label={t('header.language')}>
                    <ActionIcon variant="subtle" aria-label={t('header.language')} data-testid="language-switcher">
                        <IconLanguage size={18} />
                    </ActionIcon>
                </Tooltip>
            </Menu.Target>
            <Menu.Dropdown>
                {SUPPORTED_LANGUAGES.map((lng) => (
                    <Menu.Item
                        key={lng.code}
                        onClick={() => i18n.changeLanguage(lng.code)}
                        data-testid={`language-option-${lng.code}`}
                        style={{ fontWeight: active === lng.code ? 600 : 400 }}
                    >
                        {lng.label}
                    </Menu.Item>
                ))}
            </Menu.Dropdown>
        </Menu>
    )
}
