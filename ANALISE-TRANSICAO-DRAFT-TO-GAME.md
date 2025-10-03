# 📊 Análise: Transição Draft → Game In Progress

## 🎯 **Objetivo**

Migrar/adaptar o fluxo de confirmação final do draft para transição segura para `in_progress`, exibindo o componente `GameInProgressComponent` com os dados dos times e picks/bans.

---

## 🔍 **1. ESTADO ATUAL**

### ✅ **O que já funciona**

#### **Backend Spring (DraftFlowService)**

- ✅ `broadcastAllConfirmed()`: Quando todos confirmam, envia evento `draft_confirmed` e atualiza status para `game_ready`
- ✅ `pick_ban_data`: Coluna no banco contém JSON completo com estrutura hierárquica `teams.blue/red`
- ✅ Dados persistidos incluem: `team1`, `team2` (flat), `teams.blue/red` (hierárquico), e metadados
- ✅ `match_game_ready`: Evento WebSocket broadcast quando status vira `game_ready`

#### **Frontend (DraftConfirmationModal)**

- ✅ `confirmFinalDraft()`: Emite evento `onConfirm` quando líder clica em "Confirmar"
- ✅ `updateConfirmationState()`: Atualiza UI com mensagens de confirmação
- ✅ Estrutura de dados com `teams.blue/red` já preparada

#### **Frontend (DraftPickBan)**

- ✅ Listener para `game_ready`: Fecha modal e emite `onPickBanComplete` com status `in_progress`
- ✅ Estrutura `confirmationData` com `pickBanData` dos times

---

## ❌ **2. O QUE ESTÁ FALTANDO**

### **2.1 Backend: Endpoint de Confirmação Final**

**Problema:** Não existe endpoint REST para o frontend chamar quando o líder confirma a seleção final.

**Solução Necessária:**

```java
// DraftController.java
@PostMapping("/match/{matchId}/confirm-final-draft")
public ResponseEntity<Map<String, Object>> confirmFinalDraft(
    @PathVariable Long matchId,
    @RequestBody ConfirmFinalRequest request
) {
    // 1. Validar se é o líder
    // 2. Chamar DraftFlowService.finalizeDraft(matchId, playerId)
    // 3. Retornar sucesso
}
```

**Funcionalidades Necessárias no DraftFlowService:**

```java
@Transactional
public void finalizeDraft(Long matchId, String leaderId) {
    // 1. Validar que todas as ações foram completadas
    // 2. Marcar confirmação do líder
    // 3. Atualizar status para "game_ready"
    // 4. Broadcast "match_game_ready" via WebSocket
    // 5. Chamar GameInProgressService.startGame()
}
```

---

### **2.2 Backend: GameInProgressService.startGame()**

**Status Atual:** Parcialmente implementado, mas precisa de adaptação.

**O que precisa:**

