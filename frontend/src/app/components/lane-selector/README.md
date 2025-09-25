# Documentação do Componente `LaneSelectorComponent`

## 📄 Visão Geral

O `LaneSelectorComponent` é um modal de seleção de posições (lanes) utilizado para permitir que o usuário defina suas preferências de fila para partidas. Ele oferece uma interface visual para escolher uma lane primária e uma secundária, além de uma opção para aceitar partidas automaticamente. O componente é essencial para configurar o perfil do jogador antes de entrar em uma fila de matchmaking.

## 📁 Estrutura do Diretório

- `lane-selector.ts`: Contém a lógica do componente, gerenciamento de seleção de lanes, e emissão de preferências.
- `lane-selector.html`: Define a estrutura visual (template) do modal de seleção de lanes.
- `lane-selector.scss`: Contém os estilos específicos do modal, garantindo um design visualmente atraente.

## 💡 `lane-selector.ts`

Este é o arquivo TypeScript que implementa a lógica do `LaneSelectorComponent`.

### Propósito e Funcionalidades Principais

As funcionalidades incluem:

- **Gerenciamento de Preferências:** Recebe as preferências atuais de fila (`currentPreferences`) como entrada e emite as preferências atualizadas (`confirm` event) quando o usuário confirma sua seleção.
- **Seleção de Lanes:** Permite ao usuário escolher uma `primaryLane` e uma `secondaryLane` a partir de uma lista predefinida de lanes. Impede que a lane secundária seja igual à primária.
- **Opção de Auto-Aceitar:** Inclui uma opção para o usuário ativar/desativar o `autoAccept` de partidas.
- **Validação:** `isValidSelection()` garante que tanto a lane primária quanto a secundária foram selecionadas antes de permitir a confirmação.
- **Emissão de Eventos:** Emite `close` quando o modal é fechado e `confirm` com as `QueuePreferences` atualizadas.
- **Métodos Auxiliares:** Fornece métodos como `getLaneName()` e `getLaneIcon()` para formatar e exibir as informações das lanes na interface.

## 🎨 `lane-selector.html`

Este arquivo define a estrutura HTML do `LaneSelectorComponent`, responsável pela interface visual do modal de seleção de lanes.

### Estrutura e Elementos Chave

O template HTML é composto pelas seguintes seções principais:

- **Overlay (`lane-selector-overlay`):** Uma camada de fundo que escurece o restante da tela e fecha o modal ao ser clicada, com renderização condicional baseada na visibilidade do modal (`*ngIf="isVisible"`).
- **Modal Principal (`lane-selector-modal`):** O contêiner central do modal, que impede o fechamento ao ser clicado diretamente (`$event.stopPropagation()`).
- **Header do Modal (`modal-header`):** Contém o título "Selecionar Posições" e um botão para fechar o modal (`close-btn`).
- **Conteúdo do Modal (`modal-content`):** A área principal que contém as seções de seleção de lanes e preferências.
  - **Seção de Seleção (`selection-section`):** Duas seções (para Lane Primária e Lane Secundária) que exibem um grid de botões de lane (`lane-button`). Cada botão exibe um ícone, nome e descrição da lane. O botão selecionado tem uma classe `selected` e o botão desabilitado (`disabled`) é para a lane já escolhida como primária.
  - **Seção de Preferências (`preferences-section`):** Contém um checkbox (`checkbox-label`) para "Aceitar partidas automaticamente", usando `[(ngModel)]="autoAccept"`.
  - **Resumo da Seleção (`selection-summary`):** Exibe um resumo das lanes primária e secundária selecionadas, incluindo seus ícones e nomes, e o status da opção de auto-aceitar.
- **Footer do Modal (`modal-footer`):** Contém botões para "Cancelar" (`btn-secondary`) e "Entrar na Fila" (`btn-primary`), com o botão de entrar na fila desabilitado se a seleção for inválida (`[disabled]="!isValidSelection()"`).

### Interatividade e Ligação de Dados

O template utiliza as seguintes diretivas e ligações de dados do Angular:

