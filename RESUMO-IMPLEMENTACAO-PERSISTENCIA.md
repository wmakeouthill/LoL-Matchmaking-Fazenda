# 🎯 RESUMO EXECUTIVO: Persistência de Estado

## 📌 Status Atual

**Problema 1 - Saída Automática**: ✅ **NÃO ENCONTRADO**

- Analisado `game-in-progress.ts`: Apenas polling de detecção LCU (intervalo de 2 min)
- Não há lógica que force saída automática ou navegação
- **Possível causa**: Polling LCU detecta fim de partida e emite `onGameComplete`
- **Solução**: Desabilitar detecção automática de fim via LCU (manter apenas manual)

**Problema 2 - Sem Detecção Automática**: ❌ **FALTA IMPLEMENTAR**
**Problema 3 - Sem Persistência**: ❌ **FALTA IMPLEMENTAR**

---

## 🚀 AÇÕES IMEDIATAS

### 1️⃣ **Criar Endpoint de Partida Ativa** (Backend)

**Arquivo**: `CustomMatchRepository.java`

```java
@Query("SELECT cm FROM CustomMatch cm " +
       "WHERE cm.status IN ('draft', 'in_progress') " +
       "AND (cm.team1PlayersJson LIKE CONCAT('%', :puuid, '%') " +
       "     OR cm.team2PlayersJson LIKE CONCAT('%', :puuid, '%')) " +
       "ORDER BY cm.createdAt DESC")
Optional<CustomMatch> findActiveMatchByPlayerPuuid(@Param("puuid") String puuid);
```

**Arquivo**: `CustomMatchController.java`

```java
@GetMapping("/my-active-match")
public ResponseEntity<Map<String, Object>> getMyActiveMatch(Principal principal) {
    String backendId = principal.getName();
    
    Player player = playerRepository.findByBackendId(backendId)
        .orElseThrow(() -> new NotFoundException("Player not found"));
    
    Optional<CustomMatch> activeMatch = 
        customMatchRepository.findActiveMatchByPlayerPuuid(player.getPuuid());
    
    if (activeMatch.isEmpty()) {
        return ResponseEntity.notFound().build();
    }
    
    CustomMatch match = activeMatch.get();
    
    // Converter para formato esperado pelo frontend
    Map<String, Object> response = new HashMap<>();
    response.put("id", match.getId());
    response.put("status", match.getStatus());
    response.put("title", match.getTitle());
    response.put("matchId", match.getId());
    
    // Adicionar dados do draft/game conforme necessário
    if ("draft".equals(match.getStatus())) {
        response.put("draftState", match.getPickBanDataJson());
        response.put("team1", match.getTeam1PlayersJson());
        response.put("team2", match.getTeam2PlayersJson());
    } else if ("in_progress".equals(match.getStatus())) {
        response.put("team1", match.getTeam1PlayersJson());
        response.put("team2", match.getTeam2PlayersJson());
        response.put("pickBanData", match.getPickBanDataJson());
    }
    
    return ResponseEntity.ok(response);
}
```

---

### 2️⃣ **Adicionar Verificação ao Iniciar App** (Frontend)

**Arquivo**: `api.service.ts`

```typescript
getMyActiveMatch(): Observable<any> {
  return this.http.get<any>(`${this.apiUrl}/custom-matches/my-active-match`);
}
```

**Arquivo**: `app.ts` - Adicionar ao final de `initializeAppSequence()`:

```typescript
// 10. Verificar se jogador tem partida ativa e restaurar
console.log('🔄 [App] Passo 10: Verificando partida ativa...');
await this.checkAndRestoreActiveMatch();

private async checkAndRestoreActiveMatch(): Promise<void> {
  try {
    console.log('🔍 [App] Verificando se jogador tem partida ativa...');
    
    const activeMatch: any = await firstValueFrom(
      this.apiService.getMyActiveMatch()
    );
    
    if (!activeMatch || !activeMatch.id) {
      console.log('✅ [App] Nenhuma partida ativa encontrada');
      return;
    }
    
    console.log('🎮 [App] Partida ativa encontrada:', {
      id: activeMatch.id,
      status: activeMatch.status,
      title: activeMatch.title
    });
    
    // Redirecionar baseado no status
    if (activeMatch.status === 'draft') {
      console.log('🎯 [App] Redirecionando para draft...');
      this.inDraftPhase = true;
      this.inGamePhase = false;
      this.draftData = {
        matchId: activeMatch.id,
        state: activeMatch.draftState,
        team1: activeMatch.team1,
        team2: activeMatch.team2
      };
      this.cdr.detectChanges();
      
    } else if (activeMatch.status === 'in_progress') {
      console.log('🎯 [App] Redirecionando para game-in-progress...');
      this.inGamePhase = true;
      this.inDraftPhase = false;
      this.gameData = {
        matchId: activeMatch.id,
        team1: activeMatch.team1,
        team2: activeMatch.team2,
        pickBanData: activeMatch.pickBanData,
        sessionId: `restored-${activeMatch.id}`,
        gameId: `restored-${activeMatch.id}`,
        startTime: new Date(),
        isCustomGame: true
      };
      this.cdr.detectChanges();
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

---

### 3️⃣ **Opcional: Detecção Automática via LCU** (Backend - Scheduled)

**Arquivo**: `LcuGatewayService.java` (novo) ou adicionar ao `LcuService.java`

```java
@Service
@Slf4j
public class LcuGatewayService {

