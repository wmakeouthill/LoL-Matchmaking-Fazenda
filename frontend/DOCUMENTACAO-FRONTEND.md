# Documentação do Frontend do Projeto Vibecoding-do-lol

## 🌐 Visão Geral do Frontend Angular

O frontend é a camada de interface do usuário do projeto Vibecoding-do-lol, construída com o framework Angular. Localizado em `spring-backend/frontend`, ele é responsável por todas as interações visuais e lógicas do lado do cliente, proporcionando uma experiência rica e interativa para o usuário. Ele consome dados do backend via API RESTful e WebSockets, e os apresenta de forma dinâmica através de uma arquitetura baseada em componentes.

O objetivo principal do frontend é exibir informações relevantes do League of Legends (como status de matchmaking, detalhes de partidas, histórico, perfis de jogadores, etc.) e permitir que o usuário interaja com as funcionalidades do aplicativo de forma intuitiva. A modularidade do Angular e o uso de TypeScript garantem um código escalável, manutenível e com forte tipagem.

## 📁 Estrutura do Módulo Frontend

O diretório `spring-backend/frontend` contém a aplicação Angular principal, organizada da seguinte forma:

- `angular.json`: Arquivo de configuração do Angular CLI.
- `package.json` e `package-lock.json`: Gerenciamento de dependências do Node.js para o frontend.
- `public/`: Contém assets estáticos como imagens e favicon.
- `src/`: O diretório principal da aplicação Angular.
  - `app/`: Contém os módulos, componentes, serviços, rotas e interfaces da aplicação.
    - `app.config.ts`: Configurações de aplicação do Angular.
    - `app.routes.ts`: Definições de rotas da aplicação.
    - `app.ts`: O componente raiz da aplicação (`App`).
    - `app.html` e `app-simple.html`: Templates HTML para o componente raiz.
    - `app.scss`: Estilos SCSS globais e de layout para a aplicação.
    - `app.spec.ts`: Testes unitários para o componente `App`.
    - `interfaces.ts`: Definições de interfaces TypeScript compartilhadas.
      - Ex: `custom-pick-ban/`, `dashboard/`, `draft/`, `game-in-progress/`, `lane-selector/`, `leaderboard/`, `match-found/`, `match-history/`, `queue/`.
    - `services/`: Contém os serviços Angular que encapsulam a lógica de negócio, comunicação com APIs e gerenciamento de estado.
      - Ex: `api.ts`, `bot.service.ts`, `champion.service.ts`, `discord-integration.service.ts`, `match-linking.ts`, `player-search.ts`, `profile-icon.service.ts`, `queue-state.ts`.
  - `index.html`: O ponto de entrada HTML da aplicação Angular.
  - `main.ts`: O arquivo principal para bootstrapping da aplicação Angular.
  - `styles.scss`: Estilos globais da aplicação.
- `tsconfig.*.json`: Arquivos de configuração do TypeScript.

## 🧠 Análise Detalhada da Arquitetura e Componentes Chave

### Arquitetura Angular

O frontend segue a arquitetura de componentes do Angular, que promove a modularidade e reutilização de código. Cada funcionalidade ou seção da UI é encapsulada em um componente, que pode ser aninhado ou reutilizado. Serviços são injetados nos componentes para separar a lógica de negócio da lógica de apresentação, e o roteamento (`app.routes.ts`) gerencia a navegação entre as diferentes visualizações da aplicação.

- **Componentes:** São os blocos de construção da UI. Eles combinam um template (HTML), estilos (SCSS) e lógica (TypeScript). O Angular gerencia o ciclo de vida dos componentes (criação, atualização, destruição), permitindo manipulações eficientes do DOM.
- **Serviços:** São classes que encapsulam a lógica de negócio, comunicação com APIs externas, gerenciamento de estado e outras funcionalidades que podem ser compartilhadas entre múltiplos componentes. A injeção de dependência do Angular facilita a utilização e testabilidade dos serviços.
- **Roteamento:** O `app.routes.ts` define as rotas da aplicação, mapeando URLs a componentes específicos. Isso permite uma navegação Single Page Application (SPA) sem recarregar a página inteira.
- **HTTP Client:** O Angular utiliza seu próprio `HttpClient` para se comunicar com o backend, fazendo requisições GET, POST, PUT, DELETE para os endpoints da API.
- **RxJS:** Amplamente utilizado para lidar com eventos assíncronos e fluxos de dados, especialmente na comunicação com o backend e no gerenciamento de estado reativo.

### 📄 Arquivo: `main.ts` (Entry Point)

