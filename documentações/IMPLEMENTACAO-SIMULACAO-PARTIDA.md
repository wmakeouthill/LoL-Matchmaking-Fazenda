# 🎮 Implementação: Simulação de Partida do LCU

## 📋 Resumo da Implementação

Sistema completo para simular partidas personalizadas do League of Legends usando dados reais do LCU, criando uma partida no estado `in_progress` para testes de finalização.

---

## 🔄 Fluxo Completo Implementado

### 1️⃣ **Frontend: Botão Simular Partida**

**Arquivo**: `frontend/src/app/app-simple.html` (linha 874)

**Localização**: Tela de Configurações → Ferramentas de Desenvolvimento (Special Users)

**Método**: `app.ts → simulateLastMatch()`

#### Funcionamento

```typescript
async simulateLastMatch() {
  // 1. Buscar histórico do LCU (últimas 20 partidas)
  const response = await getLCUMatchHistoryAll(0, 20, false);
  
  // 2. Filtrar apenas partidas PERSONALIZADAS
  const customMatches = response.matches.filter(m => m.gameType === 'CUSTOM_GAME');
  
  // 3. Pegar a primeira (última jogada)
  const lastMatch = customMatches[0];
  
  // 4. Enviar para backend
  await this.apiService.simulateLastLcuMatch(lastMatch);
  
  // 5. Aguardar broadcast WebSocket game_started
  // Frontend será redirecionado automaticamente
}
```

---

### 2️⃣ **Backend: Endpoint de Simulação**

**Arquivo**: `DebugController.java`

**Endpoint**: `POST /api/debug/simulate-last-match`

#### Melhorias Implementadas

✅ **Extração Correta de Participantes**

- Separa times por `teamId` (100 = Blue, 200 = Red)
- Valida que a partida tem exatamente 10 jogadores

✅ **Criação de `pick_ban_data` Estruturado**

```java
private Map<String, Object> createPickBanDataFromLcu(blueTeam, redTeam) {
    // Criar estrutura teams.blue e teams.red
    Map<String, Object> teams = new HashMap<>();
    
    // Blue Team
    blueTeamData.put("players", bluePlayers);
    blueTeamData.put("allPicks", [championIds]);
    blueTeamData.put("allBans", []);
    blueTeamData.put("teamNumber", 1);
    
    // Red Team similar...
    
    teams.put("blue", blueTeamData);
    teams.put("red", redTeamData);
    
    pickBanData.put("teams", teams);
    pickBanData.put("currentPhase", "completed");
}
```

✅ **Criação de Players Compatíveis**

```java
private Map<String, Object> createPlayerFromLcuParticipant() {
    player.put("summonerName", summonerName);
    player.put("championId", championId);
    player.put("championName", championName);
    player.put("assignedLane", determineLaneByIndex(teamIndex % 5));
    player.put("teamIndex", teamIndex);
    
    // Criar actions (pick)
    List<Map<String, Object>> actions = [...];
    player.put("actions", actions);
}
```

✅ **Mapeamento de Lanes por Índice**

```java
private String determineLaneByIndex(int index) {
    return switch (index) {
        case 0 -> "top";
        case 1 -> "jungle";
        case 2 -> "mid";
        case 3 -> "bot";
        case 4 -> "support";
    };
}
```

✅ **Salvar Dados Completos**

```java
// Salvar em múltiplos formatos para compatibilidade
simulatedMatch.setTeam1Players(String.join(",", team1PlayerNames)); // CSV
simulatedMatch.setTeam1PlayersJson(mapper.writeValueAsString(blueTeam)); // JSON
simulatedMatch.setPickBanDataJson(mapper.writeValueAsString(pickBanData)); // Estrutura completa
simulatedMatch.setLcuMatchData(mapper.writeValueAsString(lcuMatchData)); // LCU original
```

---

### 3️⃣ **GameInProgressService: Iniciar Jogo**

**Arquivo**: `GameInProgressService.java`

**Método**: `startGame(Long matchId)`

#### Fluxo