    @Autowired
    private CustomMatchRepository customMatchRepository;
    
    @Autowired
    private PlayerRepository playerRepository;
    
    @Autowired
    private LcuService lcuService;
    
    @Autowired
    private SimpMessagingTemplate messagingTemplate;
    
    // Lista de players conectados (injetada do WebSocket ou SessionRegistry)
    private Set<String> connectedPlayers = new ConcurrentHashMap<String, Boolean>().keySet(true);

    @Scheduled(fixedDelay = 15000) // a cada 15 segundos
    public void detectActiveGamesFromLcu() {
        log.debug("🔍 [LcuGateway] Verificando partidas ativas via LCU...");
        
        for (String backendId : connectedPlayers) {
            try {
                Player player = playerRepository.findByBackendId(backendId).orElse(null);
                if (player == null || player.getPuuid() == null) continue;
                
                // Verificar se player tem partida ativa no banco
                Optional<CustomMatch> activeMatch = 
                    customMatchRepository.findActiveMatchByPlayerPuuid(player.getPuuid());
                
                if (activeMatch.isPresent()) {
                    CustomMatch match = activeMatch.get();
                    
                    // Enviar notificação ao player via WebSocket
                    Map<String, Object> notification = new HashMap<>();
                    notification.put("matchId", match.getId());
                    notification.put("status", match.getStatus());
                    
                    if ("draft".equals(match.getStatus())) {
                        messagingTemplate.convertAndSendToUser(
                            backendId, 
                            "/queue/messages", 
                            Map.of("type", "redirect_to_draft", "data", notification)
                        );
                        log.info("📡 [LcuGateway] Notificando {} sobre draft ativo: {}", 
                            backendId, match.getId());
                        
                    } else if ("in_progress".equals(match.getStatus())) {
                        messagingTemplate.convertAndSendToUser(
                            backendId, 
                            "/queue/messages", 
                            Map.of("type", "redirect_to_game", "data", notification)
                        );
                        log.info("📡 [LcuGateway] Notificando {} sobre game ativo: {}", 
                            backendId, match.getId());
                    }
                }
                
            } catch (Exception e) {
                log.error("❌ [LcuGateway] Erro ao verificar partida do jogador {}: {}", 
                    backendId, e.getMessage());
            }
        }
    }
    
    public void addConnectedPlayer(String backendId) {
        connectedPlayers.add(backendId);
    }
    
    public void removeConnectedPlayer(String backendId) {
        connectedPlayers.remove(backendId);
    }
}
```

**Adicionar handlers no Frontend** (`app.ts` - `handleBackendMessage()`):

```typescript
case 'redirect_to_draft':
  console.log('🎯 [App] Redirecionando para draft:', message.data);
  this.inDraftPhase = true;
  this.inGamePhase = false;
  // Carregar dados do draft via API
  this.loadDraftData(message.data.matchId);
  break;

case 'redirect_to_game':
  console.log('🎯 [App] Redirecionando para game-in-progress:', message.data);
  this.inGamePhase = true;
  this.inDraftPhase = false;
  // Carregar dados do game via API
  this.loadGameData(message.data.matchId);
  break;
```

---

## 📝 CHECKLIST DE IMPLEMENTAÇÃO

### ✅ **Fase 1: Persistência Básica** (PRIORIDADE MÁXIMA)

- [ ] **Backend**: Criar `findActiveMatchByPlayerPuuid()` no repository
- [ ] **Backend**: Criar endpoint `GET /api/custom-matches/my-active-match`
- [ ] **Frontend**: Adicionar `getMyActiveMatch()` no `api.service.ts`
- [ ] **Frontend**: Criar método `checkAndRestoreActiveMatch()` no `app.ts`
- [ ] **Frontend**: Chamar no final de `initializeAppSequence()`
- [ ] **Teste**: Simular partida, fechar app, reabrir → deve voltar para game-in-progress

### ⚠️ **Fase 2: Detecção Automática** (OPCIONAL)

- [ ] **Backend**: Criar `LcuGatewayService` com `@Scheduled`
- [ ] **Backend**: Adicionar eventos `redirect_to_draft` e `redirect_to_game`
- [ ] **Frontend**: Adicionar handlers no `handleBackendMessage()`
- [ ] **Teste**: Entrar em partida custom no cliente → deve redirecionar automaticamente

---

## 🧪 TESTES NECESSÁRIOS

1. **Teste de Persistência Draft**:
   - Criar partida com status `draft`
   - Fechar app (`Ctrl+C` no terminal ou fechar janela)
   - Reabrir app (`npm run electron`)
   - ✅ **Deve** voltar para tela de draft

2. **Teste de Persistência Game**:
   - Simular partida (status `in_progress`)
   - Fechar app
   - Reabrir app
   - ✅ **Deve** voltar para tela de game-in-progress

3. **Teste de Não-Saída Automática**:
   - Simular partida
   - Aguardar 10+ minutos
   - ✅ **NÃO deve** sair da tela automaticamente

---

## ⚡ IMPLEMENTAÇÃO AGORA?

Posso começar implementando a **Fase 1 (Persistência Básica)**:

1. Criar método no repository
2. Criar endpoint no controller
3. Adicionar método no api.service.ts
4. Adicionar verificação no app.ts

Deseja que eu implemente agora? 🚀
