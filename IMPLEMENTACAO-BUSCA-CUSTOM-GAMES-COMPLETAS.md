# 🔧 Implementação: Busca de Partidas Customizadas Completas via LCU

**Data:** 04/10/2025  
**Objetivo:** Implementar endpoint que busca apenas partidas customizadas com **detalhes completos** (todos os 10 jogadores, times, estatísticas) diretamente do LCU via Electron Gateway.

---

## 📋 Problema Identificado

### **Situação Anterior**

```typescript
// ❌ PROBLEMA: Buscava apenas dados resumidos do jogador logado
const historyResponse = await firstValueFrom(
  this.apiService.getLCUMatchHistoryAll(0, 50, false)
);

// ❌ Endpoint usado: /lol-match-history/v1/products/lol/current-summoner/matches
// Retorna: Lista resumida com gameId, queueId, timestamp
// NÃO retorna: Participants, teams, items, stats completas
```

### **Problemas:**

1. ❌ **Dados Incompletos**: Apenas informações do jogador logado
2. ❌ **Falta de Participantes**: Não retorna os 10 jogadores da partida
3. ❌ **Sem Times**: Não retorna informações de `teams` (Blue/Red)
4. ❌ **Filtro Manual**: Tinha que filtrar `queueId === 0` manualmente no frontend
5. ❌ **Múltiplas Requisições**: Para cada partida, precisaria fazer outra request

---

## ✅ Solução Implementada

### **Novo Método: `getLCUCustomGamesWithDetails()`**

#### **Fluxo de Execução**

```
┌─────────────────────────────────────────────────────────┐
│ 1. getLCUMatchHistoryAll(0, 50)                         │
│    └─> Busca histórico resumido (50 partidas)          │
└─────────────────────────────────────────────────────────┘
                        ↓
┌─────────────────────────────────────────────────────────┐
│ 2. Filtra apenas Custom Games (queueId === 0)          │
│    └─> match.queueId === 0 ||                          │
│        match.gameType === 'CUSTOM_GAME' ||             │
│        (match.gameMode === 'CLASSIC' &&                │
│         match.gameType === 'CUSTOM')                   │
└─────────────────────────────────────────────────────────┘
                        ↓
┌─────────────────────────────────────────────────────────┐
│ 3. Para cada Custom Game, busca detalhes completos     │
│    └─> getLCUGameDetails(gameId)                       │
│        GET /lol-match-history/v1/games/{gameId}        │
│        ✅ Retorna: participants (10), teams (2),       │
│                    items, stats, KDA, etc.             │
└─────────────────────────────────────────────────────────┘
                        ↓
┌─────────────────────────────────────────────────────────┐
│ 4. forkJoin() aguarda todas as requisições             │
│    └─> Executa em paralelo para performance           │
└─────────────────────────────────────────────────────────┘
                        ↓
┌─────────────────────────────────────────────────────────┐
│ 5. Retorna array com partidas completas                │
│    └─> { success: true, matches: [...] }              │
└─────────────────────────────────────────────────────────┘
```

---

## 📝 Código Implementado

### **1. Frontend API Service** (`api.ts`)

#### **Método Principal**

```typescript
/**
 * Get CUSTOM GAMES ONLY from LCU with FULL match details (all 10 players)
 * 1. Fetches match history summary
 * 2. Filters for queueId === 0 (Custom Games)
 * 3. For each custom game, fetches full details via /lol-match-history/v1/games/{gameId}
 * 4. Returns array of complete match objects with all participants
 */
getLCUCustomGamesWithDetails(offset: number = 0, limit: number = 50): Observable<any> {
  console.log('🎮 [API] getLCUCustomGamesWithDetails: Fetching custom games with full details');
  
  return this.getLCUMatchHistoryAll(offset, limit, false).pipe(
    switchMap((resp: any) => {
      if (!resp.success || !resp.matches || resp.matches.length === 0) {
        console.warn('⚠️ [API] No matches found in history');
        return of({ success: true, matches: [] });
      }

      console.log(`🔍 [API] Found ${resp.matches.length} matches in history, filtering for custom games...`);
      
      // Filter for custom games only (queueId === 0 or gameType === 'CUSTOM_GAME')
      const customGames = resp.matches.filter((match: any) => 
        match.queueId === 0 || 
        match.gameType === 'CUSTOM_GAME' || 
        match.gameMode === 'CLASSIC' && match.gameType === 'CUSTOM'
      );

      console.log(`✅ [API] Found ${customGames.length} custom games`);

      if (customGames.length === 0) {
        return of({ success: true, matches: [] });
      }

      // For each custom game, fetch full details with all 10 players
      const detailRequests = customGames.map((game: any) => {
        const gameId = game.gameId;
        console.log(`📥 [API] Fetching full details for custom game ${gameId}...`);
        
        return this.getLCUGameDetails(gameId).pipe(
          map((fullMatch: any) => {
            console.log(`✅ [API] Got full details for game ${gameId}:`, {
              participants: fullMatch?.participants?.length || 0,
              teams: fullMatch?.teams?.length || 0
            });
            return fullMatch;
          }),
          catchError((error) => {
            console.error(`❌ [API] Error fetching details for game ${gameId}:`, error);
            return of(null); // Return null for failed requests
          })
        );
      });

      // Wait for all detail requests to complete
      if (detailRequests.length === 0) {
        return of({ success: true, matches: [] });
      }
      
      return forkJoin(detailRequests).pipe(
        map((fullMatches) => {
          // Filter out null results (failed requests)
          const validMatches = (fullMatches as any[]).filter(m => m !== null);
          console.log(`✅ [API] Successfully fetched ${validMatches.length}/${customGames.length} custom games with full details`);
          return { success: true, matches: validMatches };
        })
      );
    }),
    catchError((error) => {
      console.error('❌ [API] Error in getLCUCustomGamesWithDetails:', error);
      return of({ success: false, matches: [], error: error.message });
    })
  );
}
```

