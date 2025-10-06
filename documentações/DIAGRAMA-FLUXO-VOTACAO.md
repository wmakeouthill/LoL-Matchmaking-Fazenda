# 🔄 Diagrama de Fluxo: Sistema de Votação de Partidas LCU

## 📊 Fluxo Completo - Visão Geral

```
┌─────────────────────────────────────────────────────────────────────────┐
│                          GAME IN PROGRESS                                │
│                                                                           │
│  [Status: Em andamento]                                                   │
│  [Tempo: 25:30]                                                          │
│                                                                           │
│  ┌─────────────────────────────────────────────────────────────┐        │
│  │                   Time Azul vs Time Vermelho                 │        │
│  │                                                               │        │
│  │  Jogador1 (Yasuo)     │  Jogador6 (Darius)                  │        │
│  │  Jogador2 (Lux)       │  Jogador7 (Ahri)                    │        │
│  │  Jogador3 (Thresh)    │  Jogador8 (Leona)                   │        │
│  │  Jogador4 (Jinx)      │  Jogador9 (Caitlyn)                 │        │
│  │  Jogador5 (Lee Sin)   │  Jogador10 (Vi)                     │        │
│  └─────────────────────────────────────────────────────────────┘        │
│                                                                           │
│  ┌─────────────────────────────────────────────────────────────┐        │
│  │                                                               │        │
│  │        [🔍 Escolher Partida do LCU]  ◄─── CLIQUE             │        │
│  │                                                               │        │
│  └─────────────────────────────────────────────────────────────┘        │
│                                                                           │
└─────────────────────────────────────────────────────────────────────────┘
                                    │
                                    ↓
┌─────────────────────────────────────────────────────────────────────────┐
│                   MODAL: Escolher Partida do LCU                         │
├─────────────────────────────────────────────────────────────────────────┤
│                                                                           │
│  Selecione qual das últimas partidas corresponde ao jogo atual:         │
│                                                                           │
│  ┌───────────────────────────────────────────────────────────────┐      │
│  │ 📋 Partida #1                          ⏱️ 5 min atrás         │      │
│  │                                                                 │      │
│  │ 🗳️ 0/10 votos  [░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░] 0%          │      │
│  │                                                                 │      │
│  │ Vencedor: Time Azul 🏆                                          │      │
│  │                                                                 │      │
│  │ ┌─────────────────┐          ┌─────────────────┐              │      │
│  │ │ Time Azul       │          │ Time Vermelho   │              │      │
│  │ │ • Yasuo ✓       │   VS     │ • Darius ✓      │              │      │
│  │ │ • Lux ✓         │          │ • Ahri ✓        │              │      │
│  │ │ • Thresh ✓      │          │ • Leona ✓       │              │      │
│  │ │ • Jinx ✓        │          │ • Caitlyn ✓     │              │      │
│  │ │ • Lee Sin ✓     │          │ • Vi ✓          │              │      │
│  │ └─────────────────┘          └─────────────────┘              │      │
│  │                                                                 │      │
│  │ Jogadores da nossa partida: 10/10                              │      │
│  │                                                                 │      │
│  │                    [🗳️ Votar nesta partida]  ◄─── CLIQUE      │      │
│  │                                                                 │      │
│  └───────────────────────────────────────────────────────────────┘      │
│                                    │                                      │
│                                    ↓ JOGADOR VOTA                        │
│                                                                           │
│  ┌───────────────────────────────────────────────────────────────┐      │
│  │ 📋 Partida #1                          ⏱️ 5 min atrás         │      │
│  │                                                                 │      │
│  │ 🗳️ 1/10 votos  [███░░░░░░░░░░░░░░░░░░░░░░░░░░░░░] 10%        │      │
│  │ ✓ Você votou aqui                                              │      │
│  │                                                                 │      │
│  │ Vencedor: Time Azul 🏆                                          │      │
│  │ ...                                                             │      │
│  │                    [✓ Remover voto]                             │      │
│  └───────────────────────────────────────────────────────────────┘      │
│                                                                           │
└─────────────────────────────────────────────────────────────────────────┘
```

---

## 🔄 Fluxo Técnico Detalhado

### **1. Abertura do Modal**

```
Jogador1 clica "Escolher Partida"
       ↓
[Frontend] game-in-progress.component.ts
       ↓
   retryAutoDetection() {
       this.isAutoDetecting = true;
       
       // Buscar histórico do LCU
       const history = await this.apiService
           .getLCUMatchHistoryAll(0, 50, false);
       
       // Filtrar apenas custom matches
       const customMatches = history.matches
           .filter(m => m.queueId === 0);
       
       // Pegar últimas 3
       this.customMatchesForConfirmation = 
           customMatches.slice(0, 3);
       
       // Abrir modal
       this.showWinnerConfirmationModal = true;
       this.isAutoDetecting = false;
   }
       ↓
[Frontend] winner-confirmation-modal.component.ts
       ↓
   ngOnInit() {
       this.processMatches();
       this.subscribeToVoteUpdates();
   }
       ↓
   subscribeToVoteUpdates() {
       // Escutar eventos WebSocket
       this.ws.on('match_vote_update', ...);
       this.ws.on('match_linked', ...);
   }
       ↓
[UI] Modal exibe 3 partidas com contadores zerados
```

