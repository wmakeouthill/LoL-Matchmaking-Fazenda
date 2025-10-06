# 🎮 Fluxo de Simulação de Partida Personalizada

## 📋 Resumo

Sistema para testar o fluxo completo de finalização de partidas personalizadas usando dados reais do LCU.

---

## 🔄 Arquitetura de Comunicação LCU

### ✅ Regra Fundamental

**TODAS as requisições ao LCU DEVEM passar pelo Gateway do Electron**

```
┌─────────────────┐
│    Frontend     │
│   (Angular)     │
└────────┬────────┘
         │ electronAPI.lcu.*
         ↓
┌─────────────────┐
│ Electron Gateway│ ← WebSocket Seguro
│    (main.js)    │
└────────┬────────┘
         │ HTTPS + Auth
         ↓
┌─────────────────┐
│   LCU Client    │ ← lockfile credentials
│ (League Client) │
└─────────────────┘
```

### ❌ Proibido

- Backend NÃO se comunica diretamente com LCU
- Frontend NÃO faz requisições HTTP diretas ao LCU

---

## 🎯 Fluxo do Botão "Simular Última Partida Ranqueada"

### Localização

- **Arquivo**: `frontend/src/app/app-simple.html`
- **Linha**: 880
- **Seção**: Ferramentas de Desenvolvimento (isSpecialUser)
- **Método**: `app.ts → simulateLastMatch()`

### Passo a Passo

#### 1️⃣ **Frontend - Buscar Histórico LCU**

```typescript
// app.ts - simulateLastMatch()
const response = await firstValueFrom(
  this.apiService.getLCUMatchHistoryAll(0, 20, false)
);
```

**Fluxo interno**:

```typescript
// api.ts
getLCUMatchHistory() {
  if (isElectron() && window.electronAPI?.lcu?.getMatchHistory) {
    return window.electronAPI.lcu.getMatchHistory(); // ✅ Via Gateway
  }
  return http.get('/lcu/match-history'); // ❌ Fallback (não deve acontecer)
}
```

#### 2️⃣ **Frontend - Filtrar Partidas PERSONALIZADAS**

```typescript
const customMatches = response.matches.filter(
  (m: any) => m.gameType === 'CUSTOM_GAME'
);
```

**Tipos de partida no LCU**:

- `CUSTOM_GAME` ✅ - Partidas personalizadas (desejado)
- `MATCHED_GAME` ❌ - Partidas ranqueadas/normais
- `TUTORIAL_GAME` ❌ - Tutoriais

#### 3️⃣ **Frontend → Backend - Criar Partida Simulada**

```typescript
const simulateResponse = await firstValueFrom(
  this.apiService.simulateLastLcuMatch(lastMatch)
);
```

**Request**:

```http
POST /api/debug/simulate-last-match
Content-Type: application/json

{
  "gameId": 1234567890,
  "gameMode": "CLASSIC",
  "gameType": "CUSTOM_GAME",
  "gameDuration": 1850,
  "participants": [
    {
      "summonerName": "Player1",
      "championId": 157,
      "championName": "Yasuo",
      "teamId": 100,
      "win": true,
      ...
    },
    // ... 9 outros jogadores
  ]
}
```

#### 4️⃣ **Backend - Criar CustomMatch (IN_PROGRESS)**

