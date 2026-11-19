import { Stack, Text, Title, Button, Center } from '@mantine/core';
import type { ReactNode } from 'react';
import type { Accent } from './PageHeader';

interface EmptyStateProps {
    /** Ícone Tabler (ou qualquer nó) exibido no centro da ilustração. */
    icon: ReactNode;
    title: ReactNode;
    description?: ReactNode;
    /** Rótulo do call-to-action; omitir esconde o botão. */
    actionLabel?: string;
    onAction?: () => void;
    /** Conteúdo extra abaixo do CTA (ex.: link de docs, botão customizado). */
    children?: ReactNode;
    accent?: Accent;
    /** Versão baixa, para dentro de tabelas e cartões. */
    compact?: boolean;
}

/**
 * Estado vazio padrão da aplicação: ícone num círculo com degradê e anéis
 * pontilhados (af-empty-* em App.css), título curto, uma linha de descrição e
 * um CTA opcional — para nenhuma lista vazia ficar só com uma frase cinza.
 */
export default function EmptyState({
    icon,
    title,
    description,
    actionLabel,
    onAction,
    children,
    accent = 'blue',
    compact = false,
}: EmptyStateProps) {
    return (
        <Center mih={compact ? 180 : 280} p={compact ? 'lg' : 'xl'}>
            <Stack align="center" gap="sm" maw={440}>
                <span className="af-empty-art" data-accent={accent} data-compact={compact || undefined} aria-hidden>
                    <span className="af-empty-icon">{icon}</span>
                </span>
                <Title order={compact ? 5 : 4} ta="center" mt={compact ? 4 : 8}>{title}</Title>
                {description && (
                    <Text size="sm" c="dimmed" ta="center" lh={1.55}>
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
