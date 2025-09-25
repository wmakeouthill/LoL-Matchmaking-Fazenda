# Documentação do Componente `DraftConfirmationModalComponent`

## 📄 Visão Geral

O `DraftConfirmationModalComponent` é um modal de confirmação final utilizado ao término da fase de seleção e banimento de campeões. Ele exibe um resumo detalhado das escolhas e banimentos de ambos os times, permitindo que o líder da partida revise e confirme o draft. O modal também oferece a funcionalidade de editar um pick específico, redirecionando o fluxo de volta para o modal de seleção de campeões.

**Nota:** Devido a limitações técnicas na leitura completa dos arquivos `draft-confirmation-modal.ts` e `draft-confirmation-modal.scss`, esta documentação é baseada nas partes do código que foram acessadas. Pode haver funcionalidades adicionais ou detalhes não abordados aqui. Se necessário, o código-fonte deve ser consultado diretamente para uma compreensão completa.

## 📁 Estrutura do Diretório

- `draft-confirmation-modal.ts`: Contém a lógica do modal, incluindo a organização dos dados dos times e a manipulação de eventos de confirmação e edição.
- `draft-confirmation-modal.html`: Define a estrutura visual (template) do modal, exibindo os picks e bans dos times.
- `draft-confirmation-modal.scss`: Contém os estilos específicos do modal, garantindo um design responsivo e visualmente atraente.

## 💡 `draft-confirmation-modal.ts`

Este é o arquivo TypeScript que implementa a lógica do `DraftConfirmationModalComponent`.

### Propósito e Funcionalidades Principais

As funcionalidades observadas incluem:

- **Exibição de Dados do Draft:** Recebe a sessão de pick/ban (`session`) e o jogador atual (`currentPlayer`) como inputs. Organiza e exibe os campeões banidos (`getBannedChampions()`, `getTeamBans()`) e os campeões escolhidos por cada jogador em seus respectivos times e lanes (`getTeamByLane()`).
- **Organização de Times por Lane:** `getSortedTeamByLane()` e `organizeTeamByLanes()` processam os jogadores e seus picks para exibi-los de forma organizada por lane (Top, Jungle, Mid, ADC, Support).
- **Gerenciamento de Cache:** Utiliza um sistema de cache (`_cachedBannedChampions`, `_cachedBlueTeamPicks`, `_cachedRedTeamPicks`, `_cachedBlueTeamByLane`, `_cachedRedTeamByLane`) para otimizar a renderização, invalidando o cache quando a sessão ou a visibilidade do modal mudam (`forceRefresh()`).
- **Interação do Jogador:** Fornece métodos para fechar o modal (`closeModal()`), confirmar o draft (`confirmFinalDraft()`), cancelar o draft (`cancelFinalDraft()`) e, crucialmente, iniciar a edição de um pick (`startEditingPick()`).
- **Verificação de Jogador e Bot:** Inclui lógicas para verificar se um determinado jogador é o jogador atual (`isCurrentPlayer()`) e se é um bot (`isPlayerBot()`), o que influencia a exibição do botão de edição.
- **Emissão de Eventos:** Emite eventos `onClose` (ao fechar o modal), `onConfirm` (ao confirmar o draft) e `onEditPick` (ao solicitar a edição de um pick, passando o `playerId` e o `phaseIndex`).
- **Ações de Edição:** `startEditingPick()` é chamado quando o botão de edição de um pick é clicado, emitindo um evento que instrui o componente pai a iniciar o processo de edição. `startEditingCurrentPlayer()` é um método auxiliar para editar especificamente o pick do jogador atual.

## 🎨 `draft-confirmation-modal.html`

Este arquivo define a estrutura HTML do `DraftConfirmationModalComponent`, responsável pela interface visual do modal de confirmação final.

### Estrutura e Elementos Chave

O template HTML é composto pelas seguintes seções principais:

- **Overlay (`final-confirmation-overlay`):** Uma camada de fundo que escurece o restante da tela e fecha o modal ao ser clicada, com renderização condicional baseada na visibilidade do modal (`*ngIf="isVisible"`).
- **Modal Principal (`final-confirmation-modal`):** O contêiner central do modal, que impede o fechamento ao ser clicado diretamente (`$event.stopPropagation()`).
- **Header (`confirmation-header`):** Contém o título "Confirmar Seleção Final", um ícone (`confirmation-icon`) e uma breve instrução para o usuário.
- **Conteúdo (`confirmation-content`):** A área principal que exibe o resumo do draft.
  - **Teams Container (`teams-container`):** Divide a tela em duas colunas para exibir o **Time Azul** (`blue-team`) e o **Time Vermelho** (`red-team`).
    - **Bans por Time (`team-bans`):** Exibe os campeões banidos por cada equipe (`getTeamBans()`), com slots vazios (`empty-ban`) para completar os 5 bans por time.
    - **Picks por Time (`team-picks`):** Lista os jogadores e seus respectivos picks (`getTeamByLane()`), organizados por lane. Cada `team-slot` exibe informações do jogador (`player-info`, com nome, lane e indicador "(Você)") e do campeão (`champion-info`, com imagem e nome) ou um placeholder (`champion-placeholder`) se o campeão ainda não foi escolhido. Um botão de edição (`edit-btn`) é exibido condicionalmente (`*ngIf="shouldShowEditButton(slot)"`) para jogadores humanos, permitindo a edição do pick (`onButtonClick(slot)`).