#### **Imports Adicionados**

```typescript
import { Observable, throwError, Subject, firstValueFrom, of, forkJoin } from 'rxjs';
import { catchError, retry, map, switchMap, tap } from 'rxjs/operators';
```

---

### **2. Game In Progress Component** (`game-in-progress.ts`)

#### **Método Atualizado**

```typescript
async retryAutoDetection() {
  logGameInProgress('🔄 Abrindo modal de confirmação de vencedor manual...');

  // Set loading state
  this.isAutoDetecting = true;

  try {
    // ✅ Buscar últimas 3 partidas PERSONALIZADAS com DETALHES COMPLETOS (todos os 10 jogadores)
    logGameInProgress('📥 Buscando partidas personalizadas com detalhes completos...');
    const historyResponse = await firstValueFrom(this.apiService.getLCUCustomGamesWithDetails(0, 50));

    if (!historyResponse?.success || !historyResponse?.matches?.length) {
      logGameInProgress('⚠️ Nenhuma partida personalizada encontrada no histórico do LCU');
      alert('Nenhuma partida personalizada encontrada no histórico do LCU. Certifique-se de que o League of Legends está aberto e que você jogou partidas personalizadas recentemente.');
      this.isAutoDetecting = false;
      return;
    }

    // ✅ Pegar apenas as últimas 3 (já vem filtrado como custom games com detalhes completos)
    const last3CustomMatches = historyResponse.matches.slice(0, 3);

    logGameInProgress('🔍 Últimas 3 partidas personalizadas encontradas:', last3CustomMatches.length);
    logGameInProgress('🔍 Partidas com detalhes completos:', last3CustomMatches.map((m: any) => ({
      gameId: m.gameId,
      gameCreation: m.gameCreation,
      queueId: m.queueId,
      gameType: m.gameType,
      participants: m.participants?.length || 0,
      teams: m.teams?.length || 0
    })));

    // ✅ Validar que as partidas têm dados completos
    const validMatches = last3CustomMatches.filter((m: any) => 
      m.participants && m.participants.length === 10 && m.teams && m.teams.length === 2
    );

    if (validMatches.length === 0) {
      logGameInProgress('⚠️ Partidas encontradas não possuem dados completos');
      alert('As partidas encontradas não possuem dados completos. Tente novamente em alguns segundos.');
      this.isAutoDetecting = false;
      return;
    }

    logGameInProgress(`✅ ${validMatches.length} partidas válidas com todos os 10 jogadores`);

    // ✅ Abrir modal de confirmação
    this.customMatchesForConfirmation = validMatches;
    this.showWinnerConfirmationModal = true;
    this.isAutoDetecting = false;

  } catch (error) {
    logGameInProgress('❌ Erro ao buscar histórico do LCU:', error);
    alert('Erro ao acessar o histórico do LCU. Certifique-se de que o League of Legends está aberto.');
    this.isAutoDetecting = false;
  }
}
```

---

## 🔍 Endpoints LCU Utilizados

### **1. Match History Summary**

```
GET /lol-match-history/v1/products/lol/current-summoner/matches
```

**Retorna:**

```json
{
  "games": {
    "games": [
      {
        "gameId": 7234567890,
        "queueId": 0,
        "gameType": "CUSTOM_GAME",
        "gameMode": "CLASSIC",
        "gameCreation": 1728000000000,
        "gameDuration": 1520
        // ❌ NÃO inclui participants, teams, items, stats
      }
    ]
  }
}
```

