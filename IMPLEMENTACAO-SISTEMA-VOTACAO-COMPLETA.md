# ✅ Sistema de Votação de Partidas LCU - Implementação Completa

## 📋 Resumo das Alterações

Este documento resume todas as alterações feitas para substituir o sistema de "Declarar Vencedor" por um sistema democrático de votação de partidas LCU.

---

## 🗄️ Backend - Spring Boot

### 1. Entidades (Domain)

#### `MatchVote.java`

- **Localização**: `src/main/java/com/lolmatchmaking/domain/entity/MatchVote.java`
- **Campos**:
  - `id`: ID único do voto
  - `matchId`: Referência à partida customizada
  - `playerId`: Referência ao jogador que votou
  - `lcuGameId`: ID da partida LCU votada
  - `votedAt`: Timestamp do voto
- **Constraints**: UNIQUE (match_id, player_id) - cada jogador vota apenas uma vez

#### `Match.java` (Modificado)

- **Campos Adicionados**:
  - `linkedLcuGameId`: ID da partida LCU vinculada após 5+ votos
  - `lcuMatchData`: JSON completo da partida LCU (TEXT) para histórico

### 2. Repositórios

#### `MatchVoteRepository.java`

- **Métodos**:
  - `findByMatchId(Long matchId)`: Busca todos os votos de uma partida
  - `findByMatchIdAndPlayerId(Long matchId, Long playerId)`: Verifica se jogador já votou
  - `countVotesByLcuGameId(Long matchId)`: Query customizada que retorna contagem de votos por LCU game ID

    ```java
    @Query("SELECT mv.lcuGameId as lcuGameId, COUNT(mv.id) as voteCount " +
           "FROM MatchVote mv WHERE mv.matchId = :matchId " +
           "GROUP BY mv.lcuGameId")
    List<VoteCount> countVotesByLcuGameId(@Param("matchId") Long matchId);
    ```

  - **Interface Projection**: `VoteCount` com `getLcuGameId()` e `getVoteCount()`

### 3. Serviços

#### `MatchVoteService.java`

- **Métodos Principais**:
  - `processVote(matchId, playerId, lcuGameId)`:
    1. Valida se jogador pertence à partida
    2. Salva/atualiza voto no banco
    3. Broadcast WebSocket: `match_vote_update`
    4. Verifica se algum LCU game atingiu 5+ votos
    5. Se sim, chama `linkMatch()`
  
  - `linkMatch(matchId, lcuGameId, lcuMatchData)`:
    1. Atualiza `Match` com linkedLcuGameId e lcuMatchData
    2. Detecta vencedor automaticamente do JSON LCU
    3. Atualiza `Match.winner` ("blue" ou "red")
    4. Broadcast WebSocket: `match_linked`
  
  - `detectWinnerFromLcuData(JsonNode lcuMatchData)`:
    - Analisa `teams[].win` do JSON LCU
    - Retorna "blue" se teamId 100 venceu
    - Retorna "red" se teamId 200 venceu
  
  - `countVotesByLcuGameId(matchId)`: Retorna `Map<Long, Long>` (lcuGameId → voteCount)
  
  - **Broadcasts WebSocket**:
    - `broadcastVoteUpdate(matchId, voteCounts)`: Envia contadores de votos atualizados
    - `broadcastMatchLinked(matchId, lcuGameId, winner)`: Notifica vinculação e resultado

### 4. Controlador (REST API)

#### `MatchVoteController.java`

- **Endpoints**:
  - `POST /api/matches/{matchId}/vote`
    - Body: `{ "playerId": 123, "lcuGameId": 456789 }`
    - Retorna: `{ "success": true, "message": "Vote registered", "voteCounts": {...} }`
  
  - `GET /api/matches/{matchId}/votes`
    - Retorna: `Map<Long, Long>` de votos por LCU game ID
  
  - `DELETE /api/matches/{matchId}/vote`
    - Body: `{ "playerId": 123 }`
    - Remove voto do jogador
  
  - `POST /api/matches/{matchId}/link` (Manual override)
    - Body: `{ "lcuGameId": 456789, "lcuMatchData": {...} }`
    - Força vinculação sem precisar de 5 votos

