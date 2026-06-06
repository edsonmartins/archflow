import { useState } from 'react';
import { Button, Group, Stack, Text, TextInput } from '@mantine/core';
import { modals } from '@mantine/modals';
import i18n from 'i18next';

/**
 * Centralized confirmation dialogs built on @mantine/modals, so every
 * destructive action across the app shares one accessible, themeable,
 * dark-mode-aware confirm flow (replacing instant-action handlers and the
 * native `window.confirm`).
 *
 * Two entry points:
 *  - confirmAction(): a standard confirm/cancel dialog.
 *  - confirmWithText(): requires the user to type a phrase (e.g. the tenant
 *    name) before the confirm button enables — for high-impact deletes.
 */

interface ConfirmActionOptions {
    title: string;
    message: React.ReactNode;
    /** Confirm button label. Defaults to common.confirm. */
    confirmLabel?: string;
    cancelLabel?: string;
    /** Render the confirm button in the danger (red) color. Default true. */
    danger?: boolean;
    onConfirm: () => void | Promise<void>;
}

export function confirmAction({
    title,
    message,
    confirmLabel,
    cancelLabel,
    danger = true,
    onConfirm,
}: ConfirmActionOptions) {
    modals.openConfirmModal({
        title,
        children: typeof message === 'string' ? <Text size="sm">{message}</Text> : message,
        labels: {
            confirm: confirmLabel ?? i18n.t('common.confirm'),
            cancel: cancelLabel ?? i18n.t('common.cancel'),
        },
        confirmProps: danger ? { color: 'red' } : undefined,
        onConfirm: () => {
            void onConfirm();
        },
    });
}

interface ConfirmWithTextOptions extends Omit<ConfirmActionOptions, 'message'> {
    message?: React.ReactNode;
    /** Label shown above the input describing what to type. */
    prompt: string;
    /** The exact string the user must type to enable the confirm button. */
    expected: string;
}

function ConfirmWithTextBody({
    modalId,
    message,
    prompt,
    expected,
    confirmLabel,
    cancelLabel,
    danger,
    onConfirm,
}: ConfirmWithTextOptions & { modalId: string }) {
    const [value, setValue] = useState('');
    const matches = value.trim() === expected;

    return (
        <Stack gap="sm">
            {typeof message === 'string' ? <Text size="sm">{message}</Text> : message}
            <TextInput
                label={prompt}
                placeholder={expected}
                value={value}
                onChange={(e) => setValue(e.currentTarget.value)}
                data-autofocus
                autoComplete="off"
            />
            <Group justify="flex-end" gap="xs" mt="xs">
                <Button variant="default" onClick={() => modals.close(modalId)}>
                    {cancelLabel ?? i18n.t('common.cancel')}
                </Button>
                <Button
                    color={danger === false ? undefined : 'red'}
                    disabled={!matches}
                    onClick={() => {
                        modals.close(modalId);
                        void onConfirm();
                    }}
                >
                    {confirmLabel ?? i18n.t('common.confirm')}
                </Button>
            </Group>
        </Stack>
    );
}

export function confirmWithText(opts: ConfirmWithTextOptions) {
    const modalId = 'confirm-with-text';
    modals.open({
        modalId,
        title: opts.title,
        children: <ConfirmWithTextBody modalId={modalId} {...opts} />,
    });
}
