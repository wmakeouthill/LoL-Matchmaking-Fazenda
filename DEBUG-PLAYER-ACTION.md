# 🔍 DEBUG: Por que o jogador real não consegue salvar pick/ban?

## ✅ Logs Adicionados ao `processAction()`

Adicionei logs detalhados em `DraftFlowService.processAction()` para identificar o problema:

```java
log.info("🔍 [processAction] Ação atual: type={}, team={}", prev.type(), prev.team());
log.info("🔍 [processAction] Jogador esperado: {}", expectedPlayer);
log.info("🔍 [processAction] Jogador recebido: {}", byPlayer);

// Se falhar validação:
log.warn("❌ [processAction] Jogador {} NÃO pertence ao team {}", byPlayer, prev.team());
log.warn("❌ [processAction] Team1 players: {}", st.getTeam1Players());
log.warn("❌ [processAction] Team2 players: {}", st.getTeam2Players());
```

---

## 🧪 COMO TESTAR

### 1. Reiniciar backend com logs

```bash
cd "c:\lol-matchmaking - backend-refactor\spring-backend"
mvn spring-boot:run
```

### 2. Iniciar novo draft

### 3. Na SUA VEZ, tentar fazer pick/ban

### 4. Observar logs do backend

**Procurar por:**

```
🔍 [processAction] Jogador esperado: XXX
🔍 [processAction] Jogador recebido: YYY
```

---

## 🎯 POSSÍVEIS CAUSAS

### **Causa 1: Nome diferente entre LCU e backend**

**LCU envia:** `FZD Ratoso#fzd`  
**Backend espera:** `FZD Ratoso#fzd` ou `FZD Ratoso` (sem #fzd)?

**Solução:** Normalizar nomes no backend

---

### **Causa 2: playerId vs summonerName**

Frontend pode estar enviando `playerId` (número) ao invés de `summonerName` (string).

**Verificar no log do frontend:**

```
🎯 [onanySelected] Enviando ação: {
  "matchId": 123,
  "playerId": "1786097",  // ⚠️ É número ou nome?
  "championId": "Ahri",
  "action": "pick",
  "actionIndex": 13
}
```

**Se estiver enviando playerId (número):**

- Backend precisa fazer lookup: playerId → summonerName
- Ou backend precisa validar usando playerId

---

### **Causa 3: Time errado**

Se a **ordem do draft** estava errada antes, o jogador pode estar no time errado.

**Exemplo:**

- Backend acha que ação 13 é do **Team 2**
- Mas você está no **Team 1**
- Validação `isPlayerInTeam` falha

**Solução:** Ordem já foi corrigida no commit anterior

---

## 🔧 SOLUÇÕES POSSÍVEIS

### Solução A: Normalizar nomes no backend

```java
private String normalizePlayerName(String playerName) {
    if (playerName == null) return null;
    
    // Remover #tag se existir
    if (playerName.contains("#")) {
        return playerName.split("#")[0];
    }
    
    return playerName;
}
```

### Solução B: Aceitar playerId OU summonerName

```java
public boolean isPlayerInTeam(String identifier, int team) {
    Set<String> teamPlayers = team == 1 ? team1Players : team2Players;
    
    // Tentar match direto
    if (teamPlayers.contains(identifier)) {
        return true;
    }
    
    // Tentar match sem #tag
    String normalized = identifier.contains("#") ? identifier.split("#")[0] : identifier;
    return teamPlayers.stream()
        .anyMatch(p -> p.startsWith(normalized));
}
```

### Solução C: Backend aceitar playerId

```java
// No processAction, antes de validar:
String playerIdentifier = byPlayer;

// Se byPlayer é número (playerId), buscar summonerName
if (byPlayer.matches("\\d+")) {
    playerIdentifier = lookupSummonerNameByPlayerId(byPlayer);
}
```

---

## 📋 PRÓXIMOS PASSOS

1. ✅ Compilar e executar backend com logs
2. 🔍 Testar e observar logs quando você tenta fazer pick/ban
3. 📊 Identificar causa exata nos logs
4. 🔧 Aplicar solução apropriada

---

## 🎬 EXECUTAR AGORA

```bash
# Terminal 1 - Backend com logs
cd "c:\lol-matchmaking - backend-refactor\spring-backend"
mvn spring-boot:run | grep -E "processAction|DraftController"

# Terminal 2 - Frontend
cd "c:\lol-matchmaking - backend-refactor\spring-backend"
npm run electron
```

**Depois:** Criar novo draft e na sua vez, tentar fazer pick/ban e COPIAR OS LOGS!
