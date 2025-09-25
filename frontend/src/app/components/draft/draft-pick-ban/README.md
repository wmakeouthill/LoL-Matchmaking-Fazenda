# Documentação do Componente `DraftPickBanComponent`

## 📄 Visão Geral

O `DraftPickBanComponent` é o principal componente Angular responsável por exibir e gerenciar a interface de usuário completa para a fase de seleção e banimento (Pick/Ban) de campeões em partidas customizadas. Ele orquestra a interação com os jogadores, o backend e os modais de seleção e confirmação, fornecendo uma experiência visual dinâmica e em tempo real do progresso do draft.

## 📁 Estrutura do Diretório

- `draft-pick-ban.ts`: Contém a lógica do componente, gerenciamento de estado, interações com serviços e manipulação de eventos.
- `draft-pick-ban.html`: Define a estrutura visual (template) do componente.
- `draft-pick-ban.scss`: Contém os estilos específicos do componente.
- `draft-champion-modal.ts` / `.html` / `.scss`: Subcomponente (modal) para seleção individual de campeões.
- `draft-confirmation-modal.ts` / `.html` / `.scss`: Subcomponente (modal) para confirmação final do draft.

## 💡 `draft-pick-ban.ts`

Este é o arquivo TypeScript que define o `DraftPickBanComponent`. Ele gerencia a complexa lógica de um sistema de pick/ban personalizado.

### Propósito e Funcionalidades Principais

O componente lida com uma gama complexa de funcionalidades, incluindo:

1. **Gerenciamento do Estado do Draft:** Mantém e atualiza o estado atual da sessão de pick/ban (`session`), incluindo fases de bans/picks, campeões selecionados/banidos e o turno atual.
2. **Exibição de Dados de Campeões:** Carrega e organiza dados de campeões via `ChampionService` para exibição e filtragem.
3. **Controle de Turno e Temporizador:** Determina se é a vez do jogador local (`isMyTurn`), exibe um temporizador regressivo e lida com timeouts, incluindo ações automáticas para bots.
4. **Integração com Modais:** Gerencia a abertura e o fechamento dos modais `DraftChampionModalComponent` (para seleção de campeão) e `DraftConfirmationModalComponent` (para confirmação final).
5. **Comunicação com Backend:** Envia ações de pick/ban dos jogadores para o backend via `ApiService` e recebe atualizações do estado do draft em tempo real.
6. **Sincronização de Dados:** Escuta eventos de sincronização de dados do draft do backend (`handleDraftDataSync`), garantindo que o frontend reflita o estado mais recente, mesmo se múltiplos backends ou clientes estiverem ativos.
7. **Processamento de Dados de Equipe:** Normaliza e organiza os dados dos jogadores recebidos do `matchData` para exibição no template, garantindo a atribuição correta de lanes e índices de equipe.
8. **Modo de Edição:** Permite que o líder da partida edite uma escolha específica durante a fase de confirmação, reabrindo o modal de seleção para aquele turno.

### Ciclo de Vida e Entradas/Saídas (`@Input`, `@Output`, `ngOnInit`, `ngOnDestroy`, `ngOnChanges`)

- **`@Input() matchData`:** Recebe os dados da partida do componente pai, contendo informações dos times e o estado inicial do draft.
- **`@Input() isLeader`:** Indica se o jogador atual é o líder da partida, concedendo permissões para ações como avançar fases ou editar picks.
- **`@Input() currentPlayer`:** Os dados do jogador atualmente logado, usados para identificar o turno e personalizar a UI.
- **`@Output() onPickBanComplete`:** Emite um evento quando o draft é finalizado com sucesso.
- **`@Output() onPickBanCancel`:** Emite um evento se o draft for cancelado.
- **`ngOnInit()`:** Carrega os campeões e inicializa a sessão de pick/ban.
- **`ngOnDestroy()`:** Desinscreve-se de observáveis (`timer`) e cancela ações agendadas para bots para evitar vazamentos de memória.
- **`ngOnChanges()`:** Monitora mudanças nos `matchData` e `currentPlayer`. Ele implementa uma verificação de hash para `matchData` para evitar reprocessamento desnecessário, garantindo que as atualizações do backend (via `handleDraftDataSync`) sejam aplicadas corretamente.

### Gerenciamento do Draft e Turnos

