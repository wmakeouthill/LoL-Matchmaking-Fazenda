# Documentação: `BotService.ts`

O `BotService.ts`, localizado em `spring-backend/frontend/src/app/services/`, é um serviço Angular responsável por gerenciar a lógica e o comportamento de jogadores "bot" dentro da aplicação. Ele é particularmente importante nas fases de matchmaking e pick/ban, onde simula as ações dos bots para garantir o fluxo contínuo do jogo, mesmo na ausência de jogadores humanos suficientes.

## 🎯 Propósito e Funcionalidades Principais

O `BotService` abrange as seguintes responsabilidades:

1. **Identificação de Bots:** Fornece métodos para determinar se um determinado objeto `player` representa um bot, com base em convenções de nomenclatura ou outras propriedades.
2. **Auto-Aceitação de Partidas:** Implementa a lógica para que os bots aceitem automaticamente as partidas encontradas, acelerando o processo de matchmaking.
3. **Simulação de Ações no Draft:** Simula as ações de pick e ban de campeões para bots durante a fase de draft, escolhendo campeões de forma aleatória entre os disponíveis.
4. **Gerenciamento de Ações Agendadas:** Permite agendar ações para bots com um atraso (usando `setTimeout`), para simular um comportamento mais natural e dar tempo para as animações de UI.
5. **Comparação de Jogadores:** Oferece métodos robustos para comparar objetos de jogadores, lidando com diferentes formatos de IDs e nomes (legado, Riot ID completo).
6. **Estatísticas de Bots:** Fornece informações sobre o número de bots presentes em uma equipe ou sessão de draft.

## ⚙️ Lógica e Funcionamento

### Identificação de Bots (`isBot`, `isPlayerBot`)

* **`isBot(player)`:** Esta é a função principal para identificar um bot. Ela verifica o `name`, `summonerName`, `displayName` ou `gameName` de um objeto `player` em busca de substrings como "bot", "ai", "computer" ou "cpu".
* **`isPlayerBot(player)`:** Um alias para `isBot`, usado para clareza em contextos onde o objeto é explicitamente um jogador.

### Auto-Aceitação de Partidas (`shouldAutoAcceptMatch`)

* **`shouldAutoAcceptMatch(currentPlayer)`:** Chamado por outros serviços (ex: `MatchFoundComponent`) para determinar se o `currentPlayer` (o jogador logado na aplicação) é um bot e, portanto, deve aceitar a partida automaticamente. Isso é crucial para jogos de teste ou quando a aplicação está sendo executada com bots.

### Simulação de Ações no Draft (`shouldPerformBotAction`, `performBotAction`, `scheduleBotAction`)

* **`shouldPerformBotAction(phase, session)`:** Verifica se é o turno de um bot na fase atual do draft. Ele identifica o jogador do turno e usa `isBot()` para determinar se uma ação automática é necessária.
* **`performBotAction(phase, session, champions)`:** Implementa a lógica para o bot escolher ou banir um campeão. Atualmente, ele seleciona um campeão aleatório que não foi banido ou escolhido. Também pode lidar com `auto-accept` implícito em certos cenários de draft.
* **`scheduleBotAction(phase, session, champions, callback)`:** Agenda a execução de `performBotAction` após um atraso aleatório (entre 1 e 3 segundos), para simular um tempo de resposta "humano". Retorna um ID de temporizador para que a ação possa ser cancelada.
* **`cancelScheduledAction(timerId)`:** Permite cancelar uma ação agendada, útil quando o estado do draft muda ou a partida é cancelada.

### Comparação de Jogadores (`comparePlayers`, `comparePlayerWithId`)

* **`comparePlayers(player1, player2)`:** Compara dois objetos `player` de forma flexível, tentando encontrar correspondências por `id`, `summonerName` (incluindo o formato `gameName#tagLine`) ou `name`.
* **`comparePlayerWithId(player, targetId)`:** Compara um objeto `player` com um `targetId` específico, usando uma lógica de prioridade para `summonerName` (gameName#tagLine), `gameName`, `name`, `id`, `teamIndex` e `puuid`.

### Estatísticas de Bots (`getBotInfo`, `hasBots`, `getSessionBotStats`)

* **`getBotInfo(team)`:** Retorna a contagem e a lista de jogadores bots em uma dada equipe.
* **`hasBots(team)`:** Um utilitário simples para verificar se uma equipe contém bots.
* **`getSessionBotStats(session)`:** Calcula e retorna estatísticas agregadas sobre o número de bots nos times azul e vermelho de uma sessão de draft, incluindo a porcentagem total de bots.

## 🛠️ Tecnologias e Implementação

* **Angular `Injectable`:** Marca o serviço para que possa ser injetado em outros componentes e serviços.
* **TypeScript:** Garante a tipagem forte de interfaces como `PickBanPhase` e `CustomPickBanSession`, e dos parâmetros e retornos dos métodos.
* **`setTimeout` / `clearTimeout`:** Funções nativas do JavaScript usadas para agendar e cancelar ações de bot, simulando atrasos de tempo.
* **Lógica Booleana e String:** As funções de identificação e comparação de bots/jogadores dependem fortemente de manipulação de strings e lógica condicional.

## ⚠️ Considerações e Boas Práticas

* **Sofisticação do Comportamento do Bot:** Atualmente, os bots realizam escolhas aleatórias. Para uma experiência mais realista, o comportamento do bot poderia ser aprimorado com inteligência artificial básica (ex: picks baseados em meta, sinergias de equipe, counters, preferências de lane específicas para bots).
* **Configuração de Bots:** As propriedades que identificam um bot (`includes('bot')`) são baseadas em convenções de nome. Para um sistema mais robusto, um flag explícito `isBot: boolean` no objeto `Player` ou no backend seria mais confiável.
* **Testes:** Testes unitários para a lógica de identificação de bots, auto-aceitação, e, especialmente, para as funções de comparação de jogadores são cruciais para garantir a corretude.
* **Desempenho:** Para um grande número de bots ou em lógicas de pick/ban muito complexas, a simulação e o agendamento de ações devem ser otimizados para não impactar o desempenho da UI.
* **Logging:** O logging detalhado (`console.log`) é útil para depuração, mas deve ser ajustado para produção para evitar sobrecarga de logs.