```java
// DebugController.java
@PostMapping("/simulate-last-match")
public ResponseEntity<Map<String, Object>> simulateLastMatch(@RequestBody Map<String, Object> lcuMatchData) {
    CustomMatch simulatedMatch = new CustomMatch();
    simulatedMatch.setStatus("in_progress"); // ✅ IN_PROGRESS para testar finalização
    simulatedMatch.setRiotGameId(lcuMatchData.get("gameId"));
    simulatedMatch.setLcuMatchData(mapper.writeValueAsString(lcuMatchData)); // JSON completo
    
    // Extrair participantes e dividir em times
    List<Map<String, Object>> participants = lcuMatchData.get("participants");
    simulatedMatch.setTeam1PlayersJson(mapper.writeValueAsString(participants.subList(0, 5)));
    simulatedMatch.setTeam2PlayersJson(mapper.writeValueAsString(participants.subList(5, 10)));
    
    // Identificar vencedor e duração
    simulatedMatch.setWinnerTeam(extractWinnerFromLcuData(participants));
    simulatedMatch.setActualWinner(winnerTeam);
    simulatedMatch.setActualDuration((Integer) lcuMatchData.get("gameDuration"));
    
    CustomMatch saved = customMatchRepository.save(simulatedMatch);
    
    // ✅ Iniciar jogo e fazer broadcast
    gameInProgressService.startGame(saved.getId());
    
    return ResponseEntity.ok(Map.of("success", true, "matchId", saved.getId()));
}
```

#### 5️⃣ **Backend - Broadcast WebSocket**

```java
// GameInProgressService.java
public void startGame(Long matchId) {
    // Busca dados da partida e constrói GameData
    GameData gameData = buildGameDataFromMatch(matchId);
    
    // Broadcast para todos os jogadores conectados
    broadcastGameStarted(matchId, gameData);
}

private void broadcastGameStarted(Long matchId, GameData gameData) {
    Map<String, Object> message = Map.of(
        "type", "game_started",
        "matchId", matchId,
        "gameData", gameData
    );
    sessionRegistry.sendToAll(message);
}
```

#### 6️⃣ **Frontend - WebSocket Handler**

```typescript
// app.ts - handleWebSocketMessage()
case 'game_started':
  console.log('🎮 Jogo iniciado!', data);
  this.gameData = data.gameData;
  this.inGamePhase = true;
  this.inDraftPhase = false;
  this.showMatchFound = false;
  // Angular detecta mudança e renderiza <app-game-in-progress>
  break;
```

#### 7️⃣ **Tela Game In Progress**

Usuário é redirecionado automaticamente para:

- **Componente**: `<app-game-in-progress>`
- **Funcionalidades**:
  - ✅ Visualizar composição dos times
  - ✅ Botão "Detectar Vencedor" (abre modal de votação)
  - ✅ Botão "Cancelar" (exclui partida)

---

## 🗳️ Sistema de Votação (Próxima Fase)

### Modal de Votação

Quando o usuário clicar em "Detectar Vencedor":

1. **Frontend busca últimas 3 partidas personalizadas**

```typescript
// Via Electron Gateway
const matches = await electronAPI.lcu.getMatchHistory();
const last3Custom = matches.filter(m => m.gameType === 'CUSTOM_GAME').slice(0, 3);
```

2. **Modal exibe as 3 partidas para votação**

```
╔════════════════════════════════════════════════╗
║  🗳️ Qual foi a partida jogada?                ║
╠════════════════════════════════════════════════╣
║  ○ Partida 1 - 25min - Yasuo, Zed, ...        ║
║  ○ Partida 2 - 31min - Lee Sin, Ahri, ...     ║
║  ○ Partida 3 - 18min - Garen, Lux, ...        ║
╠════════════════════════════════════════════════╣
║              [Confirmar Voto]                  ║
╚════════════════════════════════════════════════╝
```

3. **Sistema de votação 10/10**

- Backend armazena votos de cada jogador
- Quando todos votarem na mesma partida → finaliza automaticamente
- Vincula `lcu_match_data` ao `custom_match`
- Atualiza `status: "completed"`

---

## 🗄️ Estrutura do Banco de Dados

### Tabela: `custom_matches`