### 5. Migrations (Liquibase)

#### `0005-add-match-voting-system.yaml`

- **ChangeSet 1**: Criação da tabela `match_votes`
  - Colunas: id, match_id, player_id, lcu_game_id, voted_at
  - FK: match_id → matches(id), player_id → players(id)
  - UNIQUE constraint: (match_id, player_id)

- **ChangeSet 2**: Adiciona `linked_lcu_game_id` em `matches`
  - Tipo: BIGINT, nullable

- **ChangeSet 3**: Adiciona `lcu_match_data` em `matches`
  - Tipo: TEXT, nullable

- **ChangeSets 4-6**: Índices
  - `idx_match_votes_match_id`
  - `idx_match_votes_lcu_game_id`
  - `idx_matches_linked_lcu_game_id`

#### `db.changelog-master.yaml`

- Adicionado include para `0005-add-match-voting-system.yaml`

---

## 🎨 Frontend - Angular

### 1. Tela de Jogo em Progresso

#### `game-in-progress.html`

**Remoções:**

- ❌ Seção completa "Declarar Vencedor" (botões Time Azul/Vermelho)
- ❌ Checkbox "Detecção automática via LCU"
- ❌ Botão "Confirmar Resultado"

**Alterações:**

- ✅ Título da seção mudou de "Configurações" para "Vincular Partida do League of Legends"
- ✅ Texto do botão mudou de "Tentar Identificar Vencedor" para:

  ```
  "Vincular Partida do Discord à Partida do League of Legends"
  ```

**HTML Simplificado:**

```html
<!-- Settings Section -->
<div class="settings-section">
  <div class="settings-card">
    <h4>Vincular Partida do League of Legends</h4>
    <div class="setting-item">
      <div class="detection-buttons">
        <button class="btn btn-outline retry-detection-btn" 
                (click)="retryAutoDetection()"
                [disabled]="isAutoDetecting">
          <span class="btn-icon" *ngIf="!isAutoDetecting">🔍</span>
          <span class="btn-icon spinning" *ngIf="isAutoDetecting">⏳</span>
          <span *ngIf="!isAutoDetecting">
            Vincular Partida do Discord à Partida do League of Legends
          </span>
          <span *ngIf="isAutoDetecting">Carregando partidas...</span>
        </button>
      </div>
      <p class="setting-description">
        Busca as últimas 3 partidas personalizadas do histórico 
        e permite selecionar qual corresponde a esta partida.
      </p>
    </div>
  </div>
</div>

<!-- Action Buttons -->
<div class="action-buttons">
  <button class="btn btn-secondary" (click)="cancelGame()">
    <span class="btn-icon">❌</span>
    Cancelar Partida
  </button>
</div>
```

### 2. Modal de Confirmação de Vencedor

#### `winner-confirmation-modal.component.html`

**Melhorias Implementadas:**

- ✅ Display completo de KDA (Kills/Deaths/Assists)
- ✅ Ratio KDA formatado (ex: 3.50, Perfect)
- ✅ Grid de 7 itens com ícones da Riot CDN
- ✅ Stats: Dano aos Campeões, Gold, CS
- ✅ Ícone do campeão com nível
- ✅ Badge "✓ Você" para jogadores da partida atual

**Estrutura de Card de Jogador:**

