import { useCallback } from 'react'
import { useNavigate } from 'react-router-dom'
import { LOGOUT_NAVIGATION_STATE } from '../../lib/sessionNavigation'

/**
 * O Sair das barras laterais (app e Administração). Só navega: quem encerra a sessão é a página de login, ao
 * receber a navegação (ver LOGOUT_NAVIGATION_STATE). Com o editor sujo, o useBlocker dele segura esta
 * navegação como as outras saídas (RCHFLW-142); "Continuar editando" mantém a sessão, e "Salvar e sair" grava
 * ainda com o token.
 */
export function useLogout(): () => void {
    const navigate = useNavigate()
    return useCallback(() => {
        void navigate('/login', { state: LOGOUT_NAVIGATION_STATE })
    }, [navigate])
}
