# Correção do Histórico de Partidas Customizadas

## 🎯 Problema Identificado

1. **Backend** retornava `"data"` mas frontend esperava `"matches"`
2. **MatchDTO** do Spring tem campos diferentes do backend Node.js antigo
3. Frontend estava tentando mapear `match.team1_players` (formato antigo) em vez de `match.participantsData` (formato novo)

## ✅ Correções Aplicadas

### 1. Backend - CompatibilityController.java (LINHA 51-83)

**Antes:**

```java
return ResponseEntity.ok(Map.of("success", true, "data", p.getContent(), "total", p.getTotalElements()));
```

**Depois:**

```java
Map<String, Object> pagination = Map.of(
    "page", p.getNumber(),
    "size", p.getSize(),
    "total", p.getTotalElements(),
    "totalPages", p.getTotalPages()
);
return ResponseEntity.ok(Map.of(
    "success", true, 
    "matches", p.getContent(),  // ✅ Mudou de "data" para "matches"
    "pagination", pagination
));
```

### 2. Frontend - game-in-progress.ts (LINHA 837)

**Antes:**

```typescript
this.apiService.getLCUCustomGamesWithDetails(0, 20, false) // false = todas as partidas
```

**Depois:**

```typescript
this.apiService.getLCUCustomGamesWithDetails(0, 20, true) // true = apenas custom games
```

### 3. Frontend - api.ts (LINHA 835-850)

**Antes:**

```typescript
gamesToFetch = resp.matches.filter((match: any) =>
    match.queueId === 0 || match.gameType === 'CUSTOM_GAME' || match.gameMode === 'CLASSIC' && match.gameType === 'CUSTOM'
);
```

**Depois:**

```typescript
// Debug: Log queueIds of all matches
console.log('🔍 [API] QueueIds in matches:', resp.matches.map((m: any) => ({ 
    gameId: m.gameId, queueId: m.queueId, gameType: m.gameType, gameMode: m.gameMode 
})));

gamesToFetch = resp.matches.filter((match: any) => {
    const isCustomByQueue = match.queueId === 0;
    const isCustomByType = match.gameType && (
        match.gameType.toUpperCase().includes('CUSTOM') || 
        match.gameType === 'CUSTOM_GAME'
    );
    return isCustomByQueue || isCustomByType;
});

console.log(`✅ [API] Found ${gamesToFetch.length} custom games out of ${resp.matches.length} total matches`);
```

## 📋 Estrutura de Dados

### MatchDTO (Backend Spring)

```json
{
  "matchId": "123",
  "status": "COMPLETED",
  "createdAt": "2025-10-05T10:00:00",
  "matchEndedAt": "2025-10-05T10:25:00",
  "duration": 1500,
  "gameMode": "CLASSIC",
  "winnerTeam": "team1",
  "riotMatchId": "3147970591",
  "participantsData": "[{...10 jogadores...}]"  // ✅ JSON string com todos os dados
}
```

### participants_data (Estrutura Interna)

```json
[
  {
    "summonerName": "FZD Ratoso#fzd",
    "riotIdGameName": "FZD Ratoso",
    "riotIdTagline": "fzd",
    "teamId": 100,
    "championId": 893,
    "championName": "Champion 893",
    "assignedLane": "mid",
    "primaryLane": "mid",
    "kills": 28,
    "deaths": 4,
    "assists": 12,
    "goldEarned": 18500,
    "totalMinionsKilled": 245,
    "item0": 6653,
    "item1": 3089,
    "item2": 3020,
    ...
  },
  // ... mais 9 jogadores
]
```

## 🔧 Próximo Passo: Reescrever mapApiMatchesToModel()

O método `mapApiMatchesToModel()` está muito complexo e mistura lógica antiga (backend Node.js) com nova (backend Spring).

### Novo Mapeamento Simplificado

