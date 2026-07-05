import { Stack, Text, Title, ThemeIcon, Button, Center } from '@mantine/core';
import type { ReactNode } from 'react';

interface EmptyStateProps {
    /** Ícone Tabler (ou qualquer nó) exibido no topo. */
    icon: ReactNode;
    title: string;
    description?: string;
    /** Rótulo do call-to-action; omitir esconde o botão. */
    actionLabel?: string;
    onAction?: () => void;
    /** Conteúdo extra abaixo do CTA (ex.: link de docs). */
    children?: ReactNode;
}

/**
 * Estado vazio padrão da aplicação — substitui os textos centralizados e
 * Alerts ad-hoc para que toda lista/painel vazio tenha a mesma voz:
 * ícone suave, título curto, descrição de uma linha e um CTA opcional.
 */
export default function EmptyState({
    icon,
    title,
    description,
    actionLabel,
    onAction,
    children,
}: EmptyStateProps) {
    return (
        <Center mih={240} p="xl">
            <Stack align="center" gap="sm" maw={420}>
                <ThemeIcon size={56} radius="xl" variant="light" color="archBlue">
                    {icon}
                </ThemeIcon>
                <Title order={4} ta="center">{title}</Title>
                {description && (
                    <Text size="sm" c="dimmed" ta="center">
                        {description}
                    </Text>
                )}
                {actionLabel && onAction && (
                    <Button mt="xs" onClick={onAction}>
                        {actionLabel}
                    </Button>
                )}
                {children}
            </Stack>
        </Center>
    );
}
