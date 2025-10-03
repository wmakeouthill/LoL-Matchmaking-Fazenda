# 🎯 Fluxo de Confirmação: 10 Jogadores (Sem Líder)

## 📋 **Conceito**

**Antes (Old Backend):** Sistema multi-backend, cada servidor tinha 1 líder que confirmava por seu grupo.

**Agora (Spring Centralizado):** Todos os **10 jogadores** precisam confirmar **individualmente** antes do jogo iniciar.

---

## 🔄 **Fluxo Detalhado**

### **1. Draft Completo**

```
Draft Phase: Todas as 20 ações (bans + picks) completadas
Status: "draft"
Próximo: Exibir modal de confirmação para TODOS os 10 jogadores
```

### **2. Modal Exibido (Para Todos)**

```
Cada jogador vê:
┌────────────────────────────────────────┐
│  ✅ DRAFT COMPLETO                     │
│                                        │
│  Time Azul          Time Vermelho      │
│  Player1 - Aatrox   Player6 - Ahri     │
│  Player2 - Lee Sin  Player7 - Jinx     │
│  ...                ...                │
│                                        │
│  📊 Confirmações: 0/10                 │
│                                        │
│  [ Confirmar Seleção ]                 │
└────────────────────────────────────────┘
```

### **3. Jogador 1 Confirma**

```typescript
// Frontend: draft-confirmation-modal.ts
async confirmFinalDraft() {
    // POST /api/match/123/confirm-final-draft
    // Body: { playerId: "Player1" }
    
    const response = await http.post(...);
    // response = { success: true, allConfirmed: false }
    
    // Atualizar UI
    this.confirmationMessage = "Sua confirmação registrada! (1/10)";
}
```

```java
// Backend: DraftController
@PostMapping("/api/match/{matchId}/confirm-final-draft")
public ResponseEntity<Map<String, Object>> confirmFinalDraft(...) {
    boolean allConfirmed = draftFlowService.confirmPlayer(matchId, "Player1");
    // allConfirmed = false (apenas 1/10)
    
    return ResponseEntity.ok(Map.of(
        "success", true,
        "allConfirmed", false,
        "confirmedCount", 1,
        "totalPlayers", 10
    ));
}
```

```java
// Backend: DraftFlowService
public boolean confirmPlayer(Long matchId, String playerId) {
    // Adicionar ao Set
    Set<String> confirmed = draftConfirmations
        .computeIfAbsent(matchId, k -> ConcurrentHashMap.newKeySet());
    confirmed.add("Player1");
    
    // Verificar total
    boolean allConfirmed = confirmed.size() >= 10;
    
    // Broadcast para todos
    broadcastConfirmationUpdate(matchId, confirmed, allConfirmed);
    // Envia: { type: "draft_confirmation_update", confirmations: ["Player1"], allConfirmed: false }
    
    return allConfirmed; // false
}
```

### **4. WebSocket Atualiza Todos**

```typescript
// Frontend: Todos os 10 jogadores recebem
document.addEventListener('draft_confirmation_update', (event) => {
    // event.detail = {
    //   matchId: 123,
    //   confirmations: ["Player1"],
    //   allConfirmed: false,
    //   message: "1 jogador confirmou. Aguardando restantes..."
    // }
    
    // Atualizar contador no modal
    this.confirmationData = {
        confirmed: ["Player1"],
        total: 10,
        message: "1/10 confirmaram"
    };
    
    // ✅ Destacar jogadores que confirmaram
    this.updatePlayerConfirmationStatus();
});
```

### **5. Modal Atualizado (Todos Veem)**

```
Cada jogador vê:
┌────────────────────────────────────────┐
│  ✅ DRAFT COMPLETO                     │
│                                        │
│  Time Azul          Time Vermelho      │
│  ✅ Player1 - Aatrox   Player6 - Ahri     │
│  Player2 - Lee Sin  Player7 - Jinx     │
│  ...                ...                │
│                                        │
│  📊 Confirmações: 1/10                 │
│  ⏳ Aguardando outros jogadores...     │
│                                        │
│  [ ✅ Confirmado ] (ou [Confirmar])   │
└────────────────────────────────────────┘
```

### **6. Jogadores 2-9 Confirmam (Repetir)**

```
Player2 confirma → 2/10 → broadcast update → todos veem "2/10"
Player3 confirma → 3/10 → broadcast update → todos veem "3/10"
...
Player9 confirma → 9/10 → broadcast update → todos veem "9/10"
```

### **7. Jogador 10 Confirma (TODOS CONFIRMARAM)**

```typescript
// Frontend: Player10 clica "Confirmar"
const response = await http.post(...);
// response = { success: true, allConfirmed: TRUE }

// UI muda automaticamente
this.confirmationMessage = "Todos confirmaram! Iniciando jogo...";
```

