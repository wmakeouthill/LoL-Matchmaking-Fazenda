# Documentação do Componente `GameInProgressComponent`

## 📄 Visão Geral

O `GameInProgressComponent` é responsável por gerenciar e exibir o estado de uma partida em andamento, desde o início (após a fase de pick/ban) até sua conclusão. Ele monitora o tempo de jogo, tenta automaticamente vincular-se a partidas reais do League of Legends através do LCU (League Client Update) e permite a declaração manual do vencedor da partida quando o LCU não é capaz de detectar o fim do jogo automaticamente. O componente é crucial para registrar os resultados das partidas personalizadas no sistema.

**Nota:** Devido a limitações técnicas na leitura completa dos arquivos `game-in-progress.ts`, `game-in-progress.html` e `game-in-progress.scss`, esta documentação é baseada nas partes do código que foram acessadas. Pode haver funcionalidades adicionais ou detalhes não abordados aqui. Se necessário, o código-fonte deve ser consultado diretamente para uma compreensão completa.

## 📁 Estrutura do Diretório

- `game-in-progress.ts`: Contém a lógica do componente, gerenciamento de estado do jogo, detecção LCU, e comunicação com o backend.
- `game-in-progress.html`: Define a estrutura visual (template) do componente, exibindo informações da partida, times, e opções para declarar o vencedor.
- `game-in-progress.scss`: Contém os estilos específicos do componente, garantindo um design visualmente atraente e responsivo.

## 💡 `game-in-progress.ts`

Este é o arquivo TypeScript que implementa a lógica do `GameInProgressComponent`.

### Propósito e Funcionalidades Principais

As funcionalidades observadas incluem:

- **Inicialização do Jogo:** `initializeGame()` é chamado quando os `gameData` são recebidos. Ele configura o `gameStartTime`, o `gameStatus` para 'waiting', e inicia os temporizadores e o sistema de vinculação de partida ao vivo.
- **Temporizador do Jogo:** `startGameTimer()` inicia um `interval` que atualiza a `gameDuration` a cada segundo, exibindo o tempo decorrido da partida.
- **Vinculação de Partidas ao Vivo (LCU):** `startLiveMatchLinking()` e `tryLinkToLiveMatch()` tentam conectar a partida personalizada com uma partida real detectada pelo LCU. Ele realiza tentativas periódicas, verifica se a partida LCU corresponde ao draft (baseado em campeões e timing via `calculateLiveLinkingScore()`) e, se houver sucesso, define `currentLiveMatchId`. Isso é crucial para a detecção automática do vencedor.
- **Detecção Automática de Vencedor:** `tryAutoResolveWinner()` e `retryAutoDetection()` (chamado manualmente) tentam determinar o vencedor da partida automaticamente usando dados do LCU. Ele busca o histórico de partidas do LCU (`apiService.getLCUMatchHistoryAll()`), tenta encontrar uma partida correspondente (`findMatchingLCUGame()`), e, se um vencedor for detectado, completa o jogo automaticamente (`autoCompleteGameWithRealData()`).
- **Declaração Manual do Vencedor:** O componente permite que o usuário declare manualmente o vencedor (`declareWinner()`, `confirmWinner()`) caso a detecção automática falhe.
- **Conclusão da Partida:** `onGameComplete` é emitido com os `GameResult` quando a partida é finalizada (seja por detecção automática ou declaração manual), enviando os dados para o componente pai para registro no backend.
- **Cancelamento da Partida:** `cancelGame()` permite que o usuário cancele a partida em andamento, emitindo `onGameCancel`.
- **Métodos Auxiliares para Dados:** Inclui métodos para formatar informações (`formatGameDuration()`, `formatLCUMatchDate()`), obter nomes de campeões e lanes (`getChampionNameById()`, `getLaneName()`, `getLaneIcon()`), e manipular dados dos times (`getTeamPlayers()`, `getTeamBans()`).
- **Notificações:** `showSuccessNotification()` e `showErrorNotification()` fornecem feedback simples ao usuário (atualmente usando `alert()`).

## 🎨 `game-in-progress.html`

Este arquivo define a estrutura HTML do `GameInProgressComponent`, responsável pela interface visual da partida em andamento.

### Estrutura e Elementos Chave

O template HTML é dividido nas seguintes seções principais:

