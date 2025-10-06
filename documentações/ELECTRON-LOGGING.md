# 📋 Sistema de Logging Avançado do Electron

## 🎯 Problema Resolvido

Anteriormente, os logs do dev tools console do Electron eram salvos de forma incompleta em dois arquivos separados (`app.log` e `frontend.log`), perdendo muitas informações importantes que apareciam no console do DevTools.

## ✅ Solução Implementada

Foi implementado um sistema robusto de logging que captura **TODOS** os logs do dev tools console e os salva em um único arquivo consolidado: `electron.log` na raiz do projeto.

## 🔧 Componentes do Sistema

### 1. **ElectronLogger** (main.ts)
- Classe principal de logging com buffer otimizado
- Salva logs em `electron.log` na raiz do projeto
- Flush automático para erros críticos
- Formatação consistente com timestamps

### 2. **Interceptação de WebContents** (setupWebContentsLogging)
- Captura mensagens do console (console.log, error, warn, info, debug)
- Monitora requisições de rede (requests/responses/errors)
- Detecta crashes do renderer process
- Rastreia navegação e mudanças de título

### 3. **Script Injetado** (DOM ready)
- Intercepta TODOS os console.* calls do renderer
- Captura erros JavaScript globais
- Detecta promises rejeitadas não tratadas
- Envia logs via IPC para o main process

### 4. **Sistema IPC Aprimorado** (preload.ts)
- Método `sendLog()` exposto no `window.electronAPI`
- Interceptação precoce no preload script
- Fallback silencioso para falhas de IPC

## 📁 Estrutura dos Logs

### Formato do Arquivo `electron.log`:
```
================================================================================
[2025-09-26T23:45:30.123Z] ELECTRON SESSION STARTED
================================================================================

[2025-09-26T23:45:30.456Z] [Main:info] 🚀 Criando janela principal do Electron...
[2025-09-26T23:45:31.789Z] [Console:log] 🎮 Frontend carregado no Electron!
[2025-09-26T23:45:32.012Z] [Network:info] REQUEST: GET http://localhost:8080/api/
[2025-09-26T23:45:32.345Z] [Network:info] RESPONSE: GET http://localhost:8080/api/ - 200
[2025-09-26T23:45:33.678Z] [Console:error] Erro na aplicação: Detalhes do erro...
```

### Fontes de Logs Capturadas:
- **Main**: Logs do processo principal do Electron
- **Console**: Todos os console.* do dev tools
- **Network**: Requisições HTTP/HTTPS
- **Navigation**: Mudanças de URL/página
- **Page**: Eventos da página (DOM ready, título, etc.)
- **Setup**: Configuração do sistema de logging
- **Injection**: Scripts injetados no renderer

## 🚀 Como Usar

### 1. **Iniciar o Sistema**
O novo sistema de logging é ativado automaticamente quando o Electron inicia. Não precisa de configuração adicional.

### 2. **Visualizar Logs em Tempo Real**
```bash
# Windows
Get-Content "electron.log" -Wait

# Linux/macOS  
tail -f electron.log
```

### 3. **Logs do Frontend (Angular)**
Todos os logs do Angular/frontend agora aparecem automaticamente no `electron.log` com a fonte `[Console:*]`.

### 4. **Logs de Rede**
Requisições para APIs são capturadas com detalhes completos:
```
[Network:info] REQUEST: POST http://localhost:8080/api/lcu/configure
[Network:info] RESPONSE: POST http://localhost:8080/api/lcu/configure - 200
```

### 5. **Logs de Erro**
Erros JavaScript são capturados com stack trace completo:
```
[Console:error] JavaScript Error: Cannot read property 'x' of undefined at app.component.ts:45:12
```

## 🔍 Debugging Avançado

### DevTools Console
Todos os logs que você vê no DevTools console (F12) agora são automaticamente salvos no arquivo.

### Network Tab
Requisições HTTP são logadas com método, URL e código de status.

### Error Tracking
- Erros JavaScript não capturados
- Promises rejeitadas
- Crashes do renderer process

## 📊 Benefícios

### ✅ **Consolidação**
- Um único arquivo `electron.log` em vez de múltiplos arquivos fragmentados
- Logs organizados por fonte e timestamp

### ✅ **Completude** 
- Captura **100%** dos logs do dev tools console
- Inclui logs de rede, navegação e erros globais

### ✅ **Performance**
- Buffer otimizado com flush inteligente
- Flush imediato apenas para erros críticos
- Tratamento robusto de falhas de I/O

### ✅ **Facilidade de Uso**
- Ativação automática, sem configuração
- Compatível com desenvolvimento e produção
- Logs estruturados e fáceis de filtrar

## 🛠️ Comandos de Manutenção

```bash
# Limpar logs antigos
del electron.log     # Windows
rm electron.log      # Linux/macOS

# Ver apenas logs de erro
findstr "error" electron.log                    # Windows
grep "error" electron.log                       # Linux/macOS

# Ver logs de uma fonte específica
findstr "[Console:" electron.log                # Windows  
grep "\[Console:" electron.log                  # Linux/macOS

# Ver logs das últimas 100 linhas
Get-Content electron.log -Tail 100              # Windows PowerShell
tail -100 electron.log                          # Linux/macOS
```

## 🔄 Comparação: Antes vs Depois

### ❌ **Sistema Anterior**
```
app.log (incompleto):
[App] 🚀 [App] === INICIALIZAÇÃO DO APP ===

frontend.log (incompleto):  
[renderer:log] 🔧 Preload script carregado
```

### ✅ **Sistema Novo**
```
electron.log (completo):
[Main:info] 🚀 Criando janela principal do Electron...
[Console:log] 🔧 Preload script carregado com sucesso
[Console:log] 🎮 Frontend carregado no Electron!
[Console:log] URL atual: http://localhost:8080/api/
[Network:info] REQUEST: GET http://localhost:8080/api/health
[Console:info] ✅ Backend conectado com sucesso
[Console:error] ❌ Erro ao buscar dados: Network timeout
```

## 🎯 Resultado Final

Agora você tem acesso completo a **TODOS** os logs que aparecem no DevTools console do Electron, organizados de forma clara e persistente no arquivo `electron.log`. O sistema funciona automaticamente e captura:

- ✅ Todos os console.log/error/warn/info/debug
- ✅ Requisições HTTP e respostas
- ✅ Erros JavaScript globais
- ✅ Navegação e eventos da página
- ✅ Crashes e problemas do renderer

Não há mais logs perdidos ou incompletos! 🎉
