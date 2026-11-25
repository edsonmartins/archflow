import { useEffect, useMemo, useState } from 'react'
import { useAgentContext } from '@copilotkit/react-core/v2'

const MINUTE_MS = 60_000
// Nome fixo em inglês: getDay() é local, como o instante; um nome traduzido variaria com o idioma da tela.
const WEEKDAYS = ['Sunday', 'Monday', 'Tuesday', 'Wednesday', 'Thursday', 'Friday', 'Saturday']

const pad = (n: number) => String(n).padStart(2, '0')

/**
 * Instante local com o deslocamento (2026-11-25T09:30-03:00), o fuso IANA e o dia da semana. O deslocamento
 * explícito é o que torna o valor inequívoco para o modelo: um "09:30" sem ele não diz de que fuso é, e o fuso
 * IANA sozinho exigiria que o modelo soubesse a regra de horário de verão daquela data.
 */
function clockContext(date: Date) {
    const offset = -date.getTimezoneOffset()
    const sign = offset >= 0 ? '+' : '-'
    const abs = Math.abs(offset)
    const now = `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}`
        + `T${pad(date.getHours())}:${pad(date.getMinutes())}${sign}${pad(Math.floor(abs / 60))}:${pad(abs % 60)}`
    return {
        now,
        timeZone: Intl.DateTimeFormat().resolvedOptions().timeZone,
        weekday: WEEKDAYS[date.getDay()],
    }
}

/**
 * RCHFLW-98 — o copiloto não sabia que dia é hoje (respondia uma data inventada): nada no contexto levava a data
 * e o backend, por decisão, não a injeta. A data vem do navegador, com o fuso do usuário, que é o "hoje" dele.
 *
 * <p>Componente próprio, e não mais um useAgentContext no CopilotAppOperator, para que o relógio que vira a cada
 * minuto renderize só isto, e não o operador inteiro (canvas, fluxos, ferramentas). Montado uma vez dentro do
 * provider, vale para os dois agentes (archflow e archflow-generate).</p>
 */
export default function CopilotClockContext() {
    const [now, setNow] = useState(() => new Date())

    useEffect(() => {
        let timer: ReturnType<typeof setTimeout>
        // Agenda para a virada do minuto, não a cada 60 s a partir da montagem: senão o valor ficaria até quase um
        // minuto atrasado. Reagenda a cada disparo, porque um timer atrasado (aba em segundo plano) acumularia erro.
        const schedule = () => {
            timer = setTimeout(tick, MINUTE_MS - (Date.now() % MINUTE_MS))
        }
        // Mantém o Date anterior quando o minuto não mudou: um objeto novo a cada foco recalcularia o valor e
        // registraria de novo a mesma entrada no provider, à toa.
        const tick = () => {
            clearTimeout(timer)
            setNow((prev) => Math.floor(prev.getTime() / MINUTE_MS) === Math.floor(Date.now() / MINUTE_MS)
                ? prev : new Date())
            schedule()
        }
        // O timer do navegador corre num relógio que para durante a suspensão da máquina, e a aba oculta é
        // estrangulada: ao voltar, o disparo pendente pode estar até um minuto atrasado e o "hoje" ainda ser o de
        // ontem. Voltar a ficar visível ou receber foco é quando o usuário vai perguntar; ali o relógio se acerta.
        const onVisible = () => {
            if (document.visibilityState === 'visible') tick()
        }
        schedule()
        document.addEventListener('visibilitychange', onVisible)
        window.addEventListener('focus', tick)
        return () => {
            clearTimeout(timer)
            document.removeEventListener('visibilitychange', onVisible)
            window.removeEventListener('focus', tick)
        }
    }, [])

    const value = useMemo(() => clockContext(now), [now])
    useAgentContext({
        description: "The user's current local date and time (ISO 8601 with UTC offset, minute precision), "
            + 'IANA time zone and weekday — use it for "today", "now" and relative dates',
        value,
    })

    return null
}
