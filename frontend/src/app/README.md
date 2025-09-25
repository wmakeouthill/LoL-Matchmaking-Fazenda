# Documentação do Módulo Principal do Frontend (`spring-backend/frontend/src/app/`)

## 📄 Visão Geral

O diretório `spring-backend/frontend/src/app/` é o coração da aplicação Angular, contendo o componente raiz (`App`), as definições de interfaces compartilhadas, as configurações do aplicativo, a lógica de roteamento, estilos globais e os módulos de teste. Ele orquestra a inicialização da aplicação, gerencia o estado global e atua como o ponto de integração para todos os componentes de UI e serviços de lógica de negócio.

## 📁 Estrutura do Diretório

- `app.ts`: O componente raiz da aplicação (`App`).
- `app.config.ts`: Configurações de aplicação do Angular.
- `app.routes.ts`: Definições de rotas da aplicação (atualmente vazias, roteamento imperativo).
- `app.html`: Template HTML principal (versão simplificada/alternativa é usada).
- `app-simple.html`: O template HTML **ativo** e mais detalhado do componente raiz.
- `app.scss`: Estilos SCSS globais e de layout para a aplicação.
- `app.spec.ts`: Testes unitários para o componente `App`.
- `interfaces.ts`: Definições de interfaces TypeScript compartilhadas.
- `components/`: Subdiretório contendo todos os componentes de UI da aplicação.
- `services/`: Subdiretório contendo todos os serviços de lógica de negócio.

## 🧠 Análise Detalhada dos Componentes Chave

### 📄 `App` Componente Principal (`app.ts`)

O `App` é o componente raiz da aplicação. Ele é responsável por:

- **Gerenciamento de Visão:** Controla a visualização ativa (`currentView`) entre `dashboard`, `queue`, `history`, `leaderboard` e `settings` usando a diretiva `*ngIf` no template, em vez do roteador Angular padrão.
- **Estado Global:** Mantém o estado global da aplicação, incluindo `currentPlayer` (dados do jogador logado), `queueStatus` (status da fila de matchmaking), `lcuStatus` (status de conexão com o cliente League of Legends), `discordStatus` (status de conexão com o bot Discord), e o estado das fases do jogo (match encontrado, draft, em jogo).
- **Inicialização (`initializeAppSequence`):** Implementa uma sequência de inicialização assíncrona e robusta para garantir que a aplicação esteja em um estado consistente desde o início. Esta sequência inclui:
  1. **Configurações Básicas:** Configura listeners de status do Discord e inicia a verificação do status do LCU.
  2. **Verificação do Backend:** Garante que o backend esteja acessível antes de prosseguir, com múltiplas tentativas e atrasos.
  3. **Configuração de Comunicação (`setupBackendCommunication`):** Estabelece a comunicação WebSocket com o backend, configurando listeners para mensagens e aguardando a prontidão do WebSocket.
  4. **Carregamento de Dados do Jogador (`loadPlayerDataWithRetry`):** Tenta carregar os dados do jogador do LCU com retentativas, persistindo-os no localStorage.
  5. **Identificação do Jogador:** Identifica o jogador atual no WebSocket para comunicação personalizada.
  6. **Busca de Status da Fila:** Obtém o status inicial da fila de matchmaking.
  7. **Carregamento de Configurações:** Carrega as configurações do usuário armazenadas no banco de dados (via backend).
  8. **Atualizações Periódicas:** Inicia verificações periódicas para manter os estados do LCU e da fila atualizados.
- **Comunicação:** Interage extensivamente com a `ApiService` para comunicação RESTful e WebSocket com o backend, recebendo atualizações em tempo real e enviando ações.
- **Gerenciamento de Notificações:** Adiciona, exibe e dispensa notificações (`addNotification`, `dismissNotification`) para feedback ao usuário.
- **Integração com Electron:** Detecta se está sendo executado no Electron (`isElectron`) e expõe métodos para controlar a janela (minimizar, maximizar, fechar) via `electronAPI` (exposto pelo `preload.ts`).
- **Lógica de Negócio Principal:** Contém métodos para interagir com a fila (`joinQueue`, `leaveQueue`), aceitar/recusar partidas (`acceptMatch`, `declineMatch`), e atualizar configurações relacionadas ao usuário e integrações (Riot API Key, Discord Bot Token, Discord Channel).
- **Monitoramento:** Realiza verificações periódicas do status do LCU (`startLCUStatusCheck`) e do backend.
- **Manipulação de Eventos de Jogo:** Lida com mensagens WebSocket do backend para processar eventos como partidas encontradas (`handleMatchFound`), fases de draft (`handleDraftStarted`, `handleDraftAction`), início de jogo (`handleGameStarting`), cancelamento de partida (`handleMatchCancelled`), e atualizações de timer de partida.

