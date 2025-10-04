# 🎯 SUMÁRIO EXECUTIVO: Sistema de Votação de Partidas LCU

## ✅ Entendi Perfeitamente

Você quer **substituir o sistema atual** de "Declarar Vencedor" por um **sistema democrático de votação**, onde:

1. ❌ **REMOVER:** Jogador clica "Time Azul" ou "Time Vermelho" diretamente
2. ✅ **ADICIONAR:** Jogador clica "Escolher Partida do LCU"
3. ✅ **MOSTRAR:** Modal com últimas 3 partidas customizadas
4. ✅ **VOTAR:** Jogadores votam em qual partida corresponde
5. ✅ **AUTOMATIZAR:** 5 votos = vinculação automática
6. ✅ **SALVAR:** Todos os dados do LCU salvos no banco
7. ✅ **EXIBIR:** Histórico mostra dados como LCU (KDA, itens, etc.)

---

## 📊 Status Atual da Implementação

### ✅ O que JÁ FUNCIONA (70% pronto)

| Componente | Status | Descrição |
|-----------|--------|-----------|
| Modal de Seleção | ✅ 100% | `winner-confirmation-modal` existe e funciona |
| Busca LCU | ✅ 100% | `getLCUMatchHistoryAll()` funcionando |
| Filtro Custom | ✅ 100% | Filtra apenas partidas personalizadas |
| UI Base | ✅ 90% | Template HTML quase completo |
| Callback | ✅ 100% | `onWinnerConfirmed()` existe |

### ❌ O que FALTA IMPLEMENTAR (30% restante)

| Componente | Status | Complexidade |
|-----------|--------|--------------|
| **Sistema de Votação** | ❌ 0% | 🔴 CRÍTICO |
| **Backend REST API** | ❌ 0% | 🔴 ALTA |
| **WebSocket Sync** | ❌ 0% | 🔴 ALTA |
| **Tabela `match_votes`** | ❌ 0% | 🟡 MÉDIA |
| **UI de Votação** | ❌ 0% | 🟢 BAIXA |
| **Carregar Mais** | ❌ 0% | 🟢 BAIXA |
| **Dados LCU no Histórico** | ❌ 0% | 🟢 BAIXA |

---

## 🚀 O que PRECISA ser feito

### **BACKEND (Java/Spring Boot)** - 60% do trabalho

#### 1. Criar Entidade `MatchVote`

```java
@Entity
@Table(name = "match_votes")
public class MatchVote {
    Long id;
    Long matchId;        // ID da partida customizada
    Long playerId;       // Quem votou
    Long lcuGameId;      // Partida do LCU escolhida
    LocalDateTime votedAt;
}
```

#### 2. Adicionar campos em `Match`

```java
@Entity
@Table(name = "matches")
public class Match {
    // ... existente ...
    Long linkedLcuGameId;        // Qual partida do LCU foi vinculada
    String lcuMatchData;         // JSON completo da partida
}
```

#### 3. Criar Endpoints REST

```java
// Votar em uma partida
POST /api/match/{matchId}/vote
Body: { "lcuGameId": 12345 }

// Consultar votos atuais
GET /api/match/{matchId}/votes
Response: { "12345": 4, "67890": 1, "11111": 0 }

// Remover voto
DELETE /api/match/{matchId}/vote
```

#### 4. Lógica de Vinculação Automática

```java
// Quando 5º voto é registrado:
1. Buscar dados completos da partida do LCU
2. Detectar vencedor (Team 100 = blue, Team 200 = red)
3. Atualizar Match:
   - linked_lcu_game_id = 12345
   - lcu_match_data = JSON completo
   - winner = "blue"
   - status = "COMPLETED"
4. Broadcast via WebSocket: "match_linked"
```

#### 5. Eventos WebSocket

```java
// Quando alguém vota
MATCH_VOTE_UPDATE → { "votes": { "12345": 4, ... } }

// Quando 5 votos são atingidos
MATCH_LINKED → { "winner": "blue", "lcuGameId": 12345 }
```

---

### **FRONTEND (Angular)** - 30% do trabalho

#### 1. Modificar `winner-confirmation-modal.component.ts`

```typescript
export class WinnerConfirmationModalComponent {
    votesByMatch: Map<number, number> = new Map();
    playerVote: number | null = null;
    
    // Escutar WebSocket
    ngOnInit() {
        this.ws.on('match_vote_update', (data) => {
            this.votesByMatch = new Map(Object.entries(data.votes));
        });
        
        this.ws.on('match_linked', (data) => {
            alert(`Partida vinculada! Vencedor: ${data.winner}`);
            this.onCancel.emit();
        });
    }
    
    // Votar
    voteForMatch(lcuGameId: number) {
        this.onVote.emit({ lcuGameId });
    }
}
```

#### 2. Modificar Template do Modal

