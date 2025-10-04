# 🗳️ Sistema de Votação de Partidas LCU - Resumo Executivo

## 🎯 Objetivo Final

Substituir o botão **"Declarar Vencedor"** por um sistema de **votação democrática**:

1. **Jogadores clicam** em "Escolher Partida"
2. **Sistema busca** últimas 3 partidas custom do LCU
3. **Jogadores votam** na partida correspondente
4. **5 votos = vinculação automática**
5. **Dados completos** salvos no banco
6. **Histórico** exibe tudo como no LCU

---

## ✅ O que JÁ ESTÁ PRONTO

| Componente | Status | Descrição |
|-----------|--------|-----------|
| **Modal de Seleção** | ✅ 100% | Exibe partidas, mostra times/jogadores |
| **Busca LCU** | ✅ 100% | `getLCUMatchHistoryAll()` funciona |
| **Filtro Custom** | ✅ 100% | Filtra só partidas personalizadas |
| **Callback** | ✅ 100% | `onWinnerConfirmed()` existe |
| **UI Base** | ✅ 100% | Template HTML completo |

---

## ❌ O que FALTA IMPLEMENTAR

| Componente | Status | Prioridade |
|-----------|--------|------------|
| **Sistema de Votação** | ❌ 0% | 🔴 CRÍTICO |
| **Backend: Endpoints** | ❌ 0% | 🔴 CRÍTICO |
| **WebSocket: Eventos** | ❌ 0% | 🔴 CRÍTICO |
| **BD: Tabela votos** | ❌ 0% | 🔴 CRÍTICO |
| **UI: Contadores** | ❌ 0% | 🟡 IMPORTANTE |
| **Botão Carregar Mais** | ❌ 0% | 🟢 OPCIONAL |
| **Exibir dados LCU** | ❌ 0% | 🟢 OPCIONAL |

---

## 🏗️ Implementação em 5 Passos

### **PASSO 1: Backend - Estrutura** ⏱️ ~2h

```java
// 1.1 Criar entidade
@Entity
@Table(name = "match_votes")
class MatchVote {
    Long id;
    Long matchId;
    Long playerId;
    Long lcuGameId;
    LocalDateTime votedAt;
}

// 1.2 Adicionar campos em Match
class Match {
    // ... existente ...
    Long linkedLcuGameId;
    String lcuMatchData; // JSON completo
}

// 1.3 Migration SQL
CREATE TABLE match_votes (...);
ALTER TABLE matches ADD COLUMN linked_lcu_game_id BIGINT;
```

### **PASSO 2: Backend - Endpoints** ⏱️ ~2h

```java
@RestController
@RequestMapping("/api/match")
class MatchVoteController {
    
    // Votar
    @PostMapping("/{matchId}/vote")
    ResponseEntity<?> vote(@PathVariable Long matchId, 
                           @RequestBody VoteRequest req) {
        // 1. Validar jogador na partida
        // 2. Salvar voto (substituir se já votou)
        // 3. Contar votos
        // 4. Se >= 5: vincular automaticamente
        // 5. Broadcast WebSocket
    }
    
    // Consultar votos
    @GetMapping("/{matchId}/votes")
    ResponseEntity<?> getVotes(@PathVariable Long matchId) {
        // Retornar Map<lcuGameId, voteCount>
    }
}
```

### **PASSO 3: WebSocket - Eventos** ⏱️ ~1h

```java
// Novos eventos
enum WebSocketEventType {
    MATCH_VOTE_UPDATE,  // Atualizar contagem
    MATCH_LINKED,       // Partida vinculada
}

// Broadcasts
webSocketService.sendToMatch(matchId, 
    new WebSocketMessage(MATCH_VOTE_UPDATE, 
        Map.of("votes", votesByLcuGameId)));

webSocketService.sendToMatch(matchId, 
    new WebSocketMessage(MATCH_LINKED, 
        Map.of("winner", "blue", "lcuGameId", 12345)));
```

### **PASSO 4: Frontend - Adaptar Modal** ⏱️ ~2h

