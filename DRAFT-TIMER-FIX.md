# 🔧 CORREÇÕES CRÍTICAS DO SISTEMA DE DRAFT

## ❌ **PROBLEMAS IDENTIFICADOS**

### 1. ⏰ **Timer travado em 30 segundos**

- **Sintoma:** Timer não atualiza na interface, permanece em 30s
- **Causa:** `updateTimerFromBackend()` atualiza `timeRemaining` mas não força change detection
- **Arquivo:** `frontend/src/app/components/draft/draft-pick-ban.ts`

### 2. 🎯 **Pick/Ban não salva e não avança**

- **Sintoma:** Ao selecionar campeão e confirmar, ação não é salva e draft não avança
- **Causa Potencial 1:** Backend rejeita ação (validação)
- **Causa Potencial 2:** Timer update não atualiza `currentAction` na UI
- **Arquivo:** `frontend/src/app/components/draft/draft-pick-ban.ts`

### 3. 📝 **JSON mal formatado - Falta championName**

- **Sintoma:** JSON salvo só tem `championId` (número), falta `championName` (string)
- **Causa:** `DraftAction` record não inclui campo `championName`
- **Arquivo:** `src/main/java/.../DraftFlowService.java`
- **Exemplo do JSON atual:**

```json
{
  "index": 0,
  "type": "ban",
  "team": 1,
  "championId": "45",  // ❌ Só tem o ID
  "byPlayer": "Bot6"
}
```

- **JSON esperado:**

```json
{
  "index": 0,
  "type": "ban",
  "team": 1,
  "championId": "45",
  "championName": "Veigar",  // ✅ Incluir nome
  "byPlayer": "Bot6"
}
```

---

## ✅ **CORREÇÕES NECESSÁRIAS**

### **CORREÇÃO #1: Timer Update com Change Detection** ⏰

**Arquivo:** `frontend/src/app/components/draft/draft-pick-ban.ts`

**Método:** `updateTimerFromBackend()`

**ANTES:**

```typescript
private updateTimerFromBackend(data: any): void {
  if (data.timeRemaining !== undefined) {
    this.timeRemaining = data.timeRemaining;
    console.log(`⏰ [updateTimerFromBackend] Timer atualizado: ${this.timeRemaining}s`);
    
    // ✅ CRÍTICO: Forçar detecção de mudanças SEMPRE
    this.cdr.markForCheck();
  }
}
```

**DEPOIS:**

```typescript
private updateTimerFromBackend(data: any): void {
  if (data.timeRemaining !== undefined) {
    this.timeRemaining = data.timeRemaining;
    console.log(`⏰ [updateTimerFromBackend] Timer atualizado: ${this.timeRemaining}s`);
    
    // ✅ CRÍTICO: Forçar detecção de mudanças SEMPRE com detectChanges() ao invés de markForCheck()
    this.cdr.markForCheck();
    this.cdr.detectChanges(); // ⭐ ADICIONAR ESTA LINHA
  }
}
```

---

### **CORREÇÃO #2: Adicionar championName ao DraftAction** 📝

**Arquivo:** `src/main/java/.../DraftFlowService.java`

**ANTES (linha 31):**

```java
public record DraftAction(int index, String type, int team, String championId, String byPlayer) {
}
```

**DEPOIS:**

```java
public record DraftAction(
    int index, 
    String type, 
    int team, 
    String championId, 
    String championName,  // ⭐ ADICIONAR
    String byPlayer
) {}
```

---

### **CORREÇÃO #3: Atualizar todos os construtores de DraftAction**

#### **3.1. Método buildDefaultActionSequence() - linha ~182**

**ANTES:**

```java
list.add(new DraftAction(i++, "ban", t, null, null));
list.add(new DraftAction(i++, "pick", t, null, null));
```

**DEPOIS:**

```java
list.add(new DraftAction(i++, "ban", t, null, null, null));  // ⭐ +1 null
list.add(new DraftAction(i++, "pick", t, null, null, null)); // ⭐ +1 null
```

#### **3.2. Método processAction() - linha ~279**

**ANTES:**

```java
DraftAction updated = new DraftAction(
    prev.index(), 
    prev.type(), 
    prev.team(), 
    normalizedChampionId, 
    byPlayer
);
```

**DEPOIS:**

```java
// ✅ Buscar nome do campeão
String championName = dataDragonService.getChampionName(normalizedChampionId);

DraftAction updated = new DraftAction(
    prev.index(), 
    prev.type(), 
    prev.team(), 
    normalizedChampionId,
    championName,  // ⭐ ADICIONAR
    byPlayer
);
```

