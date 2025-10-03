# 🔄 Diagrama: Fluxo Draft → Game In Progress

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                    ESTADO ATUAL: DRAFT COMPLETO                             │
└─────────────────────────────────────────────────────────────────────────────┘
                                      │
                                      ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│  FRONTEND: DraftConfirmationModal                                           │
│  ├─ TODOS OS 10 JOGADORES veem modal com picks/bans                        │
│  ├─ Botão "Confirmar Seleção" (individual)                                  │
│  ├─ Contador: "X/10 jogadores confirmaram"                                  │
│  └─ onClick → confirmFinalDraft()                                           │
└─────────────────────────────────────────────────────────────────────────────┘
                                      │
                                      ▼ HTTP POST (cada jogador)
┌─────────────────────────────────────────────────────────────────────────────┐
│  ❌ FALTANDO: POST /api/match/{matchId}/confirm-final-draft                │
│  Backend: DraftController                                                   │
│  ├─ Recebe: { playerId }  (SEM isLeader!)                                   │
│  ├─ Valida: matchId existe, playerId válido                                 │
│  ├─ Chama: boolean allConfirmed = confirmPlayer(matchId, playerId)         │
│  ├─ Se allConfirmed == true: chama finalizeDraft(matchId)                  │
│  └─ Retorna: { success, allConfirmed, message }                            │
└─────────────────────────────────────────────────────────────────────────────┘
                                      │
                                      ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│  ❌ FALTANDO: DraftFlowService.confirmPlayer()                             │
│  ├─ 1. Adicionar playerId ao Set de confirmações                            │
│  ├─ 2. Verificar se todos os 10 confirmaram                                 │
│  ├─ 3. Broadcast: draft_confirmation_update (progresso X/10)               │
│  └─ 4. Retornar: true se todos confirmaram, false caso contrário           │
└─────────────────────────────────────────────────────────────────────────────┘
                                      │
                   ┌──────────────────┴──────────────────┐
                   │                                     │
                   ▼ Se < 10                             ▼ Se == 10
          ┌────────────────────┐           ┌────────────────────────────┐
          │  Aguardar mais     │           │  DraftFlowService.         │
          │  confirmações      │           │  finalizeDraft()           │
          │  (volta pro modal) │           │  ├─ Validar 10/10          │
          └────────────────────┘           │  ├─ Status → game_ready    │
                                           │  ├─ Chamar startGame()     │
                                           │  └─ Limpar memória         │
                                           └────────────────────────────┘
                                      │
                                      ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│  ❌ FALTANDO: GameInProgressService.startGame()                            │
│  ├─ 1. Buscar match do banco                                                │
│  ├─ 2. Parsear pick_ban_data (JSON → Map)                                  │
│  ├─ 3. Extrair teams.blue/red                                               │
│  ├─ 4. Criar GamePlayer[] com championId/Name (das actions)                │
│  ├─ 5. Atualizar status: game_ready → in_progress                          │
│  ├─ 6. Salvar no banco                                                      │
│  ├─ 7. Adicionar ao cache activeGames                                       │
│  └─ 8. Chamar: broadcastGameStarted()                                      │
└─────────────────────────────────────────────────────────────────────────────┘
                                      │
                                      ▼ WebSocket
┌─────────────────────────────────────────────────────────────────────────────┐
│  ❌ FALTANDO: broadcastGameStarted()                                       │
│  Envia via WebSocket para TODOS os jogadores:                              │
│  {                                                                          │
│    "type": "game_started",                                                  │
│    "matchId": 123,                                                          │
│    "gameData": {                                                            │
│      "matchId": 123,                                                        │
│      "sessionId": "session_123",                                            │
│      "status": "in_progress",                                               │
│      "team1": [                                                             │
│        { summonerName, championId, championName, assignedLane, ... }        │
│      ],                                                                     │
│      "team2": [ ... ],                                                      │
│      "pickBanData": { teams: { blue: [...], red: [...] }, actions: [...] } │
│    }                                                                        │
│  }                                                                          │
└─────────────────────────────────────────────────────────────────────────────┘
                                      │
                                      ▼ WebSocket received