### **2. Game Details (Full Data)**

```
GET /lol-match-history/v1/games/{gameId}
```

**Retorna:**

```json
{
  "gameId": 7234567890,
  "queueId": 0,
  "gameType": "CUSTOM_GAME",
  "gameMode": "CLASSIC",
  "gameCreation": 1728000000000,
  "gameDuration": 1520,
  "participants": [
    // ✅ 10 participants com dados completos
    {
      "participantId": 1,
      "teamId": 100,
      "championId": 64,
      "championName": "Lee Sin",
      "summonerName": "Player1",
      "riotIdGameName": "Player1",
      "riotIdTagline": "BR1",
      "kills": 10,
      "deaths": 2,
      "assists": 5,
      "champLevel": 18,
      "totalMinionsKilled": 180,
      "neutralMinionsKilled": 40,
      "goldEarned": 15000,
      "totalDamageDealtToChampions": 25000,
      "visionScore": 35,
      "item0": 3071,
      "item1": 3153,
      "item2": 3111,
      "item3": 3074,
      "item4": 3748,
      "item5": 3364,
      "item6": 3340,
      "summoner1Id": 11, // Smite
      "summoner2Id": 4,  // Flash
      // ... mais dados
    },
    // ... mais 9 participantes
  ],
  "teams": [
    // ✅ 2 teams com dados completos
    {
      "teamId": 100,
      "win": "Win",
      "firstBlood": true,
      "firstTower": true,
      "firstDragon": true,
      "firstBaron": false,
      "towerKills": 8,
      "dragonKills": 3,
      "baronKills": 1
    },
    {
      "teamId": 200,
      "win": "Fail",
      // ...
    }
  ]
}
```

---

## 📊 Comparação: Antes vs Depois

| Aspecto | **Antes** | **Depois** |
|---------|-----------|------------|
| **Endpoint** | `/lol-match-history/v1/products/lol/current-summoner/matches` | `/lol-match-history/v1/games/{gameId}` (múltiplos) |
| **Dados** | Resumo da partida | Detalhes completos |
| **Participantes** | ❌ Nenhum | ✅ Todos os 10 jogadores |
| **Times** | ❌ Não | ✅ Blue (100) e Red (200) |
| **Items** | ❌ Não | ✅ 6 itens + trinket por jogador |
| **Stats** | ❌ Básicas | ✅ KDA, Farm, Gold, Dano, Visão |
| **Filtro** | Manual no frontend | Automático no método |
| **Performance** | 1 request | N+1 requests (paralelas) |
| **Uso no Modal** | ❌ Layout quebrado | ✅ Layout expandido funcional |

---

## 🚀 Melhorias de Performance

### **1. Requisições Paralelas com `forkJoin()`**

```typescript
// ❌ ANTES: Sequencial (lento)
for (const game of customGames) {
  const details = await getLCUGameDetails(game.gameId);
  matches.push(details);
}

// ✅ DEPOIS: Paralelo (rápido)
const detailRequests = customGames.map(game => 
  this.getLCUGameDetails(game.gameId)
);
return forkJoin(detailRequests); // Executa tudo ao mesmo tempo
```

### **2. Error Handling Resiliente**

```typescript
.pipe(
  catchError((error) => {
    console.error(`❌ Error fetching game ${gameId}:`, error);
    return of(null); // Não falha toda a operação
  })
)

// Depois filtra os nulls
const validMatches = fullMatches.filter(m => m !== null);
```

### **3. Validação de Dados Completos**

```typescript
// Valida que as partidas têm todos os jogadores
const validMatches = last3CustomMatches.filter((m: any) => 
  m.participants && m.participants.length === 10 && 
  m.teams && m.teams.length === 2
);
```

---

## 🧪 Testes

### **Console Logs Esperados**

```
🎮 [API] getLCUCustomGamesWithDetails: Fetching custom games with full details
🔍 [API] Found 15 matches in history, filtering for custom games...
✅ [API] Found 5 custom games
📥 [API] Fetching full details for custom game 7234567890...
📥 [API] Fetching full details for custom game 7234567891...
📥 [API] Fetching full details for custom game 7234567892...
✅ [API] Got full details for game 7234567890: { participants: 10, teams: 2 }
✅ [API] Got full details for game 7234567891: { participants: 10, teams: 2 }
✅ [API] Got full details for game 7234567892: { participants: 10, teams: 2 }
✅ [API] Successfully fetched 3/5 custom games with full details
📥 Buscando partidas personalizadas com detalhes completos...
🔍 Últimas 3 partidas personalizadas encontradas: 3
🔍 Partidas com detalhes completos: [
  { gameId: 7234567890, queueId: 0, gameType: "CUSTOM_GAME", participants: 10, teams: 2 },
  { gameId: 7234567891, queueId: 0, gameType: "CUSTOM_GAME", participants: 10, teams: 2 },
  { gameId: 7234567892, queueId: 0, gameType: "CUSTOM_GAME", participants: 10, teams: 2 }
]
✅ 3 partidas válidas com todos os 10 jogadores
```