### 📄 `app.config.ts`

Define a configuração fundamental do aplicativo Angular. Inclui provedores essenciais como:

- `provideBrowserGlobalErrorListeners()`: Para lidar com erros globais no navegador.
- `provideZoneChangeDetection({ eventCoalescing: true })`: Otimiza a detecção de mudanças do Angular.
- `provideRouter(routes)`: Configura o serviço de roteamento (embora as rotas sejam controladas imperativamente no `app.ts` neste projeto, este provedor ainda é necessário para o módulo do roteador funcionar).
- `provideHttpClient()`: Fornece o `HttpClient` para fazer requisições HTTP.

### 📄 `app.routes.ts`

Este arquivo define a configuração de rotas da aplicação. No entanto, notavelmente, a array `routes` está vazia (`export const routes: Routes = [];`). Isso indica que a navegação entre as diferentes 'páginas' ou visualizações do aplicativo (Dashboard, Fila, Histórico, etc.) é gerenciada diretamente pelo `App` componente (`app.ts`) através da propriedade `currentView` e das diretivas `*ngIf` no template, em vez de utilizar o sistema de roteamento declarativo padrão do Angular.

### 📄 `app.html` e `app-simple.html`

- **`app.html`**: Este arquivo HTML parece ser um template antigo ou alternativo e **não está em uso ativo** pela aplicação principal. O componente `App` (`app.ts`) utiliza `app-simple.html` como seu template.
- **`app-simple.html`**: Este é o template HTML **ativo e principal** para o componente `App` (`app.ts`), conforme especificado em `@Component({ templateUrl: './app-simple.html' })`. Ele define a estrutura de layout fundamental da aplicação, que inclui:
  - **Cabeçalho (`.app-header`):** Contém o título da aplicação, a versão, informações do jogador logado (`currentPlayer` com ícone, nome, MMR e rank), e controles de janela para o ambiente Electron (minimizar, maximizar, fechar).
  - **Navegação (`.app-nav`):** Uma barra de navegação com botões para alternar entre as diferentes visualizações da aplicação (Dashboard, Fila, Histórico, Leaderboard, Configurações). A navegação é controlada imperativamente pela propriedade `currentView` no `App` componente, em vez do roteador Angular padrão. Inclui um `queue-badge` para exibir o número de jogadores na fila.
  - **Área de Conteúdo Principal (`.app-main`):** Um contêiner que renderiza dinamicamente o componente da visualização ativa (ex: `<app-dashboard>`, `<app-queue>`) usando diretivas `*ngIf` e `*ngSwitchCase`.
    - Cada sub-componente recebe dados relevantes via `@Input` (ex: `currentPlayer`, `queueStatus`) e emite eventos (`@Output`) de volta para o `App` componente para interações (ex: `joinQueue`, `leaveQueue`).
    - A seção de Configurações (`settings-view`) está diretamente embutida neste template e permite a atualização de dados do jogador e configurações de API/bot.
  - **Barra de Status (`.app-footer`):** Exibe o status de conexão geral (`isConnected`), o número de jogadores na fila, e a fase atual do jogo detectada pelo LCU (`lcuStatus.gameflowPhase`).
  - **Notificações (`.notifications`):** Uma área para exibir notificações pop-up (sucesso, informação, aviso, erro) ao usuário, com animações de entrada e saída.
  - **Modal de Partida Encontrada (`app-match-found`):** Um componente de modal para lidar com o processo de aceitação/recusa de partidas encontradas, que é exibido condicionalmente (`showMatchFound`).

### 📄 `app.scss`

Contém os estilos SCSS globais e específicos do layout principal da aplicação, definindo a identidade visual e a responsividade. Ele utiliza variáveis CSS para manter a consistência e facilita a manutenção do tema.