---

### **2. Jogador Vota**

```
Jogador1 clica "Votar nesta partida" (lcuGameId: 12345)
       ↓
[Frontend] winner-confirmation-modal.component.ts
       ↓
   voteForMatch(lcuGameId: 12345) {
       this.onVote.emit({ lcuGameId: 12345 });
       this.playerVote = 12345;
   }
       ↓
[Frontend] game-in-progress.component.ts
       ↓
   onVote(data: { lcuGameId: 12345 }) {
       this.apiService.voteForMatch(
           this.gameData.matchId,  // 999
           data.lcuGameId          // 12345
       ).subscribe();
   }
       ↓
[HTTP] POST /api/match/999/vote
       Body: { "lcuGameId": 12345, "playerId": 1 }
       ↓
[Backend] MatchVoteController.vote()
       ↓
   1. Validar: jogador pertence à partida?
   2. Salvar voto no banco (substituir se já votou)
   3. Contar votos por lcuGameId
   4. Verificar se algum atingiu 5 votos
   5. Broadcast via WebSocket
       ↓
[Backend] MatchVoteService.processVote()
       ↓
   processVote(matchId: 999, playerId: 1, lcuGameId: 12345) {
       // Salvar
       MatchVote vote = new MatchVote();
       vote.setMatchId(999);
       vote.setPlayerId(1);
       vote.setLcuGameId(12345);
       matchVoteRepository.save(vote);
       
       // Contar
       Map<Long, Long> votes = countVotes(999);
       // votes = { 12345: 1, 67890: 0, 11111: 0 }
       
       // Broadcast
       webSocketService.sendToMatch(999,
           new WebSocketMessage(
               "match_vote_update",
               votes
           ));
   }
       ↓
[WebSocket] Broadcast para todos os 10 jogadores
       ↓
[Frontend] winner-confirmation-modal.component.ts
       ↓
   // Todos recebem atualização
   this.ws.on('match_vote_update', (data) => {
       this.votesByMatch = new Map(Object.entries(data));
       // votesByMatch = { 12345: 1, 67890: 0, 11111: 0 }
   });
       ↓
[UI] Contador atualiza para "1/10 votos"
```

---

### **3. Mais Jogadores Votam**

```
Jogador2 vota: lcuGameId 12345
       ↓
[Backend] votes = { 12345: 2, 67890: 0, 11111: 0 }
       ↓
[WebSocket] Broadcast
       ↓
[UI] "2/10 votos"

Jogador3 vota: lcuGameId 12345
       ↓
[Backend] votes = { 12345: 3, 67890: 0, 11111: 0 }
       ↓
[UI] "3/10 votos"

Jogador4 vota: lcuGameId 12345
       ↓
[Backend] votes = { 12345: 4, 67890: 0, 11111: 0 }
       ↓
[UI] "4/10 votos"

Jogador5 vota: lcuGameId 12345
       ↓
[Backend] votes = { 12345: 5, 67890: 0, 11111: 0 }
       ↓
⚡ 5 VOTOS ATINGIDOS! VINCULAÇÃO AUTOMÁTICA
```

---

### **4. Vinculação Automática**

```
[Backend] MatchVoteService.processVote()
       ↓
   // Detectar 5 votos
   Optional<Long> winner = votes.entrySet().stream()
       .filter(e -> e.getValue() >= 5)
       .map(Map.Entry::getKey)
       .findFirst();
   
   if (winner.isPresent()) {
       linkMatchAutomatically(matchId, winner.get());
   }
       ↓
[Backend] MatchVoteService.linkMatchAutomatically()
       ↓
   linkMatchAutomatically(matchId: 999, lcuGameId: 12345) {
       // 1. Buscar dados completos da partida do LCU
       LcuMatchDetails details = lcuService.getMatchDetails(12345);
       
       // 2. Detectar vencedor
       String winner = detectWinner(details);
       // winner = "blue" (Team 100 ganhou)
       
       // 3. Atualizar Match no banco
       Match match = matchRepository.findById(999).get();
       match.setLinkedLcuGameId(12345);
       match.setLcuMatchData(JSON.stringify(details));
       match.setWinner(winner);
       match.setStatus("COMPLETED");
       matchRepository.save(match);
       
       // 4. Broadcast conclusão
       webSocketService.sendToMatch(999,
           new WebSocketMessage(
               "match_linked",
               Map.of(
                   "lcuGameId", 12345,
                   "winner", "blue",
                   "message", "Partida vinculada com sucesso!"
               )
           ));
   }
       ↓
[WebSocket] Broadcast para todos
       ↓
[Frontend] winner-confirmation-modal.component.ts
       ↓
   this.ws.on('match_linked', (data) => {
       // Fechar modal
       this.onCancel.emit();
       
       // Exibir notificação
       this.showSuccess(
           `Partida vinculada! Vencedor: ${data.winner}`
       );
   });
       ↓
[Frontend] game-in-progress.component.ts
       ↓
   // Modal fechado, voltar para tela de resultado
   // Evento onGameComplete já foi emitido pelo backend
       ↓
[UI] Redirecionar para tela de resultado ou histórico
```