- **Ações (`confirmation-actions`):** Contém botões para interagir com o draft: "Cancelar Partida" (`btn-danger`), "Editar Minha Seleção" (`btn-secondary`, que chama `startEditingCurrentPlayer()`) e "Confirmar e Continuar" (`btn-success`).

### Interatividade e Ligação de Dados

O template faz uso extensivo de:

- **`*ngIf`:** Para renderização condicional de seções e elementos com base na visibilidade do modal, na presença de campeões, ou na identificação do jogador atual.
- **`*ngFor`:** Para iterar sobre as coleções de bans e picks, gerando dinamicamente os slots e cards.
- **Interpolação `{{ }}` e Property Binding `[ ]`:** Para exibir dinamicamente nomes de jogadores e campeões, URLs de imagem, e aplicar classes CSS baseadas em condições.
- **Event Binding `( )`:** Para lidar com cliques em botões (confirmar, cancelar, editar) e fechar o modal (`closeModal()`).

## 🖌️ `draft-confirmation-modal.scss`

Este arquivo SCSS é responsável pela estilização do `DraftConfirmationModalComponent`, garantindo um design claro, visualmente organizado e responsivo para o resumo final do draft.

**Nota:** A análise completa do `draft-confirmation-modal.scss` não foi possível devido a limitações da ferramenta, portanto, esta descrição é baseada nas partes do arquivo que foram acessadas.

### Estilos Principais e Organização

O SCSS é bem segmentado para estilizar cada parte do modal:

- **Overlay (`.final-confirmation-overlay`):** Define a camada de fundo escura e embaçada, com animação `fadeIn` para uma transição suave.
- **Modal Principal (`.final-confirmation-modal`):** Estiliza o contêiner do modal, incluindo background com gradiente, bordas arredondadas, dimensões máximas e animação `slideIn` para entrada.
- **Header (`.confirmation-header`):** Estiliza o cabeçalho, com alinhamento centralizado, padding, borda inferior, e estilos para o ícone (`.confirmation-icon`), título (`h2`) e subtítulo (`p`).
- **Conteúdo (`.confirmation-content`):** Define o padding e o overflow para a área de rolagem do conteúdo.
- **Teams Container (`.teams-container`):** Define o layout de grid para os painéis dos times (Azul e Vermelho), com espaçamento adequado.
- **Estilos de Times (`.team`, `.blue-team`, `.red-team`):** Estilos gerais para os painéis das equipes, incluindo background, bordas arredondadas e cores de borda distintas para cada time. Inclui estilos para o cabeçalho do time (`.team-header`, `.team-name`).
- **Bans (`.team-bans`, `.ban-slot`, `.ban-img`, `.ban-name`, `.empty-ban`, `.ban-placeholder`):** Estilos para a exibição dos campeões banidos, com layout flexível, imagens arredondadas, nomes, e placeholders para slots vazios.
- **Picks (`.team-picks`, `.team-slot`, `.slot-content`, `.player-info`, `.champion-info`, `.champion-img`, `.champion-name`, `.champion-placeholder`, `.edit-btn`):** Estilos para os slots de escolha de campeões, com layout flex, informações do jogador (nome, lane, "(Você)"), detalhes do campeão (imagem, nome), placeholders e o botão de edição. O botão de edição tem gradiente e efeitos de hover.
- **Ações (`.confirmation-actions`):** Estilos para a área de botões de ação, com layout flex, espaçamento e borda superior.
- **Botões (`.btn`, `.btn-success`, `.btn-secondary`, `.btn-danger`, `.btn-lg`):** Estilos genéricos e específicos para os botões do modal, com gradientes de cor, efeitos de hover, e tamanhos diferentes.

### Responsividade

O SCSS incorpora `media queries` (`@media (max-width: 1200px)`, `@media (max-width: 768px)`) para adaptar o layout a telas menores. Isso inclui ajustes no tamanho do modal, no layout dos times (de duas colunas para uma coluna em telas menores), nos espaçamentos e nos tamanhos de fonte e imagem, garantindo que o modal seja funcional e esteticamente agradável em diversos dispositivos.

## 🔗 Dependências

Este componente depende de:

- **Módulos Angular:** `CommonModule`.
- **Serviços Customizados:** `ChampionService`.
- **Interfaces:** `Champion`, `PickBanPhase`, `CustomPickBanSession`, `TeamSlot` (definidas em `spring-backend/frontend/src/app/interfaces.ts` ou diretamente no componente).

## 🛠️ Tecnologias Utilizadas

- **Angular**: Framework para o desenvolvimento do frontend.
- **TypeScript**: Linguagem de programação para a lógica do componente.
- **HTML**: Estrutura do template.
- **SCSS (Sass)**: Pré-processador CSS para estilos, com variáveis e `keyframes` para animações.

## 📈 Potenciais Melhorias

- **Testes Unitários:** Adicionar testes abrangentes para a lógica de organização de dados, detecção de bots e a emissão de eventos de edição.
- **Feedback Visual para Edição:** Adicionar um feedback visual mais claro quando um pick é editado, por exemplo, destacando o slot que foi modificado.
- **Internacionalização (i18n):** Implementar suporte a múltiplos idiomas para todos os textos exibidos no modal.
- **Acessibilidade:** Melhorar a acessibilidade para usuários com deficiência, garantindo que a navegação por teclado e leitores de tela funcionem perfeitamente.
- **Validação de Estado:** Adicionar validações mais robustas no início dos métodos para garantir que a `session` e `currentPlayer` não sejam nulos, evitando erros de runtime.
