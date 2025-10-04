-- ========================================
-- COPIE E COLE ESTE SQL NO MYSQL WORKBENCH
-- ========================================

-- 1. Conecte ao banco de dados
USE lol_matchmaking;

-- 2. Adicionar coluna lcu_match_data
ALTER TABLE custom_matches 
ADD COLUMN lcu_match_data TEXT COMMENT 'JSON completo da partida do LCU';

-- 3. Criar índice para riot_game_id
CREATE INDEX idx_custom_matches_riot_game_id ON custom_matches(riot_game_id);

-- 4. Criar índice composto para status + completed_at
CREATE INDEX idx_custom_matches_status_completed ON custom_matches(status, completed_at);

-- 5. Verificar se foi criado
DESCRIBE custom_matches;

-- 6. Verificar detalhes da coluna
SELECT 
    COLUMN_NAME,
    DATA_TYPE,
    IS_NULLABLE,
    COLUMN_COMMENT
FROM INFORMATION_SCHEMA.COLUMNS
WHERE TABLE_SCHEMA = 'lol_matchmaking'
AND TABLE_NAME = 'custom_matches'
AND COLUMN_NAME = 'lcu_match_data';

-- ========================================
-- RESULTADO ESPERADO:
-- Deve aparecer a coluna lcu_match_data tipo TEXT
-- ========================================
