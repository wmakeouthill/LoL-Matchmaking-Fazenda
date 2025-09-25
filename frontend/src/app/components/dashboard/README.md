# Documentação do Componente `DashboardComponent`

## 📄 Visão Geral

O `DashboardComponent` é a principal página de aterrissagem da aplicação, atuando como um hub central para o usuário. Ele exibe informações essenciais do jogador, como estatísticas de MMR e ranqueadas, histórico de partidas recentes, status da fila de matchmaking e oferece acesso rápido a funcionalidades como entrar na fila e configurações. O componente gerencia o carregamento e a exibição desses dados de várias fontes (banco de dados customizado, LCU e Riot API), com mecanismos de fallback e caching para garantir uma experiência fluida.

**Nota:** Devido a limitações técnicas na leitura completa dos arquivos `dashboard.ts` e `dashboard.scss`, esta documentação é baseada nas partes do código que foram acessadas. Pode haver funcionalidades adicionais ou detalhes não abordados aqui. Se necessário, o código-fonte deve ser consultado diretamente para uma compreensão completa.

## 📁 Estrutura do Diretório

- `dashboard.ts`: Contém a lógica do componente, gerenciamento de estado, chamadas a serviços e manipulação de dados.
- `dashboard.html`: Define a estrutura visual (template) do componente.
- `dashboard.scss`: Contém os estilos específicos do componente.

## 💡 `dashboard.ts`

Este é o arquivo TypeScript que define o `DashboardComponent`. Ele é responsável pela orquestração de dados e lógica da interface do dashboard.

### Propósito e Funcionalidades Principais

O componente lida com uma gama complexa de funcionalidades, incluindo:

- **Gerenciamento de Dados do Jogador:** Recebe e processa dados do jogador (`Player` Input) e do status da fila (`QueueStatus` Input).
- **Histórico de Partidas:** Carrega e exibe um histórico de partidas recentes, priorizando partidas customizadas do banco de dados, com fallback para o LCU (League Client Update) e, em última instância, para a Riot API ou dados mockados.
- **Estatísticas de Performance:** Calcula e exibe taxa de vitória, sequência de vitórias/derrotas, MMR mais alto e vitórias do dia.
- **Status da Fila:** Mostra o número de jogadores na fila, tempo médio de espera e tempo estimado para a próxima partida.
- **Integração com Serviços:** Interage com `ApiService` para comunicação com o backend e `ChangeDetectorRef` para forçar a detecção de mudanças na UI.
- **Contagem de Partidas Customizadas:** Exibe o número de partidas customizadas registradas.
- **Ações Rápidas:** Fornece botões para o usuário entrar na fila, ver o histórico completo de partidas e acessar as configurações.
- **Gerenciamento de Cache:** Implementa um mecanismo de cache (`sessionStorage`) para dados do LCU, evitando chamadas repetitivas e melhorando o desempenho.

### Observações sobre a Lógica (Baseado em Informações Parciais)

As seções observadas do `dashboard.ts` revelam as seguintes características e padrões:

- **Ciclo de Vida do Componente (`ngOnChanges`, `ngOnInit`, `ngOnDestroy`):**
  - `ngOnChanges`: É crucial para detectar mudanças na propriedade `player` (Input). Contém lógica para evitar loops de processamento, aplicar `throttle` em carregamentos de dados frequentes e resetar o estado do dashboard quando o jogador muda.
  - `ngOnInit`: Inicia o carregamento de todos os dados (`loadAllData()`) quando o componente é inicializado, se houver um jogador disponível e os dados ainda não tiverem sido carregados.
  - `ngOnDestroy`: Realiza a limpeza de `subscriptions` para evitar vazamentos de memória e reseta certas flags de estado, mas mantém o cache do LCU na `sessionStorage` para persistência.
- **Estratégia de Carregamento de Dados (`loadAllData`, `loadRecentMatches`, `loadCustomMatchesFromDatabase`, `loadFromLCUSafe`, `loadFromRiotAPI`):**
  - A lógica prioriza o carregamento de partidas customizadas do banco de dados. Se não houver, tenta buscar do LCU. Se o LCU falhar ou já tiver sido tentado, recorre à Riot API (com logging minimizado para erros esperados) ou gera dados mockados.
  - Flags de controle (`dataLoaded`, `lcuFallbackAttempted`, `customMatchesAttempted`, `fallbackCompleted`, `processingPlayer`) são usadas extensivamente para evitar carregamentos duplicados e controlar o fluxo de fallback.
  - Métodos como `getCachedLCUData` e `setCachedLCUData` implementam um cache em `sessionStorage` para dados do LCU, com um tempo de expiração (`cacheExpireMs`).