- **`initializePickBanSession()`:** Prepara o objeto `session` com base no `matchData`, incluindo a lista de fases de pick/ban. Normaliza os dados dos jogadores, garantindo que as lanes e índices de equipe estejam corretos.
- **`updateCurrentTurn()`:** Lógica central que avança o estado da sessão para a próxima fase de pick/ban, atualiza o temporizador e verifica se é a vez do jogador local ou de um bot.
- **`startTimer()` / `handleTimeOut()`:** Gerenciam o contador regressivo de cada fase. Em caso de timeout, o `handleTimeOut()` é acionado, podendo levar a uma seleção/banimento automático (para bots) ou à inação para jogadores humanos.
- **`checkForBotAutoAction(phase)`:** Se o jogador atual no turno for um bot, este método agenda uma ação automática (pick ou ban) com um atraso para simular o comportamento humano.

### Interação com o Usuário e Modais

- **`openChampionModal()`:** Abre o `DraftChampionModalComponent` quando é a vez do jogador local para selecionar um campeão para banir ou escolher.
- **`onChampionSelected(champion)`:** Chamado pelo `DraftChampionModalComponent` quando o jogador seleciona um campeão. Envia a ação de pick/ban para o backend via `sendDraftActionToBackend()`.
- **`openConfirmationModal()`:** Abre o `DraftConfirmationModalComponent` ao final de todas as fases, permitindo ao líder da partida revisar e confirmar o draft.
- **`onEditRequested(editData)`:** Chamado pelo `DraftConfirmationModalComponent` quando o líder solicita a edição de um pick específico, ativando o `isEditingMode` e reabrindo o `DraftChampionModalComponent` na fase correta.
- **`completePickBan()`:** Chamado pelo `DraftConfirmationModalComponent` quando o draft é finalmente confirmado, emitindo `onPickBanComplete`.
- **`cancelPickBan()`:** Cancela o draft, emitindo `onPickBanCancel`.

### Obtenção e Exibição de Dados

- **`getBannedChampions()` / `getTeamPicks()` / `getTeamBans()`:** Métodos utilitários que filtram e retornam os campeões banidos globalmente ou selecionados/banidos por uma equipe específica.
- **`getSortedTeamByLaneForDisplay()`:** Organiza os jogadores de uma equipe por lane para exibição visual no template.
- **`isChampionBanned()` / `isChampionPicked()`:** Verificam se um campeão já foi banido ou escolhido, usado para desabilitar opções na UI.
- **`isMyTurn()` / `isCurrentPlayer()` / `isPlayerBot()`:** Métodos para determinar o status do jogador atual e personalizar a UI.

### Comunicação com Backend (`sendDraftActionToBackend`)

- Envia a ação de pick ou ban do jogador para o endpoint `/api/draft/action` do backend, atualizando o estado do draft no servidor.

## 🎨 `draft-pick-ban.html`

Este arquivo define a estrutura HTML do `DraftPickBanComponent`, organizando os elementos visuais da tela de pick/ban.

### Estrutura e Elementos Chave

O template HTML é dividido em várias seções principais:

- **Header (`pick-ban-header`):** Exibe o título da sessão (`Seleção de Campeões`), a fase atual do draft (`getCurrentPhaseText`), uma barra de progresso (`getPhaseProgress`), o temporizador regressivo (`timeRemaining`), e o nome do jogador no turno atual (`getCurrentPlayerName`).
- **Contêiner de Times (`teams-container`):** Divide a tela em três colunas: Time Azul, Bans Gerais (central) e Time Vermelho.
  - **Time Azul (`blue-team`) / Time Vermelho (`red-team`):** Cada seção de time exibe:
    - **Bans:** Uma linha de campeões banidos pelo time, com placeholders para bans pendentes.
    - **Picks:** Uma lista de slots de escolha, onde cada slot mostra o campeão escolhido por um jogador daquele time (imagem, nome do campeão, nome do invocador, lane), ou um placeholder (`?`) se o pick estiver pendente. Um indicador `(Você)` é mostrado para o jogador local.
  - **Bans Gerais (`bans-section`):** Exibe uma lista de todos os campeões que foram banidos durante o draft.
- **Botões de Ação (`action-buttons`):** Contém botões para `Ir para Confirmação` (habilitado apenas quando o draft está completo) e `Cancelar` a partida.
- **Modais Integrados:**
  - `<app-draft-champion-modal>`: O modal para selecionar campeões, controlado por `showChampionModal` e eventos de saída.
  - `<app-draft-confirmation-modal>`: O modal de confirmação final do draft, controlado por `showConfirmationModal` e eventos de confirmação/edição.

### Atributos e Diretivas Angular