1. **Buscar partida do banco**
2. **Parsear `pick_ban_data`** (fonte de verdade)
3. **Extrair times da estrutura hierárquica** (`teams.blue.players` / `teams.red.players`)
4. **Criar `GamePlayers` com campeões** (via `createGamePlayersWithChampions`)
5. **Criar `GameData`** com todos os dados
6. **Broadcast `game_started`** via WebSocket

```java
private void broadcastGameStarted(Long matchId, GameData gameData) {
    Map<String, Object> payload = Map.of(
        "type", "game_started",
        "matchId", matchId,
        "gameData", gameDataMap
    );
    
    // Enviar para TODAS as sessões WebSocket
    for (WebSocketSession ws : sessionRegistry.all()) {
        if (ws.isOpen()) {
            ws.sendMessage(new TextMessage(json));
        }
    }
}
```

---

### 4️⃣ **Frontend: Handler WebSocket**

**Arquivo**: `app.ts`

**Adicionado**: Handler para `case 'game_started':`

```typescript
case 'game_started':
  const gameData = message.gameData || message.data || message;
  
  // Atualizar estado
  this.gameData = gameData;
  this.inGamePhase = true;
  this.inDraftPhase = false;
  this.showMatchFound = false;
  this.matchFoundData = null;
  this.draftData = null;
  
  this.cdr.detectChanges();
  
  this.addNotification('success', 'Jogo Iniciado!', 'A partida está em andamento');
  break;
```

---

## 🎯 Formato dos Dados

### Estrutura LCU (Entrada)

```json
{
  "gameId": 1234567890,
  "gameType": "CUSTOM_GAME",
  "gameMode": "CLASSIC",
  "gameDuration": 1850,
  "participants": [
    {
      "summonerName": "Player1",
      "championId": 157,
      "championName": "Yasuo",
      "teamId": 100,
      "win": true,
      "participantId": 1
    },
    // ... 9 outros jogadores
  ]
}
```

### Estrutura CustomMatch (Banco de Dados)

```sql
CREATE TABLE custom_matches (
  id BIGINT PRIMARY KEY,
  title VARCHAR(255),
  status VARCHAR(50) DEFAULT 'in_progress',
  
  -- Times no formato CSV
  team1_players TEXT,
  team2_players TEXT,
  
  -- Times no formato JSON detalhado
  team1_players_json TEXT,
  team2_players_json TEXT,
  
  -- Estrutura completa de draft (teams.blue/red)
  pick_ban_data TEXT,
  
  -- JSON original do LCU
  lcu_match_data TEXT,
  
  riot_game_id VARCHAR(255),
  winner_team INT,
  actual_winner INT,
  actual_duration INT,
  average_mmr_team1 INT,
  average_mmr_team2 INT,
  
  created_at TIMESTAMP,
  updated_at TIMESTAMP
);
```

### Estrutura pick_ban_data (JSON)

```json
{
  "teams": {
    "blue": {
      "players": [
        {
          "summonerName": "Player1",
          "championId": "157",
          "championName": "Yasuo",
          "teamIndex": 0,
          "assignedLane": "top",
          "actions": [
            {
              "phase": "pick1",
              "championId": "157",
              "championName": "Yasuo",
              "index": 0,
              "type": "pick",
              "status": "completed"
            }
          ]
        }
        // ... 4 outros jogadores
      ],
      "allPicks": ["157", "84", "67", "119", "102"],
      "allBans": [],
      "name": "Blue Team",
      "teamNumber": 1,
      "averageMmr": 1500
    },
    "red": {
      // Estrutura similar
    }
  },
  "currentPhase": "completed",
  "currentIndex": 20,
  "team1": [...], // Compatibilidade
  "team2": [...]  // Compatibilidade
}
```

### Estrutura game_started (WebSocket)

```json
{
  "type": "game_started",
  "matchId": 105,
  "gameData": {
    "matchId": 105,
    "sessionId": "session_105",
    "status": "in_progress",
    "startTime": "2025-10-03T21:30:00",
    "isCustomGame": true,
    "originalMatchId": 105,
    "team1": [
      {
        "summonerName": "Player1",
        "assignedLane": "top",
        "championId": 157,
        "championName": "Yasuo",
        "teamIndex": 0,
        "isConnected": true
      }
      // ... 4 outros
    ],
    "team2": [...],
    "pickBanData": { /* estrutura completa */ }
  }
}
```

