import { Group, Text, Button } from '@mantine/core'
import { IconFlag } from '@tabler/icons-react'
import { useTenantStore } from '../../stores/useTenantStore'

export function ImpersonationBanner() {
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
        Viewing as admin of <b>{impersonating.name}</b>.
        All actions are recorded in the audit log.
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
        Back to superadmin →
      </Button>
    </div>
  )
}
