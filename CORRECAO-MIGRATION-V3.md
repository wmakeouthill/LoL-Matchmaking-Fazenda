# 🔧 Guia de Correção - Erro Migration V3

## ❌ Problema

```
Unknown column 'cm1_0.lcu_match_data' in 'field list'
```

A coluna `lcu_match_data` não existe no banco de dados porque a migration V3 não foi aplicada.

---

## ✅ Solução Rápida

### Opção 1: Via MySQL Workbench (Recomendado)

1. **Abra o MySQL Workbench**
2. **Conecte ao banco `lol_matchmaking`**
3. **Execute este SQL**:

```sql
-- Adicionar coluna lcu_match_data
ALTER TABLE custom_matches 
ADD COLUMN lcu_match_data TEXT COMMENT 'JSON completo da partida do LCU';

-- Criar índice para riot_game_id
CREATE INDEX idx_custom_matches_riot_game_id ON custom_matches(riot_game_id);

-- Criar índice composto para status + completed_at
CREATE INDEX idx_custom_matches_status_completed ON custom_matches(status, completed_at);
```

4. **Verifique se foi criado**:

```sql
DESCRIBE custom_matches;
```

Você deve ver a coluna `lcu_match_data` tipo `TEXT` na lista.

---

### Opção 2: Via Script Batch (Windows)

1. **Abra o PowerShell ou CMD** na pasta do projeto
2. **Execute**:

```batch
cd "c:\lol-matchmaking - backend-refactor\spring-backend"
scripts\apply-migration-v3.bat
```

3. **Digite a senha do MySQL** quando solicitado

---

### Opção 3: Via MySQL Command Line

1. **Abra o MySQL Command Line Client** (Start Menu → MySQL)
2. **Digite a senha** (provavelmente `root`)
3. **Execute**:

```sql
USE lol_matchmaking;

ALTER TABLE custom_matches 
ADD COLUMN lcu_match_data TEXT COMMENT 'JSON completo da partida do LCU';

CREATE INDEX idx_custom_matches_riot_game_id ON custom_matches(riot_game_id);

CREATE INDEX idx_custom_matches_status_completed ON custom_matches(status, completed_at);

-- Verificar
DESCRIBE custom_matches;
```

---

### Opção 4: Via Docker (se estiver usando)

```bash
docker exec -it mysql-container mysql -u root -proot lol_matchmaking -e "
ALTER TABLE custom_matches 
ADD COLUMN lcu_match_data TEXT COMMENT 'JSON completo da partida do LCU';

CREATE INDEX idx_custom_matches_riot_game_id ON custom_matches(riot_game_id);

CREATE INDEX idx_custom_matches_status_completed ON custom_matches(status, completed_at);
"
```

---

## 🔍 Verificação

Após aplicar a migration, verifique:

### 1. Verificar coluna criada

```sql
SELECT 
    COLUMN_NAME,
    DATA_TYPE,
    IS_NULLABLE,
    COLUMN_COMMENT
FROM INFORMATION_SCHEMA.COLUMNS
WHERE TABLE_SCHEMA = 'lol_matchmaking'
AND TABLE_NAME = 'custom_matches'
AND COLUMN_NAME = 'lcu_match_data';
```

**Resultado esperado**:

```
COLUMN_NAME      | DATA_TYPE | IS_NULLABLE | COLUMN_COMMENT
-----------------|-----------|-------------|---------------------------
lcu_match_data   | text      | YES         | JSON completo da partida do LCU
```

### 2. Verificar índices criados

```sql
SHOW INDEX FROM custom_matches 
WHERE Key_name IN ('idx_custom_matches_riot_game_id', 'idx_custom_matches_status_completed');
```

**Resultado esperado**: Deve listar 2 índices

---

## 🚀 Após Corrigir

1. **Reinicie o backend Spring Boot**

   ```bash
   # Pare o backend (Ctrl+C)
   # Recompile (opcional)
   mvn clean package -DskipTests
   # Inicie novamente
   java -jar target/spring-backend-0.1.0-SNAPSHOT.jar
   ```

2. **Verifique os logs**
   - ✅ Não deve mais aparecer o erro `Unknown column 'lcu_match_data'`
   - ✅ Deve aparecer: `Flyway: Successfully validated N migrations`

3. **Teste o botão**
   - Abra o app → Configurações → Simular Última Partida Ranqueada
   - Deve funcionar sem erros!

---

## 🤔 Por que isso aconteceu?

### Problema com a Migration V3

A migration original usava sintaxe PostgreSQL incompatível com MySQL:

❌ **Antes** (não funciona no MySQL):

```sql
ALTER TABLE custom_matches 
ADD COLUMN IF NOT EXISTS lcu_match_data TEXT;  -- IF NOT EXISTS não existe no MySQL

CREATE INDEX IF NOT EXISTS idx_name ON table(col);  -- IF NOT EXISTS não existe no MySQL

CREATE INDEX idx ON table(col) WHERE condition;  -- WHERE não suportado no MySQL
```

✅ **Corrigido** (compatível com MySQL):

```sql
ALTER TABLE custom_matches 
ADD COLUMN lcu_match_data TEXT;

CREATE INDEX idx_custom_matches_riot_game_id ON custom_matches(riot_game_id);

CREATE INDEX idx_custom_matches_status_completed ON custom_matches(status, completed_at);
```

---

## 📝 Arquivos Criados

1. **`scripts/apply-migration-v3-safe.sql`**
   - Script SQL seguro que verifica se já existe antes de criar

2. **`scripts/apply-migration-v3.bat`** (Windows)
   - Script batch para aplicar automaticamente

3. **`scripts/apply-migration-v3.sh`** (Linux/Mac)
   - Script shell para aplicar automaticamente

4. **`src/main/resources/db/migration/V3__add_lcu_match_data.sql`**
   - Migration corrigida (será aplicada automaticamente pelo Flyway na próxima inicialização limpa)

---

## ⚠️ Nota Importante

Se você já tiver dados importantes no banco, **não use Flyway Clean**.

Use os scripts manuais para adicionar apenas a coluna faltante sem perder dados existentes.

---

## 🆘 Problemas?

Se encontrar erros como:

### "Duplicate column name 'lcu_match_data'"

✅ **Significa que já foi aplicado com sucesso!** Pode ignorar e reiniciar o backend.

### "Access denied for user"

🔑 **Verifique usuário e senha do MySQL** no comando ou script.

### "Table 'custom_matches' doesn't exist"

❌ **Problema maior** - O banco não está inicializado. Execute todas as migrations do zero.

---

**Documentação criada em**: 03/10/2025  
**Versão**: 1.0
