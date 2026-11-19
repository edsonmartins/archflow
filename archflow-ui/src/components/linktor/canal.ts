import {
    IconBrandInstagram,
    IconBrandMessenger,
    IconBrandTelegram,
    IconBrandWhatsapp,
    IconMail,
    IconMessage2,
    IconPlug,
    IconWorldWww,
} from '@tabler/icons-react'

type TablerIcon = typeof IconPlug

/** Ícone e cor Mantine de um canal do Linktor (whatsapp, telegram…), pelo tipo; desconhecido → tomada genérica. */
export function canalDoLinktor(tipo: string | undefined | null): { icon: TablerIcon; color: string } {
    const t = (tipo ?? '').toLowerCase()
    if (t.includes('whatsapp')) return { icon: IconBrandWhatsapp, color: 'green' }
    if (t.includes('telegram')) return { icon: IconBrandTelegram, color: 'cyan' }
    if (t.includes('instagram')) return { icon: IconBrandInstagram, color: 'pink' }
    if (t.includes('facebook') || t.includes('messenger')) return { icon: IconBrandMessenger, color: 'blue' }
    if (t.includes('mail')) return { icon: IconMail, color: 'orange' }
    if (t.includes('sms')) return { icon: IconMessage2, color: 'violet' }
    if (t.includes('web')) return { icon: IconWorldWww, color: 'teal' }
    return { icon: IconPlug, color: 'gray' }
}