#### **3.3. Método restoreDraftStates() - linha ~138**

**ANTES:**

```java
actions.add(new DraftAction(idx, type, team, championId, byPlayer));
```

**DEPOIS:**

```java
// ✅ Buscar championName do Data Dragon
String championName = null;
if (championId != null && !championId.isBlank() && !SKIPPED.equalsIgnoreCase(championId)) {
    championName = dataDragonService.getChampionName(championId);
}

actions.add(new DraftAction(idx, type, team, championId, championName, byPlayer));
```

---

### **CORREÇÃO #4: Verificar logs do backend durante pick**

**Quando o usuário clicar para confirmar um pick/ban, verificar:**

1. **Backend recebe o request?**

   ```
   grep "processAction" backend.log | tail -n 20
   ```

2. **Validação está passando?**

   ```
   grep -E "championId inválido|normalizeChampionId|não autorizado" backend.log | tail -n 20
   ```

3. **Draft está avançando (`currentIndex` aumenta)?**

   ```
   grep -E "currentIndex|advance|Broadcasting" backend.log | tail -n 20
   ```

---

## 🧪 **TESTE APÓS CORREÇÕES**

### **Teste 1: Timer Update**

1. Iniciar draft
2. Observar console do browser: `⏰ [updateTimerFromBackend]`
3. **Esperado:** Timer deve contar regressivamente de 30 até 0
4. **Se falhar:** Timer fica congelado em 30s

### **Teste 2: Pick/Ban Salva**

1. Quando for sua vez, abrir modal de campeões
2. Selecionar um campeão e confirmar
3. Verificar backend.log: `✅ [processAction]`
4. **Esperado:**
   - Campeão aparece no slot do jogador
   - Draft avança para próxima ação
   - Timer reinicia em 30s
5. **Se falhar:**
   - Campeão não aparece
   - Draft não avança
   - Timer continua travado

### **Teste 3: JSON com championName**

1. Após um pick/ban completo, verificar banco de dados:

   ```sql
   SELECT pick_ban_data FROM custom_matches WHERE id = <matchId>;
   ```

2. **Esperado:**

   ```json
   {
     "index": 0,
     "type": "ban",
     "team": 1,
     "championId": "45",
     "championName": "Veigar",  // ✅ Deve aparecer
     "byPlayer": "Bot6"
   }
   ```

---

## 📊 **ORDEM DE IMPLEMENTAÇÃO**

1. ✅ **CORREÇÃO #1** (Timer) - MAIS RÁPIDA
2. ✅ **CORREÇÃO #2** (championName no record)
3. ✅ **CORREÇÃO #3.1** (buildDefaultActionSequence)
4. ✅ **CORREÇÃO #3.2** (processAction)
5. ✅ **CORREÇÃO #3.3** (restoreDraftStates)
6. 🧪 **Recompilar e testar**
7. 📋 **CORREÇÃO #4** (análise de logs)

---

## 🎯 **PRÓXIMOS PASSOS**

Depois das correções acima, se o problema persistir:

### **Investigar WebSocket**

- Verificar se mensagens `draft_updated` chegam no frontend
- Verificar se `draftUpdate` event listener é disparado
- Verificar se `ngOnChanges` é chamado após WebSocket

### **Investigar Backend**

- Adicionar mais logs em `processAction()`
- Verificar se `broadcastUpdate()` está enviando `currentIndex` correto
- Verificar se `persist()` está salvando no banco corretamente

---

## 📝 **NOTAS IMPORTANTES**

1. **OnPush Change Detection:** Componente usa `ChangeDetectionStrategy.OnPush`, então SEMPRE chamar `cdr.detectChanges()` após atualizar propriedades

2. **WebSocket vs HTTP:** Sistema usa AMBOS:
   - WebSocket: Updates em tempo real (`draft_updated`, `draftTimerUpdate`)
   - HTTP: Sincronização manual (`syncSessionWithBackend()`)

3. **Timer Format:** Backend envia 3 formatos:
   - `timeRemaining` (segundos) - USAR ESTE
   - `remainingMs` (milissegundos)
   - `timeRemainingMs` (milissegundos)

4. **Champion ID Convention:**
   - Frontend envia: `champion.id` (nome: "Ahri")
   - Backend normaliza para: numeric key ("103")
   - Backend salva: numeric key
   - Backend broadcast: numeric key

---

**Última atualização:** 2025-10-02
**Autor:** GitHub Copilot
**Status:** 🔴 Aguardando implementação
