# Documentação do Componente `DraftChampionModalComponent`

## 📄 Visão Geral

O `DraftChampionModalComponent` é um modal de seleção de campeões utilizado durante a fase de pick/ban em partidas personalizadas. Ele permite que o jogador atual selecione um campeão para banir ou escolher, exibindo uma lista filtrável de campeões e fornecendo feedback visual sobre campeões já banidos ou escolhidos. O modal gerencia seu próprio temporizador e interage com o componente pai (`DraftPickBanComponent`) para comunicar a seleção do campeão.

**Nota:** Devido a limitações técnicas na leitura completa do arquivo `draft-champion-modal.ts`, esta documentação é baseada nas partes do código que foram acessadas. Pode haver funcionalidades adicionais ou detalhes não abordados aqui. Se necessário, o código-fonte deve ser consultado diretamente para uma compreensão completa.

## 📁 Estrutura do Diretório

- `draft-champion-modal.ts`: Contém a lógica do modal, incluindo o carregamento e filtragem de campeões, gerenciamento do timer, e emissão de eventos.
- `draft-champion-modal.html`: Define a estrutura visual (template) do modal, com a grade de campeões, filtros e botões de ação.
- `draft-champion-modal.scss`: Contém os estilos específicos do modal, garantindo um design responsivo e visualmente atraente.

## 💡 `draft-champion-modal.ts`

Este é o arquivo TypeScript que implementa a lógica do `DraftChampionModalComponent`.

### Propósito e Funcionalidades Principais

As funcionalidades observadas incluem:

- **Carregamento de Campeões:** Utiliza o `ChampionService` para carregar a lista completa de campeões e organizá-los por função (`organizeChampionsByRole()`).
- **Filtragem de Campeões:** Permite que o usuário filtre a lista de campeões por lane (`selectRoleInModal()`) e por nome (`searchFilter`), atualizando dinamicamente a grade de exibição (`getModalFilteredChampions()`).
- **Gerenciamento de Estado do Modal:** Controla a visibilidade do modal (`isVisible`), o campeão selecionado (`selectedChampion`), e o papel selecionado para filtragem.
- **Temporizador do Modal:** `startModalTimer()` inicia um contador regressivo (`timeRemaining`) que é exibido no modal. Se o tempo esgotar, `handleModalTimeOut()` é chamado, fechando o modal e cancelando a seleção.
- **Interação com Campeões:** Métodos como `isChampionBanned()` e `isChampionPicked()` verificam se um campeão já foi banido ou escolhido em outras fases do draft, desabilitando a seleção desses campeões no modal.
- **Emissão de Eventos:** Emite eventos `onClose` para fechar o modal e `onChampionSelected` para enviar o campeão escolhido de volta ao componente pai (`DraftPickBanComponent`).
- **Cache:** Implementa um sistema de cache (`_cachedBannedChampions`, `_cachedBlueTeamPicks`, `_cachedRedTeamPicks`, `_cachedModalFilteredChampions`) com duração limitada (`CACHE_DURATION`) para otimizar o desempenho das funções de filtragem e verificação de picks/bans, invalidando o cache em mudanças importantes na sessão (`session`) ou no jogador atual (`currentPlayer`).
- **Ações de Confirmação/Cancelamento:** `confirmModalSelection()` emite o campeão selecionado e fecha o modal, enquanto `cancelModalSelection()` apenas fecha o modal.
- **Informações do Jogador Atual:** Métodos como `getCurrentPlayerNameForModal()`, `getCurrentPlayerTeamForModal()`, e `isCurrentPlayerForModal()` obtêm e exibem informações sobre o jogador que está realizando a ação no modal.

## 🎨 `draft-champion-modal.html`

Este arquivo define a estrutura HTML do `DraftChampionModalComponent`, responsável pela interface visual do modal de seleção de campeões.

### Estrutura e Elementos Chave

O template HTML é organizado da seguinte forma:

- **Overlay (`champion-modal-overlay`):** Uma camada de fundo que escurece o restante da tela e fecha o modal ao ser clicada, com renderização condicional baseada na visibilidade do modal, no estado da sessão e na ação atual (`*ngIf="isVisible && session && session.phase !== 'completed' && session.currentAction < session.phases.length"`).
- **Modal Principal (`champion-modal`):** O contêiner central do modal, que impede o fechamento ao ser clicado diretamente (`$event.stopPropagation()`).
- **Header do Modal (`modal-header`):** Exibe o título da ação (banir/escolher campeão), um ícone representativo (`getCurrentActionIcon()`), o nome e o time do jogador atual (`getCurrentPlayerNameForModal()`, `getCurrentPlayerTeamForModal()`, com indicador "(Você)"). Inclui um temporizador (`timeRemaining`) e um botão para fechar o modal (`modal-close-btn`). A cor da borda inferior do header é dinâmica (`[style.border-color]="getCurrentTeamColor()"`).
- **Conteúdo do Modal (`modal-content`):** A área principal que contém os filtros e a grade de campeões.
  - **Filtros (`modal-filters`):** Contém botões para filtrar campeões por lane (`role-buttons`, com estado `active` dinâmico e evento `(click)="selectRoleInModal()"`) e um campo de busca (`modal-champion-search`, usando `[(ngModel)]="searchFilter"`) para buscar campeões por nome. Exibe a contagem de campeões encontrados (`search-results-count`).
  - **Grid de Campeões (`modal-champions-grid`):** Exibe os campeões filtrados (`*ngFor="let champion of getModalFilteredChampions()"`) em uma grade. Cada `modal-champion-card` mostra o retrato e o nome do campeão. Classes `selected`, `banned` e `picked` são aplicadas dinamicamente (`[class.selected]`, `[class.banned]`, `[class.picked]`) para indicar o estado do campeão. Overlays (`banned-overlay`, `picked-overlay`) fornecem feedback visual para campeões banidos ou já escolhidos. Um estado vazio (`modal-no-champions`) é exibido quando nenhum campeão é encontrado.
