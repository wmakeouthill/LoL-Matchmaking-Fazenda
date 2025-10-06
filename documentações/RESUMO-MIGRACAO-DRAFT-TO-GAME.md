# 🚀 Resumo: Migração Draft → Game In Progress

## 📊 Status Atual

### ✅ **O que já está funcionando**

1. **Backend**: `DraftFlowService` gerencia todo o fluxo de draft com `pick_ban_data` completo
2. **Banco de Dados**: Coluna `pick_ban_data` com estrutura hierárquica `teams.blue/red` + dados completos
3. **Frontend**: `DraftConfirmationModal` exibe modal de confirmação com picks/bans organizados
4. **WebSocket**: Eventos `draft_updated`, `draft_confirmed` funcionando

### ❌ **O que está faltando**

1. **Backend**: Endpoint REST para confirmação final do draft
2. **Backend**: `DraftFlowService.finalizeDraft()` para transição `game_ready` → `in_progress`
3. **Backend**: `GameInProgressService.startGame()` adaptado para extrair campeões do `pick_ban_data`
4. **Backend**: Broadcast WebSocket `game_started` com dados completos
5. **Frontend**: Chamada HTTP ao confirmar draft no modal
6. **Frontend**: Listener para evento `game_started`
7. **Frontend**: Handler em `app.ts` para transição visual

---

## 🎯 Solução: O que precisa ser construído

### 1️⃣ **Backend: Endpoint de Confirmação** (DraftController.java)

```java
@PostMapping("/api/match/{matchId}/confirm-final-draft")
public ResponseEntity<Map<String, Object>> confirmFinalDraft(
    @PathVariable Long matchId,
    @RequestBody Map<String, Object> request
) {
    String playerId = (String) request.get("playerId");
    
    // ✅ Registrar confirmação individual do jogador
    boolean allConfirmed = draftFlowService.confirmPlayer(matchId, playerId);
    
    // ✅ Se TODOS os 10 jogadores confirmaram, finalizar draft
    if (allConfirmed) {
        draftFlowService.finalizeDraft(matchId);
    }
    
    return ResponseEntity.ok(Map.of(
        "success", true,
        "allConfirmed", allConfirmed,
        "message", allConfirmed ? "Todos confirmaram! Iniciando jogo..." : "Aguardando outros jogadores..."
    ));
}
```

### 2️⃣ **Backend: Confirmação Individual e Finalização** (DraftFlowService.java)

