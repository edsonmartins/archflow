import type { ReactElement } from 'react';
import { Tooltip } from '@mantine/core';
import { useTranslation } from 'react-i18next';
import type { Permission } from '../lib/permissions';

interface Props {
    /** O que falta para a ação: a permissão do servidor, `'ADMIN'` para o papel, ou `null` quando pode. */
    missing: Permission | 'ADMIN' | null;
    children: ReactElement;
}

/**
 * Diz, no lugar de uma ação desabilitada, qual permissão falta (QA2 RCHFLW-136/158). O nome é o mesmo
 * que o servidor devolve no 403 — o usuário que pedir acesso ao admin pede a coisa certa.
 *
 * O filho fica dentro de um `span`: um botão `disabled` não dispara os eventos de mouse que abrem o
 * tooltip. Quando nada falta, o filho é devolvido como está.
 */
export function PermissionTooltip({ missing, children }: Props) {
    const { t } = useTranslation();
    if (!missing) return children;
    const label = missing === 'ADMIN'
        ? t('common.requiresAdmin')
        : t('common.requiresPermission', { permission: missing });
    return (
        <Tooltip label={label} withArrow>
            <span style={{ display: 'inline-block' }} data-permission-missing={missing}>
                {children}
            </span>
        </Tooltip>
    );
}
