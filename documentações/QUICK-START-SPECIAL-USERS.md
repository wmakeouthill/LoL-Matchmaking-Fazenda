# 🚀 Quick Start: Special Users

## Configuração em 3 Passos

### 1️⃣ Cadastrar Special User no Banco

```sql
UPDATE settings
SET value = '["SEU_SUMMONER_NAME#TAG"]'
WHERE settings_key = 'special_users';
```

**Exemplo:**

```sql
UPDATE settings
SET value = '["FZD Ratoso#fzd", "Admin#br1"]'
WHERE settings_key = 'special_users';
```

### 2️⃣ Iniciar Aplicação

```bash
npm run electron
```

### 3️⃣ Testar

1. Jogue uma partida customizada com o special user
2. Após finalizar, vote em qualquer partida LCU no modal
3. ✅ Partida finaliza instantaneamente!

---

## Verificar se Funcionou

```sql
SELECT status, winner_team, linked_lcu_game_id
FROM custom_matches
ORDER BY id DESC
LIMIT 1;
```

**Status deve ser:** `completed`  
**linked_lcu_game_id deve ter:** ID da partida LCU

---

## Adicionar Mais Special Users

```sql
UPDATE settings
SET value = JSON_ARRAY_APPEND(value, '$', 'NOVO_PLAYER#TAG')
WHERE settings_key = 'special_users';
```

## Remover Special User

```sql
UPDATE settings
SET value = JSON_REMOVE(
    value,
    JSON_UNQUOTE(JSON_SEARCH(value, 'one', 'PLAYER#TAG'))
)
WHERE settings_key = 'special_users';
```

---

## ⚠️ Importante

- Use o nome **EXATO** que aparece no LCU
- Formato: `SummonerName#TAG` (ex: `Player#br1`)
- Case-insensitive, mas sem espaços extras
