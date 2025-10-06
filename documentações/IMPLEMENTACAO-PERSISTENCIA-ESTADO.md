# 🎯 Implementação: Persistência de Estado de Partidas

## 📋 Requisitos do Usuário

1. **Não finalizar automaticamente**: Partida em `in_progress` NÃO deve sair automaticamente da tela
2. **Detecção automática via LCU**: Quando LCU detectar partida do jogador logado:
   - Se partida existe no banco com status `draft` → redirecionar para tela de draft
   - Se partida existe no banco com status `in_progress` → redirecionar para tela de game-in-progress
3. **Persistência após reabrir**: Mesmo após fechar e reabrir o app:
   - Se usuário tem partida `draft` → voltar para draft
   - Se usuário tem partida `in_progress` → voltar para game-in-progress

---

## 🔍 Análise do Problema Atual

### ❌ **Problema 1: Saída Automática da Tela**

**Sintoma**: Após alguns segundos/minutos, o app sai da tela `game-in-progress` automaticamente.

**Possíveis Causas**:

1. Timer/timeout de inatividade
2. Polling que detecta que a partida LCU terminou e limpa o estado
3. WebSocket que recebe evento de término de partida
4. Lógica de "auto-finalize" ou "heartbeat timeout"

**Arquivos a Investigar**:

- `frontend/src/app/components/game-in-progress/*` (componentes da tela)
- `frontend/src/app/app.ts` (polling e handlers WebSocket)
- `backend/.../GameInProgressService.java` (lógica de finalização)
- `backend/.../LcuService.java` (detecção de término via LCU)

### ❌ **Problema 2: Sem Detecção Automática de Partidas**

**Sintoma**: App não detecta automaticamente quando jogador entra em partida no cliente LoL.

**O que falta**:

1. **Backend**: Endpoint ou serviço que verifica se jogador tem partida ativa
2. **Backend**: Repository method para buscar partida ativa do jogador
3. **Frontend**: Polling/listener para verificar partida ativa ao iniciar app
4. **Frontend**: Handler para eventos LCU de início de partida

### ❌ **Problema 3: Sem Persistência ao Reabrir App**

**Sintoma**: Ao fechar e reabrir app, usuário não volta para a tela da partida em andamento.

**O que falta**:

1. **Frontend**: Verificação no `ngOnInit()` se usuário tem partida ativa
2. **Frontend**: Redirecionamento automático para tela correta baseado no status
3. **Backend**: Endpoint `/api/matches/my-active-match` para retornar partida ativa

---

## 🛠️ Plano de Implementação

### **FASE 1: Evitar Saída Automática** 🔥

#### **1.1 Backend: Verificar Lógica de Auto-Finalização**

```java
// GameInProgressService.java
// VERIFICAR: métodos que finalizam partida automaticamente
// - finishGameAutomatically()
// - checkGameTimeout()
// - handleHeartbeatTimeout()
// REMOVER: qualquer lógica que finalize sem ação manual do usuário
```

#### **1.2 Frontend: Desabilitar Timeouts/Polling de Finalização**

```typescript
// app.ts ou game-in-progress component
// VERIFICAR: setInterval/setTimeout que verificam término
// REMOVER: lógica que redireciona automaticamente após término LCU
```

---

### **FASE 2: Detecção Automática via LCU** 🎮

#### **2.1 Backend: Repository para Buscar Partida Ativa do Jogador**

```java
// CustomMatchRepository.java
@Query("SELECT cm FROM CustomMatch cm " +
       "WHERE cm.status IN ('draft', 'in_progress') " +
       "AND (cm.team1PlayersJson LIKE %:puuid% OR cm.team2PlayersJson LIKE %:puuid%) " +
       "ORDER BY cm.createdAt DESC")
Optional<CustomMatch> findActiveMatchByPlayerPuuid(@Param("puuid") String puuid);
```

#### **2.2 Backend: Endpoint para Verificar Partida Ativa**

```java
// CustomMatchController.java
@GetMapping("/my-active-match")
public ResponseEntity<CustomMatchDTO> getMyActiveMatch(Principal principal) {
    String backendId = principal.getName();
    
    // Buscar player pelo backendId
    Player player = playerRepository.findByBackendId(backendId)
        .orElseThrow(() -> new NotFoundException("Player not found"));
    
    // Buscar partida ativa pelo PUUID
    Optional<CustomMatch> activeMatch = 
        customMatchRepository.findActiveMatchByPlayerPuuid(player.getPuuid());
    
    if (activeMatch.isEmpty()) {
        return ResponseEntity.notFound().build();
    }
    
    CustomMatch match = activeMatch.get();
    return ResponseEntity.ok(convertToDTO(match));
}
```

#### **2.3 Backend: Service para Detectar Partidas via LCU**

```java
// LcuGatewayService.java (novo ou adicionar ao LcuService)
@Scheduled(fixedDelay = 10000) // a cada 10 segundos
public void detectActiveGamesFromLcu() {
    // Para cada player conectado no WebSocket:
    for (String backendId : connectedPlayers) {
        try {
            Player player = playerRepository.findByBackendId(backendId).orElse(null);
            if (player == null) continue;
            
            // Buscar sessão do LCU
            GameflowSession session = lcuService.getGameflowSession(player);
            
            if (session != null && session.getPhase().equals("InProgress")) {
                // Verificar se já existe partida no banco
                Optional<CustomMatch> existingMatch = 
                    customMatchRepository.findActiveMatchByPlayerPuuid(player.getPuuid());
                
                if (existingMatch.isPresent()) {
                    // Notificar frontend via WebSocket
                    CustomMatch match = existingMatch.get();
                    
                    if ("draft".equals(match.getStatus())) {
                        sendWebSocketMessage(backendId, "redirect_to_draft", match.getId());
                    } else if ("in_progress".equals(match.getStatus())) {
                        sendWebSocketMessage(backendId, "redirect_to_game", match.getId());
                    }
                }
            }
        } catch (Exception e) {
            log.error("Erro ao detectar partida do jogador {}: {}", backendId, e.getMessage());
        }
    }
}
```