```java
// ✅ NOVO: Mapa para rastrear confirmações por partida
private final Map<Long, Set<String>> draftConfirmations = new ConcurrentHashMap<>();

/**
 * ✅ Registra confirmação de um jogador
 * @return true se TODOS os 10 jogadores confirmaram
 */
public boolean confirmPlayer(Long matchId, String playerId) {
    log.info("✅ [DraftFlow] Jogador {} confirmou draft da match {}", playerId, matchId);
    
    // Adicionar jogador ao set de confirmações
    draftConfirmations.computeIfAbsent(matchId, k -> ConcurrentHashMap.newKeySet()).add(playerId);
    
    Set<String> confirmed = draftConfirmations.get(matchId);
    
    // Verificar se todos os 10 jogadores confirmaram
    DraftState state = draftStates.get(matchId);
    if (state == null) {
        log.warn("⚠️ [DraftFlow] Estado não encontrado para match {}", matchId);
        return false;
    }
    
    int totalPlayers = state.getTeam1Players().size() + state.getTeam2Players().size();
    boolean allConfirmed = confirmed.size() >= totalPlayers;
    
    log.info("📊 [DraftFlow] Confirmações: {}/{} jogadores", confirmed.size(), totalPlayers);
    
    // ✅ Broadcast atualização de confirmação para todos
    broadcastConfirmationUpdate(matchId, confirmed, allConfirmed);
    
    return allConfirmed;
}

/**
 * ✅ Finaliza draft quando TODOS confirmaram
 */
@Transactional
public void finalizeDraft(Long matchId) {
    log.info("🏁 [DraftFlow] Finalizando draft para match {}", matchId);
    
    // 1. Validar que draft está completo
    DraftState state = draftStates.get(matchId);
    if (state == null || state.getCurrentIndex() < state.getActions().size()) {
        throw new RuntimeException("Draft não está completo");
    }
    
    // 2. Validar que todos confirmaram
    Set<String> confirmed = draftConfirmations.get(matchId);
    int totalPlayers = state.getTeam1Players().size() + state.getTeam2Players().size();
    if (confirmed == null || confirmed.size() < totalPlayers) {
        throw new RuntimeException("Nem todos os jogadores confirmaram");
    }
    
    // 3. Atualizar status para "game_ready"
    customMatchRepository.findById(matchId).ifPresent(cm -> {
        cm.setStatus("game_ready");
        customMatchRepository.save(cm);
    });
    
    // 4. Chamar GameInProgressService para iniciar jogo
    gameInProgressService.startGame(matchId);
    
    // 5. Limpar estados da memória
    draftStates.remove(matchId);
    draftConfirmations.remove(matchId);
    
    log.info("✅ [DraftFlow] Draft finalizado, jogo iniciando");
}

/**
 * ✅ Broadcast atualização de confirmações
 */
private void broadcastConfirmationUpdate(Long matchId, Set<String> confirmed, boolean allConfirmed) {
    try {
        Map<String, Object> payload = Map.of(
            "type", "draft_confirmation_update",
            "matchId", matchId,
            "confirmations", confirmed,
            "allConfirmed", allConfirmed,
            "message", allConfirmed 
                ? "Todos confirmaram! Iniciando jogo..." 
                : confirmed.size() + " jogadores confirmaram. Aguardando restantes..."
        );
        
        String json = mapper.writeValueAsString(payload);
        sessionRegistry.all().forEach(ws -> {
            try {
                ws.sendMessage(new TextMessage(json));
            } catch (Exception e) {
                log.warn("Erro ao enviar confirmação", e);
            }
        });
        
        log.info("📡 [DraftFlow] Broadcast confirmação: {}/{} jogadores", 
            confirmed.size(), 
            draftStates.get(matchId).getTeam1Players().size() + 
            draftStates.get(matchId).getTeam2Players().size()
        );
        
    } catch (Exception e) {
        log.error("❌ Erro ao broadcast confirmação", e);
    }
}
```

### 3️⃣ **Backend: Iniciar Jogo** (GameInProgressService.java)

```java
@Transactional
public void startGame(Long matchId) {
    log.info("🎮 [GameInProgress] Iniciando jogo para match {}", matchId);
    
    CustomMatch match = customMatchRepository.findById(matchId)
        .orElseThrow(() -> new RuntimeException("Match não encontrado"));
    
    // ✅ CRÍTICO: Parsear pick_ban_data (fonte de verdade)
    Map<String, Object> pickBanData = parsePickBanData(match.getPickBanDataJson());
    
    // ✅ Extrair teams.blue/red
    Map<String, Object> teams = (Map<String, Object>) pickBanData.get("teams");
    List<Map<String, Object>> blueTeam = (List) teams.get("blue");
    List<Map<String, Object>> redTeam = (List) teams.get("red");
    
    // ✅ Criar GamePlayers com campeões (extraídos das actions)
    List<GamePlayer> team1 = createGamePlayersWithChampions(blueTeam, pickBanData);
    List<GamePlayer> team2 = createGamePlayersWithChampions(redTeam, pickBanData);
    
    // ✅ Criar GameData
    GameData gameData = new GameData(
        matchId,
        "in_progress",
        LocalDateTime.now(),
        team1,
        team2,
        pickBanData
    );
    
    // ✅ Atualizar banco
    match.setStatus("in_progress");
    customMatchRepository.save(match);
    
    // ✅ Adicionar ao cache
    activeGames.put(matchId, gameData);
    
    // ✅ NOVO: Broadcast game_started
    broadcastGameStarted(matchId, gameData);
    
    log.info("✅ [GameInProgress] Jogo iniciado com sucesso");
}

private List<GamePlayer> createGamePlayersWithChampions(
    List<Map<String, Object>> teamData,
    Map<String, Object> pickBanData
) {
    // ✅ Extrair actions (lista de picks/bans)
    List<Map<String, Object>> actions = (List) pickBanData.get("actions");
    
    return teamData.stream().map(player -> {
        String summonerName = (String) player.get("summonerName");
        
        // ✅ Buscar pick do jogador
        Map<String, Object> pickAction = actions.stream()
            .filter(a -> "pick".equals(a.get("type")))
            .filter(a -> summonerName.equals(a.get("byPlayer")))
            .findFirst()
            .orElse(null);
        
        Integer championId = null;
        String championName = null;
        
        if (pickAction != null) {
            String champIdStr = (String) pickAction.get("championId");
            championId = champIdStr != null ? Integer.parseInt(champIdStr) : null;
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
            } catch (Exception e) {
                log.warn("Erro ao enviar game_started", e);
            }
        });
        
        log.info("✅ [GameInProgress] Broadcast game_started enviado");
        
    } catch (Exception e) {
        log.error("❌ Erro ao broadcast game_started", e);
    }
}
```

