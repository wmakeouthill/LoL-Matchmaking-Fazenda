# 🚨 CORREÇÕES FINAIS DO DRAFT - ORDEM + TIMER + AÇÕES

## ❌ **PROBLEMA #1: ORDEM DO DRAFT ERRADA**

### **Situação Atual (ERRADO):**

```
Fase 1 Bans:
- 0: Bot6 (Support team1) ❌ deveria ser TOP
- 1: Bot3 (Top team2) ✅
- 2: Bot5 (Bot team1) ❌ deveria ser JUNGLE  
- 3: Bot1 (Support team2) ❌ deveria ser JUNGLE
- 4: Bot4 (Top team1) ❌ deveria ser MID
- 5: Bot9 (Bot team2) ❌ deveria ser MID
```

### **Ordem CORRETA esperada:**

```
Fase 1 - Bans (6 ações):
0: Top Azul (team1[0])
1: Top Vermelho (team2[0])
2: Jungle Azul (team1[1])
3: Jungle Vermelho (team2[1])
4: Mid Azul (team1[2])
5: Mid Vermelho (team2[2])

Fase 2 - Picks (6 ações):
6: Top Azul (team1[0]) - First Pick
7: Top Vermelho (team2[0])
8: Jungle Vermelho (team2[1])
9: Jungle Azul (team1[1])
10: Mid Azul (team1[2])
11: Mid Vermelho (team2[2])

Fase 3 - Bans (4 ações):
12: ADC Azul (team1[3])
13: ADC Vermelho (team2[3])
14: Support Azul (team1[4])
15: Support Vermelho (team2[4])

Fase 4 - Picks (4 ações):
16: ADC Vermelho (team2[3])
17: Support Vermelho (team2[4])
18: ADC Azul (team1[3])
19: Support Azul (team1[4]) - Last Pick
```

### **🔧 CORREÇÃO: getPlayerForTeamAndIndex()**

**Arquivo:** `DraftFlowService.java` linha ~845-905

**O problema:** O mapeamento atual está usando índices errados!

Analisando o JSON:

- `team1[0]` = Bot4 (assignedLane: top) ✅
- `team1[1]` = FZD Ratoso (assignedLane: jungle) ✅
- `team1[2]` = Bot2 (assignedLane: mid) ✅
- `team1[3]` = Bot5 (assignedLane: bot) ✅
- `team1[4]` = Bot6 (assignedLane: support) ✅

Mas as ações mostram:

- Ação 0 (deveria ser team1[0] Top): está pegando Bot6 (team1[4] Support)

**Causa:** O switch case está usando os índices da ORDEM CORRIGIDA que você explicou, mas o código atual AINDA está mapeando errado.

---

## ❌ **PROBLEMA #2: TIMER NÃO ATUALIZA**

### **Diagnóstico:**

**Backend envia:** ✅

```java
updateData.put("timeRemaining", (int) Math.ceil(remainingMs / 1000.0));
// Linha 586 de DraftFlowService.java
```

**Frontend recebe:** ✅

```typescript
// app.ts linha 493
document.dispatchEvent(new CustomEvent('draftTimerUpdate', {
  detail: {
    matchId: this.draftData.matchId,
    timeRemaining: newTimeRemaining
  }
}));
```

**Componente escuta:** ✅

```typescript
// draft-pick-ban.ts linha 359
document.addEventListener('draftTimerUpdate', (event: any) => {
  // ...
  this.updateTimerFromBackend(event.detail);
});
```

**Mas:** `updateTimerFromBackend()` atualiza, mas **OnPush não detecta mudança!**

### **🔧 CORREÇÃO: Forçar detecção no HTML**

**Problema:** O timer está no template HTML, mas OnPush não re-renderiza.

**Solução:** Adicionar `| async` ou usar `ngZone.run()`.

---

## ❌ **PROBLEMA #3: AÇÃO NÃO SALVA**

### **Sintomas:**

- Usuário clica no campeão no modal
- Modal fecha
- Mas o campeão NÃO aparece no slot
- Draft NÃO avança para próxima ação
- currentIndex continua em 15

### **Diagnóstico:**

**Frontend envia:** (linha ~1997 draft-pick-ban.ts)