```typescript
private mapApiMatchesToModel(apiMatches: any[]): Match[] {
  return apiMatches.map((match) => {
    // 1. Parse participantsData
    let participants: any[] = [];
    try {
      participants = typeof match.participantsData === 'string' ? 
        JSON.parse(match.participantsData) : (match.participantsData || []);
    } catch (e) {
      console.error('❌ Erro ao parsear participantsData:', e);
    }

    // 2. Separar por time
    const team1 = participants.filter(p => p.teamId === 100);
    const team2 = participants.filter(p => p.teamId === 200);

    // 3. Encontrar jogador atual
    const playerName = this.player?.summonerName?.toLowerCase() || '';
    const playerData = participants.find(p => 
      p.summonerName?.toLowerCase().includes(playerName)
    );

    // 4. Mapear para estrutura do frontend
    return {
      id: match.matchId,
      createdAt: new Date(match.createdAt),
      duration: match.duration || 1500,
      gameMode: match.gameMode || 'CUSTOM',
      winner: match.winnerTeam === 'team1' ? 1 : 2,
      
      team1: team1.map(p => ({
        name: p.summonerName,
        champion: this.championService.getChampionName(p.championId),
        championName: this.championService.getChampionName(p.championId),
        summonerName: p.summonerName,
        teamId: 100,
        kills: p.kills || 0,
        deaths: p.deaths || 0,
        assists: p.assists || 0,
        champLevel: p.champLevel || 18,
        goldEarned: p.goldEarned || 0,
        totalMinionsKilled: p.totalMinionsKilled || 0,
        visionScore: p.visionScore || 0,
        item0: p.item0 || 0,
        item1: p.item1 || 0,
        item2: p.item2 || 0,
        item3: p.item3 || 0,
        item4: p.item4 || 0,
        item5: p.item5 || 0
      })),
      
      team2: team2.map(p => ({
        name: p.summonerName,
        champion: this.championService.getChampionName(p.championId),
        championName: this.championService.getChampionName(p.championId),
        summonerName: p.summonerName,
        teamId: 200,
        kills: p.kills || 0,
        deaths: p.deaths || 0,
        assists: p.assists || 0,
        champLevel: p.champLevel || 18,
        goldEarned: p.goldEarned || 0,
        totalMinionsKilled: p.totalMinionsKilled || 0,
        visionScore: p.visionScore || 0,
        item0: p.item0 || 0,
        item1: p.item1 || 0,
        item2: p.item2 || 0,
        item3: p.item3 || 0,
        item4: p.item4 || 0,
        item5: p.item5 || 0
      })),
      
      playerStats: playerData ? {
        champion: this.championService.getChampionName(playerData.championId),
        kills: playerData.kills || 0,
        deaths: playerData.deaths || 0,
        assists: playerData.assists || 0,
        isWin: playerData.win || false,
        mmrChange: 0, // Custom games não afetam MMR
        lpChange: 0,
        championLevel: playerData.champLevel || 18,
        items: [
          playerData.item0 || 0,
          playerData.item1 || 0,
          playerData.item2 || 0,
          playerData.item3 || 0,
          playerData.item4 || 0,
          playerData.item5 || 0
        ],
        goldEarned: playerData.goldEarned || 0,
        totalMinionsKilled: playerData.totalMinionsKilled || 0,
        visionScore: playerData.visionScore || 0
      } : null
    };
  });
}
```

## 🚀 Para Testar

1. Compile o backend:

```bash
cd "c:/lol-matchmaking - backend-refactor/spring-backend"
mvn clean install -DskipTests
```

2. Compile o frontend:

```bash
cd "c:/lol-matchmaking - backend-refactor/spring-backend/frontend"
npm run build
```

3. Inicie a aplicação e verifique:
   - Modal de confirmação de vencedor mostra apenas custom games
   - Aba "Partidas Customizadas" no histórico carrega dados do banco
   - Todos os 10 jogadores aparecem com stats completos
   - K/D/A, items, gold estão corretos

## 📊 Status Atual

- ✅ Backend retorna formato correto (`matches` + `pagination`)
- ✅ Modal filtra apenas custom games
- ✅ Fuzzy matching de jogadores implementado
- ✅ Endpoint LCU retorna 10 jogadores completos
- ⏳ Frontend precisa de refatoração do `mapApiMatchesToModel()`

## 🔍 Logs para Debug

```typescript
console.log('🔍 [mapApiMatchesToModel] Match recebido:', match);
console.log('🔍 [mapApiMatchesToModel] participantsData:', participants);
console.log('🔍 [mapApiMatchesToModel] Team 1:', team1.length, 'jogadores');
console.log('🔍 [mapApiMatchesToModel] Team 2:', team2.length, 'jogadores');
```