### 4️⃣ **Frontend: Confirmação Individual** (draft-confirmation-modal.ts)

```typescript
async confirmFinalDraft(): Promise<void> {
    console.log('✅ [confirmFinalDraft] Confirmando seleção individual...');
    
    this.isConfirming = true;
    this.confirmationMessage = 'Confirmando sua seleção...';
    
    try {
        const url = `${this.baseUrl}/match/${this.session.id}/confirm-final-draft`;
        const response: any = await firstValueFrom(
            this.http.post(url, {
                playerId: this.currentPlayer.summonerName
                // ✅ Removido: isLeader (não existe mais)
            })
        );
        
        console.log('✅ [confirmFinalDraft] Confirmação registrada:', response);
        
        // ✅ Verificar se TODOS confirmaram
        if (response.allConfirmed) {
            this.confirmationMessage = 'Todos confirmaram! Iniciando jogo...';
            // ✅ Modal será fechado quando receber evento game_started
        } else {
            this.confirmationMessage = 'Sua confirmação foi registrada! Aguardando outros jogadores...';
            this.isConfirming = false; // ✅ Liberar modal para mostrar status
        }
        
        // ✅ Emitir evento para atualizar UI
        this.updateConfirmationState(true, response.allConfirmed);
        
    } catch (error) {
        console.error('❌ [confirmFinalDraft] Erro:', error);
        this.confirmationMessage = 'Erro ao confirmar. Tente novamente.';
        this.isConfirming = false;
    }
}
```

### 5️⃣ **Frontend: Listeners WebSocket** (draft-pick-ban.ts)

```typescript
// ✅ Adicionar ao ngOnInit(), junto com outros listeners

// 1️⃣ Listener para atualizações de confirmação (tempo real)
document.addEventListener('draft_confirmation_update', (event: any) => {
    if (event.detail?.matchId === this.matchId) {
        logDraft('📊 [DraftPickBan] Confirmação atualizada:', event.detail);
        
        // ✅ Atualizar contador de confirmações no modal
        this.confirmationData = {
            confirmations: event.detail.confirmations,
            allConfirmed: event.detail.allConfirmed,
            message: event.detail.message
        };
        
        // ✅ Notificar modal de confirmação
        if (this.confirmationModalComponent) {
            this.confirmationModalComponent.updateConfirmationState(
                event.detail.confirmations.has(this.currentPlayer.summonerName),
                event.detail.allConfirmed
            );
        }
        
        this.cdr.detectChanges();
    }
});

// 2️⃣ Listener para início do jogo (quando TODOS confirmaram)
document.addEventListener('game_started', (event: any) => {
    if (event.detail?.matchId === this.matchId) {
        logDraft('🎮 [DraftPickBan] game_started recebido:', event.detail);
        
        // ✅ Fechar modal
        this.showConfirmationModal = false;
        
        // ✅ Emitir evento para App.ts
        this.onPickBanComplete.emit({
            matchData: this.matchData,
            session: this.session,
            status: 'in_progress',
            gameData: event.detail.gameData  // ✅ Dados completos do jogo
        });
        
        this.cdr.detectChanges();
    }
});
```