- **Estrutura da Aplicação (`.app-container`):** Define o layout principal como um flex container de coluna, com background gradiente e pseudo-elementos para efeitos visuais sutis. Garante que todo o conteúdo da aplicação se adapte à altura da tela.
- **Cabeçalho (`.app-header`):** Estiliza a barra superior, incluindo o título da aplicação (com gradiente de texto), informações do jogador, e controles de janela (minimizar, maximizar, fechar) para a aplicação Electron. Utiliza `backdrop-filter: blur()` para um efeito de transparência.
- **Navegação (`.app-nav`):** Estiliza os botões de navegação lateral. Define cores, padding, bordas, e transições para os estados normal, hover e ativo. Inclui um `queue-badge` estilizado para exibir a contagem de jogadores na fila.
- **Área de Conteúdo Principal (`.app-main`):** Define o layout da área onde os diferentes componentes (Dashboard, Fila, Histórico, etc.) são renderizados. Inclui regras para overflow e altura máxima para garantir que o conteúdo seja scrollável.
- **Barra de Status (`.app-footer`):** Estiliza a barra inferior de status, que exibe o status de conexão geral e do LCU, além do número de jogadores na fila, usando indicadores visuais.
- **Notificações (`.notifications`, `.notification`):** Define a aparência e as animações para as notificações pop-up, com diferentes estilos para `success`, `info`, `warning` e `error`.
- **Elementos Comuns:** Inclui estilos genéricos para botões (`.btn`, `.btn-primary`, `.btn-secondary`), campos de formulário (`.form-group`), e indicadores de status (`.status-indicator`, `.connected`, `.disconnected`).
- **Variáveis CSS:** Utiliza um sistema robusto de variáveis CSS (ex: `--bg-primary`, `--text-primary`, `--primary-gold`, `--shadow-md`, `--spacing-sm`, `--radius-md`, `--transition-fast`) para cores, espaçamentos, sombras, bordas e transições. Isso permite uma gestão de tema centralizada e fácil modificação do design.
- **Animações CSS:** Define keyframes para animações como `fadeIn`, `scaleIn`, `slideInRight` e `fadeOut`, aplicadas a elementos como notificações e modais para uma experiência de usuário mais fluida e dinâmica.
- **Ícones:** Utiliza classes como `icon-dashboard`, `icon-queue` para exibir ícones de forma consistente.

### 📄 `app.spec.ts`

Este arquivo contém os testes unitários para o componente `App`. Utiliza o framework de teste do Angular (`TestBed`, `describe`, `it`, `expect`) para garantir a integridade básica do componente raiz da aplicação.

- **Setup (`beforeEach`):** Antes de cada teste, o `TestBed` é configurado para importar o componente `App`, garantindo que todas as suas dependências sejam resolvidas para o ambiente de teste.
- **Teste de Criação (`should create the app`):** Verifica se o componente `App` pode ser instanciado com sucesso. Isso é um teste fundamental para assegurar que não há erros básicos de compilação ou dependência que impeçam o componente de ser criado.
- **Teste de Renderização de Título (`should render title`):** Verifica se o título da aplicação é renderizado corretamente no template. Note que o teste espera o texto "Hello, lol-matchmaking", o que pode ser um resquício de um projeto Angular padrão e pode precisar ser atualizado se o título na UI for diferente (conforme `app-simple.html` que usa "LoL Matchmaking").

## 🔗 Integração do Módulo Principal com o Resto da Aplicação

O módulo `app/` é o orquestrador principal, conectando:

- **Componentes de UI:** Ele hospeda e gerencia a exibição dos sub-componentes (Dashboard, Fila, Histórico, etc.), passando dados (`@Input`) e ouvindo eventos (`@Output`).
- **Serviços de Lógica de Negócio:** Injeta e utiliza diversos serviços (ex: `ApiService`, `QueueStateService`, `DiscordIntegrationService`, `BotService`) para realizar operações assíncronas, gerenciar estado e interagir com o backend.
- **Backend:** A comunicação é feita principalmente via `ApiService` (HTTP e WebSockets), garantindo que o frontend receba atualizações em tempo real e possa enviar ações.
- **Electron:** A integração com o processo `main` do Electron permite funcionalidades nativas, como controle da janela e acesso a informações do sistema, via `electronAPI` exposto pelo `preload.ts`.

## 💡 Considerações e Potenciais Melhorias

- **Roteamento:** A abordagem de roteamento imperativo (`currentView` com `*ngIf`) pode se tornar complexa em aplicações maiores. Considerar a migração para o roteador declarativo do Angular (`RouterModule.forRoot`) para melhor escalabilidade e manutenção.
- **Gerenciamento de Estado:** Para aplicações mais complexas, a gestão de estado global diretamente no componente `App` pode levar a um 'componente gorducho'. Avaliar a adoção de uma biblioteca de gerenciamento de estado (ex: NgRx, Akita, ou uma solução baseada em RxJS mais robusta) para dados compartilhados.
- **Refatoração de Template:** O `app-simple.html` contém muitos estilos inline. Mover esses estilos para `app.scss` ou para os arquivos SCSS dos componentes aninhados melhoraria a manutenibilidade.
- **Separação de Preocupações:** O `App` componente atualmente lida com uma ampla gama de responsabilidades (inicialização, gerenciamento de UI, comunicação de dados). Refatorar algumas dessas responsabilidades para serviços mais especializados pode melhorar a modularidade.
- **Testes:** Expandir a cobertura de testes unitários e adicionar testes de integração para as interações entre o `App` componente e seus serviços dependentes.

