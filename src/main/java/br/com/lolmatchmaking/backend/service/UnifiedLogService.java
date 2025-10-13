package br.com.lolmatchmaking.backend.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ✅ NOVO: Serviço para enviar logs específicos [Player-Sessions] para o
 * Electron
 * 
 * Envia APENAS logs relacionados à vinculação player-sessão para o console do
 * Electron.
 * Focado em logs específicos para não sobrecarregar o sistema.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UnifiedLogService {

    private final ObjectMapper objectMapper;
    private final RedisTemplate<String, Object> redisTemplate;

    // ✅ Cache de sessões que solicitam logs [Player-Sessions]
    private final Set<String> playerSessionLogSessions = ConcurrentHashMap.newKeySet();

    /**
     * ✅ NOVO: Registra uma sessão para receber logs [Player-Sessions]
     */
    public void registerPlayerSessionLogSession(String sessionId) {
        playerSessionLogSessions.add(sessionId);
        log.debug("📋 [Player-Sessions] [UNIFIED-LOGS] Sessão {} registrada para logs player-sessions", sessionId);
    }

    /**
     * ✅ NOVO: Remove uma sessão dos logs [Player-Sessions]
     */
    public void unregisterPlayerSessionLogSession(String sessionId) {
        playerSessionLogSessions.remove(sessionId);
        log.debug("📋 [Player-Sessions] [UNIFIED-LOGS] Sessão {} removida dos logs player-sessions", sessionId);
    }

    /**
     * ✅ NOVO: Envia um log [Player-Sessions] para todas as sessões registradas
     */
    public void sendPlayerSessionLog(String level, String tag, String message, Object... args) {
        if (playerSessionLogSessions.isEmpty()) {
            return; // Nenhuma sessão registrada
        }

        try {
            // Formatar a mensagem
            String formattedMessage = String.format(message, args);

            // Criar payload do log
            Map<String, Object> logPayload = Map.of(
                    "type", "player_session_log",
                    "level", level,
                    "tag", tag,
                    "message", formattedMessage,
                    "timestamp", System.currentTimeMillis(),
                    "source", "backend");

            String logJson = objectMapper.writeValueAsString(logPayload);

            // ✅ NOVO: Enviar via Redis Pub/Sub (mais simples, sem dependência circular)
            redisTemplate.convertAndSend("player_session_logs", logJson);

            log.debug("📋 [Player-Sessions] [UNIFIED-LOGS] Log enviado: {} - {}", tag, formattedMessage);

        } catch (Exception e) {
            log.error("❌ [Player-Sessions] [UNIFIED-LOGS] Erro ao enviar log player-sessions", e);
        }
    }

    /**
     * ✅ NOVO: Métodos de conveniência para diferentes níveis de log
     * [Player-Sessions]
     */
    public void sendPlayerSessionInfoLog(String tag, String message, Object... args) {
        sendPlayerSessionLog("info", tag, message, args);
    }

    public void sendPlayerSessionWarnLog(String tag, String message, Object... args) {
        sendPlayerSessionLog("warn", tag, message, args);
    }

    public void sendPlayerSessionErrorLog(String tag, String message, Object... args) {
        sendPlayerSessionLog("error", tag, message, args);
    }

    public void sendPlayerSessionDebugLog(String tag, String message, Object... args) {
        sendPlayerSessionLog("debug", tag, message, args);
    }

    /**
     * ✅ NOVO: Verifica se há sessões registradas para logs [Player-Sessions]
     */
    public boolean hasRegisteredPlayerSessionLogSessions() {
        return !playerSessionLogSessions.isEmpty();
    }

    /**
     * ✅ NOVO: Retorna número de sessões registradas para logs [Player-Sessions]
     */
    public int getRegisteredPlayerSessionLogSessionsCount() {
        return playerSessionLogSessions.size();
    }

}