---

## 🧪 Como Testar

### Pré-requisitos

1. ✅ League of Legends aberto e logado
2. ✅ Ter jogado pelo menos 1 partida personalizada
3. ✅ Ser um "Special User" no sistema (configuração em `settings.special_users`)

### Passo a Passo

1. **Abrir aplicação e logar**
2. **Ir para Configurações** (último ícone lateral)
3. **Localizar seção "Ferramentas de Desenvolvimento"**
4. **Clicar no botão "🎮 Simular Última Partida Ranqueada"**

### Resultado Esperado

✅ **Backend busca última partida PERSONALIZADA do LCU**
✅ **Cria partida no banco com status `in_progress`**
✅ **Cria estrutura `pick_ban_data` completa**
✅ **Divide jogadores em times (Blue/Red) por `teamId`**
✅ **Mapeia lanes automaticamente (top/jungle/mid/bot/support)**
✅ **Chama `GameInProgressService.startGame()`**
✅ **Broadcast `game_started` via WebSocket**
✅ **Frontend redireciona para tela Game In Progress**

---

## 📊 Logs de Debug

### Backend (DebugController)

```log
╔════════════════════════════════════════════════════════════════╗
║  🎮 [DEBUG] SIMULANDO ÚLTIMA PARTIDA (IN PROGRESS)            ║
╚════════════════════════════════════════════════════════════════╝
✅ Times separados: Blue=5 jogadores, Red=5 jogadores
📊 MMR Médio - Team1: 1500, Team2: 1500
⏱️ Duração: 1850 segundos
📊 Match ID: 106
🎮 Riot Game ID: 1234567890
🏆 Vencedor: Team 1
👥 Blue Team: Player1, Player2, Player3, Player4, Player5
👥 Red Team: Player6, Player7, Player8, Player9, Player10
✅ [DEBUG] Game started - Usuário será direcionado para game-in-progress
```

### Backend (GameInProgressService)

```log
╔════════════════════════════════════════════════════════════════╗
║  🎮 [GameInProgress] INICIANDO JOGO                           ║
╚════════════════════════════════════════════════════════════════╝
🎯 Match ID: 106
✅ [GameInProgress] pick_ban_data parseado com sucesso
✅ [GameInProgress] Usando estrutura teams.blue/red
✅ [GameInProgress] Blue team: 5 players, Red team: 5 players
✅ [GameInProgress] 5 jogadores criados com campeões
╔════════════════════════════════════════════════════════════════╗
║  ✅ [GameInProgress] JOGO INICIADO COM SUCESSO                ║
╚════════════════════════════════════════════════════════════════╝
╔════════════════════════════════════════════════════════════════╗
║  📡 [GameInProgress] BROADCAST game_started                   ║
╚════════════════════════════════════════════════════════════════╝
✅ Evento enviado para 1 sessões WebSocket
```

### Frontend (app.ts)

```log
🎮 [App] Simulando última partida PERSONALIZADA do LCU...
🔍 [App] Encontradas 3 partidas personalizadas de 20 totais
✅ [App] Última partida personalizada encontrada: {...}
📡 [App] Enviando partida para backend criar como IN_PROGRESS...
✅ [App] Partida simulada criada - aguardando broadcast game_started
🎮 [App] Game started recebido: {...}
✅ [App] Estado atualizado para game in progress
```

---

## ✅ Correções Implementadas

### 1. **Extração de Participantes por Time**

- **Antes**: Assumia ordem fixa (0-4 = team1, 5-9 = team2)
- **Agora**: Separa por `teamId` (100 = Blue, 200 = Red)

### 2. **Criação de pick_ban_data**

- **Antes**: Não existia estrutura
- **Agora**: Cria estrutura hierárquica completa `teams.blue/red`

### 3. **Mapeamento de Lanes**

- **Antes**: Não mapeava
- **Agora**: Mapeia automaticamente por índice (0=top, 1=jungle, etc.)

### 4. **Actions dos Players**

- **Antes**: Não criava actions
- **Agora**: Cria action de pick para cada jogador