Este módulo é a fundação sobre a qual toda a experiência do usuário do aplicativo é construída.

## Documentação: `interfaces.ts`

O arquivo `interfaces.ts`, localizado em `spring-backend/frontend/src/app/`, serve como um repositório centralizado para todas as definições de interfaces TypeScript utilizadas em todo o frontend da aplicação. O propósito principal deste arquivo é garantir a consistência dos dados, promover a segurança de tipagem (type-safety) e melhorar a legibilidade e a manutenção do código, ao definir claramente a estrutura dos objetos de dados que transitam entre os componentes e serviços.

## 🎯 Propósito e Funcionalidades Principais

Este arquivo é essencial para:

1. **Padronização de Dados:** Define contratos claros para a estrutura dos dados, garantindo que todos os componentes e serviços manipulem objetos com as mesmas propriedades e tipos.
2. **Segurança de Tipagem:** Permite que o TypeScript realize verificações em tempo de compilação, ajudando a prevenir erros relacionados a dados inesperados ou ausentes.
3. **Documentação Implícita:** As interfaces servem como uma forma de documentação do schema dos dados, facilitando para novos desenvolvedores entenderem as estruturas de dados do projeto.
4. **Reusabilidade:** Evita a redefinição de tipos de dados em múltiplos locais, mantendo as definições em um único ponto de verdade.

## ⚙️ Interfaces Detalhadas

### `Player`

Representa o perfil completo de um jogador, combinando dados do backend (banco de dados customizado) e da Riot API.

```typescript
export interface Player {
  id: number;
  summonerName: string;
  displayName?: string; // Nome completo formatado (gameName#tagLine)
  gameName?: string;    // Nome do Riot ID (sem tag)
  summonerId?: string;
  puuid?: string;
  tagLine?: string;
  profileIconId?: string | number;
  summonerLevel?: number;
  currentMMR: number;
  mmr?: number; // Alias para currentMMR
  customLp?: number; // LP customizado
  region: string;
  rank?: {         // Informações de rank oficial da Riot
    tier: string;
    rank: string;
    display: string;
    lp?: number;
  };
  wins?: number;
  losses?: number;
  lastMatchDate?: Date;
  rankedData?: {   // Dados brutos de rank da Riot API
    soloQueue?: any;
    flexQueue?: any;
  };
}
```

### `QueueStatus`

Descreve o estado atual da fila de matchmaking, exibindo informações globais e detalhes dos jogadores na fila.

```typescript
export interface QueueStatus {
  playersInQueue: number;
  averageWaitTime: number;
  estimatedMatchTime?: number;
  isActive?: boolean;
  yourPosition?: number;
  playersInQueueList?: QueuedPlayerInfo[]; // Lista dos jogadores na fila
  recentActivities?: QueueActivity[];    // Atividades recentes na fila
  isCurrentPlayerInQueue?: boolean;      // Indica se o usuário atual está na fila
}
```

### `QueuedPlayerInfo`

Contém informações detalhadas de um jogador específico que está atualmente na fila de matchmaking.

```typescript
export interface QueuedPlayerInfo {
  summonerName: string;
  tagLine?: string;
  primaryLane: string;
  secondaryLane: string;
  primaryLaneDisplay: string;
  secondaryLaneDisplay: string;
  mmr: number;
  queuePosition: number;
  joinTime: string; // ISO string da data de entrada na fila
  isCurrentPlayer?: boolean; // Indica se este é o jogador atual
}
```

### `QueueActivity`

Representa um evento ou atividade que ocorreu na fila de matchmaking.

```typescript
export interface QueueActivity {
  id: string;
  timestamp: Date;
  type: 'player_joined' | 'player_left' | 'match_created' | 'system_update' | 'queue_cleared';
  message: string;
  playerName?: string;
  playerTag?: string;
  lane?: string;
}
```

### `Lane`

Define a estrutura de uma lane (posição) em League of Legends para seleção de preferências.

```typescript
export interface Lane {
  id: string;
  name: string;
  icon: string;
  description: string;
}
```

### `QueuePreferences`

Contém as preferências de lane e a opção de auto-aceitação do jogador para entrar na fila.

```typescript
export interface QueuePreferences {
  primaryLane: string;
  secondaryLane: string;
  autoAccept?: boolean;
}
```

### `LCUStatus`

Descreve o status de conexão e a fase atual do League of Legends Client Update (LCU).