- **Processamento de Dados:**
  - `processRiotApiMatches` e `processLCUMatches` convertem as respostas das APIs para o formato `Match[]` utilizado pelo componente.
  - `convertCustomMatchesToDashboard` adapta a estrutura de dados das partidas customizadas do backend para o formato exibível no dashboard, incluindo a lógica para extrair informações do jogador e do campeão.
- **Cálculos e Formatação:** Métodos auxiliares como `getWinRate()`, `getTotalGames()`, `getRankDisplay()`, `getRankLP()`, `formatWaitTime()`, `formatMatchDuration()`, `formatRelativeTime()`, `getCurrentStreak()`, `getStreakType()`, `getHighestMMR()`, `getTodayWins()`, `getRankColor()`, `getWaitTimeText()`, `getPlayerTag()`, `getProfileIconUrl()`, `hasSoloQueueRank()`, `hasFlexRank()`, `getSoloQueueRank()`, `getSoloQueueLP()`, `getFlexRank()`, `getFlexLP()`, e `getRankStatus()` fornecem a lógica para exibir os dados de forma formatada e calcular métricas de performance.
- **Controle de UI:** `cdr.detectChanges()` é usado para forçar a atualização da interface do usuário após a conclusão de operações assíncronas que modificam o estado do componente.
- **Fallback para Ícones de Perfil:** `onProfileIconError` lida com erros no carregamento de ícones de perfil, tentando URLs alternativas e, em última instância, um ícone padrão.
- **Feedback Visual de Botões:** `showButtonFeedback` adiciona um efeito visual de clique aos botões.

## 🎨 `dashboard.html`

Este arquivo define a estrutura HTML do `DashboardComponent`, organizando os diferentes painéis e seções que compõem a interface do usuário.

### Estrutura e Elementos Chave

O template HTML é composto pelas seguintes seções principais:

- **`hero-section`:** A seção de destaque no topo, exibindo o avatar do jogador (`profile-icon`), um badge de ranqueamento (`rank-badge`), e um resumo de suas informações e estatísticas principais (`player-stats-row`), como MMR, ranques (Solo/Duo e Flex), taxa de vitória, total de jogos, e contagem de partidas customizadas. Também inclui um estado de boas-vindas (`welcome-no-player`) se nenhum jogador estiver logado.
- **`quick-actions-section`:** Contém cartões (`action-card`) para ações rápidas, como "Entrar na Fila", "Ver Histórico" e "Configurações".
- **`queue-status-section`:** Exibe o status atual da fila de matchmaking em um grid de cartões (`status-card`), mostrando o número de jogadores na fila, tempo médio de espera, tempo estimado para a próxima partida e o status do sistema.
- **`recent-activity-section`:** Apresenta um histórico de partidas recentes (`recent-matches`). Inclui estados de carregamento (`loading-matches`), erro (`matches-error`) e um fallback para quando não há partidas encontradas (`no-matches`). Cada item de partida (`match-item`) exibe o resultado (vitória/derrota), modo de jogo, duração, campeão, KDA, mudança de MMR e data/hora relativa.
- **`performance-section`:** Detalha estatísticas de performance do jogador em cartões (`performance-card`), como sequência atual (vitória/derrota), MMR mais alto e vitórias do dia.

### Atributos e Diretivas Angular

O template faz uso extensivo de:

- **Interpolação (`{{ }}`):** Para exibir dados do componente (e.g., `{{ player.summonerName }}`, `{{ getWinRate() }}`).
- **Property Binding (`[ ]`):** Para vincular propriedades de elementos a valores do componente (e.g., `[src]="getProfileIconUrl()"`, `[class.victory]="match.isVictory"`, `[style.background]="getRankColor()"`).
- **Event Binding (`( )`):** Para lidar com eventos do usuário (e.g., `(click)="onJoinQueue()"`, `(error)="onProfileIconError($event)"`).
- **Diretivas Estruturais (`*ngIf`, `*ngFor`):** Para renderização condicional de seções e iteração sobre coleções (e.g., `*ngIf="player"`, `*ngFor="let match of recentMatches.slice(0, 3)"`).

## 🖌️ `dashboard.scss`

Este arquivo SCSS fornece a estilização abrangente para o `DashboardComponent`, garantindo um design visualmente atraente e responsivo em diversas resoluções de tela. Ele utiliza variáveis CSS para manter a consistência e modularidade do design.

**Nota:** A análise completa do `dashboard.scss` não foi possível devido a limitações da ferramenta, portanto, esta descrição é baseada nas partes do arquivo que foram acessadas.

### Estilos Principais e Organização

O `dashboard.scss` é bem organizado em seções para cada parte do componente:

- **Estilos do Componente Raiz (`.dashboard`):** Define o layout principal, espaçamentos e altura mínima, estabelecendo a estrutura geral.
- **Seções de Layout:**
  - **Hero Section (`.hero-section`, `.player-card`):** Estilos para o cartão do jogador, incluindo layout flex, background, bordas, e um efeito visual de gradiente no topo. Define a estilização do avatar (`.profile-icon`), do badge de ranque (`.rank-badge`) e da grade de estatísticas (`.player-stats`).
  - **Seções de Informação (`.status-section`, `.actions-section`, `.recent-matches`, `.performance-section`):** Cada uma dessas seções possui estilos para seus títulos (`h3`) e seus respectivos grids ou listas de cartões (`.status-grid`, `.action-buttons`, `.recent-matches`, `.performance-grid`).
- **Cartões e Elementos:**
  - **Cartões de Estatísticas (`.stat-pill`):** Estilos para a exibição individual de cada estatística do jogador, com ícones, valores e rótulos. Inclui estilos específicos para `custom-matches`, `mmr`, `rank`, `winrate`, e `games`.
  - **Cartões de Status (`.status-card`):** Estilos para os cartões que exibem o status da fila, com ícones, valores e rótulos, e um estado `active` com animação `pulse` para o sistema ativo.
  - **Botões de Ação (`.action-card`, `.action-btn`):** Estilos para os botões de ações rápidas, definindo backgrounds, bordas, e efeitos de hover. Diferentes variações (e.g., `primary`, `secondary`, `tertiary`, `queue-btn`) possuem cores de borda e hover específicas.
  - **Itens de Partida Recente (`.match-item`):** Estilos para a exibição de partidas individuais, incluindo resultados (vitória/derrota), detalhes da partida, KDA, e data. Possui classes `victory` e `defeat` para diferenciar visualmente.
  - **Cartões de Performance (`.performance-card`):** Estilos para os cartões que exibem as estatísticas de performance, como sequência atual, MMR mais alto e vitórias do dia.
- **Elementos de Feedback:** Estilos para estados de carregamento (`.loading-matches`), erro (`.matches-error`) e quando não há dados (`.no-matches`).
- **Variáveis e Mixins:** O arquivo faz uso de variáveis CSS (`var(--spacing-md)`, `var(--primary-gold)`) para padronizar espaçamentos, cores e tamanhos de fonte, o que facilita a manutenção e a consistência visual. Embora a análise profunda de mixins não tenha sido possível, a estrutura sugere o uso de recursos avançados do SASS.

### Responsividade e Animações

O SCSS incorpora media queries para garantir que o layout do dashboard se adapte de forma otimizada a diferentes tamanhos de tela, proporcionando uma experiência de usuário consistente em dispositivos variados. Animações, como a `pulse` para o temporizador de aviso e o estado ativo do sistema, e transições para efeitos de hover, contribuem para um feedback visual dinâmico e uma interface mais interativa.

## 🔗 Dependências

Este componente depende de:

- **Módulos Angular:** `CommonModule`.
- **Serviços Angular:** `HttpClient` (indiretamente, via `ApiService`), `ChangeDetectorRef`.
- **Serviços Customizados:** `ApiService` (para comunicação com o backend), `Subscription` (do RxJS para gerenciar observáveis).
- **Interfaces:** `Player`, `QueueStatus`, `Match` (definidas em `spring-backend/frontend/src/app/interfaces.ts`).

## 🛠️ Tecnologias Utilizadas

- **Angular**: Framework para o desenvolvimento do frontend.
- **TypeScript**: Linguagem de programação para a lógica do componente.
- **HTML**: Estrutura do template.
- **SCSS (Sass)**: Pré-processador CSS para estilos, com variáveis CSS.
- **RxJS**: Para programação reativa, especialmente no gerenciamento de assinaturas.

## 📈 Potenciais Melhorias

- **Internacionalização (i18n):** Adicionar suporte a múltiplos idiomas para textos exibidos na UI.
- **Otimização de Imagens:** Implementar lazy loading ou otimização de tamanhos para ícones de perfil e campeões, melhorando o desempenho.
- **Testes Unitários/de Integração:** Expandir a cobertura de testes para a lógica de carregamento de dados, formatação e interações com serviços.
- **Tratamento de Erros de UI:** Exibir mensagens de erro mais específicas e amigáveis ao usuário para falhas de API.
- **Paginação/Virtualização:** Para histórico de partidas muito longos, implementar paginação ou virtualização para melhorar o desempenho de renderização.
- **Configuração de Fallback:** Permitir que o usuário configure a ordem ou preferência das fontes de dados (custom, LCU, Riot API).
- **Otimização de LCU/Riot API:** Explorar formas de otimizar chamadas a essas APIs, considerando seus limites e possíveis atrasos. Em caso de falha da LCU, uma opção seria o Electron fazer uma chamada ao Client API e enviar a resposta ao backend, que então a processaria. Isso minimizaria as chamadas à API da Riot.
