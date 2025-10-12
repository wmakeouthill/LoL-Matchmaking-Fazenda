const sharp = require('sharp');
const toIco = require('to-ico');
const fs = require('fs');
const path = require('path');

async function convertIcon() {
  try {
    console.log('🔄 Convertendo icon.png para icon.ico com múltiplas resoluções...');
    
    const inputPath = path.join(__dirname, 'build', 'icon.png');
    const outputPath = path.join(__dirname, 'build', 'icon.ico');
    
    // Verificar se o PNG existe
    if (!fs.existsSync(inputPath)) {
      console.error('❌ Arquivo build/icon.png não encontrado!');
      process.exit(1);
    }
    
    // Fazer backup do icon.ico antigo
    if (fs.existsSync(outputPath)) {
      const backupPath = path.join(__dirname, 'build', 'icon.ico.backup');
      fs.copyFileSync(outputPath, backupPath);
      console.log('📦 Backup criado: build/icon.ico.backup');
    }
    
    // Criar múltiplas resoluções do ícone
    const sizes = [16, 24, 32, 48, 64, 128, 256];
    const pngBuffers = [];
    
    console.log('📐 Criando resoluções: ' + sizes.join(', ') + ' pixels...');
    
    for (const size of sizes) {
      const buffer = await sharp(inputPath)
        .resize(size, size, {
          fit: 'contain',
          background: { r: 0, g: 0, b: 0, alpha: 0 }
        })
        .png()
        .toBuffer();
      pngBuffers.push(buffer);
    }
    
    // Converter os PNGs para um único arquivo ICO
    const icoBuffer = await toIco(pngBuffers);
    
    // Salvar o arquivo ICO
    fs.writeFileSync(outputPath, icoBuffer);
    
    console.log('✅ Ícone convertido com sucesso!');
    console.log('📁 Arquivo gerado: build/icon.ico');
    console.log('📊 Tamanho do arquivo:', (icoBuffer.length / 1024).toFixed(2), 'KB');
    console.log('🎯 O ícone agora contém múltiplas resoluções (16, 24, 32, 48, 64, 128, 256 pixels)');
    console.log('');
    console.log('✅ Agora execute: rebuild-electron-quick.bat');
    
  } catch (error) {
    console.error('❌ Erro ao converter ícone:', error.message);
    process.exit(1);
  }
}

convertIcon();