- **Header (`game-header`):** Exibe o título do jogo, o status atual (`gameStatus`), o tempo de jogo decorrido (`gameDurationFormatted`), e o status da detecção LCU (`lcu-status`).
- **Teams Section (`teams-section`):** Apresenta os dois times (Azul e Vermelho) lado a lado, com um divisor central (`vs-divider`).
  - **Times (`team`):** Cada painel de time exibe o nome do time (`team-name`), uma seção de bans (`team-bans`) listando os campeões banidos, e uma lista de jogadores (`team-players`).
  - **Jogadores (`player`):** Cada jogador exibe sua lane (com ícone e nome formatado), nome, e campeão escolhido. O jogador atual (`currentPlayer`) é visualmente destacado.
- **Game Status Section (`game-status-section`):** Exibe detalhes sobre o status da partida, incluindo detecção LCU e o `currentLiveMatchId` se uma partida real for vinculada.
- **Winner Section (`winner-section`):** Permite a declaração manual do vencedor, com botões para cada time (`team-button`) e um resumo do resultado se o usuário estiver na equipe vencedora (`your-team-info`).
- **Settings Section (`settings-section`):** Contém opções para o usuário, como ativar/desativar a detecção LCU (`lcuDetectionEnabled`).
- **Action Buttons (`action-buttons`):** Botões para "Finalizar Partida" (declarando o vencedor ou tentando detecção automática), "Tentar Auto-Detecção" e "Cancelar Partida".
- **Match Confirmation Modal (`modal-overlay`, `match-confirmation-modal`):** Um modal opcional que aparece quando uma partida LCU é detectada e precisa de confirmação. Ele exibe detalhes da partida LCU, um indicador de confiança (`confidence-indicator`), e permite que o usuário confirme ou rejeite a vinculação.

### Interatividade e Ligação de Dados

O template utiliza amplamente as diretivas e ligações de dados do Angular:

- **`*ngIf`:** Para renderizar condicionalmente seções e elementos com base no `gameData`, `gameStatus`, `lcuGameDetected`, `showMatchConfirmation`, e se há um `currentPlayer`.
- **`*ngFor`:** Para iterar sobre as listas de jogadores (`team1`, `team2`) e bans (`getTeamBans()`), gerando dinamicamente os elementos da UI.
- **Interpolação `{{ }}` e Property Binding `[ ]`:** Para exibir dinamicamente o status do jogo, tempo, nomes de jogadores/campeões, URLs de imagem, e aplicar classes CSS baseadas em condições (e.g., `[class.active]`, `[class.team-blue]`).
- **Event Binding `( )`:** Para lidar com cliques em botões (declarar vencedor, finalizar, cancelar, tentar auto-detecção) e alternar configurações.
- **`[(ngModel)]`:** Para a ligação de dados bidirecional na seleção manual do vencedor.

## 🖌️ `game-in-progress.scss`

Este arquivo SCSS é responsável pela estilização do `GameInProgressComponent`, garantindo um design visualmente intuitivo e responsivo para a tela de partida em andamento.

**Nota:** A análise completa do `game-in-progress.scss` não foi possível devido a limitações da ferramenta, portanto, esta descrição é baseada nas partes do arquivo que foram acessadas.

### Estilos Principais e Organização

O SCSS é bem segmentado para estilizar cada parte do componente:

- **Contêiner Geral (`.game-in-progress-container`):** Define o padding, largura máxima e centralização.
- **Header (`.game-header`):** Estiliza o cabeçalho principal com layout flex, background gradiente, bordas arredondadas, e estilos para o título do jogo (`.game-title`), status (`.game-status`), temporizador (`.game-timer`, com `timer-label` e `timer-value`), e o indicador de status LCU (`.lcu-status`, com `status-indicator` e estados `active`).
- **Seção de Times (`.teams-section`):** Define o layout de grid para os painéis dos times e o divisor central.
  - **Times (`.team`, `.team-blue`, `.team-red`):** Estilos gerais para os painéis das equipes, incluindo background, bordas arredondadas e cores de borda distintas. Inclui estilos para o cabeçalho do time (`.team-header`), nome do time (`h3`), e badges de vencedor/perdedor.
  - **Bans por Time (`.team-bans`, `.bans-header`, `.bans-list`, `.ban-item`, `.ban-champion`):** Estilos para a exibição dos campeões banidos, com background e bordas específicas.
  - **Jogadores (`.team-players`, `.player`):** Estilos para a lista de jogadores, com layout de grid, backgrounds, bordas, e um destaque para o `current-player`. Inclui estilos para a lane (`.player-lane`, com `lane-icon` e `lane-name`), informações do jogador (`.player-info`, `.player-name`), e detalhes do campeão (`.player-champion`, com `champion-name` e `champion-placeholder`).
  - **Divisor VS (`.vs-divider`):** Estilos para o separador entre os times, com texto "VS" (`vs-text`) e duração do jogo (`game-duration-display`).
