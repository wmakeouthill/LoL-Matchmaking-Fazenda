-- ========================================
-- Migration V3: Adicionar suporte a LCU match data e corrigir settings
-- ========================================

-- 1. Adicionar coluna lcu_match_data na tabela custom_matches
--    Para vinculação de partidas customizadas com dados completos do LCU
ALTER TABLE custom_matches 
ADD COLUMN lcu_match_data TEXT COMMENT 'JSON completo da partida do LCU para vinculação e histórico';

-- 2. Criar índice para busca rápida por riot_game_id
CREATE INDEX idx_custom_matches_riot_game_id ON custom_matches(riot_game_id);

-- 3. Criar índice composto para busca de partidas completadas
CREATE INDEX idx_custom_matches_status_completed ON custom_matches(status, completed_at);

-- 4. Modificar coluna value da tabela settings para suportar emojis e caracteres especiais
--    Mudando de TEXT para MEDIUMTEXT com charset utf8mb4 para suportar emojis
ALTER TABLE settings 
MODIFY COLUMN `value` MEDIUMTEXT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL 
COMMENT 'Valor da configuração (suporta emojis e caracteres especiais)';

-- 5. Garantir que a coluna settings_key também suporte utf8mb4
ALTER TABLE settings 
MODIFY COLUMN settings_key VARCHAR(191) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL;
