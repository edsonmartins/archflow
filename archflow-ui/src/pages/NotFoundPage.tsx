import { Paper, Stack } from '@mantine/core';
import { IconMapOff } from '@tabler/icons-react';
import { useNavigate } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import EmptyState from '../components/EmptyState';

/**
 * Rota que não existe (RCHFLW-188): antes não havia rota '*' e o app mostrava uma página inteira em branco, sem
 * menu e sem erro — de um link velho (/marketplace, /admin/brainsentry, desligados pela decisão 0.2) não se
 * sabia se era falha ou página vazia. Mora dentro dos layouts, então o menu continua ali; `homePath` é o início
 * de quem a mostra (o app ou a Administração).
 */
export default function NotFoundPage({ homePath = '/' }: { homePath?: string }) {
    const { t } = useTranslation();
    const navigate = useNavigate();
    return (
        <Stack gap="lg" p="md" data-testid="route-not-found">
            <Paper withBorder>
                <EmptyState
                    icon={<IconMapOff />}
                    accent="amber"
                    title={t('common.notFoundTitle')}
                    description={t('common.notFoundHint')}
                    actionLabel={t('common.backHome')}
                    onAction={() => navigate(homePath)}
                />
            </Paper>
        </Stack>
    );
}
