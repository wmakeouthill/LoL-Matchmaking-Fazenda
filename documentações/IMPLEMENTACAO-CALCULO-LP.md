# Implementação do Sistema de Cálculo de LP (League Points)

## Resumo

Foi implementado o sistema de cálculo de LP (League Points) para partidas customizadas no novo backend Spring, baseado na lógica do old backend Node.js/TypeScript.

## O que foi implementado

### 1. **LPCalculationService** (Novo Serviço)

Localização: `src/main/java/br/com/lolmatchmaking/backend/service/LPCalculationService.java`

Este serviço centraliza toda a lógica de cálculo de LP baseada no sistema ELO/MMR:

#### Principais métodos

- **`calculateLPChange(int playerMMR, int opponentMMR, boolean isWin)`**
  - Calcula o LP ganho ou perdido por um jogador individual
  - Usa a fórmula ELO: `K_FACTOR * (actualScore - expectedScore)`
  - K_FACTOR = 32 (padrão)
  - Expected score é calculado com: `1 / (1 + 10^((opponentMMR - playerMMR) / 400))`

- **`calculateMatchLPChanges(List<String> team1Players, List<String> team2Players, int winnerTeam)`**
  - Calcula as mudanças de LP para todos os jogadores de uma partida
  - Retorna um Map com nome do jogador como chave e LP ganho/perdido como valor
  - Calcula o MMR médio de cada time e usa isso para determinar o LP de cada jogador

- **`updatePlayerStats(String playerName, int lpChange, boolean isWin)`**
  - Atualiza as estatísticas do jogador após uma partida:
    - `custom_lp`: LP acumulado (soma/subtrai o lpChange)
    - `custom_mmr`: MMR customizado (currentMmr + custom_lp)
    - `custom_games_played`: Incrementa +1
    - `custom_wins` ou `custom_losses`: Incrementa conforme resultado
    - `custom_peak_mmr`: Atualiza se o novo MMR for maior

### 2. **Integração com MatchVoteService**

O serviço `MatchVoteService` foi atualizado para calcular e salvar o LP automaticamente quando uma partida é vinculada ao resultado do LCU:

```java
// Após determinar o time vencedor
if (match.getWinnerTeam() != null && match.getWinnerTeam() > 0) {
    // Extrair jogadores dos times
    List<String> team1Players = parsePlayerList(match.getTeam1PlayersJson());
    List<String> team2Players = parsePlayerList(match.getTeam2PlayersJson());
    
    // Calcular mudanças de LP
    Map<String, Integer> lpChanges = lpCalculationService.calculateMatchLPChanges(
        team1Players, team2Players, match.getWinnerTeam()
    );
    
    // Salvar LP changes na partida
    match.setLpChanges(objectMapper.writeValueAsString(lpChanges));
    
    // Calcular LP total
    int totalLp = lpCalculationService.calculateTotalMatchLP(lpChanges);
    match.setCustomLp(totalLp);
    
    // Atualizar estatísticas de cada jogador
    for (Map.Entry<String, Integer> entry : lpChanges.entrySet()) {
        String playerName = entry.getKey();
        Integer lpChange = entry.getValue();
        boolean playerWon = // ... determinar se jogador venceu
        
        lpCalculationService.updatePlayerStats(playerName, lpChange, playerWon);
    }
}
```

### 3. **Método Auxiliar `parsePlayerList`**

Foi adicionado ao `MatchVoteService` para converter o JSON de jogadores em uma lista de strings:

```java
private List<String> parsePlayerList(String playersJson) {
    // Parseia ["Player1#BR1", "Player2#BR1", ...] 
    // e retorna List<String>
}
```

## Como funciona o sistema de LP

### Sistema Balanceado (ELO)

O sistema usa a fórmula ELO para garantir que:

1. **Jogadores com MMR alto ganham menos LP ao vencer** e perdem mais ao perder
2. **Jogadores com MMR baixo ganham mais LP ao vencer** e perdem menos ao perder
3. **A diferença de MMR entre times afeta o ganho/perda de LP**

