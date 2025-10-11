package br.com.lolmatchmaking.backend.service.lock;

/**
 * ✅ Estados possíveis de um jogador no sistema
 * 
 * Gerencia o ciclo de vida do jogador através dos estados:
 * AVAILABLE → IN_QUEUE → IN_MATCH_FOUND → IN_DRAFT → IN_GAME → AVAILABLE
 * 
 * REFERÊNCIA:
 * -
 * ARQUITETURA-CORRETA-SINCRONIZACAO.md#3-lock-de-estado-do-jogador-player-state-lock
 */
public enum PlayerState {

    /**
     * Jogador disponível, pode entrar na fila
     */
    AVAILABLE,

    /**
     * Jogador na fila aguardando partida
     */
    IN_QUEUE,

    /**
     * Partida encontrada, aguardando aceitação
     */
    IN_MATCH_FOUND,

    /**
     * No draft de campeões
     */
    IN_DRAFT,

    /**
     * Jogando partida
     */
    IN_GAME;

    /**
     * Verifica se é um estado que indica que o jogador está em partida
     */
    public boolean isInMatch() {
        return this == IN_MATCH_FOUND || this == IN_DRAFT || this == IN_GAME;
    }

    /**
     * Verifica se jogador pode entrar na fila
     */
    public boolean canJoinQueue() {
        return this == AVAILABLE;
    }
}