```html
<div class="match-card" *ngFor="let match of matchOptions">
    <!-- Contador de votos -->
    <div class="vote-info">
        🗳️ {{ getVoteCount(match.gameId) }}/10 votos
        <div class="progress-bar" 
             [style.width.%]="getVoteCount(match.gameId) / 10 * 100">
        </div>
        <span *ngIf="hasPlayerVoted(match.gameId)">
            ✓ Você votou aqui
        </span>
    </div>
    
    <!-- Botão de votar -->
    <button (click)="voteForMatch(match.gameId)">
        {{ hasPlayerVoted(match.gameId) ? '✓ Remover voto' : '🗳️ Votar' }}
    </button>
</div>
```

#### 3. Modificar `game-in-progress.component.ts`

```typescript
// Trocar callback direto por votação
onVote(data: { lcuGameId: number }) {
    this.apiService.voteForMatch(
        this.gameData.matchId,
        data.lcuGameId
    ).subscribe();
}
```

#### 4. Adicionar Métodos ao `ApiService`

```typescript
voteForMatch(matchId: number, lcuGameId: number): Observable<any> {
    return this.http.post(`/api/match/${matchId}/vote`, { lcuGameId });
}

getMatchVotes(matchId: number): Observable<any> {
    return this.http.get(`/api/match/${matchId}/votes`);
}
```

---

### **BANCO DE DADOS** - 10% do trabalho

#### Migration SQL

```sql
-- V8__add_match_voting.sql

-- Tabela de votos
CREATE TABLE match_votes (
    id BIGSERIAL PRIMARY KEY,
    match_id BIGINT NOT NULL,
    player_id BIGINT NOT NULL,
    lcu_game_id BIGINT NOT NULL,
    voted_at TIMESTAMP DEFAULT NOW(),
    CONSTRAINT unique_player_vote UNIQUE (match_id, player_id),
    CONSTRAINT fk_match FOREIGN KEY (match_id) 
        REFERENCES matches(id) ON DELETE CASCADE,
    CONSTRAINT fk_player FOREIGN KEY (player_id) 
        REFERENCES players(id) ON DELETE CASCADE
);

-- Adicionar campos em matches
ALTER TABLE matches 
    ADD COLUMN linked_lcu_game_id BIGINT,
    ADD COLUMN lcu_match_data TEXT;

-- Índices para performance
CREATE INDEX idx_match_votes_match_id ON match_votes(match_id);
CREATE INDEX idx_match_votes_lcu_game_id ON match_votes(lcu_game_id);
```

---

## 🔄 Fluxo Completo (Passo a Passo)

```
1. Jogador clica "Escolher Partida"
   ↓
2. Frontend busca GET /api/lcu/match-history
   ↓
3. Sistema filtra últimas 3 partidas customizadas
   ↓
4. Modal exibe 3 partidas com:
   - Data/hora
   - Duração
   - Times (com jogadores da partida atual destacados)
   - Vencedor
   - Contador: "0/10 votos"
   ↓
5. Jogador1 vota na Partida #2
   ↓
6. Frontend envia POST /api/match/999/vote {lcuGameId: 12345}
   ↓
7. Backend:
   - Salva voto no banco
   - Conta votos: {12345: 1, 67890: 0, 11111: 0}
   - Broadcast via WebSocket: "match_vote_update"
   ↓
8. Todos os 10 jogadores veem: "1/10 votos"
   ↓
9. Jogador2 vota na mesma partida
   ↓
10. Contador atualiza: "2/10 votos"
    ↓
... (mais jogadores votam) ...
    ↓
11. Jogador5 vota (5º voto!)
    ↓
12. Backend detecta: 5 votos atingidos!
    ↓
13. Backend:
    - Busca dados completos da partida do LCU
    - Detecta vencedor (Team 100 = blue)
    - Salva no banco:
      * linked_lcu_game_id = 12345
      * lcu_match_data = JSON completo
      * winner = "blue"
      * status = "COMPLETED"
    - Broadcast: "match_linked"
    ↓
14. Todos os jogadores recebem evento
    ↓
15. Modal fecha automaticamente
    ↓
16. Exibe: "Partida vinculada! Vencedor: Time Azul"
    ↓
17. Redirecionamento para tela de resultado ou histórico
```

---

## 📋 Checklist de Implementação

### **Backend (Java/Spring Boot)**

- [ ] 1. Criar `src/main/java/.../entity/MatchVote.java`
- [ ] 2. Criar `src/main/java/.../repository/MatchVoteRepository.java`
- [ ] 3. Criar `src/main/java/.../service/MatchVoteService.java`
  - [ ] 3.1. Método `processVote()`
  - [ ] 3.2. Método `countVotes()`
  - [ ] 3.3. Método `linkMatchAutomatically()`
- [ ] 4. Criar `src/main/java/.../controller/MatchVoteController.java`
  - [ ] 4.1. `POST /api/match/{id}/vote`
  - [ ] 4.2. `GET /api/match/{id}/votes`
  - [ ] 4.3. `DELETE /api/match/{id}/vote`
- [ ] 5. Modificar `src/main/java/.../entity/Match.java`
  - [ ] 5.1. Adicionar `linkedLcuGameId`
  - [ ] 5.2. Adicionar `lcuMatchData`