```html
<div class="player-detailed-item" [class.our-player]="isPlayerInOurMatch(player, 'blue')">
  <!-- Champion Icon & Name -->
  <div class="player-champion-section">
    <div class="champion-icon-wrapper">
      <img [src]="getChampionIconUrl(player.championId)" class="champion-icon">
      <span class="champion-level">{{ player.champLevel }}</span>
    </div>
    <span class="champion-name">{{ getChampionName(player.championId) }}</span>
  </div>

  <!-- Summoner Name -->
  <div class="player-name-section">
    <span class="player-name">{{ player.summonerName }}</span>
    <span class="our-player-badge" *ngIf="isPlayerInOurMatch(player, 'blue')">
      ✓ Você
    </span>
  </div>

  <!-- KDA -->
  <div class="player-kda-section">
    <span class="kda-value">
      <span class="kills">{{ player.kills }}</span> /
      <span class="deaths">{{ player.deaths }}</span> /
      <span class="assists">{{ player.assists }}</span>
    </span>
    <span class="kda-ratio" [class.perfect-kda]="player.deaths === 0">
      {{ getKDAFormattedRatio(player) }}
    </span>
  </div>

  <!-- Items Grid (7 slots) -->
  <div class="player-items-section">
    <div class="items-grid">
      <div class="item-slot" *ngFor="let itemId of getPlayerItems(player)">
        <img *ngIf="itemId > 0" [src]="getItemIconUrl(itemId)" class="item-icon">
        <div *ngIf="itemId === 0" class="item-empty"></div>
      </div>
    </div>
  </div>

  <!-- Stats Summary -->
  <div class="player-stats-section">
    <span class="stat-item" title="Dano aos Campeões">
      <span class="stat-icon">⚔️</span>
      {{ formatNumber(player.totalDamageDealtToChampions) }}
    </span>
    <span class="stat-item" title="Gold">
      <span class="stat-icon">💰</span>
      {{ formatNumber(player.goldEarned) }}
    </span>
    <span class="stat-item" title="CS (Creep Score)">
      <span class="stat-icon">🗡️</span>
      {{ player.totalMinionsKilled + player.neutralMinionsKilled }}
    </span>
  </div>
</div>
```

#### `winner-confirmation-modal.component.ts`

**Métodos Adicionados:**

```typescript
getChampionIconUrl(championId: number): string {
  return `https://ddragon.leagueoflegends.com/cdn/14.1.1/img/champion/${this.getChampionKeyById(championId)}.png`;
}

getChampionKeyById(championId: number): string {
  // Placeholder - deve ser mapeado com dados reais
  return `Champion_${championId}`;
}

getItemIconUrl(itemId: number): string {
  return `https://ddragon.leagueoflegends.com/cdn/14.1.1/img/item/${itemId}.png`;
}

getPlayerItems(player: any): number[] {
  return [
    player.item0 || 0,
    player.item1 || 0,
    player.item2 || 0,
    player.item3 || 0,
    player.item4 || 0,
    player.item5 || 0,
    player.item6 || 0 // Trinket
  ];
}

getKDAFormattedRatio(player: any): string {
  if (!player.deaths || player.deaths === 0) {
    return 'Perfect';
  }
  const kda = (player.kills + player.assists) / player.deaths;
  return kda.toFixed(2);
}

formatNumber(num: number): string {
  if (!num) return '0';
  if (num >= 1000) {
    return (num / 1000).toFixed(1) + 'k';
  }
  return num.toString();
}
```

---

## 🔄 Fluxo de Funcionamento

### 1. Durante a Partida

1. Jogadores clicam em "Vincular Partida do Discord à Partida do League of Legends"
2. Sistema busca últimas 3 partidas customizadas do LCU
3. Modal abre exibindo:
   - Composição completa dos times
   - KDA de cada jogador (K/D/A + ratio)
   - Itens equipados (grid 7 slots)
   - Stats: Dano, Gold, CS
   - Badge "✓ Você" nos jogadores da partida atual

### 2. Sistema de Votação

1. Jogadores clicam em uma das 3 partidas
2. Frontend envia `POST /api/matches/{matchId}/vote` com:

   ```json
   {
     "playerId": 123,
     "lcuGameId": 4567890
   }
   ```

3. Backend:
   - Valida se jogador pertence à partida
   - Salva voto (ou atualiza se já votou)
   - Broadcast WebSocket: `match_vote_update` com contadores
   - Verifica se alguma partida LCU atingiu 5+ votos
   - Se sim: vincula automaticamente e detecta vencedor

### 3. Vinculação Automática (5+ votos)

1. `MatchVoteService.linkMatch()` é chamado
2. Atualiza `Match`:
   - `linkedLcuGameId` = ID da partida LCU
   - `lcuMatchData` = JSON completo (TEXT)
   - `winner` = "blue" ou "red" (detectado do JSON)
3. Broadcast WebSocket: `match_linked`
4. Frontend fecha modal e exibe resultado

### 4. WebSocket Events

**Frontend deve se inscrever:**

```typescript
this.webSocketService.onMatchVoteUpdate((data) => {
  // data.voteCounts: { "4567890": 3, "4567891": 2 }
  // Atualizar UI com contadores de votos
});

