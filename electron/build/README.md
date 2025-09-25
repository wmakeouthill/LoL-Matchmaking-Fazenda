# 🎨 Configuração de Ícones

Este diretório contém os arquivos de configuração para os ícones do aplicativo.

## 📋 Arquivos Necessários

### Windows (.ico)

- **Arquivo**: `icon.ico`
- **Tamanhos**: 16x16, 32x32, 48x48, 256x256 pixels
- **Formato**: ICO (Windows Icon)

### macOS (.icns)

- **Arquivo**: `icon.icns`
- **Tamanhos**: 16x16, 32x32, 48x48, 128x128, 256x256, 512x512 pixels
- **Formato**: ICNS (macOS Icon)

### Linux (.png)

- **Arquivo**: `icon.png`
- **Tamanho**: 512x512 pixels (recomendado)
- **Formato**: PNG

## 🛠️ Como Criar os Ícones

### Opção 1: Ferramentas Online

1. **Convertio**: <https://convertio.co/png-ico/>
2. **CloudConvert**: <https://cloudconvert.com/png-to-ico>
3. **IcoConvert**: <https://icoconvert.com/>

### Opção 2: Software Local

- **GIMP** (gratuito)
- **Photoshop** (pago)
- **Inkscape** (gratuito)

### Opção 3: Geradores de Ícone

- **Favicon.io**: <https://favicon.io/>
- **RealFaviconGenerator**: <https://realfavicongenerator.net/>

## 📐 Especificações Técnicas

### Windows ICO

```bash
# Usando ImageMagick
convert icon.png -resize 16x16 icon-16.png
convert icon.png -resize 32x32 icon-32.png
convert icon.png -resize 48x48 icon-48.png
convert icon.png -resize 256x256 icon-256.png
convert icon-16.png icon-32.png icon-48.png icon-256.png icon.ico
```

### macOS ICNS

```bash
# Usando iconutil (macOS)
mkdir icon.iconset
sips -z 16 16 icon.png --out icon.iconset/icon_16x16.png
sips -z 32 32 icon.png --out icon.iconset/icon_16x16@2x.png
sips -z 32 32 icon.png --out icon.iconset/icon_32x32.png
sips -z 64 64 icon.png --out icon.iconset/icon_32x32@2x.png
sips -z 128 128 icon.png --out icon.iconset/icon_128x128.png
sips -z 256 256 icon.png --out icon.iconset/icon_128x128@2x.png
sips -z 256 256 icon.png --out icon.iconset/icon_256x256.png
sips -z 512 512 icon.png --out icon.iconset/icon_256x256@2x.png
sips -z 512 512 icon.png --out icon.iconset/icon_512x512.png
sips -z 1024 1024 icon.png --out icon.iconset/icon_512x512@2x.png
iconutil -c icns icon.iconset
```

## 🎯 Dicas de Design

### Boas Práticas

- Use fundo transparente ou sólido
- Mantenha o design simples e reconhecível
- Teste em diferentes tamanhos
- Use cores contrastantes
- Evite texto pequeno

### Tema do Aplicativo

Para um aplicativo de League of Legends:

- Use cores do jogo (azul, dourado, vermelho)
- Inclua elementos relacionados ao LoL
- Mantenha o estilo consistente com o jogo

## 🔧 Configuração no Electron Builder

Os ícones são automaticamente detectados pelo `electron-builder` baseado na configuração no `package.json`:

```json
{
  "build": {
    "win": {
      "icon": "build/icon.ico"
    },
    "mac": {
      "icon": "build/icon.icns"
    },
    "linux": {
      "icon": "build/icon.png"
    }
  }
}
```

## ✅ Verificação

Após adicionar os ícones, teste o build:

```bash
npm run build:standalone
```

Verifique se os ícones aparecem corretamente no executável gerado.

## 🆘 Problemas Comuns

### Ícone não aparece

- Verifique se o arquivo está no formato correto
- Confirme se o caminho no `package.json` está correto
- Teste com um ícone simples primeiro

### Tamanho muito grande

- Otimize as imagens antes da conversão
- Use ferramentas de compressão
- Remova metadados desnecessários

### Qualidade ruim

- Use imagens de alta resolução como base
- Evite redimensionar imagens pequenas
- Use formatos sem perda (PNG) como fonte
