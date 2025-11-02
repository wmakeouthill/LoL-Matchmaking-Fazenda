import { Injectable, OnDestroy } from '@angular/core';

/**
 * ✅ SERVIÇO DE ÁUDIO - OTIMIZADO COM WEB AUDIO API
 *
 * Usa Web Audio API para:
 * - Buffer compartilhado (áudio carregado UMA vez por cliente)
 * - GainNode individual (controle de volume/mute por jogador)
 * - Sincronização precisa via timestamp
 */
@Injectable({ providedIn: 'root' })
export class AudioService implements OnDestroy {
  // ✅ Web Audio API - Contexto global (compartilhado)
  private audioContext: AudioContext | null = null;
  private draftAudioBuffer: AudioBuffer | null = null;
  private draftSourceNode: AudioBufferSourceNode | null = null;
  private draftGainNode: GainNode | null = null;
  private draftStartTime: number = 0;

  // ✅ HTMLAudioElement para sons simples (match_found, your_turn)
  private matchFoundAudio: HTMLAudioElement | null = null;

  // ✅ Estado local do jogador
  private draftMuted = false;

  // ✅ SINCRONIZAÇÃO: Duração da música do draft (5 minutos e 6 segundos)
  private readonly DRAFT_MUSIC_DURATION = 306; // segundos

  constructor() {
    console.log('[AudioService] 🎵 Inicializado com Web Audio API');
  }

  /**
   * ✅ INICIALIZAR Web Audio API (lazy loading)
   */
  private async initAudioContext(): Promise<void> {
    if (!this.audioContext) {
      this.audioContext = new AudioContext();
      console.log('[AudioService] ✅ AudioContext criado:', this.audioContext.state);
    }

    // Resume se estiver suspenso (política do navegador)
    if (this.audioContext.state === 'suspended') {
      await this.audioContext.resume();
      console.log('[AudioService] ✅ AudioContext resumed');
    }
  }

  /**
   * ✅ CARREGAR ÁUDIO DO DRAFT (uma vez por cliente)
   */
  private async loadDraftAudio(): Promise<void> {
    if (this.draftAudioBuffer) {
      console.log('[AudioService] ✅ Buffer já carregado, reutilizando');
      return;
    }

    console.log('[AudioService] 📥 Carregando draft.mp3...');

    try {
      // ✅ TIMEOUT: Evitar fetch infinito
      const controller = new AbortController();
      const timeoutId = setTimeout(() => controller.abort(), 10000); // 10s timeout

      const response = await fetch('/sounds/draft.mp3', {
        signal: controller.signal
      });

      clearTimeout(timeoutId);

      if (!response.ok) {
        throw new Error(`HTTP error! status: ${response.status}`);
      }

      const arrayBuffer = await response.arrayBuffer();

      if (!this.audioContext) {
        await this.initAudioContext();
      }

      this.draftAudioBuffer = await this.audioContext!.decodeAudioData(arrayBuffer);
      console.log('[AudioService] ✅ Buffer decodificado:', {
        duration: this.draftAudioBuffer.duration.toFixed(2),
        channels: this.draftAudioBuffer.numberOfChannels,
        sampleRate: this.draftAudioBuffer.sampleRate
      });
    } catch (error: any) {
      if (error.name === 'AbortError') {
        console.error('[AudioService] ❌ Timeout ao carregar draft.mp3 (10s)');
      } else {
        console.error('[AudioService] ❌ Erro ao carregar draft.mp3:', error);
      }
      throw error;
    }
  }

