-- =====================================================
-- Configuração Inicial de Special Users
-- Execute este script para configurar seu primeiro special user
-- =====================================================

-- 1️⃣ Criar registro na tabela settings (se não existir)
INSERT INTO settings (settings_key, value, created_at, updated_at)
VALUES (
    'special_users',
    '["FZD Ratoso#fzd"]',  -- 👈 ALTERE PARA SEU SUMMONER NAME
    NOW(),
    NOW()
)
ON DUPLICATE KEY UPDATE
    value = '["FZD Ratoso#fzd"]',  -- 👈 ALTERE PARA SEU SUMMONER NAME
    updated_at = NOW();

-- 2️⃣ Verificar se foi criado corretamente
SELECT 
    settings_key,
    value,
    created_at,
    updated_at
FROM settings
WHERE settings_key = 'special_users';

-- ✅ Resultado esperado:
-- ┌──────────────┬─────────────────────┬─────────────┬─────────────┐
-- │ settings_key │ value               │ created_at  │ updated_at  │
-- ├──────────────┼─────────────────────┼─────────────┼─────────────┤
-- │special_users │["FZD Ratoso#fzd"]   │ 2025-10-05  │ 2025-10-05  │
-- └──────────────┴─────────────────────┴─────────────┴─────────────┘