```typescript
const requestData = {
  matchId: effectiveMatchId,
  playerId: playerIdentifier,
  championId: champion.id,  // Nome: "Ahri"
  action: currentPhase.action,
  actionIndex: this.session.currentAction
};

// POST /match/draft-action
```

**Backend recebe e valida:** (linha ~256 DraftFlowService.java)

```java
@Transactional
public synchronized boolean processAction(long matchId, int actionIndex, String championId, String byPlayer) {
    // Normaliza championId
    // Valida time
    // Valida duplicata
    // Cria DraftAction
    // Salva no banco
    // Faz broadcast
}
```

**Mas:** Se retornar `false`, a ação NÃO é salva e NÃO há broadcast!

### **Possíveis causas:**

1. **Validação de time falha:**

   ```java
   if (!st.isPlayerInTeam(byPlayer, prev.team())) {
       return false; // ❌ Jogador não pertence ao time
   }
   ```

2. **actionIndex diferente de currentIndex:**

   ```java
   if (actionIndex != st.currentIndex) {
       return false; // ❌ Tentando fazer ação fora de ordem
   }
   ```

3. **Campeão já foi usado:**

   ```java
   boolean alreadyUsed = st.actions.stream()
       .filter(a -> a.championId() != null && !SKIPPED.equalsIgnoreCase(a.championId()))
       .anyMatch(a -> normalizedChampionId.equalsIgnoreCase(a.championId()));
   if (alreadyUsed) {
       return false; // ❌ Campeão duplicado
   }
   ```

---

## ✅ **SOLUÇÃO COMPLETA**

### **PASSO 1: Corrigir ordem do draft**

Você precisa verificar se o `getPlayerForTeamAndIndex()` está realmente usando os índices corretos.

**Verifique se está assim:**

```java
// FASE 1 - BANS (ações 0-5): Top → Jungle → Mid de ambos os times
case 0:
    playerIndex = 0;
    break; // team1[0] - Top Azul
case 1:
    playerIndex = 0;
    break; // team2[0] - Top Vermelho
case 2:
    playerIndex = 1;
    break; // team1[1] - Jungle Azul
case 3:
    playerIndex = 1;
    break; // team2[1] - Jungle Vermelho
case 4:
    playerIndex = 2;
    break; // team1[2] - Mid Azul
case 5:
    playerIndex = 2;
    break; // team2[2] - Mid Vermelho
```

**Se NÃO estiver assim, corrija!**

---

### **PASSO 2: Adicionar logs para debug**

**No DraftController.java:**

```java
@PostMapping("/match/draft-action")
public ResponseEntity<?> processDraftAction(@RequestBody DraftActionRequest request) {
    log.info("🎯 [DraftController] Recebido draft-action: {}", request);
    
    boolean success = draftFlowService.processAction(
        request.matchId(),
        request.actionIndex(),
        request.championId(),
        request.playerId()
    );
    
    if (success) {
        log.info("✅ [DraftController] Ação processada com sucesso");
        return ResponseEntity.ok(Map.of("success", true));
    } else {
        log.warn("❌ [DraftController] Ação rejeitada - validação falhou");
        return ResponseEntity.badRequest().body(Map.of(
            "success", false,
            "error", "Ação inválida - verifique logs do backend"
        ));
    }
}
```

**No DraftFlowService.processAction():**

