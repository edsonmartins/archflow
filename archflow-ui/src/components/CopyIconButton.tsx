import { ActionIcon, CopyButton, Tooltip } from '@mantine/core'
import { IconCheck, IconCopy } from '@tabler/icons-react'
import { useTranslation } from 'react-i18next'
import type { CSSProperties } from 'react'

/**
 * Copy-to-clipboard icon with the check/teal confirmation state — shared
 * by JsonViewer, the publish snippets and any future copyable block.
 */
export function CopyIconButton({ value, style }: { value: string; style?: CSSProperties }) {
    const { t } = useTranslation()
    return (
        <CopyButton value={value}>
            {({ copied, copy }) => (
                <Tooltip label={copied
                    ? t('common.copied', { defaultValue: 'Copied' })
                    : t('common.copy', { defaultValue: 'Copy' })}>
                    <ActionIcon
                        variant="subtle"
                        size="sm"
                        color={copied ? 'teal' : 'gray'}
                        onClick={copy}
                        aria-label={t('common.copy', { defaultValue: 'Copy' })}
                        style={style}
                    >
                        {copied ? <IconCheck size={14} /> : <IconCopy size={14} />}
                    </ActionIcon>
                </Tooltip>
            )}
        </CopyButton>
    )
}