- **Localização:** `spring-backend/frontend/src/main.ts`
- **Propósito:** O ponto de entrada principal para o bootstrapping da aplicação Angular. Ele inicializa o ambiente Angular e lança o componente raiz (`App`), tornando a aplicação executável.
- **Lógica e Funcionamento:** Utiliza `bootstrapApplication` para iniciar a aplicação, passando o `App` componente como raiz e aplicando as configurações globais de `app.config.ts`. Inclui tratamento de erros para a fase de inicialização.
- **Tecnologias e Implementação:** Angular `bootstrapApplication`, TypeScript.
- **Considerações:** É o primeiro arquivo a ser executado no frontend, crucial para a inicialização e carregamento das configurações globais.

### 📄 Arquivo: `app.ts` (App Componente Principal)

- **Localização:** `spring-backend/frontend/src/app/app.ts`
- **Propósito:** O componente raiz e orquestrador principal da aplicação. Ele gerencia o estado global, a navegação entre as principais visualizações, a comunicação com o backend e a integração com o Electron.
- **Lógica e Funcionamento:**
  - **Gerenciamento de Visão:** Controla a visualização ativa (`currentView`) entre `dashboard`, `queue`, `history`, `leaderboard` e `settings` usando diretivas `*ngIf` e `*ngSwitchCase`.
  - **Estado Global:** Mantém o estado de `currentPlayer`, `queueStatus`, `lcuStatus`, `discordStatus`, e fases do jogo (match encontrado, draft, em jogo).
  - **Inicialização (`initializeAppSequence`):** Uma sequência robusta de inicialização que garante que o backend esteja pronto, a comunicação WebSocket configurada, dados do jogador carregados e identificados, status da fila buscado, e configurações carregadas.
  - **Comunicação:** Interage com `ApiService` para comunicação RESTful e WebSocket, processando mensagens do backend para atualizar o estado da UI.
  - **Integração com Electron:** Detecta o ambiente Electron e expõe métodos para controlar a janela via `electronAPI`.
- **Tecnologias e Implementação:** Angular `@Component`, `CommonModule`, `FormsModule`, RxJS (`Subject`, `filter`, `delay`, `take`), `HttpClient` (via `ApiService`), `ChangeDetectorRef`.
- **Considerações:** A alta responsabilidade do `App` componente pode ser um ponto para refatoração futura, delegando lógicas mais específicas para serviços ou componentes especializados.

### 📄 Arquivo: `app.config.ts` (Configuração da Aplicação)

- **Localização:** `spring-backend/frontend/src/app/app.config.ts`
- **Propósito:** Define a configuração fundamental do aplicativo Angular, registrando provedores essenciais que estarão disponíveis globalmente.
- **Lógica e Funcionamento:** Configura `provideBrowserGlobalErrorListeners()` para erros globais, `provideZoneChangeDetection({ eventCoalescing: true })` para otimização de detecção de mudanças, `provideRouter(routes)` para o roteador (mesmo que as rotas sejam vazias, habilita os serviços de roteamento), e `provideHttpClient()` para requisições HTTP.
- **Tecnologias e Implementação:** Angular `ApplicationConfig`, `@angular/core`, `@angular/router`, `@angular/common/http`.
- **Considerações:** Ponto centralizado para configurar serviços de nível raiz e otimizações de desempenho.

### 📄 Arquivo: `app.routes.ts` (Definição de Rotas)

- **Localização:** `spring-backend/frontend/src/app/app.routes.ts`
- **Propósito:** É o arquivo onde as rotas declarativas do Angular seriam definidas. No entanto, atualmente está com uma array de rotas vazia.
- **Lógica e Funcionamento:** `export const routes: Routes = [];` indica que a navegação primária entre as visualizações é gerenciada imperativamente pelo `App` componente (usando `currentView` e `*ngIf`), e não pelo sistema de roteamento baseado em URL do Angular. O `provideRouter` em `app.config.ts` ainda é necessário para injetar os serviços do roteador.
- **Tecnologias e Implementação:** Angular `Routes`.
- **Considerações:** Para aplicações maiores ou mais complexas, a migração para um roteamento declarativo completo pode melhorar a escalabilidade e manutenibilidade.

### 📄 Arquivo: `app.scss` (Estilos Globais)

- **Localização:** `spring-backend/frontend/src/app/app.scss`
- **Propósito:** Contém os estilos SCSS globais e específicos do layout principal da aplicação, definindo a identidade visual e a responsividade.
- **Lógica e Funcionamento:** Define variáveis CSS (`:root`) para cores, espaçamentos, sombras, gradientes e transições, garantindo um tema consistente. Inclui estilos para o cabeçalho, navegação, área de conteúdo, barra de status, notificações, e elementos comuns como botões e cards. Utiliza animações CSS para feedback visual.
- **Tecnologias e Implementação:** SCSS, CSS Variables, CSS Animations.
- **Considerações:** Estilos globais podem afetar muitos componentes; a organização via variáveis e a contenção de escopo são importantes para evitar conflitos.

### 📄 Arquivo: `app.spec.ts` (Testes Unitários)