- [ ] 6. Modificar `src/main/java/.../websocket/WebSocketEventType.java`
  - [ ] 6.1. Adicionar `MATCH_VOTE_UPDATE`
  - [ ] 6.2. Adicionar `MATCH_LINKED`
- [ ] 7. Criar `src/main/resources/db/migration/V8__add_match_voting.sql`
- [ ] 8. Testar endpoints com Postman/Insomnia

### **Frontend (Angular)**

- [ ] 1. Modificar `frontend/src/app/components/winner-confirmation-modal/winner-confirmation-modal.component.ts`
  - [ ] 1.1. Adicionar `votesByMatch: Map<number, number>`
  - [ ] 1.2. Adicionar `playerVote: number | null`
  - [ ] 1.3. Adicionar `subscribeToVoteUpdates()`
  - [ ] 1.4. Adicionar `voteForMatch(lcuGameId)`
  - [ ] 1.5. Adicionar `getVoteCount(lcuGameId)`
  - [ ] 1.6. Adicionar `hasPlayerVoted(lcuGameId)`
- [ ] 2. Modificar `frontend/src/app/components/winner-confirmation-modal/winner-confirmation-modal.component.html`
  - [ ] 2.1. Adicionar contador de votos
  - [ ] 2.2. Adicionar barra de progresso
  - [ ] 2.3. Adicionar badge "Você votou aqui"
  - [ ] 2.4. Modificar botão para "Votar nesta partida"
- [ ] 3. Modificar `frontend/src/app/components/winner-confirmation-modal/winner-confirmation-modal.component.scss`
  - [ ] 3.1. Estilizar contador de votos
  - [ ] 3.2. Estilizar barra de progresso
  - [ ] 3.3. Estilizar card votado
- [ ] 4. Modificar `frontend/src/app/components/game-in-progress/game-in-progress.ts`
  - [ ] 4.1. Adicionar método `onVote()`
  - [ ] 4.2. Modificar `@Output` do modal
- [ ] 5. Modificar `frontend/src/app/services/api.ts`
  - [ ] 5.1. Adicionar `voteForMatch()`
  - [ ] 5.2. Adicionar `removeVote()`
  - [ ] 5.3. Adicionar `getMatchVotes()`
- [ ] 6. Modificar `frontend/src/app/services/websocket.service.ts`
  - [ ] 6.1. Adicionar handler para `match_vote_update`
  - [ ] 6.2. Adicionar handler para `match_linked`

### **Testes**

- [ ] 1. Testar votação com 2 jogadores
- [ ] 2. Testar votação com 10 jogadores
- [ ] 3. Testar vinculação automática (5 votos)
- [ ] 4. Testar mudança de voto
- [ ] 5. Testar sincronização via WebSocket
- [ ] 6. Testar exibição de dados no histórico

---

## ⏱️ Estimativa de Tempo

| Etapa | Tempo |
|-------|-------|
| Backend: Estrutura (entity, repository) | 1h 30min |
| Backend: Service (lógica de votação) | 2h |
| Backend: Controller (endpoints REST) | 1h 30min |
| Backend: WebSocket (eventos) | 1h |
| Frontend: Modal (votação) | 2h |
| Frontend: Integration | 30min |
| Database: Migration | 30min |
| Testes | 1h |
| **TOTAL** | **~10h** |

---

## 🎯 Próximo Passo Sugerido

**COMEÇAR POR:** Backend - Estrutura

1. Criar `MatchVote.java` entity
2. Criar `MatchVoteRepository.java`
3. Modificar `Match.java` (adicionar campos)
4. Criar migration `V8__add_match_voting.sql`

Depois seguir ordem: Service → Controller → WebSocket → Frontend → Testes

---

## 📚 Documentos Gerados

Criei 3 documentos detalhados para te ajudar:

1. **`ANALISE-SISTEMA-VOTACAO-LCU.md`** *(Análise completa)*
   - Estado atual vs. O que falta
   - Arquitetura proposta
   - Código de exemplo completo
   - Edge cases e considerações

2. **`RESUMO-SISTEMA-VOTACAO.md`** *(Resumo executivo)*
   - Checklist de implementação
   - Estimativa de tempo
   - Arquivos principais
   - Tabelas e fluxos

3. **`DIAGRAMA-FLUXO-VOTACAO.md`** *(Fluxo visual)*
   - Diagrama ASCII do modal
   - Fluxo técnico detalhado
   - Estrutura de dados
   - Casos de uso

---

## ✅ Resumo Final

**Entendi perfeitamente!** Você quer:

1. ❌ Remover declaração direta de vencedor
2. ✅ Adicionar sistema de votação democrática
3. ✅ 5 votos = vinculação automática
4. ✅ Salvar dados completos do LCU
5. ✅ Exibir no histórico como LCU

**Status:**

- ✅ 70% já está pronto (modal, busca LCU, UI base)
- ❌ 30% falta implementar (votação, backend, WebSocket)

**Tempo estimado:** ~10 horas de desenvolvimento

**Pronto para começar?** 🚀

Posso te ajudar a implementar qualquer parte específica!