### 6️⃣ **Frontend: Transição Visual** (app.ts)

```typescript
async onDraftPickBanComplete(event: any): Promise<void> {
    console.log('🎮 [App] DRAFT COMPLETO:', event);
    
    // ✅ Verificar status
    if (event.status !== 'in_progress') {
        console.warn('⚠️ Status não é in_progress');
        return;
    }
    
    // ✅ Verificar gameData
    if (!event.gameData) {
        console.error('❌ gameData não disponível');
        return;
    }
    
    // ✅ Preparar dados para GameInProgressComponent
    const gameData = {
        sessionId: event.gameData.sessionId,
        gameId: event.gameData.matchId.toString(),
        team1: event.gameData.team1,  // ✅ Já com championId/Name
        team2: event.gameData.team2,  // ✅ Já com championId/Name
        startTime: new Date(event.gameData.startTime),
        pickBanData: event.gameData.pickBanData,
        isCustomGame: true,
        originalMatchId: event.gameData.matchId
    };
    
    // ✅ Transição
    this.inDraftPhase = false;
    this.draftData = null;
    
    this.inGamePhase = true;
    this.gameData = gameData;
    
    console.log('✅ Transição para Game In Progress concluída');
    this.cdr.detectChanges();
}
```

---

## ✅ Checklist de Implementação

### Backend

- [ ] `DraftController.confirmFinalDraft()` - Endpoint REST (retorna allConfirmed)
- [ ] `DraftFlowService.confirmPlayer()` - Registrar confirmação individual
- [ ] `DraftFlowService.finalizeDraft()` - Finalizar quando todos confirmaram
- [ ] `DraftFlowService.broadcastConfirmationUpdate()` - Notificar progresso
- [ ] `GameInProgressService.startGame()` - Parsear pick_ban_data
- [ ] `GameInProgressService.createGamePlayersWithChampions()` - Extrair campeões
- [ ] `GameInProgressService.broadcastGameStarted()` - WebSocket broadcast
- [ ] Adicionar logs detalhados em todos os métodos
- [ ] Testes unitários

### Frontend

- [ ] `draft-confirmation-modal.ts` - Chamada HTTP ao confirmar (individual)
- [ ] `draft-confirmation-modal.ts` - Exibir progresso de confirmações (X/10)
- [ ] `draft-pick-ban.ts` - Listener `draft_confirmation_update`
- [ ] `draft-pick-ban.ts` - Listener `game_started`
- [ ] `app.ts` - Handler `onDraftPickBanComplete`
- [ ] Tratamento de erros em todos os pontos
- [ ] Logs detalhados no console
- [ ] Testes E2E

### Validação

- [ ] Teste completo: queue → draft → confirm (todos) → game in progress
- [ ] Todos os 10 jogadores precisam confirmar
- [ ] Contador atualiza em tempo real (1/10, 2/10, ...)
- [ ] Todos os jogadores veem mesma tela
- [ ] Campeões aparecem corretamente
- [ ] Lanes ordenadas (Top, Jg, Mid, ADC, Sup)
- [ ] Status no banco: `draft` → `game_ready` → `in_progress`
- [ ] WebSocket entrega eventos para todos

---

## 🎯 Ordem de Implementação Recomendada

1. **Backend primeiro**: Endpoint + lógica + broadcast
2. **Teste backend**: Via Postman/curl
3. **Frontend**: Integração com backend
4. **Teste E2E**: Fluxo completo
5. **Refatoração**: Remover código legado

---

## 📝 Notas Importantes

- ✅ `pick_ban_data` já tem TODOS os dados necessários
- ✅ Estrutura `teams.blue/red` já está correta
- ✅ `GameInProgressComponent` já está pronto
- ❌ Só falta **conectar as pontas** (endpoint + broadcast + handlers)

**A migração é SEGURA** porque não há mudança estrutural, apenas integração de componentes já existentes.