---

## 🗄️ Estrutura de Dados

### **Banco de Dados**

```sql
-- Tabela de votos
CREATE TABLE match_votes (
    id BIGSERIAL PRIMARY KEY,
    match_id BIGINT NOT NULL,         -- ID da partida customizada
    player_id BIGINT NOT NULL,        -- ID do jogador
    lcu_game_id BIGINT NOT NULL,      -- ID da partida do LCU votada
    voted_at TIMESTAMP DEFAULT NOW(),
    CONSTRAINT unique_player_vote UNIQUE (match_id, player_id)
);

-- Exemplo de dados
INSERT INTO match_votes VALUES
    (1, 999, 1, 12345, NOW()),  -- Jogador1 votou em 12345
    (2, 999, 2, 12345, NOW()),  -- Jogador2 votou em 12345
    (3, 999, 3, 67890, NOW()),  -- Jogador3 votou em 67890
    (4, 999, 4, 12345, NOW()),  -- Jogador4 votou em 12345
    (5, 999, 5, 12345, NOW());  -- Jogador5 votou em 12345

-- Consulta de contagem
SELECT lcu_game_id, COUNT(*) as votes
FROM match_votes
WHERE match_id = 999
GROUP BY lcu_game_id;

-- Resultado:
-- 12345 | 4
-- 67890 | 1
```

```sql
-- Adicionar campos em matches
ALTER TABLE matches 
    ADD COLUMN linked_lcu_game_id BIGINT,
    ADD COLUMN lcu_match_data TEXT;

-- Exemplo após vinculação
UPDATE matches 
SET 
    linked_lcu_game_id = 12345,
    lcu_match_data = '{
        "gameId": 12345,
        "gameCreation": 1696000000000,
        "gameDuration": 1830,
        "participants": [...],
        "teams": [
            {"teamId": 100, "win": true},
            {"teamId": 200, "win": false}
        ]
    }',
    winner = 'blue',
    status = 'COMPLETED'
WHERE id = 999;
```

---

### **Mensagens WebSocket**

#### **match_vote_update**

```json
{
    "type": "match_vote_update",
    "data": {
        "votes": {
            "12345": 4,
            "67890": 1,
            "11111": 0
        },
        "total": 5
    }
}
```

#### **match_linked**

```json
{
    "type": "match_linked",
    "data": {
        "lcuGameId": 12345,
        "winner": "blue",
        "message": "Partida vinculada com sucesso!",
        "matchDetails": {
            "gameCreation": 1696000000000,
            "gameDuration": 1830
        }
    }
}
```

---

## 🎯 Casos de Uso

### **Caso 1: Votação Bem-Sucedida**

- 5+ jogadores votam na mesma partida
- Sistema vincula automaticamente
- Todos redirecionados para resultado

### **Caso 2: Empate de Votos**

- Partida A: 4 votos
- Partida B: 4 votos
- Solução: Esperar 5º voto de qualquer uma

### **Caso 3: Ninguém Vota**

- Fallback: Manter botão "Declarar Vencedor" manual
- Ou: Timeout de 5 minutos → declaração manual

### **Caso 4: Jogador Muda de Voto**

- Jogador1 votou em Partida A
- Clica em Partida B
- Voto é transferido (UPDATE no banco)

### **Caso 5: Partida Não Aparece**

- Botão "Carregar Mais Partidas"
- Buscar próximas 3 partidas (offset + 3)

---

## 📊 Diagrama de Entidades

```
┌──────────────┐         ┌──────────────┐         ┌──────────────┐
│   matches    │         │ match_votes  │         │   players    │
├──────────────┤         ├──────────────┤         ├──────────────┤
│ id (PK)      │◄────────┤ match_id (FK)│         │ id (PK)      │
│ status       │         │ player_id    ├────────►│ summoner_name│
│ winner       │         │ lcu_game_id  │         │ ...          │
│ linked_lcu_  │         │ voted_at     │         └──────────────┘
│   game_id    │         └──────────────┘
│ lcu_match_   │
│   data       │
└──────────────┘
       │
       │ (linked_lcu_game_id)
       ↓
┌──────────────────────┐
│  Partida do LCU      │
│  (dados externos)    │
├──────────────────────┤
│ gameId: 12345        │
│ gameCreation: ...    │
│ gameDuration: 1830   │
│ participants: [...]  │
│ teams: [...]         │
└──────────────────────┘
```

---

## ✅ Resumo Final

1. **Jogador clica** "Escolher Partida"
2. **Sistema busca** últimas 3 custom do LCU
3. **Modal exibe** partidas com contadores zerados
4. **Jogadores votam** (via WebSocket todos veem)
5. **5º voto** → vinculação automática
6. **Sistema salva** dados completos do LCU
7. **Todos redirecionados** para resultado

**Tempo estimado:** 8 horas de desenvolvimento
**Prioridade:** ALTA (funcionalidade core)
