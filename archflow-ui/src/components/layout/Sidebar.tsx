import type { ComponentType, ReactNode } from 'react'
import { ActionIcon, Tooltip, UnstyledButton } from '@mantine/core'
import { IconLogout } from '@tabler/icons-react'
import brandLogo from '../../assets/brand/logo-dark-bg.png'
import brandMark from '../../assets/brand/mark.png'

/**
 * Peças da sidebar, compartilhadas pelo app e pela Administração para as duas
 * terem a mesma cara (painel marinho, logo oficial, item ativo com pílula e
 * barra). O visual mora em App.css (`.af-sidebar*`, `.af-nav-*`, tokens `--sb-*`).
 */

/** Topo da sidebar: logo oficial (aberta) ou só o símbolo (recolhida), mais uma etiqueta à direita. */
export function SidebarBrand({ collapsed, alt, tag }: { collapsed?: boolean; alt: string; tag?: ReactNode }) {
    return (
        <div className="af-sidebar-brand">
            {collapsed ? (
                <img src={brandMark} alt={alt} className="af-sidebar-mark" />
            ) : (
                <>
                    <img src={brandLogo} alt={alt} className="af-sidebar-logo" />
                    {tag}
                </>
            )}
        </div>
    )
}

/** Título de grupo (aberta) ou separador fino (recolhida). */
export function SidebarGroup({ label, collapsed, first }: { label: string; collapsed?: boolean; first?: boolean }) {
    if (collapsed) return first ? null : <div className="af-sidebar-sep" aria-hidden />
    return <div className="af-sidebar-group">{label}</div>
}

export function SidebarSeparator() {
    return <div className="af-sidebar-sep" aria-hidden />
}

/**
 * Um item de navegação. Recolhida, só o ícone aparece e o tooltip diz o que ele é
 * (o rótulo vira aria-label). Aberta, o rótulo é texto visível — os testes E2E
 * localizam os itens por ele.
 */
export function SidebarItem({
    icon: Icon, label, active, collapsed, onClick, badge, dimmed,
}: {
    icon: ComponentType<{ size?: number; stroke?: number }>
    label: string
    active?: boolean
    collapsed?: boolean
    onClick: () => void
    badge?: ReactNode
    /** Atalho secundário (ex.: voltar ao app) — mesmo item, tom mais baixo. */
    dimmed?: boolean
}) {
    const button = (
        <UnstyledButton
            className="af-nav-item"
            data-active={active || undefined}
            data-dimmed={dimmed || undefined}
            aria-current={active ? 'page' : undefined}
            aria-label={collapsed ? label : undefined}
            onClick={onClick}
        >
            <Icon size={18} stroke={1.8} />
            {!collapsed && <span className="af-nav-label">{label}</span>}
            {badge}
        </UnstyledButton>
    )
    return collapsed ? (
        <Tooltip label={label} position="right" withArrow>{button}</Tooltip>
    ) : button
}

/**
 * Rodapé da sidebar: quem está logado (iniciais + nome + e-mail) e o botão de sair. Recolhida, só o botão de sair,
 * com tooltip. O `aria-label` do botão é o texto de sair traduzido — os testes o acham por ele.
 */
export function SidebarUser({
    name, detail, logoutLabel, onLogout, collapsed,
}: {
    name: string
    detail?: string
    logoutLabel: string
    onLogout: () => void
    collapsed?: boolean
}) {
    const iniciais = name.split(/[\s._@-]+/).filter(Boolean).slice(0, 2).map((p) => p[0]?.toUpperCase()).join('') || '?'
    const sair = (
        <ActionIcon variant="subtle" size={32} radius="md" className="af-sidebar-logout"
                    onClick={onLogout} aria-label={logoutLabel}>
            <IconLogout size={17} stroke={1.8} />
        </ActionIcon>
    )
    if (collapsed) {
        return (
            <div className="af-sidebar-user" data-collapsed>
                <Tooltip label={logoutLabel} position="right" withArrow>{sair}</Tooltip>
            </div>
        )
    }
    return (
        <div className="af-sidebar-user">
            <span className="af-sidebar-avatar" aria-hidden>{iniciais}</span>
            <span className="af-sidebar-user-text">
                <span className="af-sidebar-user-name">{name}</span>
                {detail && <span className="af-sidebar-user-detail">{detail}</span>}
            </span>
            <Tooltip label={logoutLabel} withArrow>{sair}</Tooltip>
        </div>
    )
}