```sql
CREATE TABLE custom_matches (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  title VARCHAR(255),
  status VARCHAR(50), -- 'in_progress', 'completed', 'cancelled'
  
  -- ✅ Dados da partida real (vinculação)
  riot_game_id VARCHAR(50),         -- gameId do LCU
  lcu_match_data TEXT,              -- JSON completo do LCU
  actual_winner INT,                -- Time vencedor real (1 ou 2)
  actual_duration INT,              -- Duração real em segundos
  
  -- Times simulados
  team1_players_json TEXT,
  team2_players_json TEXT,
  winner_team INT,
  
  -- Timestamps
  created_at TIMESTAMP,
  completed_at TIMESTAMP,
  updated_at TIMESTAMP,
  
  INDEX idx_riot_game_id (riot_game_id),
  INDEX idx_status_completed (status, completed_at)
);
```

---

## 📊 Histórico de Partidas Personalizadas

### Nova Aba no Match History

- **Componente**: `<app-match-history>`
- **Nova aba**: "Partidas Personalizadas"
- **Backend endpoint**: `GET /api/custom-matches`
- **Exibe**:
  - ID da partida
  - Vencedor (Team 1 ou Team 2)
  - Duração
  - Composição dos times
  - Link para Riot Game ID (se disponível)

---

## 🧪 Teste Completo

### Como testar o fluxo completo

1. **Jogue uma partida personalizada no LoL**
2. **Abra o aplicativo → Configurações → Ferramentas de Desenvolvimento**
3. **Clique em "🎮 Simular Última Partida Ranqueada"**
4. **Verifique**:
   - ✅ Notificação: "Entrando na Partida!"
   - ✅ Tela muda para Game In Progress
   - ✅ Times são exibidos corretamente
5. **Clique em "Detectar Vencedor"** (quando implementado)
6. **Selecione a partida correta no modal**
7. **Verifique**:
   - ✅ Partida finalizada no banco
   - ✅ `lcu_match_data` preenchido
   - ✅ `status: "completed"`
   - ✅ Aparece no histórico de partidas personalizadas

---

## 📝 Próximos Passos

### Backend

- [ ] `MatchVotingService.java` - Gerenciar votos dos jogadores
- [ ] `POST /api/match/{matchId}/vote` - Receber voto do jogador
- [ ] `GET /api/match/{matchId}/votes` - Status da votação
- [ ] `POST /api/match/{matchId}/finalize` - Finalizar partida

### Frontend

- [ ] `match-voting-modal.component.ts` - Modal de votação
- [ ] Botão "Detectar Vencedor" no `game-in-progress.component.html`
- [ ] Aba "Partidas Personalizadas" no `match-history.component.html`
- [ ] Serviço para buscar últimas 3 partidas do LCU

---

## 🔍 Debugging

### Logs Importantes

**Frontend (Console do navegador)**:

```
🎮 [App] Simulando última partida PERSONALIZADA do LCU...
🔍 [App] Encontradas 5 partidas personalizadas de 20 totais
✅ [App] Última partida personalizada encontrada: {...}
📡 [App] Enviando partida para backend criar como IN_PROGRESS...
✅ [App] Partida simulada criada - aguardando broadcast game_started
🎮 Jogo iniciado! {...}
```

**Backend (console Spring Boot)**:

```
╔════════════════════════════════════════════════════════════════╗
║  🎮 [DEBUG] SIMULANDO ÚLTIMA PARTIDA (IN PROGRESS)            ║
╚════════════════════════════════════════════════════════════════╝
✅ Times extraídos: Team1=5 jogadores, Team2=5 jogadores
🏆 Vencedor identificado: Team 1
⏱️ Duração: 1850 segundos
✅ [DEBUG] Game started - Usuário será direcionado para game-in-progress
```

---

## ⚠️ Avisos Importantes

1. **LCU deve estar aberto**: O League of Legends deve estar em execução
2. **Apenas partidas personalizadas**: Ranqueadas não são suportadas (por design)
3. **Electron obrigatório**: Não funciona no navegador (precisa do gateway)
4. **Special User**: Botão só aparece para usuários especiais (segurança)

---

**Documentação criada em**: 03/10/2025  
**Versão**: 1.0  
**Autor**: Sistema de Matchmaking LoL
