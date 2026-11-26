import { useEffect, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Alert, Button, Group, Modal, Stack, Text, Textarea } from '@mantine/core'
import { IconAlertTriangle, IconPlayerPlay } from '@tabler/icons-react'
import { loadLastRunInput, missingInputFields, parseRunInput, saveLastRunInput } from '../lib/runInput'
import { productionRunTarget, type RunTarget } from '../lib/runTarget'

/**
 * "Entrada da execução": pedido antes de Executar (editor), Rodar (lista) e Executar novamente.
 *
 * <p>Sem ele, essas três ações rodavam o fluxo sem nenhuma entrada e um fluxo que lê
 * <code>${input}</code> não tinha como ser testado pela UI. O modal só coleta o texto — quem chama
 * converte para o formato do seu caminho (REST ou AG-UI), via <code>lib/runInput</code>.</p>
 *
 * <p>{@code runs} diz o que o caminho de quem chama executa (RCHFLW-89): {@code 'draft'} no editor (o
 * rascunho), {@code 'production'} na lista e no Executar novamente (a versão publicada em produção, com o
 * número dela, ou o rascunho quando não há versão lá). O modal é a última tela antes de rodar nos três
 * caminhos — é aqui que o usuário precisa ler qual versão vai executar.</p>
 *
 * <p>{@code expectedFields} são os campos que os nós Entrada declaram ({@code ioFields}). Quando a entrada não
 * os traz — texto livre num fluxo que compara {@code valor} —, o modal avisa sem impedir (RCHFLW-123): o motor
 * falha fechado, e a condição que compara o campo ausente falha o passo em vez de escolher o ramo.</p>
 */
export function RunInputModal({
    workflowId,
    opened,
    onClose,
    onConfirm,
    runs,
    expectedFields = [],
}: {
    workflowId: string
    opened: boolean
    onClose: () => void
    onConfirm: (text: string) => void
    runs?: 'draft' | 'production'
    expectedFields?: readonly string[]
}) {
    const { t } = useTranslation()
    const [text, setText] = useState('')
    const [productionTarget, setProductionTarget] = useState<RunTarget | null>(null)

    // A cada abertura, porque uma publicação pode ter acontecido desde a última. Até a resposta chegar
    // (ou se ela falhar) a linha não aparece: melhor não dizer nada do que dizer a versão errada.
    useEffect(() => {
        setProductionTarget(null)
        if (!opened || runs !== 'production') return
        let cancelled = false
        void productionRunTarget(workflowId).then((target) => { if (!cancelled) setProductionTarget(target) })
        return () => { cancelled = true }
    }, [opened, runs, workflowId])
    const target: RunTarget | null = runs === 'draft' ? { kind: 'draft' } : productionTarget

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
    const missing = missingInputFields(text, expectedFields)

    const confirm = () => {
        saveLastRunInput(workflowId, text)
        onConfirm(text)
    }

    return (
        <Modal opened={opened} onClose={onClose} title={t('runInput.title')} size="lg" centered>
            <Stack gap="sm">
                {target && (
                    <Text size="sm" fw={500} data-testid="run-input-target" data-target={target.kind}>
                        {target.kind === 'published'
                            ? t('runInput.target.published', { number: target.number })
                            : t(`runInput.target.${target.kind}`)}
                    </Text>
                )}
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
                {missing.length > 0 && (
                    <Alert color="yellow" variant="light" icon={<IconAlertTriangle size={16} />}
                           data-testid="run-input-missing-fields">
                        {t('runInput.missingFields', { fields: missing.join(', ') })}
                    </Alert>
                )}
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
