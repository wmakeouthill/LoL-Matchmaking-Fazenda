-- Script para aplicar migration V3 de forma segura (MySQL)
-- Adiciona coluna lcu_match_data na tabela custom_matches

-- Verificar se a coluna já existe e adicionar se não existir
SET @col_exists = (
    SELECT COUNT(*) 
    FROM INFORMATION_SCHEMA.COLUMNS 
    WHERE TABLE_SCHEMA = DATABASE() 
    AND TABLE_NAME = 'custom_matches' 
    AND COLUMN_NAME = 'lcu_match_data'
);

SET @sql = IF(@col_exists = 0, 
    'ALTER TABLE custom_matches ADD COLUMN lcu_match_data TEXT COMMENT "JSON completo da partida do LCU para vinculação e histórico"',
    'SELECT "Coluna lcu_match_data já existe" AS message'
);

PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- Criar índice para riot_game_id se não existir
SET @index_exists = (
    SELECT COUNT(*)
    FROM INFORMATION_SCHEMA.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE()
    AND TABLE_NAME = 'custom_matches'
    AND INDEX_NAME = 'idx_custom_matches_riot_game_id'
);

SET @sql = IF(@index_exists = 0,
    'CREATE INDEX idx_custom_matches_riot_game_id ON custom_matches(riot_game_id)',
    'SELECT "Índice idx_custom_matches_riot_game_id já existe" AS message'
);

PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- Criar índice composto para status + completed_at se não existir
SET @index_exists = (
    SELECT COUNT(*)
    FROM INFORMATION_SCHEMA.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE()
    AND TABLE_NAME = 'custom_matches'
    AND INDEX_NAME = 'idx_custom_matches_status_completed'
);

SET @sql = IF(@index_exists = 0,
    'CREATE INDEX idx_custom_matches_status_completed ON custom_matches(status, completed_at)',
    'SELECT "Índice idx_custom_matches_status_completed já existe" AS message'
);

PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- Verificar resultado
SELECT 
    COLUMN_NAME,
    DATA_TYPE,
    COLUMN_COMMENT
FROM INFORMATION_SCHEMA.COLUMNS
WHERE TABLE_SCHEMA = DATABASE()
AND TABLE_NAME = 'custom_matches'
AND COLUMN_NAME = 'lcu_match_data';

SELECT 
    INDEX_NAME,
    COLUMN_NAME,
    SEQ_IN_INDEX
FROM INFORMATION_SCHEMA.STATISTICS
WHERE TABLE_SCHEMA = DATABASE()
AND TABLE_NAME = 'custom_matches'
AND INDEX_NAME IN ('idx_custom_matches_riot_game_id', 'idx_custom_matches_status_completed')
ORDER BY INDEX_NAME, SEQ_IN_INDEX;