```java
@Transactional
public void startGame(Long matchId, Map<String, Object> draftResults) {
    // 1. ✅ Buscar match do banco
    CustomMatch match = customMatchRepository.findById(matchId).orElseThrow();
    
    // 2. ✅ Parsear pick_ban_data (fonte de verdade)
    Map<String, Object> pickBanData = parsePickBanData(match.getPickBanDataJson());
    
    // 3. ✅ NOVO: Extrair teams.blue/red da estrutura hierárquica
    Map<String, Object> teams = (Map<String, Object>) pickBanData.get("teams");
    List<Map<String, Object>> blueTeam = (List) teams.get("blue");
    List<Map<String, Object>> redTeam = (List) teams.get("red");
    
    // 4. ✅ Criar GameData com dados completos (incluindo picks/bans)
    GameData gameData = new GameData(
        matchId,
        "in_progress",
        LocalDateTime.now(),
        createGamePlayers(blueTeam, pickBanData),  // ✅ Incluir championId/Name
        createGamePlayers(redTeam, pickBanData),   // ✅ Incluir championId/Name
        pickBanData
    );
    
    // 5. ✅ Atualizar status no banco
    match.setStatus("in_progress");
    customMatchRepository.save(match);
    
    // 6. ✅ NOVO: Broadcast game_started via WebSocket
    broadcastGameStarted(matchId, gameData);
    
    // 7. ✅ Adicionar ao cache de jogos ativos
    activeGames.put(matchId, gameData);
}

private List<GamePlayer> createGamePlayers(
    List<Map<String, Object>> teamData, 
    Map<String, Object> pickBanData
) {
    // ✅ CRÍTICO: Extrair championId/championName das actions
    List<Map<String, Object>> actions = (List) pickBanData.get("actions");
    
    return teamData.stream().map(player -> {
        String summonerName = (String) player.get("summonerName");
        
        // ✅ Buscar pick do jogador nas actions
        Map<String, Object> pickAction = actions.stream()
            .filter(a -> "pick".equals(a.get("type")) || "pick".equals(a.get("action")))
            .filter(a -> summonerName.equals(a.get("byPlayer")))
            .findFirst()
            .orElse(null);
        
        Integer championId = null;
        String championName = null;
        
        if (pickAction != null) {
            championId = parseChampionId(pickAction.get("championId"));
            championName = (String) pickAction.get("championName");
        }
        
        return new GamePlayer(
            summonerName,
            (String) player.get("assignedLane"),
            championId,
            championName,
            (Integer) player.get("teamIndex"),
            true  // isConnected
        );
    }).collect(Collectors.toList());
}
```

---

### **2.3 Backend: Broadcast game_started**

**Necessário:** Evento WebSocket para notificar frontend que o jogo iniciou.

```java
private void broadcastGameStarted(Long matchId, GameData gameData) {
    try {
        Map<String, Object> payload = Map.of(
            "type", "game_started",
            "matchId", matchId,
            "gameData", Map.of(
                "matchId", matchId,
                "sessionId", "session_" + matchId,
                "status", "in_progress",
                "team1", gameData.getTeam1(),
                "team2", gameData.getTeam2(),
                "pickBanData", gameData.getDraftResults(),
                "startTime", gameData.getStartedAt(),
                "isCustomGame", true
            )
        );
        
        String json = objectMapper.writeValueAsString(payload);
        sessionRegistry.all().forEach(ws -> {
            try {
                ws.sendMessage(new TextMessage(json));
            } catch (Exception ignored) {}
        });
        
        log.info("✅ [GameInProgress] Broadcast game_started para match {}", matchId);
        
    } catch (Exception e) {
        log.error("❌ Erro ao broadcast game_started", e);
    }
}
```

---

### **2.4 Frontend: Fluxo de Confirmação**

**Arquivo:** `draft-confirmation-modal.ts`

**Estado Atual:**

```typescript
confirmFinalDraft(): void {
    this.isConfirming = true;
    this.confirmationMessage = 'Confirmando sua seleção...';
    this.onConfirm.emit();  // ✅ Apenas emite evento
}
```

**Necessário:**

```typescript
async confirmFinalDraft(): Promise<void> {
    console.log('✅ [confirmFinalDraft] Iniciando confirmação...');
    
    this.isConfirming = true;
    this.confirmationMessage = 'Confirmando sua seleção...';
    
    try {
        // ✅ NOVO: Chamar endpoint do backend
        const response = await firstValueFrom(
            this.http.post(`${this.baseUrl}/match/${this.session.id}/confirm-final-draft`, {
                playerId: this.currentPlayer.summonerName,
                isLeader: true
            })
        );
        
        console.log('✅ [confirmFinalDraft] Confirmação enviada:', response);
        
        // ✅ Atualizar UI
        this.confirmationMessage = 'Aguardando início do jogo...';
        
        // ✅ Aguardar evento game_started via WebSocket
        // (listener já existe no draft-pick-ban.ts)
        
    } catch (error) {
        console.error('❌ [confirmFinalDraft] Erro:', error);
        this.confirmationMessage = 'Erro ao confirmar. Tente novamente.';
        this.isConfirming = false;
    }
}
```

---

### **2.5 Frontend: Listener game_started**

**Arquivo:** `draft-pick-ban.ts`

**Estado Atual:** Já existe listener para `game_ready`, mas precisa adaptação:

```typescript
// ✅ ATUALIZAR: Listener existente
document.addEventListener('game_ready', (event: any) => {
    if (event.detail?.matchId === this.matchId) {
        logDraft('🎯 [DraftPickBan] game_ready recebido via WebSocket:', event.detail);
        
        // ✅ Fechar modal de confirmação
        this.showConfirmationModal = false;
        
        // ✅ CRÍTICO: Emitir evento com dados completos para transição
        this.onPickBanComplete.emit({
            matchData: this.matchData,
            session: this.session,
            confirmationData: event.detail.pickBanData || this.confirmationData,
            status: 'in_progress',  // ✅ Status correto
            gameData: event.detail.gameData  // ✅ NOVO: Dados do jogo
        });
        
        this.cdr.detectChanges();
    }
});

// ✅ NOVO: Adicionar listener para game_started
document.addEventListener('game_started', (event: any) => {
    if (event.detail?.matchId === this.matchId) {
        logDraft('🎮 [DraftPickBan] game_started recebido via WebSocket:', event.detail);
        
        // ✅ Fechar modal de confirmação
        this.showConfirmationModal = false;
        
        // ✅ Emitir evento para transição ao game in progress
        this.onPickBanComplete.emit({
            matchData: this.matchData,
            session: this.session,
            confirmationData: event.detail.pickBanData,
            status: 'in_progress',
            gameData: event.detail.gameData
        });
        
        this.cdr.detectChanges();
    }
});
```

---

### **2.6 Frontend (App.ts): Transição para GameInProgress**

**Arquivo:** `app.ts`

**Necessário:** Handler para receber evento `onPickBanComplete` e inicializar `GameInProgressComponent`.

```typescript
// ✅ ATUALIZAR: Handler existente
async onDraftPickBanComplete(event: any): Promise<void> {
    console.log('🎮 [App] === DRAFT COMPLETO ===');
    console.log('🎮 [App] Event recebido:', event);
    console.log('🎮 [App] Status:', event.status);
    console.log('🎮 [App] GameData:', event.gameData);
    
    // ✅ VERIFICAR: Se é transição para in_progress
    if (event.status !== 'in_progress') {
        console.warn('⚠️ [App] Status não é in_progress, ignorando');
        return;
    }
    
    // ✅ VERIFICAR: Se temos gameData
    if (!event.gameData) {
        console.error('❌ [App] gameData não disponível no evento');
        return;
    }
    
    // ✅ PREPARAR: Dados para GameInProgressComponent
    const gameData: GameData = {
        sessionId: event.gameData.sessionId,
        gameId: event.gameData.matchId.toString(),
        team1: event.gameData.team1,  // ✅ Já com championId/Name
        team2: event.gameData.team2,  // ✅ Já com championId/Name
        startTime: new Date(event.gameData.startTime),
        pickBanData: event.gameData.pickBanData,
        isCustomGame: event.gameData.isCustomGame,
        originalMatchId: event.gameData.matchId,
        originalMatchData: event.matchData
    };
    
    console.log('✅ [App] GameData preparado:', gameData);
    
    // ✅ TRANSIÇÃO: Fechar draft e abrir game in progress
    this.inDraftPhase = false;
    this.draftData = null;
    
    this.inGamePhase = true;
    this.gameData = gameData;
    
    console.log('✅ [App] Transição para Game In Progress concluída');
    this.cdr.detectChanges();
}
```

---

## 🔧 **3. ESTRUTURA DE DADOS**

### **3.1 pick_ban_data (Banco de Dados)**

**Estrutura Atual (Correta):**

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
            "championId": "266",
            "championName": "Aatrox",
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
        "mmr": 1480,
        "puuid": "def456",
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
  "team1": [...],  // ✅ Compatibilidade (flat)
  "team2": [...],  // ✅ Compatibilidade (flat)
  "actions": [...],  // ✅ Lista sequencial de todas as ações
  "currentIndex": 20,
  "currentPhase": "completed"
}
```

### **3.2 GameData (GameInProgressComponent)**

**Estrutura Necessária:**

```typescript
interface GameData {
  sessionId: string;
  gameId: string;
  team1: GamePlayer[];  // ✅ Com championId/championName
  team2: GamePlayer[];  // ✅ Com championId/championName
  startTime: Date;
  pickBanData: any;  // ✅ Estrutura completa do draft
  isCustomGame: boolean;
  originalMatchId?: number;
  originalMatchData?: any;
  riotId?: string | null;
}

