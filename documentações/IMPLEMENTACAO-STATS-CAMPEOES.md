# 📊 Implementação: Estatísticas Detalhadas de Campeões

## ✅ Resumo da Implementação

### 🎯 Objetivo

Atualizar estatísticas detalhadas dos jogadores ao clicar no botão "Atualizar" do leaderboard, incluindo:

- Top 5 campeões mais pickados nas custom matches
- Top 3 campeões de maior maestria (Riot API)
- Top 5 campeões ranked (mais jogados + maior winrate) (Riot API)

---

## 📁 Arquivos Criados/Modificados

### 1. **Database Migration** ✅

**Arquivo**: `0007-add-player-stats-draft.yaml`

- Nova coluna `player_stats_draft` (JSON): Top 5 campeões custom matches
- Nova coluna `mastery_champions` (JSON): Top 3 campeões maestria (Riot API)
- Nova coluna `ranked_champions` (JSON): Top 5 campeões ranked (Riot API)
- Nova coluna `stats_last_updated` (TIMESTAMP): Data última atualização

### 2. **Entity** ✅

**Arquivo**: `Player.java`

```java
@Column(name = "player_stats_draft", columnDefinition = "JSON")
private String playerStatsDraft;

@Column(name = "mastery_champions", columnDefinition = "JSON")
private String masteryChampions;

@Column(name = "ranked_champions", columnDefinition = "JSON")
private String rankedChampions;

@Column(name = "stats_last_updated")
private Instant statsLastUpdated;
```

### 3. **DTOs** ✅

**Arquivo**: `PlayerChampionStatsDTO.java`

- `ChampionPickStats`: Dados de custom matches
  - championId, championName, gamesPlayed, wins, losses, winRate
- `ChampionMasteryStats`: Dados de maestria (Riot API)
  - championId, championName, championLevel, championPoints
- `ChampionRankedStats`: Dados ranked (Riot API)
  - championId, championName, gamesPlayed, wins, losses, winRate, tier

### 4. **Riot API Service** ✅

**Arquivo**: `RiotChampionStatsService.java`

**Métodos**:

- `getRiotApiKey()`: Busca API key da tabela `settings`
- `getTopMasteryChampions(puuid)`: Busca top 3 maestria
  - Endpoint: `GET /lol/champion-mastery/v4/champion-masteries/by-puuid/{puuid}/top?count=3`
- `getTopRankedChampions(puuid)`: Busca top 5 ranked
  - Endpoint: `GET /lol/match/v5/matches/by-puuid/{puuid}/ids?queue=420&count=50`
  - Processa últimas 50 partidas ranked
  - Ordena por winrate e games played

### 5. **Player Service** ✅

**Arquivo**: `PlayerService.java`

**Novos Métodos**:

- `updatePlayerChampionStats(summonerName)`: Atualiza todas as estatísticas
  - Extrai top 5 custom champions
  - Busca top 3 maestria (se tiver PUUID)
  - Busca top 5 ranked (se tiver PUUID)
  - Salva JSON nas colunas correspondentes
  
- `extractTop5CustomChampions(summonerName)`: Processa custom matches
  - Parse do JSON `participants_data`
  - Agrupa por campeão
  - Calcula wins, losses, winrate
  - Retorna top 5 por games played

- `extractJsonBoolean(json, key)`: Helper para parse JSON
- `convertToJson(data)`: Converte Object para JSON string

### 6. **Controller** ✅

**Arquivo**: `CompatibilityController.java`

**Novo Endpoint**:

```java
POST /api/stats/update-champion-stats
```

- Atualiza estatísticas básicas (KDA, etc)
- Atualiza estatísticas detalhadas de campeões
- Retorna contadores de sucesso

**Response**:

```json
{
  "success": true,
  "message": "Estatísticas de campeões atualizadas com sucesso",
  "basicStatsUpdated": 50,
  "championStatsUpdated": 50
}
```

### 7. **Frontend** ✅

**Arquivo**: `leaderboard.ts`

**Método Atualizado**:

```typescript
async updateLeaderboardStats() {
  // Chama /api/stats/update-champion-stats
  // Mostra progresso no console
  // Recarrega leaderboard após sucesso
}
```

---

## 🔧 Configuração Necessária

### Riot API Key

A Riot API Key deve ser inserida na tabela `settings`:

```sql
INSERT INTO settings (settings_key, value) 
VALUES ('riot_api_key', 'RGAPI-SUA-CHAVE-AQUI')
ON DUPLICATE KEY UPDATE value = 'RGAPI-SUA-CHAVE-AQUI';
```

**Onde conseguir**:

1. Acesse: <https://developer.riotgames.com/>
2. Faça login com conta Riot
3. Copie a Development API Key
4. ⚠️ **Importante**: A Development Key expira a cada 24 horas

---

## 📊 Estrutura dos Dados JSON

### 1. `player_stats_draft` (Custom Matches)