### 5. **Salvar em Múltiplos Formatos**

- **Antes**: Apenas JSON genérico
- **Agora**: CSV, JSON detalhado, pick_ban_data, LCU original

### 6. **Handler WebSocket no Frontend**

- **Antes**: Não existia handler para `game_started`
- **Agora**: Redireciona automaticamente para Game In Progress

### 7. **Compatibilidade com Sistema Existente**

- **Antes**: Formato incompatível
- **Agora**: Estrutura idêntica ao fluxo normal de draft

---

## 🔧 Arquivos Modificados

1. ✅ `backend/controller/DebugController.java`
   - Método `simulateLastMatch()` reescrito
   - Novos métodos auxiliares criados

2. ✅ `frontend/app/app.ts`
   - Handler `case 'game_started':` adicionado
   - Lógica de redirecionamento implementada

3. ✅ `backend/service/GameInProgressService.java`
   - Já estava correto, sem mudanças necessárias

---

## 🎯 Próximos Passos (Testes)

1. **Testar simulação de partida**
   - Verificar se redireciona para Game In Progress
   - Confirmar que times aparecem corretamente

2. **Testar finalização de partida**
   - Botão "Detectar Vencedor"
   - Modal de votação
   - Atualização de LP/MMR

3. **Testar cancelamento de partida**
   - Botão "Cancelar"
   - Verificar que partida fica como `cancelled`

---

## 📝 Notas Técnicas

### Por que CUSTOM_GAME?

- Partidas ranqueadas não podem ser recriadas fielmente
- Partidas personalizadas são controladas e podem ser repetidas
- Formato de dados é mais simples e previsível

### Por que Criar com status in_progress?

- Permite testar o fluxo completo de finalização
- Simula partida já em andamento
- Frontend vai direto para tela de Game In Progress

### Por que Salvar LCU Data Original?

- Permite vinculação futura com sistema de detecção
- Facilita debugging
- Pode ser usado para análises posteriores

### Compatibilidade

- ✅ Sistema de draft existente
- ✅ Fluxo de game-in-progress
- ✅ Finalização de partidas
- ✅ Cálculo de MMR/LP
- ✅ Histórico de partidas

---

## 🐛 Troubleshooting

### Erro: "Nenhuma partida encontrada"

- **Causa**: League of Legends não está aberto
- **Solução**: Abrir cliente e logar

### Erro: "Nenhuma partida personalizada encontrada"

- **Causa**: Nunca jogou partida personalizada
- **Solução**: Jogar pelo menos 1 partida personalizada

### Erro: "Dados da partida não fornecidos"

- **Causa**: Frontend enviou payload vazio
- **Solução**: Verificar logs do navegador e Electron gateway

### Erro: "Número de participantes inválido"

- **Causa**: Partida não tem 10 jogadores
- **Solução**: Selecionar outra partida (deve ser 5v5)

### Frontend não redireciona

- **Causa**: WebSocket não recebeu `game_started`
- **Solução**: Verificar logs do backend e sessões WebSocket

---

## ✅ Checklist de Implementação

- [x] Buscar histórico do LCU via Electron gateway
- [x] Filtrar partidas CUSTOM_GAME
- [x] Extrair participantes por teamId
- [x] Criar estrutura pick_ban_data
- [x] Mapear lanes automaticamente
- [x] Criar actions para cada player
- [x] Salvar em múltiplos formatos
- [x] Calcular MMR médio
- [x] Identificar vencedor
- [x] Salvar no banco
- [x] Chamar GameInProgressService.startGame()
- [x] Broadcast game_started via WebSocket
- [x] Handler no frontend para game_started
- [x] Redirecionar para Game In Progress
- [x] Adicionar import ArrayList no DebugController

---

## 📚 Documentação Relacionada

- `FLUXO-SIMULACAO-PARTIDA.md` - Documentação original
- `custom_matches.sql` - Exemplo de partida no banco
- `FINALIZACAO-PARTIDA-CUSTOM.md` - Finalização de partidas

---

**Status**: ✅ **IMPLEMENTADO E PRONTO PARA TESTES**

**Data**: 03/10/2025

**Autor**: Sistema de IA
