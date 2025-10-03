# 🔍 ANÁLISE: Estrutura Hierárquica vs Frontend Atual

**Data:** 02/10/2025  
**Status:** ⚠️ FRONTEND NÃO ESTÁ ADAPTADO

---

## 📊 SITUAÇÃO ATUAL

### ✅ Backend (IMPLEMENTADO)

O backend JÁ está enviando a estrutura hierárquica via WebSocket:

```java
// DraftFlowService.java - linha ~610
Map<String, Object> hierarchicalData = buildHierarchicalDraftData(st);
if (hierarchicalData.containsKey("teams")) {
    updateData.put("teams", hierarchicalData.get("teams"));
    updateData.put("currentPhase", hierarchicalData.get("currentPhase"));
    updateData.put("currentPlayer", hierarchicalData.get("currentPlayer"));
    updateData.put("currentTeam", hierarchicalData.get("currentTeam"));
    updateData.put("currentActionType", hierarchicalData.get("currentActionType"));
}
```

**Estrutura enviada:**

```json
{
  "type": "draft_updated",
  "matchId": 123,
  "teams": {
    "blue": {
      "name": "Blue Team",
      "teamNumber": 1,
      "averageMmr": 1500,
      "players": [
        {
          "summonerName": "Player1",
          "mmr": 1500,
          "actions": [
            {
              "index": 0,
              "type": "ban",
              "championId": "240",
              "championName": "Kled",
              "phase": "ban1",
              "status": "completed"
            }
          ],
          "bans": [...],
          "picks": [...]
        }
      ],
      "allBans": ["240", "266"],
      "allPicks": ["157", "64"]
    },
    "red": { ... }
  },
  "currentAction": 5,
  "currentPhase": "ban1",
  "currentPlayer": "Player3",
  "currentTeam": "blue",
  "currentActionType": "ban",
  "timeRemaining": 28,
  
  // ✅ Estrutura FLAT mantida para compatibilidade
  "actions": [...],
  "team1": [...],
  "team2": [...]
}
```

### ❌ Frontend (NÃO ADAPTADO)

O frontend ainda está usando a estrutura FLAT:

```typescript
// app.ts - linha ~433
const newPhases = (updateData.phases && updateData.phases.length > 0) 
  ? updateData.phases 
  : (updateData.actions && updateData.actions.length > 0) 
    ? updateData.actions 
    : this.draftData.phases;
```

**Busca no código confirmou:**

- ❌ Nenhuma referência a `teams.blue` ou `teams.red`
- ❌ Nenhuma referência a `updateData.teams`
- ❌ Nenhum código processando estrutura hierárquica
- ✅ Ainda usa `actions[]`, `team1[]`, `team2[]` (estrutura flat)

---

## ⏰ ANÁLISE DO TIMER

### ✅ Backend: Enviando Corretamente

```java
// DraftFlowService.java - linha ~607
updateData.put("timeRemaining", (int) Math.ceil(remainingMs / 1000.0));
```

**Timer é enviado em SEGUNDOS** ✅

### ✅ Frontend: Recebendo Corretamente

#### 1. App.ts recebe e despacha eventos

```typescript
// app.ts - linha ~464
const newTimeRemaining = updateData.timeRemaining !== undefined
  ? updateData.timeRemaining // ✅ Já vem em segundos do backend
  : (updateData.remainingMs !== undefined
    ? Math.ceil(updateData.remainingMs / 1000)
    : 30);

// Despacha evento draftTimerUpdate
document.dispatchEvent(new CustomEvent('draftTimerUpdate', {
  detail: {
    matchId: this.draftData.matchId,
    timeRemaining: newTimeRemaining
  }
}));
```

#### 2. DraftPickBan.ts escuta e atualiza

```typescript
// draft-pick-ban.ts - linha ~350
document.addEventListener('draftTimerUpdate', (event: any) => {
  if (event.detail?.matchId === this.matchId) {
    this.updateTimerFromBackend(event.detail);
  }
});

// draft-pick-ban.ts - linha ~2334
updateTimerFromBackend(data: any): void {
  this.timeRemaining = data.timeRemaining;
  this.cdr.markForCheck();
  this.cdr.detectChanges(); // ✅ FORÇANDO detecção
}
```

### ⚠️ POSSÍVEL PROBLEMA DO TIMER

O timer está sendo enviado e recebido corretamente, MAS:

**Problema de OnPush Change Detection:**

- Componente usa `ChangeDetectionStrategy.OnPush`
- Apenas mudanças em `@Input()` ou eventos disparam detecção
- `timeRemaining` é propriedade local, NÃO é `@Input()`
- Mesmo com `detectChanges()`, pode não atualizar na UI se:
  - Template não está usando pipe `async`
  - Não há referência mudando no template
  - Angular não reconhece a mudança

**Solução:**

1. Tornar `timeRemaining` um `@Input()` OU
2. Passar via `matchData.timeRemaining` do app.ts OU
3. Usar `BehaviorSubject` + `async` pipe no template

---

## 📋 PLANO DE AÇÃO

### 1️⃣ FASE 1: Adaptar Frontend para Estrutura Hierárquica

#### A. Modificar `app.ts` para processar `teams`

