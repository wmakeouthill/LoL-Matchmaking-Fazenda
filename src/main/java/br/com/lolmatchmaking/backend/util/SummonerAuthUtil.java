package br.com.lolmatchmaking.backend.util;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * Utilitário para extrair e validar o header X-Summoner-Name das requisições
 * HTTP.
 * Este header é usado para identificar o jogador que está fazendo a requisição,
 * permitindo que o backend filtre dados e rotei
 * 
 * e operações específicas para cada jogador.
 */
public class SummonerAuthUtil {

    private static final String HEADER_NAME = "X-Summoner-Name";

    // Construtor privado para classe utilitária
    private SummonerAuthUtil() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * Extrai o summonerName do header X-Summoner-Name da requisição.
     * Lança exceção HTTP 401 se o header não estiver presente ou estiver vazio.
     * 
     * @param request A requisição HTTP
     * @return O summonerName normalizado (lowercase) do jogador autenticado
     * @throws ResponseStatusException com HTTP 401 se o header estiver
     *                                 ausente/vazio
     */
    public static String getSummonerNameFromRequest(HttpServletRequest request) {
        String summonerName = request.getHeader(HEADER_NAME);

        if (summonerName == null || summonerName.trim().isEmpty()) {
            throw new ResponseStatusException(
                    HttpStatus.UNAUTHORIZED,
                    "Header X-Summoner-Name é obrigatório para identificar o jogador");
        }

        // Normalizar para lowercase (consistente com SessionRegistry)
        return summonerName.toLowerCase().trim();
    }

    /**
     * Tenta extrair o summonerName do header, mas retorna null se não estiver
     * presente
     * ao invés de lançar exceção. Útil para endpoints onde o header é opcional.
     * 
     * @param request A requisição HTTP
     * @return O summonerName normalizado (lowercase) ou null se não presente
     */
    public static String getSummonerNameFromRequestOptional(HttpServletRequest request) {
        String summonerName = request.getHeader(HEADER_NAME);

        if (summonerName == null || summonerName.trim().isEmpty()) {
            return null;
        }

        return summonerName.toLowerCase().trim();
    }

    /**
     * Verifica se a requisição possui o header X-Summoner-Name válido.
     * 
     * @param request A requisição HTTP
     * @return true se o header está presente e não está vazio
     */
    public static boolean hasValidSummonerHeader(HttpServletRequest request) {
        String summonerName = request.getHeader(HEADER_NAME);
        return summonerName != null && !summonerName.trim().isEmpty();
    }
}