```typescript
// winner-confirmation-modal.component.ts
export class WinnerConfirmationModalComponent {
    votesByMatch: Map<number, number> = new Map();
    playerVote: number | null = null;
    
    ngOnInit() {
        this.subscribeToVoteUpdates();
    }
    
    subscribeToVoteUpdates() {
        this.ws.on('match_vote_update', (data) => {
            this.votesByMatch = new Map(Object.entries(data.votes));
        });
        
        this.ws.on('match_linked', (data) => {
            this.showSuccess(`Vencedor: ${data.winner}`);
            this.onCancel.emit();
        });
    }
    
    voteForMatch(lcuGameId: number) {
        this.onVote.emit({ lcuGameId });
        this.playerVote = lcuGameId;
    }
}
```

**Template:**

```html
<div class="match-card" *ngFor="let option of matchOptions">
    <!-- Contador de votos -->
    <div class="vote-info">
        <span>🗳️ {{ getVoteCount(option.match.gameId) }}/10 votos</span>
        <div class="progress-bar" 
             [style.width.%]="getVoteCount(...) / 10 * 100">
        </div>
    </div>
    
    <!-- Botão de votar -->
    <button (click)="voteForMatch(option.match.gameId)"
            [class.voted]="hasPlayerVoted(...)">
        {{ hasPlayerVoted(...) ? '✓ Votado' : '🗳️ Votar' }}
    </button>
</div>
```

### **PASSO 5: Frontend - Game In Progress** ⏱️ ~30min

```typescript
// game-in-progress.component.ts
onVote(data: { lcuGameId: number }) {
    this.apiService.voteForMatch(
        this.gameData.matchId, 
        data.lcuGameId
    ).subscribe();
}
```

**HTML:** Remover seção "Declarar Vencedor", manter apenas:

```html
<button (click)="retryAutoDetection()">
    🔍 Escolher Partida
</button>
```

---

## 🔄 Fluxo Completo

```
1. Jogador clica "Escolher Partida"
   ↓
2. Frontend busca GET /api/lcu/match-history
   ↓
3. Exibe modal com 3 partidas
   ↓
4. Jogador vota na Partida #2
   ↓
5. Frontend envia POST /api/match/{id}/vote {lcuGameId: 12345}
   ↓
6. Backend salva voto e conta total
   ↓
7. Backend faz broadcast "match_vote_update" via WebSocket
   ↓
8. Todos jogadores veem "2/10 votos" em tempo real
   ↓
9. Quando atinge 5 votos:
   - Backend busca dados completos do LCU
   - Salva no banco (linked_lcu_game_id, lcu_match_data)
   - Detecta vencedor
   - Broadcast "match_linked"
   ↓
10. Frontend fecha modal e mostra resultado
    ↓
11. Histórico exibe dados completos do LCU
```

---

## 📊 Checklist de Implementação

### Backend (Java/Spring Boot)

- [ ] Criar `MatchVote` entity
- [ ] Criar `MatchVoteRepository`
- [ ] Criar `MatchVoteService`
  - [ ] `processVote()` - salvar + contar
  - [ ] `linkMatchAutomatically()` - vincular ao atingir 5 votos
- [ ] Criar `MatchVoteController`
  - [ ] `POST /api/match/{id}/vote`
  - [ ] `DELETE /api/match/{id}/vote`
  - [ ] `GET /api/match/{id}/votes`
- [ ] Adicionar campos em `Match`:
  - [ ] `linked_lcu_game_id`
  - [ ] `lcu_match_data`
- [ ] Criar migration SQL
- [ ] Adicionar eventos WebSocket:
  - [ ] `MATCH_VOTE_UPDATE`
  - [ ] `MATCH_LINKED`

### Frontend (Angular)

- [ ] Modificar `winner-confirmation-modal.component.ts`:
  - [ ] Adicionar `votesByMatch: Map<number, number>`
  - [ ] Adicionar `playerVote: number | null`
  - [ ] Criar `subscribeToVoteUpdates()`
  - [ ] Criar `voteForMatch(lcuGameId)`
  - [ ] Criar `getVoteCount(lcuGameId)`
  - [ ] Criar `hasPlayerVoted(lcuGameId)`