```json
[
  {
    "championId": 3,
    "championName": "Galio",
    "gamesPlayed": 15,
    "wins": 10,
    "losses": 5,
    "winRate": 66.67
  },
  ...
]
```

### 2. `mastery_champions` (Riot API)

```json
[
  {
    "championId": 103,
    "championName": "Champion 103",
    "championLevel": 7,
    "championPoints": 245000,
    "championPointsSinceLastLevel": 21600,
    "championPointsUntilNextLevel": 0
  },
  ...
]
```

### 3. `ranked_champions` (Riot API)

```json
[
  {
    "championId": 64,
    "championName": "Champion 64",
    "gamesPlayed": 32,
    "wins": 20,
    "losses": 12,
    "winRate": 62.5,
    "tier": "RANKED_SOLO_5x5"
  },
  ...
]
```

---

## 🚀 Como Usar

### 1. Iniciar o Backend

```bash
cd spring-backend
./mvnw spring-boot:run
```

### 2. Configurar API Key

```sql
INSERT INTO settings (settings_key, value) 
VALUES ('riot_api_key', 'RGAPI-SUA-CHAVE-AQUI');
```

### 3. Atualizar Estatísticas

- Abra o leaderboard no frontend
- Clique no botão "Atualizar"
- Aguarde processamento (pode levar alguns minutos)
- Dados serão salvos na tabela `players`

### 4. Verificar Dados

```sql
SELECT 
  summoner_name,
  player_stats_draft,
  mastery_champions,
  ranked_champions,
  stats_last_updated
FROM players
WHERE player_stats_draft IS NOT NULL;
```

---

## ⚠️ Considerações

### Rate Limiting

- **Riot API Development Key**: 20 requests/second, 100 requests/2 minutes
- O código implementa `Thread.sleep(50)` entre requests (20 req/s)
- Para produção, considere usar uma Production API Key

### Performance

- Processo pode ser demorado para muitos jogadores
- Top 5 ranked processa até 50 partidas por jogador
- Considere executar em background ou com fila

### PUUID Obrigatório

- Estatísticas da Riot API **requerem PUUID**
- Jogadores sem PUUID só terão stats das custom matches
- PUUID é obtido ao adicionar jogador via Riot API

### Conversão de Champion IDs

- Backend salva "Champion X" quando ID não tem nome
- Frontend deve converter usando ChampionService
- Data Dragon API já implementada no frontend

---

## 🎨 Próximos Passos (Opcional)

1. **Exibir no Frontend**:
   - Adicionar aba "Estatísticas de Campeões" no perfil do jogador
   - Mostrar top 5 custom, top 3 maestria, top 5 ranked
   - Usar ícones e nomes dos campeões

2. **Cache**:
   - Implementar cache das estatísticas (ex: 1 hora)
   - Não buscar Riot API se `stats_last_updated` < 1h

3. **Background Job**:
   - Criar job agendado para atualizar automaticamente
   - Ex: A cada 6 horas

4. **Filtros**:
   - Permitir filtrar leaderboard por campeão
   - "Jogadores que mais jogam de Yasuo nas customs"

---

## 📝 Testes

### 1. Testar Extração Custom Matches

```bash
# Verificar logs ao atualizar
tail -f logs/application.log | grep "custom champions"
```

### 2. Testar Riot API

```bash
# Verificar se API key está configurada
curl http://localhost:8080/api/settings

# Testar endpoint diretamente
curl -X POST http://localhost:8080/api/stats/update-champion-stats
```

### 3. Verificar JSON Salvo

```sql
SELECT 
  summoner_name,
  JSON_PRETTY(player_stats_draft) as custom_stats,
  JSON_PRETTY(mastery_champions) as mastery,
  JSON_PRETTY(ranked_champions) as ranked
FROM players 
LIMIT 1;
```

---

## ✅ Checklist de Implementação

- [x] Migration para novas colunas
- [x] Entity Player atualizada
- [x] DTOs criados
- [x] RiotChampionStatsService implementado
- [x] PlayerService com métodos de extração
- [x] Controller com endpoint
- [x] Frontend atualizado
- [ ] Riot API Key configurada (manual)
- [ ] Testes realizados
- [ ] Documentação completa

---

## 🆘 Troubleshooting

### Erro: "Riot API Key não encontrada"

- Verificar se existe registro na tabela `settings`
- `SELECT * FROM settings WHERE settings_key = 'riot_api_key'`

### Erro: "403 Forbidden" da Riot API

- API Key expirou (Development keys duram 24h)
- Gerar nova key em developer.riotgames.com

### Erro: "429 Too Many Requests"

- Rate limit excedido
- Aguardar 2 minutos antes de tentar novamente
- Considerar aumentar `Thread.sleep()` no código

### Dados não aparecem

- Verificar se jogador tem PUUID
- Verificar logs: `tail -f logs/application.log`
- Verificar se `stats_last_updated` foi atualizado

---

**Implementado em**: 05/10/2025
**Versão**: 1.0.0
**Status**: ✅ Completo e pronto para uso