- **Localização:** `spring-backend/frontend/src/app/app.spec.ts`
- **Propósito:** Contém os testes unitários para o componente raiz `App`, garantindo sua criação e renderização básica.
- **Lógica e Funcionamento:** Utiliza `TestBed` para configurar o ambiente de teste, cria uma instância do `App` componente e executa asserções (`expect`) para verificar se o componente é criado e se o título é renderizado corretamente. O teste de título pode precisar ser ajustado se o texto literal no template for diferente.
- **Tecnologias e Implementação:** Angular Testing Utilities (`TestBed`, `describe`, `it`, `expect`).
- **Considerações:** Aumentar a cobertura de testes para a lógica de negócio do `App` componente seria benéfico.

### 📄 Arquivo: `interfaces.ts`

- **Localização:** `spring-backend/frontend/src/app/interfaces.ts`
- **Propósito:** Centraliza todas as definições de interfaces TypeScript para os modelos de dados usados em todo o frontend da aplicação. Garante consistência de dados e forte tipagem.
- **Lógica e Funcionamento:** Contém interfaces como `Player`, `QueueStatus`, `QueuedPlayerInfo`, `QueueActivity`, `Lane`, `QueuePreferences`, `LCUStatus`, `MatchFound`, `Notification`, `CurrentGame`, `Match`, `RefreshPlayerResponse`, definindo a estrutura de dados para comunicação com o backend e uso interno.
- **Tecnologias e Implementação:** TypeScript `interface` keyword.
- **Considerações:** Manter as interfaces atualizadas com o backend é vital para evitar erros de tipagem.

### 📁 Serviços Essenciais

Cada serviço em `spring-backend/frontend/src/app/services/` encapsula uma lógica de negócio específica e é projetado para ser injetável e reutilizável:

- **`api.ts` (`ApiService`):** Centraliza todas as chamadas HTTP e WebSocket para o backend, fornecendo uma interface unificada para comunicação RESTful e em tempo real. Lida com a conexão, envio/recebimento de mensagens WebSocket, e o polling de status do LCU/Backend. Inclui `HttpClient` e `RxJS`.
- **`bot.service.ts` (`BotService`):** Lida com as interações relacionadas a bots (especificamente o bot Discord) no frontend, como a adição de bots à fila de matchmaking.
- **`champion.service.ts` (`ChampionService`):** Gerencia o carregamento, cache e fornecimento de dados de campeões do League of Legends, interagindo com o backend para obter informações e oferecendo métodos para busca e filtragem. Utiliza `HttpClient`, `RxJS` para caching e reatividade.
- **`discord-integration.service.ts` (`DiscordIntegrationService`):** Responsável por gerenciar a comunicação em tempo real com as funcionalidades do bot Discord do backend via WebSockets. Inclui reconexão robusta, heartbeats, e exposição de observables para status de conexão e usuários online. Utiliza `WebSocket` API e `RxJS`.
- **`match-linking.ts` (`MatchLinkingService`):** Conecta partidas customizadas criadas no aplicativo com os resultados reais do jogo obtidos via LCU. Gerencia sessões de linking, processa dados pós-jogo e os envia ao backend. Usa `HttpClient` e `RxJS` para monitoramento e comunicação.
- **`player-search.ts` (`PlayerSearchService`):** Centraliza operações de busca e atualização de dados de jogadores (Riot ID, PUUID, Summoner Name). Integra com o LCU para detecção automática de jogador e calcula MMR estimado. Utiliza `HttpClient` e `RxJS` para requisições e tratamento de erros.
- **`profile-icon.service.ts` (`ProfileIconService`):** Eficientemente recupera e gerencia ícones de perfil de invocadores. Implementa caching no `localStorage` e oferece estratégias de fallback para URLs de imagem. Usa `HttpClient`, `RxJS`, e `localStorage`.
- **`queue-state.ts` (`QueueStateService`):** Gerencia o estado da fila de matchmaking no frontend, sincronizando com o backend via polling. Fornece status da fila em tempo real, posição do jogador e métricas da fila via RxJS `BehaviorSubject`. Utiliza `HttpClient` e `RxJS` para o polling.

## 🔗 Integração do Frontend com Backend e Electron

- **Comunicação com Backend:** O frontend interage exclusivamente com o backend através de chamadas de API (RESTful) para dados e, crucialmente, via WebSockets para comunicação em tempo real (matchmaking, atualizações de jogo, etc.). O `ApiService` é o principal ponto de contato para REST.
- **Comunicação com Electron:** Através do script `preload.ts` e dos *handlers* `ipcMain` em `main.ts`, o frontend pode invocar funcionalidades nativas do sistema (ex: controlar a janela, acessar informações do aplicativo). Isso é feito via `ipcRenderer.invoke` (no frontend) e `ipcMain.handle` (no processo main do Electron), garantindo uma ponte de comunicação segura e tipada.

Esta documentação fornece uma análise aprofundada do módulo Frontend. Em seguida, vamos analisar a documentação do Backend, e depois as documentações específicas de cada componente/serviço.