```typescript
export interface LCUStatus {
  isConnected: boolean;
  summoner?: any;      // Dados do invocador logado no LCU
  gameflowPhase?: string; // Fase atual do cliente (Lobby, ChampSelect, InProgress, etc.)
  lobby?: any;          // Detalhes do lobby atual
}
```

### `MatchFound`

Representa os dados de uma partida que foi encontrada pelo sistema de matchmaking, antes da fase de aceitação.

```typescript
export interface MatchFound {
  matchId: string;
  countdown: number;
  acceptedPlayers: string[];
  declinedPlayers: string[];
  requiredPlayers: number;
  status: 'pending' | 'accepted' | 'declined' | 'expired';
  players: Player[]; // Lista de todos os jogadores na partida encontrada
}
```

### `Notification`

Define a estrutura de uma notificação genérica exibida na UI.

```typescript
export interface Notification {
  id: string;
  type: 'success' | 'info' | 'warning' | 'error';
  title: string;
  message: string;
  duration?: number; // Duração em milissegundos, opcional
}
```

### `CurrentGame`

Representa o estado do jogo atual do League of Legends (obtido via LCU).

```typescript
export interface CurrentGame {
  gameId: number;
  gameMode: string;
  gameType: string;
  mapId: number;
  gameStartTime: number;
  participants: any[]; // Detalhes dos participantes do jogo
  phase: string; // Ex: Lobby, ChampSelect, InProgress, EndOfGame
}
```

### `Match`

Define uma estrutura mais detalhada para uma partida do histórico (geralmente vinda da Riot API ou do backend).

```typescript
export interface Match {
  gameId: number;
  platformId: string;
  gameCreation: number;
  gameDuration: number;
  queueId: number;
  mapId: number;
  seasonId: number;
  gameVersion: string;
  gameMode: string;
  gameType: string;
  teams: any[];
  participants: any[];
  participantIdentities: any[];
  // Adicionar campos relevantes para exibição no histórico
  win?: boolean;
  championName?: string;
  kda?: string;
  lane?: string;
  role?: string;
  cs?: number;
  gold?: number;
}
```

### `RefreshPlayerResponse`

Estrutura da resposta ao solicitar uma atualização dos dados de um jogador.

```typescript
export interface RefreshPlayerResponse {
  success: boolean;
  player?: Player;
  message?: string;
}
```

## 🔗 Integração do Módulo Principal com o Resto do

O módulo `app/` é o orquestrador principal, conectando:

- **Componentes de UI:** Ele hospeda e gerencia a exibição dos sub-componentes (Dashboard, Fila, Histórico, etc.), passando dados (`@Input`) e ouvindo eventos (`@Output`).
- **Serviços de Lógica de Negócio:** Injeta e utiliza diversos serviços (ex: `ApiService`, `QueueStateService`, `DiscordIntegrationService`, `BotService`) para realizar operações assíncronas, gerenciar estado e interagir com o backend.
- **Backend:** A comunicação é feita principalmente via `ApiService` (HTTP e WebSockets), garantindo que o frontend receba atualizações em tempo real e possa enviar ações.
- **Electron:** A integração com o processo `main` do Electron permite funcionalidades nativas, como controle da janela e acesso a informações do sistema, via `electronAPI` exposto pelo `preload.ts`.

## 💡 Considerações e Potenciais Melhoriass

- **Roteamento:** A abordagem de roteamento imperativo (`currentView` com `*ngIf`) pode se tornar complexa em aplicações maiores. Considerar a migração para o roteador declarativo do Angular (`RouterModule.forRoot`) para melhor escalabilidade e manutenção.
- **Gerenciamento de Estado:** Para aplicações mais complexas, a gestão de estado global diretamente no componente `App` pode levar a um 'componente gorducho'. Avaliar a adoção de uma biblioteca de gerenciamento de estado (ex: NgRx, Akita, ou uma solução baseada em RxJS mais robusta) para dados compartilhados.
- **Refatoração de Template:** O `app-simple.html` contém muitos estilos inline. Mover esses estilos para `app.scss` ou para os arquivos SCSS dos componentes aninhados melhoraria a manutenibilidade.
- **Separação de Preocupações:** O `App` componente atualmente lida com uma ampla gama de responsabilidades (inicialização, gerenciamento de UI, comunicação de dados). Refatorar algumas dessas responsabilidades para serviços mais especializados pode melhorar a modularidade.
- **Testes:** Expandir a cobertura de testes unitários e adicionar testes de integração para as interações entre o `App` componente e seus serviços dependentes.

Este módulo é a fundação sobre a qual toda a experiência do usuário do aplicativo é construída.