interface GamePlayer {
  summonerName: string;
  assignedLane: string;
  championId?: number;     // ✅ CRÍTICO
  championName?: string;   // ✅ CRÍTICO
  teamIndex: number;
  isConnected: boolean;
  // ✅ Campos opcionais do draft
  mmr?: number;
  puuid?: string;
}
```

---

## 📋 **4. PLANO DE IMPLEMENTAÇÃO**

### **Fase 1: Backend - Endpoint e Lógica**

#### **1.1 Criar endpoint de confirmação final**

```bash
✅ Arquivo: DraftController.java
✅ Endpoint: POST /api/match/{matchId}/confirm-final-draft
✅ Validações: matchId, playerId, isLeader
```

#### **1.2 Implementar DraftFlowService.finalizeDraft()**

```bash
✅ Validar todas as ações completadas
✅ Atualizar status para "game_ready"
✅ Broadcast "match_game_ready"
✅ Chamar GameInProgressService.startGame()
```

#### **1.3 Adaptar GameInProgressService.startGame()**

```bash
✅ Parsear pick_ban_data (teams.blue/red)
✅ Extrair championId/championName das actions
✅ Criar GamePlayer[] completos
✅ Atualizar status para "in_progress"
✅ Broadcast "game_started"
✅ Adicionar ao cache activeGames
```

---

### **Fase 2: Backend - Broadcast e WebSocket**

#### **2.1 Implementar broadcastGameStarted()**

```bash
✅ Criar payload com gameData completo
✅ Incluir team1/team2 com campeões
✅ Incluir pickBanData completo
✅ Enviar via WebSocket para todos
```

#### **2.2 Verificar SessionRegistry**

```bash
✅ Garantir que todos os jogadores estão conectados
✅ Log de envio para cada sessão
✅ Tratamento de erros de envio
```

---

### **Fase 3: Frontend - Confirmação**

#### **3.1 Atualizar DraftConfirmationModal**

```bash
✅ Implementar chamada HTTP ao endpoint
✅ Tratamento de erros
✅ Feedback visual de loading
✅ Mensagens de sucesso/erro
```

#### **3.2 Atualizar DraftPickBan listeners**

```bash
✅ Adicionar listener "game_started"
✅ Fechar modal de confirmação
✅ Emitir onPickBanComplete com gameData
✅ Logs detalhados
```

---

### **Fase 4: Frontend - Transição**

#### **4.1 Atualizar App.ts**

```bash
✅ Handler onDraftPickBanComplete
✅ Validação de status "in_progress"
✅ Construir GameData completo
✅ Fechar draft (inDraftPhase = false)
✅ Abrir game (inGamePhase = true)
✅ Passar gameData para GameInProgressComponent
```

#### **4.2 Verificar GameInProgressComponent**

```bash
✅ Método hydratePlayersFromPickBanData já existe
✅ Exibir times com campeões
✅ Exibir lanes corretamente
✅ Iniciar timer do jogo
✅ Habilitar detecção LCU
```

---

### **Fase 5: Testes e Validação**

#### **5.1 Testes Backend**

```bash
✅ Draft completo → confirmFinalDraft → game_ready
✅ pick_ban_data com campeões corretos
✅ GameInProgressService.startGame com dados completos
✅ Broadcast game_started recebido
```

#### **5.2 Testes Frontend**

```bash
✅ Confirmação do líder → chamada HTTP
✅ Recebimento de game_started via WebSocket
✅ Transição visual draft → game in progress
✅ GameInProgressComponent com times e campeões
✅ Lanes ordenadas corretamente (Top, Jg, Mid, ADC, Sup)
```

#### **5.3 Testes End-to-End**

```bash
✅ Matchmaking → Draft → Confirmação → Game In Progress
✅ Todos os jogadores veem a mesma tela
✅ Dados persistidos no banco (status "in_progress")
✅ Histórico de partidas atualizado
```

---

## 🚨 **5. PONTOS CRÍTICOS**

### **5.1 Sincronização de Estados**

- ✅ Backend deve atualizar status ANTES do broadcast
- ✅ Frontend deve aguardar confirmação do backend antes de transicionar
- ✅ WebSocket deve garantir entrega para todos os jogadores

### **5.2 Dados dos Campeões**

- ✅ championId/championName devem vir das `actions` do pick_ban_data
- ✅ Não confiar em dados cached do frontend
- ✅ Validar que todos os jogadores têm campeão atribuído

### **5.3 Estrutura Hierárquica**

- ✅ `teams.blue/red` é a fonte de verdade
- ✅ `team1/team2` (flat) mantido para compatibilidade
- ✅ `actions` contém histórico completo do draft

### **5.4 Tratamento de Erros**

- ✅ Timeout de confirmação (30s)
- ✅ Jogador desconectado durante confirmação
- ✅ Falha no backend ao iniciar jogo
- ✅ WebSocket desconectado

---

## 📦 **6. ARQUIVOS A SEREM MODIFICADOS**

### **Backend**

```
✅ DraftController.java                    (novo endpoint)
✅ DraftFlowService.java                   (finalizeDraft)
✅ GameInProgressService.java              (startGame adaptado)
✅ DraftService.java                       (deprecar/remover lógica antiga)
```

### **Frontend**

```
✅ draft-confirmation-modal.ts             (confirmFinalDraft com HTTP)
✅ draft-pick-ban.ts                       (listener game_started)
✅ app.ts                                  (onDraftPickBanComplete)
✅ game-in-progress.ts                     (verificar hydratePlayersFromPickBanData)
```

---

## ✅ **7. CHECKLIST DE VALIDAÇÃO**

### **Backend**

- [ ] Endpoint `/api/match/{matchId}/confirm-final-draft` criado
- [ ] `DraftFlowService.finalizeDraft()` implementado
- [ ] `GameInProgressService.startGame()` adaptado
- [ ] `broadcastGameStarted()` implementado
- [ ] Status "game_ready" → "in_progress" funcional
- [ ] pick_ban_data parseado corretamente
- [ ] championId/championName extraídos das actions
- [ ] Logs detalhados adicionados

### **Frontend**

- [ ] `confirmFinalDraft()` com chamada HTTP
- [ ] Listener `game_started` adicionado
- [ ] `onDraftPickBanComplete()` atualizado
- [ ] Transição visual draft → game funcional
- [ ] GameInProgressComponent recebe gameData completo
- [ ] Times exibidos com campeões corretos
- [ ] Lanes ordenadas corretamente
- [ ] Tratamento de erros implementado

### **Integração**

- [ ] Teste end-to-end: queue → draft → confirm → game
- [ ] Todos os jogadores veem mesma tela
- [ ] Dados persistidos no banco
- [ ] WebSocket entrega eventos corretamente
- [ ] Rollback funciona em caso de erro

---

## 🎯 **8. PRÓXIMOS PASSOS RECOMENDADOS**

1. **Implementar Endpoint Backend** (DraftController + DraftFlowService)
2. **Adaptar GameInProgressService** (parsear pick_ban_data, extrair campeões)
3. **Implementar Broadcast game_started** (WebSocket)
4. **Atualizar Frontend Confirmação** (HTTP call + listeners)
5. **Testar Transição Completa** (draft → game)
6. **Refatorar Código Legado** (remover DraftService antigo)
7. **Documentar Fluxo** (diagrama de sequência)

---

## 📝 **Notas Adicionais**

- ✅ A estrutura `pick_ban_data` já está correta e completa
- ✅ O `DraftFlowService` já gerencia o estado do draft corretamente
- ✅ O `GameInProgressComponent` já está pronto para receber os dados
- ❌ **Falta apenas conectar as pontas** (endpoint + broadcast + handlers)

---

**Autor:** GitHub Copilot  
**Data:** 2025-10-03  
**Versão:** 1.0