```java
// Backend: DraftController
@PostMapping("/api/match/{matchId}/confirm-final-draft")
public ResponseEntity<Map<String, Object>> confirmFinalDraft(...) {
    boolean allConfirmed = draftFlowService.confirmPlayer(matchId, "Player10");
    // allConfirmed = TRUE (10/10)
    
    if (allConfirmed) {
        // ✅ AUTOMATICAMENTE: Iniciar jogo
        draftFlowService.finalizeDraft(matchId);
    }
    
    return ResponseEntity.ok(Map.of(
        "success", true,
        "allConfirmed", true,
        "confirmedCount", 10,
        "totalPlayers", 10
    ));
}
```

```java
// Backend: DraftFlowService.confirmPlayer()
public boolean confirmPlayer(Long matchId, String playerId) {
    Set<String> confirmed = ...;
    confirmed.add("Player10");
    
    boolean allConfirmed = confirmed.size() >= 10; // TRUE
    
    // Broadcast final
    broadcastConfirmationUpdate(matchId, confirmed, allConfirmed);
    // Envia: { allConfirmed: true, message: "Todos confirmaram!" }
    
    return allConfirmed; // TRUE
}
```

```java
// Backend: DraftFlowService.finalizeDraft()
@Transactional
public void finalizeDraft(Long matchId) {
    // 1. Validar 10/10
    Set<String> confirmed = draftConfirmations.get(matchId);
    if (confirmed.size() < 10) {
        throw new RuntimeException("Nem todos confirmaram");
    }
    
    // 2. Atualizar status
    customMatchRepository.findById(matchId).ifPresent(cm -> {
        cm.setStatus("game_ready");
        customMatchRepository.save(cm);
    });
    
    // 3. Iniciar jogo
    gameInProgressService.startGame(matchId);
    
    // 4. Limpar memória
    draftStates.remove(matchId);
    draftConfirmations.remove(matchId);
}
```

### **8. GameInProgressService Inicia**

```java
// Backend: GameInProgressService.startGame()
public void startGame(Long matchId) {
    // Parsear pick_ban_data
    // Criar GameData com campeões
    // Atualizar status → "in_progress"
    // Broadcast "game_started"
}
```

### **9. WebSocket game_started (Todos Recebem)**

```typescript
// Frontend: Todos os 10 jogadores recebem
document.addEventListener('game_started', (event) => {
    // event.detail = {
    //   type: "game_started",
    //   matchId: 123,
    //   gameData: { team1: [...], team2: [...], ... }
    // }
    
    // Fechar modal
    this.showConfirmationModal = false;
    
    // Transição para Game In Progress
    this.onPickBanComplete.emit({
        status: 'in_progress',
        gameData: event.detail.gameData
    });
});
```

### **10. Tela Game In Progress**

```
Todos os 10 jogadores veem:
┌────────────────────────────────────────┐
│  🎮 PARTIDA EM ANDAMENTO               │
│                                        │
│  ⏱️ 00:15 (timer rodando)              │
│                                        │
│  Time Azul          Time Vermelho      │
│  Player1 - Aatrox   Player6 - Ahri     │
│  Player2 - Lee Sin  Player7 - Jinx     │
│  ...                ...                │
│                                        │
│  [ Time Azul Venceu ] [ Time Vermelho Venceu ] │
└────────────────────────────────────────┘
```

---

## 📊 **Estrutura de Dados**

### **Backend: draftConfirmations (ConcurrentHashMap)**

```java
Map<Long, Set<String>> draftConfirmations = new ConcurrentHashMap<>();

// Exemplo após 5 confirmações:
draftConfirmations = {
    123L: Set.of("Player1", "Player2", "Player3", "Player4", "Player5")
}

// Verificar se todos confirmaram:
Set<String> confirmed = draftConfirmations.get(matchId);
boolean allConfirmed = confirmed.size() >= 10;
```

### **WebSocket: draft_confirmation_update**

```json
{
  "type": "draft_confirmation_update",
  "matchId": 123,
  "confirmations": ["Player1", "Player2", "Player3"],
  "confirmedCount": 3,
  "totalPlayers": 10,
  "allConfirmed": false,
  "message": "3 jogadores confirmaram. Aguardando restantes..."
}
```

### **Frontend: confirmationData**

```typescript
interface ConfirmationData {
  confirmed: Set<string>;  // Jogadores que confirmaram
  total: number;           // Total (sempre 10)
  allConfirmed: boolean;   // true quando confirmed.size === 10
  message: string;         // Mensagem para exibir
}

// Exemplo durante confirmação:
confirmationData = {
  confirmed: new Set(["Player1", "Player2", "Player3"]),
  total: 10,
  allConfirmed: false,
  message: "3/10 confirmaram. Aguardando outros jogadores..."
}
```

---