- **Seção de Status do Jogo (`.game-status-section`, `.status-card`, `.status-item`):** Estilos para exibir informações de status da partida, incluindo `status-label` e `status-value` com cores dinâmicas para diferentes estados (waiting, in-progress, ended, connected, linked).
- **Seção de Vencedor (`.winner-section`, `.winner-declaration`):** Estilos para a área de declaração do vencedor, incluindo botões de seleção de time (`team-button`, com estados `selected`), e informações do time do usuário.
- **Seção de Configurações (`.settings-section`, `.settings-card`, `.setting-item`):** Estilos para opções de configuração, como o toggle de detecção LCU, com um checkbox customizado (`checkmark`).
- **Botões de Ação (`.action-buttons`, `.btn`, `.btn-primary`, `.btn-secondary`, `.btn-outline`):** Estilos genéricos e específicos para os botões do componente, com fundos, cores, bordas, e efeitos de hover e disabled.
- **Seção de Debug (`.debug-section`):** Estilos para a área de debug, com um fundo escuro e borda.
- **Modal de Confirmação de Partida (`.modal-overlay`, `.match-confirmation-modal`):** Estilos para o modal que confirma a detecção de uma partida LCU. Inclui estilos para o header, conteúdo, indicador de confiança (`confidence-indicator` com `high-confidence`, `medium-confidence`, `low-confidence`), e seções de comparação de times (`team-comparison-section`).

### Responsividade e Animações

O SCSS incorpora `media queries` (`@media (max-width: 768px)`) para adaptar o layout a telas menores. Isso inclui ajustes no layout dos times (de três colunas para uma coluna), na direção dos botões, e no tamanho do modal de confirmação. Animações como `spin` (para indicadores de carregamento) e transições CSS são usadas para fornecer feedback visual dinâmico e uma interface mais interativa.

## 🔗 Dependências

Este componente depende de:

- **Módulos Angular:** `CommonModule`, `FormsModule`.
- **Serviços Customizados:** `ApiService`.
- **RxJS:** `interval`, `Subscription` (para gerenciar temporizadores).
- **Interfaces:** `GameData`, `GameResult` (definidas diretamente no componente).

## 🛠️ Tecnologias Utilizadas

- **Angular**: Framework para o desenvolvimento do frontend.
- **TypeScript**: Linguagem de programação para a lógica do componente.
- **HTML**: Estrutura do template.
- **SCSS (Sass)**: Pré-processador CSS para estilos, com variáveis e `keyframes` para animações.
- **RxJS**: Para programação reativa, especialmente no gerenciamento de temporizadores e assinaturas.

## 📈 Potenciais Melhorias

- **Internacionalização (i18n):** Adicionar suporte a múltiplos idiomas para todos os textos exibidos na UI.
- **Testes Unitários/de Integração:** Ampliar a cobertura de testes para a lógica de detecção de partidas LCU, `calculateLiveLinkingScore()`, e o fluxo de auto-conclusão.
- **Mecanismo de Notificações:** Substituir os `alert()` por um serviço de notificações mais amigável e visualmente integrado (toast, snackbar).
- **Recuperação de Erros:** Implementar um tratamento de erros mais robusto para falhas de comunicação com a LCU ou backend, oferecendo opções mais claras ao usuário.
- **Flexibilidade na Detecção LCU:** Permitir que o usuário ajuste parâmetros da detecção LCU, como a frequência de tentativas ou o limiar de confiança para vinculação automática.
- **Persistência de Estado:** Em caso de recarregamento da aplicação, tentar restaurar o estado da partida em andamento para evitar perda de dados.
