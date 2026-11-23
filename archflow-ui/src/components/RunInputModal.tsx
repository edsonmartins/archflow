import { useEffect, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Button, Group, Modal, Stack, Text, Textarea } from '@mantine/core'
import { IconPlayerPlay } from '@tabler/icons-react'
import { loadLastRunInput, parseRunInput, saveLastRunInput } from '../lib/runInput'

/**
 * "Entrada da execução": pedido antes de Executar (editor), Rodar (lista) e Executar novamente.
 *
 * <p>Sem ele, essas três ações rodavam o fluxo sem nenhuma entrada e um fluxo que lê
 * <code>${input}</code> não tinha como ser testado pela UI. O modal só coleta o texto — quem chama
 * converte para o formato do seu caminho (REST ou AG-UI), via <code>lib/runInput</code>.</p>
 */
export function RunInputModal({
    workflowId,
    opened,
    onClose,
    onConfirm,
}: {
    workflowId: string
    opened: boolean
    onClose: () => void
    onConfirm: (text: string) => void
}) {
    const { t } = useTranslation()
    const [text, setText] = useState('')

    // Recarrega a cada abertura: o padrão é a última entrada usada neste fluxo, nesta sessão.
    useEffect(() => {
        if (opened) setText(loadLastRunInput(workflowId))
    }, [opened, workflowId])

    const parsed = parseRunInput(text)
    const hint = parsed.kind === 'empty'
        ? t('runInput.asEmpty')
        : parsed.kind === 'variables'
            ? t('runInput.asVariables', { keys: Object.keys(parsed.value).join(', ') || '—' })
            : t('runInput.asText')

    const confirm = () => {
        saveLastRunInput(workflowId, text)
        onConfirm(text)
    }

    return (
        <Modal opened={opened} onClose={onClose} title={t('runInput.title')} size="lg" centered>
            <Stack gap="sm">
                <Text size="sm" c="dimmed">{t('runInput.subtitle')}</Text>
                <Textarea
                    data-testid="run-input-text"
                    aria-label={t('runInput.title')}
                    placeholder={t('runInput.placeholder')}
                    value={text}
                    onChange={(e) => setText(e.currentTarget.value)}
                    onKeyDown={(e) => {
                        if (e.key === 'Enter' && (e.ctrlKey || e.metaKey)) {
                            e.preventDefault()
                            confirm()
                        }
                    }}
                    rows={7}
                    resize="vertical"
                    styles={{ input: { fontFamily: 'var(--mantine-font-family-monospace)', fontSize: 12.5 } }}
                    data-autofocus
                />
                <Text size="xs" c="dimmed" data-testid="run-input-hint">{hint}</Text>
                <Group justify="flex-end">
                    <Button variant="default" onClick={onClose} data-testid="run-input-cancel">
                        {t('runInput.cancel')}
                    </Button>
                    <Button leftSection={<IconPlayerPlay size={14} />} onClick={confirm}
                            data-testid="run-input-confirm">
                        {t('runInput.run')}
                    </Button>
                </Group>
            </Stack>
        </Modal>
    )
}