- **`*ngIf`:** Para renderização condicional de seções, como a exibição da seção de lane secundária apenas se uma lane primária for selecionada.
- **`*ngFor`:** Para iterar sobre a lista de `lanes`, gerando dinamicamente os botões de seleção.
- **Interpolação `{{ }}` e Property Binding `[ ]`:** Para exibir dinamicamente ícones, nomes e descrições de lanes, e para aplicar classes CSS (`selected`, `disabled`) com base no estado da seleção.
- **Event Binding `( )`:** Para lidar com cliques nos botões de lane (`selectPrimaryLane()`, `selectSecondaryLane()`) e nos botões do footer (`onConfirm()`, `onClose()`).
- **`[(ngModel)]`:** Para a ligação de dados bidirecional na checkbox de `autoAccept`.

## 🖌️ `lane-selector.scss`

Este arquivo SCSS é responsável pela estilização do `LaneSelectorComponent`, garantindo um design visualmente intuitivo e funcional para o modal de seleção de lanes.

### Estilos Principais e Organização

O SCSS é bem segmentado para estilizar cada parte do modal:

- **Overlay (`.lane-selector-overlay`):** Define a camada de fundo escura e embaçada.
- **Modal Principal (`.lane-selector-modal`):** Estiliza o contêiner do modal, incluindo background com gradiente, bordas, sombra e dimensões máximas. A largura e altura são responsivas (`width: 90%; max-width: 600px; max-height: 90vh;`).
- **Header (`.modal-header`):** Estiliza o cabeçalho, com alinhamento, padding, borda inferior, e estilos para o título (`h3`) e o botão de fechar (`close-btn`).
- **Conteúdo (`.modal-content`):** Define o padding para a área de conteúdo.
- **Seções de Seleção (`.selection-section`):** Estilos para os títulos (`h4`) e a grade de botões de lane (`lane-grid`).
- **Botões de Lane (`.lane-button`):** Estilos para os botões individuais de seleção de lane, incluindo background com gradiente, bordas, efeitos de hover, e estados `selected` (com cores destacadas) e `disabled`.
- **Ícones, Nomes e Descrições de Lane (`.lane-icon`, `.lane-name`, `.lane-desc`):** Estilos para os elementos de texto e ícone dentro dos botões de lane.
- **Seção de Preferências (`.preferences-section`, `.checkbox-label`):** Estilos para a área da checkbox de auto-aceitar, incluindo um checkbox customizado (`input[type="checkbox"]:checked + .checkmark`).
- **Resumo da Seleção (`.selection-summary`):** Estilos para o painel que exibe o resumo das lanes selecionadas.
- **Footer (`.modal-footer`):** Estilos para o rodapé do modal, com botões alinhados à direita.
- **Botões de Ação (`.btn-primary`, `.btn-secondary`):** Estilos genéricos e específicos para os botões "Entrar na Fila" e "Cancelar", com gradientes, efeitos de hover e estados desabilitados.

### Responsividade

O SCSS inclui `media queries` (`@media (max-width: 768px)`) para adaptar o layout a telas menores. Isso inclui ajustes na largura do modal, o layout da grade de lanes (para duas colunas em telas menores) e o layout dos botões no footer (para empilhar verticalmente), garantindo uma boa experiência em dispositivos móveis.

## 🔗 Dependências

Este componente depende de:

- **Módulos Angular:** `CommonModule`, `FormsModule`.
- **Interfaces:** `Lane`, `QueuePreferences` (definidas em `spring-backend/frontend/src/app/interfaces.ts`).

## 🛠️ Tecnologias Utilizadas

- **Angular**: Framework para o desenvolvimento do frontend.
- **TypeScript**: Linguagem de programação para a lógica do componente.
- **HTML**: Estrutura do template.
- **SCSS (Sass)**: Pré-processador CSS para estilos, com gradientes e `box-shadow`.

## 📈 Potenciais Melhorias

- **Testes Unitários:** Adicionar testes para a lógica de seleção de lanes, validação e emissão de eventos.
- **Animações de Transição:** Implementar animações mais suaves ao abrir e fechar o modal.
- **Internacionalização (i18n):** Adicionar suporte a múltiplos idiomas para todos os textos exibidos no modal.
- **Acessibilidade:** Melhorar a acessibilidade para usuários com deficiência, garantindo a navegação por teclado e a compatibilidade com leitores de tela.