#### **2.4 Frontend: Handler para Eventos de Redirecionamento**

```typescript
// app.ts - handleBackendMessage()
case 'redirect_to_draft':
  console.log('🎯 [App] Redirecionando para draft da partida:', message.data.matchId);
  this.router.navigate(['/draft'], { 
    queryParams: { matchId: message.data.matchId } 
  });
  break;

case 'redirect_to_game':
  console.log('🎯 [App] Redirecionando para game-in-progress:', message.data.matchId);
  this.router.navigate(['/game-in-progress'], { 
    queryParams: { matchId: message.data.matchId } 
  });
  break;
```

---

### **FASE 3: Persistência ao Reabrir App** 💾

#### **3.1 Frontend: Verificar Partida Ativa no ngOnInit**

```typescript
// app.ts - initializeAppSequence()
// Adicionar após loadPlayerDataWithRetry():

// 10. Verificar se jogador tem partida ativa
console.log('🔄 [App] Passo 10: Verificando partida ativa...');
await this.checkAndRestoreActiveMatch();

private async checkAndRestoreActiveMatch(): Promise<void> {
  try {
    console.log('🔍 [App] Verificando se jogador tem partida ativa...');
    
    const activeMatch: any = await firstValueFrom(
      this.apiService.getMyActiveMatch()
    );
    
    if (!activeMatch) {
      console.log('✅ [App] Nenhuma partida ativa encontrada');
      return;
    }
    
    console.log('🎮 [App] Partida ativa encontrada:', activeMatch);
    
    // Redirecionar baseado no status
    if (activeMatch.status === 'draft') {
      console.log('🎯 [App] Redirecionando para draft...');
      this.router.navigate(['/draft'], { 
        queryParams: { matchId: activeMatch.id } 
      });
    } else if (activeMatch.status === 'in_progress') {
      console.log('🎯 [App] Redirecionando para game-in-progress...');
      this.router.navigate(['/game-in-progress'], { 
        queryParams: { matchId: activeMatch.id } 
      });
    }
    
  } catch (error: any) {
    if (error.status === 404) {
      console.log('✅ [App] Nenhuma partida ativa (404)');
    } else {
      console.error('❌ [App] Erro ao verificar partida ativa:', error);
    }
  }
}
```

#### **3.2 Frontend: Adicionar Método no API Service**

```typescript
// api.service.ts
getMyActiveMatch(): Observable<any> {
  return this.http.get(`${this.apiUrl}/custom-matches/my-active-match`);
}
```

---

## 📝 Checklist de Implementação

### Backend

- [ ] Criar `findActiveMatchByPlayerPuuid()` no `CustomMatchRepository`
- [ ] Criar endpoint `GET /api/custom-matches/my-active-match`
- [ ] Criar `LcuGatewayService.detectActiveGamesFromLcu()` com `@Scheduled`
- [ ] Adicionar eventos WebSocket `redirect_to_draft` e `redirect_to_game`
- [ ] **INVESTIGAR**: Remover lógica de auto-finalização em `GameInProgressService`
- [ ] **INVESTIGAR**: Remover polling de término automático

### Frontend

- [ ] Adicionar `getMyActiveMatch()` no `api.service.ts`
- [ ] Criar `checkAndRestoreActiveMatch()` no `app.ts`
- [ ] Adicionar handlers para `redirect_to_draft` e `redirect_to_game`
- [ ] Chamar `checkAndRestoreActiveMatch()` no `initializeAppSequence()`
- [ ] **INVESTIGAR**: Remover timeouts que saem da tela automaticamente
- [ ] **INVESTIGAR**: Desabilitar polling de término em `game-in-progress` component

---

## 🧪 Testes Necessários

1. **Teste de Simulação**:
   - ✅ Simular partida
   - ✅ Verificar que abre tela `game-in-progress`
   - ❌ Aguardar 5+ minutos → **NÃO deve sair da tela**

2. **Teste de Persistência**:
   - Simular partida (status `in_progress`)
   - Fechar app
   - Reabrir app
   - **Deve** voltar automaticamente para `game-in-progress`

3. **Teste de Draft**:
   - Criar partida com status `draft`
   - Fechar app
   - Reabrir app
   - **Deve** voltar automaticamente para `draft`

4. **Teste de Detecção LCU**:
   - Entrar em partida custom no cliente LoL
   - **Deve** detectar e redirecionar para tela correta

---

## 📊 Prioridades

1. **🔥 URGENTE**: Investigar e corrigir saída automática da tela
2. **🔥 URGENTE**: Implementar `getMyActiveMatch()` endpoint
3. **🔥 URGENTE**: Adicionar `checkAndRestoreActiveMatch()` no frontend
4. **⚠️ IMPORTANTE**: Implementar detecção via LCU com `@Scheduled`
5. **⚠️ IMPORTANTE**: Adicionar handlers de redirecionamento

---

## 🚀 Próximos Passos

1. **INVESTIGAÇÃO**: Encontrar causa da saída automática
2. **BACKEND**: Criar repository method e endpoint
3. **FRONTEND**: Implementar verificação ao iniciar app
4. **TESTE**: Validar persistência e detecção automática