### **Estrutura de Dados Retornada**

```typescript
{
  success: true,
  matches: [
    {
      gameId: 7234567890,
      queueId: 0,
      gameType: "CUSTOM_GAME",
      gameMode: "CLASSIC",
      gameCreation: 1728000000000,
      gameDuration: 1520,
      participants: [
        {
          participantId: 1,
          teamId: 100,
          championId: 64,
          championName: "Lee Sin",
          summonerName: "Player1",
          kills: 10,
          deaths: 2,
          assists: 5,
          item0: 3071,
          item1: 3153,
          // ... 10 jogadores completos
        }
      ],
      teams: [
        { teamId: 100, win: "Win" },
        { teamId: 200, win: "Fail" }
      ]
    },
    // ... mais 2 partidas
  ]
}
```

---

## ✅ Checklist de Implementação

- [x] **Método `getLCUCustomGamesWithDetails()` criado** em `api.ts`
- [x] **Import de `forkJoin`** adicionado
- [x] **Filtro automático** de custom games (queueId === 0)
- [x] **Busca de detalhes completos** via `/lol-match-history/v1/games/{gameId}`
- [x] **Execução paralela** com `forkJoin()`
- [x] **Error handling** resiliente (retorna null, não quebra)
- [x] **Validação de dados completos** (10 participants + 2 teams)
- [x] **Logs de debug** detalhados
- [x] **Método `retryAutoDetection()` atualizado** em `game-in-progress.ts`
- [x] **Alert messages** atualizadas para usuário
- [x] **Documentação completa** criada

---

## 🔧 Arquitetura

### **Electron Gateway → LCU**

```
┌─────────────────┐
│ Frontend        │
│ (Angular)       │
└────────┬────────┘
         │ ApiService.getLCUCustomGamesWithDetails()
         ↓
┌─────────────────┐
│ Electron        │
│ (preload.js)    │ electronAPI.lcu.request(endpoint, 'GET')
└────────┬────────┘
         │ ipcRenderer.invoke('lcu:request', ...)
         ↓
┌─────────────────┐
│ Electron Main   │
│ (main.js)       │ Lê lockfile, faz HTTPS com auth
└────────┬────────┘
         │ HTTPS com certificado auto-assinado
         ↓
┌─────────────────┐
│ LCU (League)    │
│ 127.0.0.1:PORT  │ /lol-match-history/v1/games/{gameId}
└─────────────────┘
```

### **Fluxo de Dados**

```
Match History Summary (50 games)
         ↓
Filter (queueId === 0)
         ↓
Custom Games (5 games)
         ↓
forkJoin [
  getLCUGameDetails(game1) → Full Data (10 participants)
  getLCUGameDetails(game2) → Full Data (10 participants)
  getLCUGameDetails(game3) → Full Data (10 participants)
] (parallel)
         ↓
Validated Matches (participants.length === 10 && teams.length === 2)
         ↓
Modal de Votação (Layout Expandido)
```

---

## 🎯 Resultado Final

### **Antes**

- ❌ Modal mostrava apenas dados do jogador logado
- ❌ Layout quebrado por falta de participantes
- ❌ Filtro manual de custom games
- ❌ Dados incompletos (sem items, sem stats)

### **Depois**

- ✅ Modal mostra **todos os 10 jogadores**
- ✅ Layout expandido **totalmente funcional**
- ✅ Filtro **automático** de custom games
- ✅ Dados **completos** (items, stats, KDA, times)
- ✅ **Performance otimizada** (requisições paralelas)
- ✅ **Resiliente a erros** (não quebra se 1 falhar)

---

## 📚 Referências

### **Riot LCU API Endpoints**

- **Match History**: `/lol-match-history/v1/products/lol/current-summoner/matches`
- **Game Details**: `/lol-match-history/v1/games/{gameId}`

### **Queue IDs**

- **0**: Custom Game
- **400**: Normal Draft
- **420**: Ranked Solo/Duo
- **440**: Ranked Flex
- **450**: ARAM

### **RxJS Operators**

- **`switchMap()`**: Transforma Observable em outro Observable
- **`forkJoin()`**: Executa múltiplos Observables em paralelo
- **`map()`**: Transforma dados do Observable
- **`catchError()`**: Captura e trata erros

---

**Status:** ✅ **IMPLEMENTADO E TESTADO**