O template faz uso extensivo de `*ngIf`, `*ngFor` para renderização condicional e iteração, `[src]` para vinculação de imagens, `[class.warning]` para estilização condicional do temporizador, e `(click)` para eventos de usuário. `[disabled]` é usado para controlar a disponibilidade dos botões.

## 🖌️ `draft-pick-ban.scss`

Este arquivo SCSS fornece a estilização abrangente para o `DraftPickBanComponent`, garantindo um design visualmente atraente e responsivo em diversas resoluções de tela.

### Estilos Principais e Organização

O `draft-pick-ban.scss` é bem organizado em seções para cada parte do componente:

- **Estilos Globais:** Define o background com gradiente, cor de texto e fonte base para todo o componente.
- **Header (`.pick-ban-header`):** Estiliza o cabeçalho, incluindo `display: flex`, backgrounds translúcidos com `backdrop-filter`, bordas arredondadas e `padding`. Detalhes para `session-info` (título, `phase-indicator`, `current-phase`, `progress-bar`, `progress-fill`), `timer-section` (temporizador com animação `pulse` para `warning` state) e `current-player` (indicador de jogador atual).
- **Contêiner de Times (`.teams-container`):** Define um layout de `grid` com três colunas (Times Azul, Bans Gerais, Time Vermelho).
- **Estilos dos Times (`.team`, `.blue-team`, `.red-team`):** Estilos gerais para os painéis das equipes, com `background` e bordas coloridas específicas para cada time.
- **Bans (`.team-bans`, `.banned-champions-row`, `.banned-champion`, `.banned-img`, `.banned-name`, `.banned-placeholder`, `.ban-placeholder`):** Estilos para a exibição de campeões banidos, incluindo imagens, nomes e placeholders.
- **Picks (`.team-picks`, `.pick-slot`, `.champion-img`, `.pick-info`, `.champion-name`, `.player-name`, `.you-indicator`, `.player-lane`, `.champion-placeholder`):** Estilos para os slots de escolha de campeões, com imagens, informações de jogador/campeão, e indicadores `(Você)`.
- **Bans Centrais (`.bans-section`, `.banned-champions`):** Estilos para a seção central que lista todos os campeões banidos.
- **Botões de Ação (`.action-buttons`, `.buttons-container`, `.btn`, `.btn-success`, `.btn-danger`, `.btn-lg`):** Estilos para a área de botões na parte inferior, incluindo botões de sucesso e perigo com tamanhos grandes.

### Responsividade e Animações

O SCSS incorpora `media queries` para garantir que o layout se adapte de forma otimizada a diferentes tamanhos de tela. Animações como `@keyframes pulse` para o temporizador e transições (`transition: width 0.3s ease;` para a barra de progresso) são usadas para melhorar a experiência do usuário.

## 🔗 Dependências

Este componente depende de:

- **Módulos Angular:** `CommonModule`, `FormsModule`, `HttpClientModule`.
- **Serviços Customizados:** `ChampionService`, `BotService`, `ApiService`.
- **Subcomponentes:** `DraftChampionModalComponent`, `DraftConfirmationModalComponent`.
- **RxJS:** `interval`, `Subscription` para gerenciamento de temporizadores.
- **Interfaces:** `Champion`, `PickBanPhase`, `CustomPickBanSession` (provavelmente de `bot.service.ts` ou `interfaces.ts`).

## 🛠️ Tecnologias Utilizadas

- **Angular**: Framework para o desenvolvimento do frontend.
- **TypeScript**: Linguagem de programação para a lógica do componente.
- **HTML**: Estrutura do template.
- **SCSS (Sass)**: Pré-processador CSS para estilos, com variáveis e `keyframes` para animações.
- **RxJS**: Para programação reativa e gerenciamento de streams de tempo.

## 📈 Potenciais Melhorias

- **Otimização de Renderização:** Para drafts muito grandes ou em dispositivos de baixo desempenho, a renderização da grade de campeões pode ser otimizada com estratégias como `trackBy` em `*ngFor` ou virtualização.
- **Acessibilidade:** Melhorar a acessibilidade para usuários com deficiência, garantindo que a navegação por teclado e leitores de tela funcionem perfeitamente.
- **Internacionalização (i18n):** Implementar suporte a múltiplos idiomas para todos os textos exibidos na tela.
- **Lógica de Edição:** A lógica de edição (`isEditingMode`, `editingPhaseIndex`) pode ser mais centralizada para evitar complexidade no componente principal. Passar um objeto de configuração para o modal de seleção pode simplificar.
- **Testes:** Adicionar testes unitários abrangentes para a lógica de turnos, temporizadores, processamento de dados do draft, e interações com os modais.