┌─────────────────────────────────────────────────────────────────────────────┐
│  ❌ FALTANDO: Frontend listeners (draft-pick-ban.ts)                       │
│  ├─ 1️⃣ Listener "draft_confirmation_update" (tempo real)                   │
│  │   ├─ Atualizar contador no modal (X/10)                                 │
│  │   ├─ Atualizar mensagem ("Aguardando jogadores...")                     │
│  │   └─ Destacar quem já confirmou                                         │
│  │                                                                          │
│  └─ 2️⃣ Listener "game_started" (quando todos confirmaram)                  │
│      ├─ Validar matchId                                                     │
│      ├─ Fechar modal (showConfirmationModal = false)                        │
│      └─ Emitir: onPickBanComplete({ status: 'in_progress', gameData })     │
└─────────────────────────────────────────────────────────────────────────────┘
                                      │
                                      ▼ Event emitted
┌─────────────────────────────────────────────────────────────────────────────┐
│  ❌ FALTANDO: app.ts - onDraftPickBanComplete()                            │
│  ├─ Validar status === 'in_progress'                                        │
│  ├─ Validar gameData presente                                               │
│  ├─ Preparar GameData para GameInProgressComponent                          │
│  ├─ Fechar draft: inDraftPhase = false                                      │
│  └─ Abrir game: inGamePhase = true, gameData = ...                          │
└─────────────────────────────────────────────────────────────────────────────┘
                                      │
                                      ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│  ✅ JÁ EXISTE: GameInProgressComponent                                      │
│  ├─ Recebe gameData via @Input                                              │
│  ├─ hydratePlayersFromPickBanData() extrai campeões                         │
│  ├─ Exibe times ordenados por lane (Top, Jg, Mid, ADC, Sup)                │
│  ├─ Inicia timer do jogo                                                    │
│  └─ Habilita detecção LCU                                                   │
└─────────────────────────────────────────────────────────────────────────────┘
                                      │
                                      ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│                    ✅ JOGO EM PROGRESSO EXIBIDO                             │
│  - Duas equipes lado a lado                                                 │
│  - Cada jogador com seu campeão e lane                                      │
│  - Timer do jogo rodando                                                    │
│  - Botões para declarar vencedor / cancelar                                 │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

## 🎯 **Dados Críticos no Fluxo**

### **pick_ban_data (Fonte de Verdade)**

Estrutura no banco de dados:

```json
{
  "teams": {
    "blue": [
      {
        "summonerName": "Player1",
        "teamIndex": 0,
        "assignedLane": "top",
        "mmr": 1500,
        "puuid": "abc123",
        "actions": [
          {
            "type": "pick",
            "championId": "266",          // ✅ ID como string
            "championName": "Aatrox",     // ✅ Nome do campeão
            "phaseIndex": 2,
            "locked": true
          }
        ]
      }
      // ... mais 4 jogadores
    ],
    "red": [
      {
        "summonerName": "Player6",
        "teamIndex": 5,
        "assignedLane": "top",
        "actions": [
          {
            "type": "pick",
            "championId": "103",
            "championName": "Ahri",
            "phaseIndex": 3,
            "locked": true
          }
        ]
      }
      // ... mais 4 jogadores
    ]
  },
  "actions": [
    // ✅ Lista sequencial de TODAS as ações (bans + picks)
    { "index": 0, "type": "ban", "team": 1, "championId": "1", ... },
    { "index": 1, "type": "ban", "team": 2, "championId": "2", ... },
    // ...
    { "index": 10, "type": "pick", "team": 1, "championId": "266", "byPlayer": "Player1", ... },
    // ...
  ],
  "team1": [ ... ],  // ✅ Compatibilidade (flat)
  "team2": [ ... ],  // ✅ Compatibilidade (flat)
  "currentIndex": 20,
  "currentPhase": "completed"
}
```

### **GameData (GameInProgressComponent)**

Estrutura esperada pelo componente:

