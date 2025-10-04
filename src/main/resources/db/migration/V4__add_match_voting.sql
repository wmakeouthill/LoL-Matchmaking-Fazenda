-- ========================================
-- Migration V4: Sistema de Votação de Partidas LCU
-- ========================================
-- Data: 2025-10-04
-- Descrição: Adiciona suporte ao sistema de votação democrática
--            onde jogadores votam em qual partida do histórico do LCU
--            corresponde à partida customizada jogada.
--            Quando 5 votos são atingidos, a partida é vinculada automaticamente.
-- ========================================

-- 1. Criar tabela de votos
CREATE TABLE IF NOT EXISTS match_votes (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    match_id BIGINT NOT NULL COMMENT 'ID da partida customizada (referência a matches.id)',
    player_id BIGINT NOT NULL COMMENT 'ID do jogador que está votando (referência a players.id)',
    lcu_game_id BIGINT NOT NULL COMMENT 'ID da partida do LCU escolhida (gameId do histórico)',
    voted_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT 'Timestamp de quando o voto foi registrado',
    
    -- Constraints
    CONSTRAINT fk_match_votes_match 
        FOREIGN KEY (match_id) 
        REFERENCES matches(id) 
        ON DELETE CASCADE,
    
    CONSTRAINT fk_match_votes_player 
        FOREIGN KEY (player_id) 
        REFERENCES players(id) 
        ON DELETE CASCADE,
    
    -- Garantir que cada jogador vota apenas uma vez por partida
    CONSTRAINT unique_player_vote_per_match 
        UNIQUE (match_id, player_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
COMMENT='Armazena votos dos jogadores para vinculação de partidas do LCU';

-- 2. Criar índices para performance
CREATE INDEX idx_match_votes_match_id ON match_votes(match_id);
CREATE INDEX idx_match_votes_player_id ON match_votes(player_id);
CREATE INDEX idx_match_votes_lcu_game_id ON match_votes(lcu_game_id);
CREATE INDEX idx_match_votes_voted_at ON match_votes(voted_at);

-- 3. Adicionar campos na tabela matches para vinculação de partidas do LCU
ALTER TABLE matches 
    ADD COLUMN linked_lcu_game_id BIGINT NULL 
        COMMENT 'ID da partida do LCU vinculada após votação dos jogadores',
    ADD COLUMN lcu_match_data TEXT NULL 
        COMMENT 'JSON completo com dados da partida do LCU (participants, teams, stats, KDA, items, runes, etc.)';

-- 4. Criar índice para busca rápida por partidas vinculadas
CREATE INDEX idx_matches_linked_lcu_game_id ON matches(linked_lcu_game_id);

-- ========================================
-- Comentários sobre o funcionamento:
-- ========================================
-- 
-- FLUXO DE VOTAÇÃO:
-- 1. Partida customizada termina → Status = 'game_in_progress'
-- 2. Jogadores clicam "Escolher Partida"
-- 3. Sistema busca últimas 3 partidas custom do LCU
-- 4. Jogadores votam → INSERT INTO match_votes
-- 5. Ao atingir 5 votos na mesma lcu_game_id:
--    - Sistema busca dados completos da partida do LCU
--    - UPDATE matches SET linked_lcu_game_id = X, lcu_match_data = {...}, status = 'completed'
--    - Broadcast via WebSocket: "match_linked"
-- 6. Histórico exibe dados completos do LCU
--
-- EXEMPLO DE USO:
-- SELECT lcu_game_id, COUNT(*) as vote_count 
-- FROM match_votes 
-- WHERE match_id = 999 
-- GROUP BY lcu_game_id;
--
-- Resultado:
-- lcu_game_id | vote_count
-- 12345       | 5    ← Esta partida será vinculada automaticamente!
-- 67890       | 2
-- 11111       | 3
--
-- ========================================
