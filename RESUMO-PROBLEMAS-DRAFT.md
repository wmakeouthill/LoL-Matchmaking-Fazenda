# 📋 RESUMO: Problemas Restantes no Draft

## ✅ FEITO

- Ordem do draft corrigida conforme documento "pergunas draft.md"
- Logs detalhados adicionados ao `processAction()`
- JSON com estrutura hierárquica `teams.blue/red` + `team1/team2` (compatibilidade)

---

## ❌ PROBLEMA 1: Jogador real não consegue salvar pick/ban

### Sintomas

- ✅ Gateway identifica jogador via LCU
- ✅ Modal de seleção abre corretamente
- ❌ Ao confirmar campeão, **não salva**
- ✅ Bots salvam normalmente

### Causa Provável

**Match de nome entre frontend e backend**

Frontend pode estar enviando:

- `playerId` (número): `1786097`
- `summonerName` (string): `"FZD Ratoso#fzd"`

Backend espera nome EXATO na lista de players do time.

### Status

🔍 **Aguardando logs** para identificar causa exata

Logs adicionados:

```java
log.info("🔍 [processAction] Jogador esperado: {}", expectedPlayer);
log.info("🔍 [processAction] Jogador recebido: {}", byPlayer);
log.warn("❌ [processAction] Team1 players: {}", st.getTeam1Players());
log.warn("❌ [processAction] Team2 players: {}", st.getTeam2Players());
```

### Próximo Passo

1. Rodar backend e testar
2. Copiar logs quando você tentar fazer pick/ban
3. Aplicar correção apropriada

---

## ❌ PROBLEMA 2: Timer não atualiza no frontend

### Sintomas

- Timer congelado em 30s
- Não atualiza nem no modal nem na tela principal
- Backend continua contagem (bots jogam na hora certa)

### Causa Provável

**Opção A: Frontend (OnPush) não detecta mudança**

```typescript
// draft-pick-ban.ts usa ChangeDetectionStrategy.OnPush
@Input() timeRemaining: number = 30;

// Quando WebSocket atualiza, precisa chamar:
this.cdr.markForCheck();
```

**Opção B: Backend não envia timeRemaining atualizado**

```java
// broadcastUpdate() deve enviar:
updateData.put("timeRemaining", (int) Math.ceil(remainingMs / 1000.0));
```

### Verificar

1. **Backend enviando?**

```bash
# Observar logs:
tail -f logs/application.log | grep "draft_updated"
# Deve ter: "timeRemaining": X (mudando a cada segundo)
```

2. **Frontend recebendo?**

```typescript
// Console do navegador:
// Verificar eventos WebSocket
// timeRemaining deve mudar: 30 → 29 → 28...
```

3. **Frontend atualizando view?**

```typescript
// app.ts ou draft-pick-ban.ts
// Após receber draft_updated:
this.timeRemaining = data.timeRemaining;
this.cdr.markForCheck(); // ⚠️ ESSENCIAL para OnPush!
```

### Solução Provável

Adicionar `markForCheck()` após atualizar `timeRemaining`:

```typescript
// frontend/src/app/components/draft/draft-pick-ban.ts
private handleDraftUpdated(data: any) {
  if (data.timeRemaining !== undefined) {
    this.timeRemaining = data.timeRemaining;
    this.cdr.markForCheck(); // ✅ Força detecção de mudança
    console.log('⏰ Timer atualizado:', this.timeRemaining);
  }
}
```

---

## ❌ PROBLEMA 3: Imagens de ban não aparecem

### Sintomas

- Picks mostram imagem do campeão
- Bans NÃO mostram imagem

### Verificar

1. **JSON tem championId para bans?**

```json
{
  "type": "ban",
  "championId": "150",
  "championName": "Gnar"
}
```

2. **Frontend renderiza imagem para bans?**

```typescript
// Verificar se há lógica diferente para type="ban" vs type="pick"
// CSS pode estar ocultando imagens de ban
```

---

## 🎯 PLANO DE AÇÃO

### Agora

1. ✅ Compilar backend com logs
2. 🧪 Testar draft completo
3. 📊 Coletar logs quando você tentar fazer pick/ban
4. 🔍 Identificar causa exata

### Depois dos logs

1. 🔧 Corrigir match de nome (Problema 1)
2. 🔧 Adicionar `markForCheck()` para timer (Problema 2)  
3. 🔧 Verificar renderização de imagens de ban (Problema 3)

---

## 🚀 COMANDO PARA TESTAR

```bash
# Backend com logs filtrados
cd "c:\lol-matchmaking - backend-refactor\spring-backend"
mvn spring-boot:run 2>&1 | grep -E "processAction|DraftController|draft_updated"
```

**Depois:** Copie os logs e compartilhe para análise!