### Exemplo Prático

**Cenário 1: Time equilibrado**

- Time 1 MMR médio: 1500
- Time 2 MMR médio: 1500
- Jogador no Time 1 com MMR 1500 vence: +16 LP (aproximadamente)

**Cenário 2: Underdog vence**

- Time 1 MMR médio: 1200 (underdog)
- Time 2 MMR médio: 1800 (favorito)
- Jogador no Time 1 com MMR 1200 vence: +28 LP (maior ganho)

**Cenário 3: Favorito vence**

- Time 1 MMR médio: 1800 (favorito)
- Time 2 MMR médio: 1200 (underdog)
- Jogador no Time 1 com MMR 1800 vence: +4 LP (menor ganho)

## Estrutura de Dados

### Tabela `players`

- `custom_lp`: LP acumulado do jogador (soma de todos os ganhos/perdas)
- `custom_mmr`: MMR customizado calculado como `current_mmr + custom_lp`
- `custom_games_played`: Total de partidas customizadas jogadas
- `custom_wins`: Vitórias em partidas customizadas
- `custom_losses`: Derrotas em partidas customizadas
- `custom_peak_mmr`: MMR máximo atingido pelo jogador

### Tabela `custom_matches`

- `lp_changes`: JSON com LP ganho/perdido por cada jogador

  ```json
  {
    "Player1#BR1": 15,
    "Player2#BR1": -12,
    ...
  }
  ```

- `custom_lp`: Soma absoluta de todos os LPs da partida (usado para estatísticas)

## Diferenças do Old Backend

### Semelhanças

- Mesma fórmula ELO (K_FACTOR = 32)
- Mesmo cálculo de expected score
- Mesma lógica de atualização de estatísticas

### Melhorias

- Código mais organizado em serviço dedicado
- Melhor separação de responsabilidades
- Logs mais detalhados para debug
- Tratamento de erros robusto (não falha a vinculação se LP falhar)

## Configurações

Atualmente, o sistema usa valores fixos:

- `K_FACTOR = 32`
- `DEFAULT_MMR = 1000`

No futuro, estes valores podem ser movidos para a tabela `settings` se necessário ter configurações dinâmicas.

## Logs

O sistema gera logs detalhados:

```
🔄 Calculando LP changes para a partida 123
📊 MMR médio - Time 1: 1450, Time 2: 1520
👤 Player1#BR1 (MMR: 1500) vs Time 2 (MMR: 1520) - VITÓRIA : +15 LP
👤 Player2#BR1 (MMR: 1400) vs Time 2 (MMR: 1520) - VITÓRIA : +18 LP
...
✅ LP changes calculados: 10 jogadores afetados, LP total: 152
✅ Jogador Player1#BR1 atualizado: LP +15 (total: 115), MMR: 1515
```

## Testes Recomendados

1. **Teste básico**: Partida com times equilibrados (MMR similar)
2. **Teste underdog**: Time com MMR baixo vence time com MMR alto
3. **Teste favorito**: Time com MMR alto vence time com MMR baixo
4. **Teste extremos**: Diferenças grandes de MMR (ex: 800 vs 1800)
5. **Teste novatos**: Jogadores sem MMR customizado ainda (usa DEFAULT_MMR)

## Próximos Passos (Opcionais)

1. Adicionar configurações na tabela `settings` para K_FACTOR e DEFAULT_MMR
2. Implementar sistema de win_streak (sequência de vitórias) que pode afetar LP
3. Adicionar endpoints REST para visualizar histórico de LP de um jogador
4. Implementar gráficos de evolução de LP/MMR no frontend

## Conclusão

O sistema de cálculo de LP foi implementado com sucesso, seguindo a mesma lógica do old backend, mas com melhorias de organização e manutenibilidade. O LP agora é calculado e salvo automaticamente quando uma partida é confirmada e vinculada ao resultado do LCU.