  /**
   * ✅ TOCAR MÚSICA DO DRAFT COM SINCRONIZAÇÃO
   */
  async playDraftMusic(draftStartTimestamp?: number): Promise<void> {
    console.log('[AudioService] 🎵 playDraftMusic()', { draftStartTimestamp });

    try {
      // ✅ Inicializar contexto e carregar buffer
      await this.initAudioContext();
      await this.loadDraftAudio();

      // ✅ Se já está tocando, não recriar
      if (this.draftSourceNode) {
        console.log('[AudioService] ✅ Já está tocando');
        return;
      }

      // ✅ SEGURANÇA: Verificar se buffer foi carregado
      if (!this.draftAudioBuffer) {
        console.error('[AudioService] ❌ Buffer não carregado - abortando');
        return;
      }

      // ✅ Criar chain de áudio: Source → Gain → Destination
      this.draftSourceNode = this.audioContext!.createBufferSource();
      this.draftSourceNode.buffer = this.draftAudioBuffer;
      this.draftSourceNode.loop = true;

      this.draftGainNode = this.audioContext!.createGain();
      this.draftGainNode.gain.value = this.draftMuted ? 0 : 0.5;

      this.draftSourceNode.connect(this.draftGainNode);
      this.draftGainNode.connect(this.audioContext!.destination);

      // ✅ SINCRONIZAÇÃO: Calcular offset de início
      let offset = 0;
      if (draftStartTimestamp && draftStartTimestamp > 0) {
        const now = Date.now();
        const elapsedSeconds = (now - draftStartTimestamp) / 1000;
        offset = elapsedSeconds % this.DRAFT_MUSIC_DURATION;

        console.log('[AudioService] 🎯 Sincronizando áudio:', {
          elapsedSeconds: elapsedSeconds.toFixed(2),
          offset: offset.toFixed(2),
          musicDuration: this.DRAFT_MUSIC_DURATION
        });
      } else {
        console.warn('[AudioService] ⚠️ Sem timestamp válido - iniciando do começo');
      }

      // ✅ Iniciar playback
      this.draftStartTime = this.audioContext!.currentTime;
      this.draftSourceNode.start(0, offset);

      console.log('[AudioService] ✅ TOCANDO! Offset:', offset.toFixed(2), 'Gain:', this.draftGainNode.gain.value);

      // ✅ Cleanup quando terminar (apenas se não estiver em loop)
      this.draftSourceNode.onended = () => {
        console.log('[AudioService] 🔚 Source node terminou');
        this.draftSourceNode = null;
      };

    } catch (error) {
      console.error('[AudioService] ❌ ERRO ao tocar:', error);
      // ✅ Cleanup em caso de erro
      if (this.draftSourceNode) {
        try {
          this.draftSourceNode.disconnect();
        } catch (e) { /* ignore */ }
        this.draftSourceNode = null;
      }
      if (this.draftGainNode) {
        try {
          this.draftGainNode.disconnect();
        } catch (e) { /* ignore */ }
        this.draftGainNode = null;
      }
    }
  }

  /**
   * ✅ PARAR MÚSICA DO DRAFT
   */
  stopDraftMusic(): void {
    console.log('[AudioService] 🛑 Parando draft music');

    if (this.draftSourceNode) {
      try {
        this.draftSourceNode.stop();
        this.draftSourceNode.disconnect();
      } catch (error) {
        console.warn('[AudioService] ⚠️ Erro ao parar source node:', error);
      }
      this.draftSourceNode = null;
    }

    if (this.draftGainNode) {
      this.draftGainNode.disconnect();
      this.draftGainNode = null;
    }
  }

  /**
   * ✅ TOGGLE MUTE (apenas ajusta o GainNode)
   */
  toggleDraftMute(): void {
    this.draftMuted = !this.draftMuted;
    const newGain = this.draftMuted ? 0 : 0.5;

    console.log('[AudioService] 🔇 Toggle mute:', this.draftMuted, '- Novo gain:', newGain);

    if (this.draftGainNode) {
      // ✅ Fade suave para evitar "click"
      this.draftGainNode.gain.setValueAtTime(this.draftGainNode.gain.value, this.audioContext!.currentTime);
      this.draftGainNode.gain.linearRampToValueAtTime(newGain, this.audioContext!.currentTime + 0.1);
      console.log('[AudioService] ✅ Gain atualizado com fade');
    } else {
      console.log('[AudioService] ⚠️ GainNode não existe ainda');
    }
  }

  isDraftMuted(): boolean {
    return this.draftMuted;
  }

  /**
   * ✅ SOM SIMPLES: Match Found (HTMLAudioElement - não precisa sincronizar)
   */
  playMatchFound(): void {
    console.log('[AudioService] 🔔 Tocando match_found');
    this.matchFoundAudio ??= new Audio('/sounds/match_found.mp3');
    this.matchFoundAudio.volume = 0.7;
    this.matchFoundAudio.currentTime = 0;
    this.matchFoundAudio.play().catch(err => console.error('[AudioService] ❌ Erro match_found:', err));
  }

  /**
   * ✅ PARAR Match Found
   */
  stopMatchFound(): void {
    console.log('[AudioService] 🛑 Parando match_found');
    if (this.matchFoundAudio) {
      this.matchFoundAudio.pause();
      this.matchFoundAudio.currentTime = 0;
    }
  }

  /**
   * ✅ SOM SIMPLES: Your Turn (HTMLAudioElement - não precisa sincronizar)
   */
  playYourTurn(): void {
    console.log('[AudioService] ⏰ Tocando your_turn');
    const audio = new Audio('/sounds/your_turn.mp3');
    audio.volume = 0.8;
    audio.play().catch(err => console.error('[AudioService] ❌ Erro your_turn:', err));
  }

  /**
   * ✅ CLEANUP: Fechar AudioContext quando o serviço for destruído
   */
  ngOnDestroy(): void {
    this.stopDraftMusic();

    if (this.audioContext) {
      this.audioContext.close();
      console.log('[AudioService] 🔚 AudioContext fechado');
    }
  }
}
