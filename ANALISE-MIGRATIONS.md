# 📊 Análise: Flyway vs Liquibase

## ✅ Situação Atual

### Configuração Ativa

- **Liquibase**: ✅ ATIVO (configurado em `application.yml`)
- **Flyway**: ❌ NÃO CONFIGURADO (não habilitado)

### Arquivos de Migration

#### 🟢 Liquibase (ATIVO - sendo executado)

```
db/changelog/
├── db.changelog-master.yaml
└── changes/
    ├── 0001-initial-schema.yaml
    ├── 0002-add-missing-columns.yaml
    ├── 0003-fix-settings-column.yaml
    └── 0004-add-lcu-match-data-and-fix-settings.yaml ← NOVO
```

#### 🔴 Flyway (INATIVO - sendo ignorado)

```
db/migration/
├── V1__baseline.sql
├── V2__additional_tables.sql
└── V3__add_lcu_match_data.sql ← NÃO SERÁ EXECUTADO
```

---

## 🔍 Comparação de Conteúdo

### Liquibase Changesets Existentes

#### 0001-initial-schema.yaml

- Cria tabela `players`
- Cria tabela `custom_matches`
- Cria tabela `event_inbox`
- Cria tabela `settings`

#### 0002-add-missing-columns.yaml

- Adiciona colunas faltantes em `discord_lol_links`
- Adiciona colunas faltantes em `event_inbox`
- Adiciona coluna `processed` em `event_inbox`

#### 0003-fix-settings-column.yaml

- Renomeia coluna `key` para `settings_key` na tabela `settings`

### Flyway Migrations (Não Executadas)

#### V1__baseline.sql

- Cria tabela `players` ← **DUPLICADO com Liquibase**
- Cria tabela `custom_matches` ← **DUPLICADO com Liquibase**
- Cria tabela `event_inbox` ← **DUPLICADO com Liquibase**

#### V2__additional_tables.sql

- Cria tabela `queue_players` ← **NÃO ESTÁ NO LIQUIBASE!** ⚠️
- Cria tabela `discord_lol_links` ← **NÃO ESTÁ NO LIQUIBASE!** ⚠️
- Cria tabela `matches` ← **NÃO ESTÁ NO LIQUIBASE!** ⚠️

#### V3__add_lcu_match_data.sql (recém criado)

- Adiciona coluna `lcu_match_data` ← **Agora está em 0004 do Liquibase** ✅
- Cria índices
- Modifica settings para utf8mb4 ← **Agora está em 0004 do Liquibase** ✅

---

## ⚠️ PROBLEMA IDENTIFICADO

### Tabelas que FALTAM no Liquibase

1. **`queue_players`** (está em V2 Flyway, mas não em Liquibase)
2. **`discord_lol_links`** (está em V2 Flyway, mas não em Liquibase)  
3. **`matches`** (está em V2 Flyway, mas não em Liquibase)

Essas tabelas podem ter sido criadas manualmente ou existiam antes do Liquibase ser implementado.

---

## ✅ Solução Implementada

### Arquivo Criado: `0004-add-lcu-match-data-and-fix-settings.yaml`

Este changeset adiciona:

1. ✅ Coluna `lcu_match_data TEXT` em `custom_matches`
2. ✅ Índice `idx_custom_matches_riot_game_id`
3. ✅ Índice `idx_custom_matches_status_completed`
4. ✅ Modifica coluna `value` de `settings` para MEDIUMTEXT utf8mb4 (suporta emojis)
5. ✅ Modifica coluna `settings_key` para utf8mb4

---

## 🎯 Próximos Passos

### Opção 1: Manter Apenas Liquibase (Recomendado)

1. ✅ Manter changeset 0004 criado
2. ❌ Remover pasta `db/migration/` (Flyway não está sendo usado)
3. ✅ Reiniciar backend para aplicar changeset 0004

### Opção 2: Migrar Tabelas Faltantes para Liquibase (Se necessário)

Se as tabelas `queue_players`, `discord_lol_links`, `matches` são necessárias:

1. Criar `0005-add-missing-tables.yaml` com essas tabelas
2. Adicionar ao `db.changelog-master.yaml`

---

## 📝 Recomendação Final

**REMOVER** a pasta `db/migration/` porque:

- Flyway não está configurado no projeto
- Conteúdo está duplicado ou obsoleto
- Causa confusão sobre qual sistema de migration está ativo
- O V3 que criamos lá **nunca será executado**

**MANTER** apenas `db/changelog/` com Liquibase.

---

## 🚀 Para Aplicar as Mudanças

Apenas **reinicie o backend**:

```bash
# Pare o backend (Ctrl+C)
# Recompile (opcional)
mvn clean package -DskipTests

# Inicie novamente
mvn spring-boot:run
```

O Liquibase irá automaticamente:

1. Detectar o novo changeset `0004`
2. Aplicar as migrations
3. Adicionar coluna `lcu_match_data`
4. Criar índices
5. Modificar tabela `settings` para utf8mb4

✅ **PRONTO!** O erro será corrigido.