- [ ] Modificar template do modal:
  - [ ] Adicionar contador de votos
  - [ ] Adicionar barra de progresso
  - [ ] Adicionar badge "Você votou aqui"
  - [ ] Mudar botão para "Votar nesta partida"
- [ ] Modificar `game-in-progress.component.ts`:
  - [ ] Modificar `onWinnerConfirmed()` para `onVote()`
  - [ ] Passar `@Output() onVote` para modal
- [ ] Modificar `game-in-progress.html`:
  - [ ] ~~Remover~~ Manter seção "Declarar Vencedor" como fallback
  - [ ] Garantir que botão "Escolher Partida" está visível
- [ ] Adicionar métodos ao `api.service.ts`:
  - [ ] `voteForMatch(matchId, lcuGameId)`
  - [ ] `removeVote(matchId)`
  - [ ] `getMatchVotes(matchId)`
- [ ] Adicionar handlers WebSocket:
  - [ ] `match_vote_update` → atualizar contadores
  - [ ] `match_linked` → fechar modal + exibir resultado

### Banco de Dados

- [ ] Criar migration `V8__add_match_voting.sql`
- [ ] Criar tabela `match_votes`
- [ ] Adicionar colunas em `matches`
- [ ] Criar índices

### Testes

- [ ] Testar votação com 2+ jogadores
- [ ] Testar vinculação automática
- [ ] Testar sincronização WebSocket
- [ ] Testar mudança de voto
- [ ] Testar fallback (declaração manual)

---

## 🎨 Melhorias de UX (Opcionais)

- [ ] Indicador visual da partida "líder"
- [ ] Animação ao atingir 5 votos
- [ ] Toast notification ao vincular
- [ ] Auto-refresh a cada 30s
- [ ] Loading ao carregar mais partidas
- [ ] Não permitir votar em partidas antigas (> 1h)
- [ ] Botão "Carregar Mais Partidas"
- [ ] Botão "Atualizar Lista"

---

## ⏱️ Estimativa de Tempo

| Etapa | Tempo Estimado |
|-------|---------------|
| Backend: Estrutura | 2h |
| Backend: Endpoints | 2h |
| WebSocket | 1h |
| Frontend: Modal | 2h |
| Frontend: Integration | 30min |
| Testes | 1h |
| **TOTAL** | **~8h** |

---

## 🚨 Edge Cases

| Situação | Solução |
|----------|---------|
| Ninguém vota | Manter declaração manual como fallback |
| Empate de votos | Primeira a atingir 5 vence |
| Partida não aparece | Botão "Carregar Mais" |
| Jogador vota errado | Permitir mudança de voto |
| Partida já finalizada | Validar timestamp (< 1h) |

---

## 📖 Arquivos Principais

### Backend

```
src/main/java/com/lolmatchmaking/
├── entity/
│   └── MatchVote.java                    [CRIAR]
├── repository/
│   └── MatchVoteRepository.java          [CRIAR]
├── service/
│   └── MatchVoteService.java             [CRIAR]
├── controller/
│   └── MatchVoteController.java          [CRIAR]
└── websocket/
    └── WebSocketEventType.java           [MODIFICAR]

src/main/resources/db/migration/
└── V8__add_match_voting.sql              [CRIAR]
```

### Frontend

```
frontend/src/app/
├── components/
│   ├── winner-confirmation-modal/
│   │   ├── winner-confirmation-modal.component.ts     [MODIFICAR]
│   │   ├── winner-confirmation-modal.component.html   [MODIFICAR]
│   │   └── winner-confirmation-modal.component.scss   [MODIFICAR]
│   └── game-in-progress/
│       ├── game-in-progress.ts                        [MODIFICAR]
│       └── game-in-progress.html                      [MODIFICAR]
└── services/
    ├── api.ts                                         [MODIFICAR]
    └── websocket.service.ts                           [MODIFICAR]
```

---

## 🎯 Próximo Passo

**COMEÇAR POR:** Backend - Estrutura (Passo 1)

Criar entidade `MatchVote` + migration SQL + adicionar campos em `Match`.

Depois seguir ordem: Backend → WebSocket → Frontend → Testes.

---

**Dúvidas? Precisando de ajuda em algum passo específico?** 🚀
