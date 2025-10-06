# Correção: Cálculo de LP no GameInProgressService

## Problema Identificado

Quando uma partida era finalizada através do modal de confirmação de winner (via `GameInProgressService.finishGame`), o campo `custom_lp` na tabela `custom_matches` estava sendo salvo como `NULL` em vez de calcular os pontos baseados no `custom_mmr` dos jogadores.

## Causa

O método `finishGame` no `GameInProgressService` apenas salvava o `winner_team` mas **não chamava** o `LPCalculationService` para calcular e atualizar os LPs dos jogadores.

## Solução Implementada

### 1. Injeção do LPCalculationService

Adicionado o `LPCalculationService` como dependência no `GameInProgressService`:

```java
@RequiredArgsConstructor
public class GameInProgressService {
    // ... outras dependências
    private final LPCalculationService lpCalculationService;
}
```

### 2. Atualização do método `finishGame`

O método foi atualizado para calcular o LP após determinar o time vencedor:

```java
@Transactional
public void finishGame(Long matchId, Integer winnerTeam, String endReason) {
    // ... código existente para salvar winner_team
    
    // ✅ NOVO: Calcular LP changes para todos os jogadores
    if (winnerTeam != null && winnerTeam > 0) {
        try {
            // Extrair listas de jogadores dos times
            List<String> team1Players = parsePlayerList(match.getTeam1PlayersJson());
            List<String> team2Players = parsePlayerList(match.getTeam2PlayersJson());
            
            // Calcular mudanças de LP
            Map<String, Integer> lpChanges = lpCalculationService.calculateMatchLPChanges(
                team1Players, team2Players, winnerTeam
            );
            
            // Salvar LP changes na partida
            if (!lpChanges.isEmpty()) {
                match.setLpChangesJson(objectMapper.writeValueAsString(lpChanges));
                
                // Calcular LP total da partida
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
        } catch (Exception lpError) {
            log.error("❌ Erro ao calcular LP changes: {}", lpError.getMessage(), lpError);
            // Não falhar a finalização por erro no cálculo de LP
        }
    }
    
    customMatchRepository.save(match);
}
```

### 3. Método auxiliar `parsePlayerList`

Adicionado método para converter JSON de jogadores em lista de strings:

```java
private List<String> parsePlayerList(String playersJson) {
    try {
        if (playersJson == null || playersJson.trim().isEmpty()) {
            return Collections.emptyList();
        }
        
        com.fasterxml.jackson.databind.JsonNode playersNode = objectMapper.readTree(playersJson);
        List<String> playerNames = new ArrayList<>();
        
        if (playersNode.isArray()) {
            for (com.fasterxml.jackson.databind.JsonNode playerNode : playersNode) {
                if (playerNode.isTextual()) {
                    playerNames.add(playerNode.asText());
                }
            }
        }
        
        return playerNames;
    } catch (Exception e) {
        log.error("❌ Erro ao parsear lista de jogadores: {}", e.getMessage(), e);
        return Collections.emptyList();
    }
}
```

## Resultado

Agora, quando uma partida é finalizada através do modal de confirmação de winner:

1. ✅ O `custom_lp` é calculado automaticamente baseado no `custom_mmr` dos jogadores
2. ✅ O valor é salvo na coluna `custom_lp` da tabela `custom_matches`
3. ✅ Os `lp_changes` são salvos em JSON com o LP de cada jogador
4. ✅ As estatísticas de cada jogador são atualizadas na tabela `players`:
   - `custom_lp`: LP acumulado
   - `custom_mmr`: MMR customizado (current_mmr + custom_lp)
   - `custom_games_played`: Incrementado
   - `custom_wins` ou `custom_losses`: Incrementado conforme resultado
   - `custom_peak_mmr`: Atualizado se necessário

## Fluxos que Calculam LP

Agora o LP é calculado em **dois fluxos diferentes**:

### 1. Via MatchVoteService (Votação LCU)

Quando os jogadores votam no gameId do LCU e a partida é vinculada:

- Método: `MatchVoteService.linkMatch()`
- Trigger: Votação dos jogadores + vinculação com LCU

### 2. Via GameInProgressService (Modal de Winner)

Quando o líder seleciona o winner no modal de confirmação:

- Método: `GameInProgressService.finishGame()`
- Trigger: Confirmação manual do winner pelo líder da partida

## Tratamento de Erros

A lógica de cálculo de LP está envolvida em try-catch para garantir que:

- ❌ **Erros no cálculo de LP não impedem a finalização da partida**
- ✅ **Erros são logados** para facilitar debug
- ✅ **A partida é salva** mesmo se o cálculo de LP falhar

## Logs Gerados

Durante a finalização com cálculo de LP, você verá logs como:

```
🔄 Calculando LP changes para a partida 123
📊 MMR médio - Time 1: 1450, Time 2: 1520
👤 Player1#BR1 (MMR: 1500) vs Time 2 (MMR: 1520) - VITÓRIA : +15 LP
👤 Player2#BR1 (MMR: 1400) vs Time 2 (MMR: 1520) - VITÓRIA : +18 LP
...
✅ LP changes calculados: 10 jogadores afetados, LP total: 152
✅ Jogador Player1#BR1 atualizado: LP +15 (total: 115), MMR: 1515
✅ Jogo finalizado para partida 123: Team 1 venceu - motivo: victory
```

## Testes Recomendados

1. ✅ Finalizar partida pelo modal de winner (GameInProgressService)
2. ✅ Verificar que `custom_lp` não é mais NULL
3. ✅ Verificar que `lp_changes` contém JSON com LP de cada jogador
4. ✅ Verificar que tabela `players` foi atualizada corretamente
5. ✅ Testar com times de MMR equilibrado
6. ✅ Testar com times de MMR desbalanceado (underdog)

## Arquivos Modificados

- ✅ `GameInProgressService.java` - Adicionado cálculo de LP no método `finishGame`
- ✅ `GameInProgressService.java` - Adicionado método auxiliar `parsePlayerList`
- ✅ `GameInProgressService.java` - Injetado `LPCalculationService`

## Conclusão

O bug foi corrigido. Agora o `custom_lp` será calculado e salvo corretamente sempre que uma partida for finalizada, independente do fluxo utilizado (votação LCU ou modal de winner manual).
