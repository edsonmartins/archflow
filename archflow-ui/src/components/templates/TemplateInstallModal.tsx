import { useEffect, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Alert, Button, Group, Modal, NumberInput, Select, Stack, Switch, Text, TextInput } from '@mantine/core'
import { IconAlertCircle, IconRocket } from '@tabler/icons-react'
import type { TemplateParameter } from '../../data/templates'
import type { FlowNodeData } from '../FlowCanvas/types'
import { KNOWLEDGE_BASE_KEY } from '../FlowCanvas/dataNodeKeys'
import { KnowledgeBaseField } from '../PropertyPanel/fields/KnowledgeBaseField'
import { fieldKind, initialValues, installFields, missingParameters } from './installParameters'

/** O seletor do painel lê a base de {@code nodeData.config}: aqui a "config" é só o valor do parâmetro. */
function baseFieldData(value: unknown): FlowNodeData {
    return { label: '', componentId: '', nodeType: '', status: 'idle', config: { [KNOWLEDGE_BASE_KEY]: value } }
}

/**
 * "Usar este modelo" de um template do backend (SPI): pede os parâmetros obrigatórios e entrega ao chamador, que
 * instala pelo POST /api/templates/{id}/install. Antes o template era clonado no navegador com o grafo já resolvido
 * com os defaults, e o knowledgeBaseId ficava vazio (RCHFLW-204). O erro do install (400 com a mensagem do servidor)
 * aparece aqui, com o formulário ainda aberto para corrigir.
 */
export function TemplateInstallModal({
    opened,
    onClose,
    parameters,
    submitting,
    error,
    onSubmit,
}: {
    opened: boolean
    onClose: () => void
    /** Referência estável (a do template): o formulário recomeça quando ela muda. */
    parameters?: readonly TemplateParameter[]
    submitting: boolean
    error: string | null
    onSubmit: (parameters: Record<string, unknown>) => void
}) {
    const { t } = useTranslation()
    const fields = installFields(parameters)
    const [values, setValues] = useState<Record<string, unknown>>(() => initialValues(fields))

    // Recomeça a cada abertura: o formulário é deste template, não o que ficou da última tentativa.
    useEffect(() => {
        if (opened) setValues(initialValues(installFields(parameters)))
    }, [opened, parameters])

    const set = (name: string, value: unknown) => setValues((current) => ({ ...current, [name]: value }))
    const missing = missingParameters(fields, values)

    const submit = () => {
        if (missing.length > 0 || submitting) return
        const collected: Record<string, unknown> = {}
        for (const p of fields) {
            const v = values[p.name]
            collected[p.name] = typeof v === 'string' ? v.trim() : v
        }
        onSubmit(collected)
    }

    const field = (p: TemplateParameter) => {
        const kind = fieldKind(p)
        const value = values[p.name]
        const testId = `template-param-${p.name}`
        switch (kind) {
            case 'knowledgeBase':
                return (
                    <div key={p.name} data-testid={testId}>
                        <KnowledgeBaseField
                            nodeData={baseFieldData(value)}
                            update={(key, v) => { if (key === KNOWLEDGE_BASE_KEY) set(p.name, v) }}
                            required
                            placeholder={t('templates.install.knowledgeBasePlaceholder')}
                        />
                    </div>
                )
            case 'select':
                return (
                    <Select key={p.name} label={p.name} description={p.description} required
                            data={p.options ?? []} value={typeof value === 'string' ? value : null}
                            onChange={(v) => set(p.name, v ?? undefined)} allowDeselect={false}
                            data-testid={testId} />
                )
            case 'integer':
                return (
                    <NumberInput key={p.name} label={p.name} description={p.description} required
                                 allowDecimal={false}
                                 value={typeof value === 'number' ? value : ''}
                                 onChange={(v) => set(p.name, typeof v === 'number' ? v : undefined)}
                                 data-testid={testId} />
                )
            case 'boolean':
                return (
                    <Switch key={p.name} label={p.name} description={p.description}
                            checked={value === true}
                            onChange={(e) => set(p.name, e.currentTarget.checked)}
                            data-testid={testId} />
                )
            case 'text':
                return (
                    <TextInput key={p.name} label={p.name} description={p.description} required
                               value={typeof value === 'string' ? value : ''}
                               onChange={(e) => set(p.name, e.currentTarget.value)}
                               data-testid={testId} />
                )
            default:
                return null
        }
    }

    return (
        <Modal opened={opened} onClose={onClose} title={t('templates.install.title')} size="lg" centered>
            <Stack gap="sm">
                <Text size="sm" c="dimmed">{t('templates.install.subtitle')}</Text>
                {fields.map(field)}
                {error && (
                    <Alert color="red" variant="light" icon={<IconAlertCircle size={16} />}
                           data-testid="template-install-error">
                        {error}
                    </Alert>
                )}
                <Group justify="flex-end">
                    <Button variant="default" onClick={onClose} data-testid="template-install-cancel">
                        {t('common.cancel')}
                    </Button>
                    <Button leftSection={<IconRocket size={14} />} onClick={submit} loading={submitting}
                            disabled={missing.length > 0} data-testid="template-install-confirm">
                        {t('templates.install.submit')}
                    </Button>
                </Group>
            </Stack>
        </Modal>
    )
}