```typescript
{
  sessionId: "session_123",
  gameId: "123",
  team1: [
    {
      summonerName: "Player1",
      assignedLane: "top",
      championId: 266,            // ✅ Integer
      championName: "Aatrox",     // ✅ String
      teamIndex: 0,
      isConnected: true,
      mmr: 1500
    }
    // ... mais 4 jogadores
  ],
  team2: [ ... ],
  startTime: Date,
  pickBanData: { ... },  // ✅ Estrutura completa do draft
  isCustomGame: true,
  originalMatchId: 123
}
```

---

## 🔑 **Pontos de Extração de Campeões**

### **Método 1: Da estrutura teams.blue/red** (Recomendado)

```java
// No GameInProgressService
List<Map<String, Object>> blueTeam = (List) teams.get("blue");

for (Map<String, Object> player : blueTeam) {
    List<Map<String, Object>> playerActions = (List) player.get("actions");
    
    Map<String, Object> pickAction = playerActions.stream()
        .filter(a -> "pick".equals(a.get("type")))
        .findFirst()
        .orElse(null);
    
    if (pickAction != null) {
        String championId = (String) pickAction.get("championId");
        String championName = (String) pickAction.get("championName");
        // ✅ Usar esses dados para criar GamePlayer
    }
}
```

### **Método 2: Da lista global de actions** (Alternativo)

```java
List<Map<String, Object>> actions = (List) pickBanData.get("actions");

for (Map<String, Object> player : teamData) {
    String summonerName = (String) player.get("summonerName");
    
    Map<String, Object> pickAction = actions.stream()
        .filter(a -> "pick".equals(a.get("type")))
        .filter(a -> summonerName.equals(a.get("byPlayer")))
        .findFirst()
        .orElse(null);
    
    // ✅ Extrair championId/championName
}
```

---

## ⚠️ **Pontos de Atenção**

### 1. **Ordem das Operações**

```
1. Validar draft completo
2. Atualizar status no banco (draft → game_ready → in_progress)
3. Parsear pick_ban_data
4. Criar GameData
5. Salvar no banco
6. Broadcast WebSocket
7. Aguardar frontend processar
```

### 2. **Sincronização**

- Backend DEVE atualizar banco ANTES de broadcast
- Frontend DEVE aguardar WebSocket (não polling)
- Todos os jogadores DEVEM receber evento simultaneamente

### 3. **Tratamento de Erros**

```
- Draft incompleto → Retornar erro 400
- Jogador desconectado → Notificar restantes
- Falha no banco → Rollback completo
- WebSocket falhou → Retry com exponential backoff
```

### 4. **Validações**

```
Backend:
- ✅ matchId existe
- ✅ playerId é líder
- ✅ Status é "draft"
- ✅ Todas as ações completadas
- ✅ pick_ban_data válido

Frontend:
- ✅ gameData presente
- ✅ team1/team2 não vazios
- ✅ Todos os jogadores têm campeão
- ✅ Status é "in_progress"
```

---

## 📊 **Fluxo de Dados Simplificado**

```
pick_ban_data (DB)
      │
      ├─ teams.blue[]
      │    ├─ player1 → actions[] → pick { championId, championName }
      │    ├─ player2 → actions[] → pick { championId, championName }
      │    └─ ...
      │
      ├─ teams.red[]
      │    └─ ...
      │
      └─ actions[] (global)
           ├─ ban, ban, ban, ...
           └─ pick, pick, pick, ...

            ↓ GameInProgressService.startGame()

GameData
      │
      ├─ team1: GamePlayer[]
      │    ├─ { summonerName, championId, championName, assignedLane }
      │    └─ ...
      │
      └─ team2: GamePlayer[]

            ↓ broadcastGameStarted()

WebSocket Event "game_started"
      │
      ├─ type: "game_started"
      ├─ matchId: 123
      └─ gameData: { team1, team2, pickBanData, ... }

            ↓ Frontend listener

GameInProgressComponent @Input gameData
      │
      └─ Renderiza UI com times e campeões
```

---

**Criado em:** 2025-10-03  
**Autor:** GitHub Copilot  
**Versão:** 1.0