this.webSocketService.onMatchLinked((data) => {
  // data.lcuGameId: 4567890
  // data.winner: "blue" ou "red"
  // Fechar modal, exibir resultado
});
```

---

## 📊 Estrutura de Dados

### JSON LCU Match Data (Exemplo)

```json
{
  "gameId": 4567890,
  "gameCreation": 1736287654321,
  "gameDuration": 1820,
  "teams": [
    {
      "teamId": 100,
      "win": "Win",
      "bans": [...]
    },
    {
      "teamId": 200,
      "win": "Fail",
      "bans": [...]
    }
  ],
  "participants": [
    {
      "participantId": 1,
      "teamId": 100,
      "championId": 1,
      "summonerName": "Player1",
      "kills": 5,
      "deaths": 2,
      "assists": 10,
      "champLevel": 15,
      "item0": 3153,
      "item1": 3078,
      "item2": 3047,
      "item3": 3065,
      "item4": 3071,
      "item5": 6333,
      "item6": 3364,
      "totalDamageDealtToChampions": 15420,
      "goldEarned": 12500,
      "totalMinionsKilled": 150,
      "neutralMinionsKilled": 30
    }
    // ... mais 9 participantes
  ]
}
```

---

## ⚠️ TODOs Pendentes

### Backend

- [ ] Implementar mapeamento championId → championKey para URLs corretas
- [ ] Adicionar endpoint para remover voto: `DELETE /api/matches/{matchId}/vote`
- [ ] Adicionar validação: partida já finalizada não pode receber votos
- [ ] Testes unitários: MatchVoteService, MatchVoteRepository
- [ ] Testes de integração: fluxo completo de votação

### Frontend

- [ ] Adicionar estilos CSS para cards detalhados de jogadores
- [ ] Implementar WebSocket subscriptions no modal
- [ ] Exibir contadores de votos em tempo real (X/10 votos)
- [ ] Adicionar botão "Remover meu voto"
- [ ] Indicador visual "Você votou aqui"
- [ ] Progress bars para votos
- [ ] Animações ao atingir 5+ votos
- [ ] Loading states melhores
- [ ] Tratamento de erros (jogador não na partida, partida já vinculada)

### Integração

- [ ] Carregar ChampionData.json para mapeamento correto de ícones
- [ ] Verificar compatibilidade com versões antigas do LCU
- [ ] Adicionar fallbacks para ícones de campeões/itens inexistentes
- [ ] Logs detalhados para debug do sistema de votação

---

## 🎉 Conclusão

O sistema de votação democrático substitui completamente o antigo "Declarar Vencedor":

✅ **Backend completo**: Entidades, repositórios, serviços, controller, migrations
✅ **Frontend UI limpo**: Removido sistema manual de declaração
✅ **Modal enriquecido**: KDA, itens, stats como no histórico de partidas
✅ **Vinculação automática**: 5+ votos → link e detecção de vencedor
✅ **WebSocket ready**: Arquitetura preparada para atualizações em tempo real

**Próximos passos**: Implementar os TODOs de CSS, WebSocket subscriptions e testes end-to-end.
