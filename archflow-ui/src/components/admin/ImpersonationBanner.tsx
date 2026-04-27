import { Text, Button } from '@mantine/core'
import { IconFlag } from '@tabler/icons-react'
import { Trans, useTranslation } from 'react-i18next'
import { useTenantStore } from '../../stores/useTenantStore'

export function ImpersonationBanner() {
  const { t } = useTranslation()
  const { impersonating, exitImpersonation } = useTenantStore()
  if (!impersonating) return null

  return (
    <div
      style={{
        background:    '#FAEEDA',
        border:        '1px solid #BA7517',
        borderRadius:  0,
        padding:       '8px 16px',
        display:       'flex',
        alignItems:    'center',
        gap:           8,
      }}
      data-testid="impersonation-banner"
    >
      <IconFlag size={16} color="#854F0B" />
      <Text size="sm" c="#854F0B" style={{ flex: 1 }}>
        <Trans
          i18nKey="admin.impersonation.viewingAs"
          values={{ name: impersonating.name }}
          components={{ b: <b /> }}
        />
      </Text>
      <Button
        size="xs"
        variant="subtle"
        color="orange"
        onClick={() => {
          sessionStorage.removeItem('archflow_impersonate_tenant')
          exitImpersonation()
        }}
      >
        {t('admin.impersonation.back')}
      </Button>
    </div>
  )
}