## 🎨 **UI: Estados do Modal**

### **Estado 1: Aguardando Confirmação (0/10)**

```
┌────────────────────────────────────────┐
│  ✅ DRAFT COMPLETO                     │
│                                        │
│  [Picks e Bans dos Times]              │
│                                        │
│  📊 Confirmações: 0/10                 │
│  💡 Confirme sua seleção para          │
│     prosseguir para o jogo             │
│                                        │
│  [ Confirmar Seleção ]                 │
└────────────────────────────────────────┘
```

### **Estado 2: Confirmação Registrada (X/10)**

```
┌────────────────────────────────────────┐
│  ✅ DRAFT COMPLETO                     │
│                                        │
│  Time Azul          Time Vermelho      │
│  ✅ Player1         Player6            │
│  ✅ Player2         Player7            │
│  ✅ Player3         ✅ Player8            │
│  Player4         Player9            │
│  Player5         Player10           │
│                                        │
│  📊 Confirmações: 5/10                 │
│  ⏳ Aguardando outros jogadores...     │
│                                        │
│  [ ✅ Você Confirmou ]                 │
└────────────────────────────────────────┘
```

### **Estado 3: Todos Confirmaram (10/10)**

```
┌────────────────────────────────────────┐
│  ✅ DRAFT COMPLETO                     │
│                                        │
│  Time Azul          Time Vermelho      │
│  ✅ Player1         ✅ Player6            │
│  ✅ Player2         ✅ Player7            │
│  ✅ Player3         ✅ Player8            │
│  ✅ Player4         ✅ Player9            │
│  ✅ Player5         ✅ Player10           │
│                                        │
│  🎉 Confirmações: 10/10                │
│  🚀 Todos confirmaram!                 │
│  🔄 Iniciando partida...               │
│                                        │
│  [ ⏳ Carregando... ]                  │
└────────────────────────────────────────┘
```

---

## ⚠️ **Casos Especiais**

### **1. Jogador Desconecta Antes de Confirmar**

```
Problema: Player3 desconecta, apenas 9 jogadores confirmam
Solução: 
  - Timeout de 60s para confirmações
  - Após timeout, cancelar partida OU considerar "confirmação automática"
  - Broadcast: { type: "draft_timeout", message: "Tempo esgotado" }
```

### **2. Jogador Clica "Confirmar" Múltiplas Vezes**

```
Problema: Spam de confirmações
Solução:
  - Backend: Set.add() é idempotente (não duplica)
  - Frontend: Desabilitar botão após primeira confirmação
```

### **3. Dois Jogadores Confirmam Simultaneamente**

```
Problema: Race condition (ambos são o 10º)
Solução:
  - ConcurrentHashMap.newKeySet() é thread-safe
  - Apenas UMA confirmação dispara finalizeDraft()
  - Sincronizado no método confirmPlayer()
```

---

## ✅ **Validações Necessárias**

### **Backend**

```java
// 1. Validar que draft está completo
if (state.getCurrentIndex() < state.getActions().size()) {
    throw new RuntimeException("Draft não completo");
}

// 2. Validar que jogador pertence à partida
if (!state.getTeam1Players().contains(playerId) && 
    !state.getTeam2Players().contains(playerId)) {
    throw new RuntimeException("Jogador não pertence a esta partida");
}

// 3. Validar que ainda não finalizou
if (draftStates.get(matchId) == null) {
    throw new RuntimeException("Draft já foi finalizado");
}

// 4. Validar que status é "draft"
CustomMatch match = customMatchRepository.findById(matchId).orElseThrow();
if (!"draft".equals(match.getStatus())) {
    throw new RuntimeException("Status inválido: " + match.getStatus());
}
```

### **Frontend**

```typescript
// 1. Validar que modal está aberto
if (!this.showConfirmationModal) return;

// 2. Validar que sessão existe
if (!this.session || !this.session.id) {
    console.error("Sessão não disponível");
    return;
}

// 3. Validar que jogador existe
if (!this.currentPlayer || !this.currentPlayer.summonerName) {
    console.error("Jogador não identificado");
    return;
}

// 4. Validar que não confirmou ainda
if (this.hasConfirmed) {
    console.warn("Já confirmou");
    return;
}
```

---

## 🎯 **Resumo**

| Aspecto | Detalhes |
|---------|----------|
| **Total de Confirmações** | 10 (todos os jogadores) |
| **Quem Confirma** | Cada jogador individualmente |
| **Quando Inicia** | Quando 10/10 confirmaram |
| **Progresso em Tempo Real** | Sim, via WebSocket |
| **Timeout** | 60s (configurável) |
| **Cancelamento** | Se timeout ou jogador sai |
| **UI Feedback** | Contador "X/10", checkmarks nos confirmados |

**Sem líder = Sistema mais democrático e resiliente! 🎮**