- **Footer do Modal (`modal-footer`):** Contém uma pré-visualização do campeão selecionado (`modal-selection-preview`, com `*ngIf="selectedChampion"`) e botões de ação (`modal-actions`) para cancelar (`cancel-btn`) ou confirmar (`confirm-btn`) a seleção. O botão de confirmação é desabilitado se nenhum campeão for selecionado ou se o campeão já estiver banido/escolhido (`[disabled]="..."`).

## 🖌️ `draft-champion-modal.scss`

Este arquivo SCSS é responsável pela estilização do `DraftChampionModalComponent`, garantindo que o modal seja visualmente coerente, intuitivo e responsivo em diferentes tamanhos de tela.

**Nota:** A análise completa do `draft-champion-modal.scss` não foi possível devido a limitações da ferramenta, portanto, esta descrição é baseada nas partes do arquivo que foram acessadas.

### Estilos Principais e Organização

O SCSS é estruturado para estilizar cada parte do modal:

- **Overlay (`.champion-modal-overlay`):** Define a camada de fundo escura e embaçada, com animação `fadeIn` para uma transição suave.
- **Modal Principal (`.champion-modal`):** Estiliza o contêiner do modal, incluindo background com gradiente, bordas arredondadas, dimensões máximas e animação `slideIn` para entrada.
- **Header (`.modal-header`):** Define o layout flexível do cabeçalho, com borda inferior dinâmica, background semi-transparente e estilos para o ícone (`.modal-icon`), título (`h2`), subtítulo (`.modal-subtitle`), e indicador "(Você)" (`.you-indicator`).
- **Temporizador do Modal (`.modal-timer-section`, `.modal-timer`):** Estilos para o contador de tempo, incluindo background, cores, bordas e uma animação `pulse` para o estado de aviso.
- **Botão de Fechar (`.modal-close-btn`):** Estiliza o botão de fechar o modal, com forma arredondada e efeitos de hover.
- **Conteúdo do Modal (`.modal-content`):** Define o padding e o overflow para a área de rolagem do conteúdo.
- **Filtros (`.modal-filters`):** Estilos para a seção de filtros, incluindo o rótulo (`.filter-label`), os botões de função (`.role-buttons`, `.role-btn`, com estados `active` e de hover), e o campo de busca (`.modal-search-container`, `.modal-champion-search`, com ícone de busca e contagem de resultados).
- **Grid de Campeões (`.modal-champions-grid`):** Define o layout de grade para os cards de campeões (`.modal-champion-card`), com espaçamento e rolagem vertical. Inclui estilos para o retrato do campeão (`.modal-champion-portrait`), o rótulo (`.modal-champion-label`), e os overlays de status (`.banned-overlay`, `.picked-overlay`, com texto de "BANIDO" e "ESCOLHIDO"). Há também estilos para o estado sem campeões (`.modal-no-champions`).
- **Footer (`.modal-footer`):** Estilos para o rodapé do modal, incluindo a pré-visualização do campeão selecionado (`.modal-selection-preview`) e os botões de ação (`.modal-actions`, com estilos para `btn-primary`, `btn-secondary` e estado `disabled`).

### Responsividade

O SCSS inclui `media queries` (`@media (max-width: 768px)`) para adaptar o modal a telas menores, ajustando o tamanho do modal, o padding, a direção dos elementos no cabeçalho e no rodapé, e o layout da grade de campeões. Isso garante que o modal seja utilizável em dispositivos móveis e tablets.

## 🔗 Dependências

Este componente depende de:

- **Módulos Angular:** `CommonModule`, `FormsModule`.
- **Serviços Angular:** `ChangeDetectorRef`.
- **Serviços Customizados:** `ChampionService`.
- **Interfaces:** `Champion`, `PickBanPhase`, `CustomPickBanSession` (definidas em `spring-backend/frontend/src/app/interfaces.ts` ou diretamente no componente, como `PickBanPhase` e `CustomPickBanSession`).

## 🛠️ Tecnologias Utilizadas

- **Angular**: Framework para o desenvolvimento do frontend.
- **TypeScript**: Linguagem de programação para a lógica do componente.
- **HTML**: Estrutura do template.
- **SCSS (Sass)**: Pré-processador CSS para estilos, com variáveis e `keyframes` para animações.

## 📈 Potenciais Melhorias

- **Otimização de Performance:** Para grandes coleções de campeões ou em dispositivos com menor desempenho, considerar a virtualização da lista de campeões na grade para reduzir o tempo de renderização.
- **Testes Unitários:** Adicionar testes para a lógica de filtragem, gerenciamento de estado e interações do timer.
- **Internacionalização (i18n):** Adicionar suporte a múltiplos idiomas para todos os textos exibidos no modal.
- **Acessibilidade:** Melhorar a acessibilidade para usuários com deficiência, adicionando atributos ARIA e garantindo a navegação por teclado para todos os elementos interativos.