```java
@Transactional
public synchronized boolean processAction(long matchId, int actionIndex, String championId, String byPlayer) {
    log.info("🔵 [processAction] Iniciando: matchId={}, actionIndex={}, championId={}, byPlayer={}", 
        matchId, actionIndex, championId, byPlayer);
    
    DraftState st = states.get(matchId);
    if (st == null) {
        log.warn("❌ [processAction] DraftState não encontrado para matchId={}", matchId);
        return false;
    }

    // ✅ CORREÇÃO #6: Normalizar championId antes de processar
    final String normalizedChampionId = normalizeChampionId(championId);
    if (normalizedChampionId == null) {
        log.warn("❌ [processAction] championId inválido após normalização: {}", championId);
        return false;
    }
    log.info("✅ [processAction] championId normalizado: {} -> {}", championId, normalizedChampionId);

    if (st.getCurrentIndex() >= st.getActions().size()) {
        log.warn("❌ [processAction] Draft já completo: currentIndex={}, totalActions={}", 
            st.getCurrentIndex(), st.getActions().size());
        return false;
    }
    
    if (actionIndex != st.currentIndex) {
        log.warn("❌ [processAction] actionIndex diferente: esperado={}, recebido={}", 
            st.currentIndex, actionIndex);
        return false;
    }
    
    DraftAction prev = st.actions.get(actionIndex);
    
    // valida se jogador pertence ao time da ação
    if (!st.isPlayerInTeam(byPlayer, prev.team())) {
        log.warn("❌ [processAction] Jogador {} não pertence ao team {} (ação {})", 
            byPlayer, prev.team(), actionIndex);
        log.warn("    Team 1 players: {}", st.getTeam1Players());
        log.warn("    Team 2 players: {}", st.getTeam2Players());
        return false;
    }
    log.info("✅ [processAction] Jogador {} validado para team {}", byPlayer, prev.team());
    
    boolean alreadyUsed = st.actions.stream()
            .filter(a -> a.championId() != null && !SKIPPED.equalsIgnoreCase(a.championId()))
            .anyMatch(a -> normalizedChampionId.equalsIgnoreCase(a.championId()));
    if (alreadyUsed) {
        log.warn("❌ [processAction] Campeão {} já foi usado", normalizedChampionId);
        return false;
    }
    log.info("✅ [processAction] Campeão {} disponível", normalizedChampionId);
    
    // ✅ CORREÇÃO: Buscar nome do campeão antes de criar DraftAction
    String championName = dataDragonService.getChampionName(normalizedChampionId);
    log.info("✅ [processAction] Campeão name: {}", championName);
    
    DraftAction updated = new DraftAction(
        prev.index(), 
        prev.type(), 
        prev.team(), 
        normalizedChampionId,
        championName,
        byPlayer
    );
    st.getActions().set(actionIndex, updated);
    st.advance();
    st.markActionStart();
    
    log.info("✅ [processAction] Ação salva: {} ({})", championName, normalizedChampionId);
    log.info("✅ [processAction] Avançando para próxima ação: currentIndex={}", st.getCurrentIndex());
    
    persist(matchId, st);
    broadcastUpdate(st, false);
    
    if (st.getCurrentIndex() >= st.getActions().size()) {
        log.info("🎉 [processAction] Draft completado!");
        broadcastDraftCompleted(st);
    }
    
    return true;
}
```

---

### **PASSO 3: Verificar logs após clicar**

Depois de adicionar os logs, quando você clicar no campeão:

**Verificar backend.log:**

```bash
tail -f backend.log | grep -E "DraftController|processAction"
```

**Esperar ver:**

```
🎯 [DraftController] Recebido draft-action: {matchId=..., championId=...}
🔵 [processAction] Iniciando: matchId=..., actionIndex=15, championId=...
✅ [processAction] championId normalizado: Ahri -> 103
✅ [processAction] Jogador validado para team 1
✅ [processAction] Campeão disponível
✅ [processAction] Ação salva: Ahri (103)
✅ [processAction] Avançando para próxima ação: currentIndex=16
✅ [DraftController] Ação processada com sucesso
```

**Se ver erro:**

- ❌ "Jogador não pertence ao team" → Problema de validação de jogador
- ❌ "actionIndex diferente" → Frontend enviando índice errado
- ❌ "Campeão já foi usado" → Duplicata

---

## 📊 **FORMATO DO JSON - AVALIAÇÃO**

### **JSON Atual:**

```json
{
  "actions": [...],
  "team1": [...],
  "team2": [...]
}
```

**Avaliação:** ✅ **ESTÁ BOM!**

- ✅ Todas as ações estão mapeadas com index/type/team/championId/championName
- ✅ Times têm todos os atributos (mmr, lane, summonerName)
- ✅ Fácil de iterar pelas ações em ordem

**Não precisa mudar!** O formato está funcional.

---

## 🎯 **RESUMO DAS AÇÕES**

1. ✅ **Verificar `getPlayerForTeamAndIndex()`** - Certifique-se que os índices estão corretos
2. ✅ **Adicionar logs no DraftController e processAction()** - Para ver onde falha
3. ✅ **Testar novamente** - Clicar no campeão e observar logs
4. ✅ **Analisar erro específico** - Corrigir baseado nos logs

---

**Data:** 2025-10-02  
**Status:** 🔴 Aguardando correção da ordem e debug de logs
