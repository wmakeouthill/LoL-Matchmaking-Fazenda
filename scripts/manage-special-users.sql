-- =====================================================
-- Script para gerenciar SPECIAL USERS
-- =====================================================
-- Special Users podem finalizar votações com apenas 1 voto
-- em vez de aguardar os 5 votos necessários normalmente
-- =====================================================

-- 1️⃣ VERIFICAR SPECIAL USERS ATUAIS
-- =====================================================
SELECT 
    settings_key,
    value AS special_users_json,
    created_at,
    updated_at
FROM settings
WHERE settings_key = 'special_users';


-- 2️⃣ CRIAR CONFIGURAÇÃO INICIAL (se não existir)
-- =====================================================
INSERT INTO settings (settings_key, value, created_at, updated_at)
SELECT 
    'special_users',
    '[]',  -- Lista vazia inicial
    NOW(),
    NOW()
WHERE NOT EXISTS (
    SELECT 1 FROM settings WHERE settings_key = 'special_users'
);


-- 3️⃣ ADICIONAR SPECIAL USER
-- =====================================================
-- ⚠️ IMPORTANTE: Substituir "FZD Ratoso#fzd" pelo nome EXATO do jogador
--    Formato: "SummonerName#TAG" (ex: "Player#br1", "Nome Completo#fzd")
--    Use o nome que aparece no LCU Gateway!

UPDATE settings
SET 
    value = JSON_ARRAY_APPEND(
        COALESCE(value, '[]'),
        '$',
        'FZD Ratoso#fzd'  -- 👈 SUBSTITUIR AQUI
    ),
    updated_at = NOW()
WHERE settings_key = 'special_users'
AND NOT JSON_CONTAINS(value, '"FZD Ratoso#fzd"');  -- Evita duplicados


-- 4️⃣ REMOVER SPECIAL USER
-- =====================================================
-- ⚠️ IMPORTANTE: Substituir "FZD Ratoso#fzd" pelo nome do jogador a remover

UPDATE settings
SET 
    value = JSON_REMOVE(
        value,
        JSON_UNQUOTE(
            JSON_SEARCH(value, 'one', 'FZD Ratoso#fzd')  -- 👈 SUBSTITUIR AQUI
        )
    ),
    updated_at = NOW()
WHERE settings_key = 'special_users'
AND JSON_CONTAINS(value, '"FZD Ratoso#fzd"');


-- 5️⃣ SUBSTITUIR TODA A LISTA DE SPECIAL USERS
-- =====================================================
-- Use este comando para redefinir completamente a lista

UPDATE settings
SET 
    value = '[
        "FZD Ratoso#fzd",
        "Admin#br1",
        "Moderador#euw"
    ]',
    updated_at = NOW()
WHERE settings_key = 'special_users';


-- 6️⃣ LIMPAR TODOS OS SPECIAL USERS
-- =====================================================
-- Remove todos os special users (volta para lista vazia)

UPDATE settings
SET 
    value = '[]',
    updated_at = NOW()
WHERE settings_key = 'special_users';


-- 7️⃣ VERIFICAR SE UM JOGADOR É SPECIAL USER
-- =====================================================
-- Substituir "FZD Ratoso#fzd" pelo nome do jogador a verificar

SELECT 
    CASE 
        WHEN JSON_CONTAINS(value, '"FZD Ratoso#fzd"') = 1 
        THEN '🌟 É SPECIAL USER'
        ELSE '❌ NÃO é special user'
    END AS status,
    value AS lista_completa
FROM settings
WHERE settings_key = 'special_users';


-- 8️⃣ LISTAR TODOS OS SPECIAL USERS (formato legível)
-- =====================================================
-- Extrai cada nome da lista JSON em linhas separadas

WITH RECURSIVE numbers AS (
    SELECT 0 AS n
    UNION ALL
    SELECT n + 1 FROM numbers WHERE n < 100
),
special_users_parsed AS (
    SELECT 
        n AS idx,
        JSON_EXTRACT(
            (SELECT value FROM settings WHERE settings_key = 'special_users'),
            CONCAT('$[', n, ']')
        ) AS username
    FROM numbers
)
SELECT 
    idx + 1 AS '#',
    JSON_UNQUOTE(username) AS summoner_name
FROM special_users_parsed
WHERE username IS NOT NULL
ORDER BY idx;


-- =====================================================
-- 📝 EXEMPLOS DE USO
-- =====================================================

-- Exemplo 1: Adicionar 3 special users
UPDATE settings
SET value = '["FZD Ratoso#fzd", "Admin#br1", "Moderador#euw"]'
WHERE settings_key = 'special_users';

-- Exemplo 2: Verificar se "FZD Ratoso#fzd" é special user
SELECT JSON_CONTAINS(value, '"FZD Ratoso#fzd"') AS is_special
FROM settings
WHERE settings_key = 'special_users';
-- Resultado: 1 = SIM, 0 = NÃO


-- =====================================================
-- 🧪 TESTAR VOTAÇÃO COM SPECIAL USER
-- =====================================================

-- 1. Configure special user no banco
UPDATE settings
SET value = '["SEU_SUMMONER_NAME#TAG"]'
WHERE settings_key = 'special_users';

-- 2. Jogue uma partida customizada com esse jogador

-- 3. Após a partida, abra o modal de votação

-- 4. Vote em qualquer partida LCU

-- 5. Verifique que a partida finalizou instantaneamente:
SELECT 
    id,
    status,  -- Deve ser 'completed'
    winner_team,
    linked_lcu_game_id,
    completed_at
FROM custom_matches
WHERE id = <MATCH_ID>  -- ID da sua partida
AND status = 'completed';

-- 6. Verifique os votos registrados:
SELECT 
    mv.match_id,
    p.summoner_name,
    mv.lcu_game_id,
    mv.voted_at
FROM match_votes mv
JOIN players p ON mv.player_id = p.id
WHERE mv.match_id = <MATCH_ID>
ORDER BY mv.voted_at;


-- =====================================================
-- 🔍 QUERIES ÚTEIS PARA DEBUG
-- =====================================================

-- Ver configuração atual de special users
SELECT * FROM settings WHERE settings_key = 'special_users';

-- Ver todas as partidas com votação em andamento
SELECT 
    cm.id,
    cm.status,
    cm.match_date,
    COUNT(mv.id) AS total_votes,
    GROUP_CONCAT(p.summoner_name ORDER BY mv.voted_at) AS voters
FROM custom_matches cm
LEFT JOIN match_votes mv ON cm.id = mv.match_id
LEFT JOIN players p ON mv.player_id = p.id
WHERE cm.status IN ('game_in_progress', 'ended')
GROUP BY cm.id
ORDER BY cm.match_date DESC;

-- Ver contagem de votos por partida LCU
SELECT 
    mv.lcu_game_id,
    COUNT(*) AS vote_count,
    GROUP_CONCAT(p.summoner_name) AS voters
FROM match_votes mv
JOIN players p ON mv.player_id = p.id
WHERE mv.match_id = <MATCH_ID>
GROUP BY mv.lcu_game_id
ORDER BY vote_count DESC;