```typescript
case 'draft_updated':
  const updateData = message.data || message;
  
  // ✅ NOVO: Processar estrutura hierárquica
  if (updateData.teams) {
    this.draftData = {
      ...this.draftData,
      teams: updateData.teams, // ✅ Passar estrutura hierárquica
      currentPhase: updateData.currentPhase,
      currentPlayer: updateData.currentPlayer,
      currentTeam: updateData.currentTeam,
      currentActionType: updateData.currentActionType,
      timeRemaining: updateData.timeRemaining,
      _updateTimestamp: Date.now()
    };
  }
```

#### B. Modificar `draft-pick-ban.ts` para usar `teams`

```typescript
// Acessar jogadores via teams.blue/red
const blueTeam = this.matchData?.teams?.blue?.players || [];
const redTeam = this.matchData?.teams?.red?.players || [];

// Acessar ações de um jogador específico
const player = this.matchData.teams.blue.players[0];
const playerBans = player.bans; // ✅ Ações já estão no jogador
const playerPicks = player.picks; // ✅ Não precisa filtrar por byPlayer
```

### 2️⃣ FASE 2: Corrigir Timer

#### Opção A: Passar via @Input (RECOMENDADO)

```typescript
// app.ts
this.draftData = {
  ...this.draftData,
  timeRemaining: newTimeRemaining,
  _updateTimestamp: Date.now() // ✅ Forçar mudança de referência
};

// draft-pick-ban.ts - remover evento draftTimerUpdate
// O timer virá via ngOnChanges automaticamente
```

#### Opção B: Usar Observable + async pipe

```typescript
// draft-pick-ban.ts
timeRemaining$ = new BehaviorSubject<number>(30);

updateTimerFromBackend(data: any): void {
  this.timeRemaining$.next(data.timeRemaining);
}

// HTML
<div>{{ timeRemaining$ | async }}s</div>
```

### 3️⃣ FASE 3: Remover Estrutura Flat (Opcional)

Após confirmar que frontend funciona com estrutura hierárquica:

```java
// DraftFlowService.java - broadcastUpdate()
// ❌ REMOVER (após testes):
// updateData.put(KEY_ACTIONS, st.getActions());
// updateData.put(KEY_TEAM1, team1Data);
// updateData.put(KEY_TEAM2, team2Data);

// ✅ MANTER apenas:
Map<String, Object> hierarchicalData = buildHierarchicalDraftData(st);
updateData.put("teams", hierarchicalData.get("teams"));
```

---

## 🎯 RESUMO

### ✅ O QUE ESTÁ FUNCIONANDO

- Backend gera estrutura hierárquica corretamente
- Backend envia estrutura hierárquica via WebSocket
- Backend mantém estrutura flat para compatibilidade
- Timer é enviado corretamente em segundos
- Timer é recebido corretamente no frontend
- `detectChanges()` é chamado após atualização

### ❌ O QUE NÃO ESTÁ FUNCIONANDO

- **Frontend não processa estrutura hierárquica**
- **Frontend ainda usa estrutura flat (`actions[]`, `team1[]`, `team2[]`)**
- **Timer pode não atualizar visualmente por problema de OnPush**
- **Nenhum código frontend usa `teams.blue` ou `teams.red`**

### 🔧 PRÓXIMOS PASSOS

1. **URGENTE:** Adaptar frontend para usar `matchData.teams.blue/red.players[]`
2. **URGENTE:** Corrigir timer usando `@Input()` ou Observable
3. **MÉDIO:** Testar estrutura hierárquica funcionando
4. **BAIXO:** Remover estrutura flat do backend (após confirmação)

---

## 📝 NOTAS TÉCNICAS

### Por que OnPush não detecta timer?

```typescript
@Component({
  changeDetection: ChangeDetectionStrategy.OnPush // ⚠️ PROBLEMA
})
export class DraftPickBanComponent {
  timeRemaining: number = 30; // ❌ Propriedade local, não @Input()
  
  updateTimerFromBackend(data: any): void {
    this.timeRemaining = data.timeRemaining; // ❌ OnPush pode ignorar
    this.cdr.detectChanges(); // ⚠️ Força, mas pode não ser suficiente
  }
}
```

**OnPush só detecta mudanças quando:**

- Valor de `@Input()` muda (por referência)
- Evento do template é disparado
- `async` pipe recebe novo valor
- `markForCheck()` + mudança real de dados

**Solução definitiva:**

```typescript
// app.ts - incluir timer no matchData
this.draftData = {
  ...this.draftData, // ✅ NOVO OBJETO (OnPush detecta)
  timeRemaining: newTimeRemaining, // ✅ Timer vem via @Input
  _updateTimestamp: Date.now() // ✅ Garante mudança de referência
};

// draft-pick-ban.ts - usar do @Input()
@Input() matchData: any = null;

ngOnChanges(changes: SimpleChanges) {
  if (changes['matchData'] && changes['matchData'].currentValue) {
    const data = changes['matchData'].currentValue;
    this.timeRemaining = data.timeRemaining || 30; // ✅ OnPush detecta
  }
}
```

---

## 🔍 BUSCA NO CÓDIGO

```bash
# Busca estrutura hierárquica no frontend (0 resultados)
grep -r "teams\.blue" frontend/
grep -r "teams\.red" frontend/
grep -r "updateData\.teams" frontend/

# Busca estrutura flat no frontend (MUITOS resultados)
grep -r "updateData.actions" frontend/
grep -r "team1\[" frontend/
grep -r "team2\[" frontend/
```

**Conclusão:** Frontend 100% ainda usa estrutura flat ❌
