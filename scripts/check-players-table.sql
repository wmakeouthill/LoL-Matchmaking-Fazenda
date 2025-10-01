-- ✅ Script para verificar os dados salvos na tabela players
-- Execute este script no MySQL para ver os players criados

USE lolmatchmaking;

-- Mostrar estrutura da tabela
DESCRIBE players;

-- Mostrar todos os players (limitado a 10)
SELECT 
    id,
    summoner_name,
    summoner_id,
    SUBSTRING(puuid, 1, 20) as puuid_preview,
    region,
    current_mmr,
    custom_lp,
    custom_mmr,
    custom_games_played,
    created_at,
    updated_at
FROM players
ORDER BY updated_at DESC
LIMIT 10;

-- Mostrar estatísticas resumidas
SELECT 
    COUNT(*) as total_players,
    AVG(current_mmr) as avg_current_mmr,
    AVG(custom_mmr) as avg_custom_mmr,
    AVG(custom_lp) as avg_custom_lp,
    COUNT(CASE WHEN current_mmr IS NULL THEN 1 END) as players_without_current_mmr,
    COUNT(CASE WHEN summoner_id IS NULL THEN 1 END) as players_without_summoner_id,
    COUNT(CASE WHEN puuid IS NULL THEN 1 END) as players_without_puuid
FROM players;

