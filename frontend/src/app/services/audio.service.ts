import { Injectable, OnDestroy } from '@angular/core';

/**
 * ✅ SERVIÇO DE ÁUDIO - SIMPLES E INDIVIDUAL
 *
 * Usa HTMLAudioElement para:
 * - Controle individual por jogador
 * - Sem sincronização (cada um toca independente)
 * - Mute/unmute individual
 * - Simples e confiável
 */
@Injectable({ providedIn: 'root' })
export class AudioService implements OnDestroy {
  // ✅ HTMLAudioElement INDIVIDUAIS - Cada jogador tem seus próprios controles
  private draftAudio: HTMLAudioElement | null = null;
  private matchFoundAudio: HTMLAudioElement | null = null;

  // ✅ Estado local do jogador
  private draftMuted = false;

  constructor() {
    console.log('[AudioService] 🎵 Inicializado - Áudio individual por jogador');
  }

  /**
   * ✅ TOCAR MÚSICA DO DRAFT (simples, sem sincronização)
   * Cada jogador toca independentemente
   */
  async playDraftMusic(draftStartTimestamp?: number): Promise<void> {
    console.log('[AudioService] 🎵 playDraftMusic() - Ignorando timestamp (áudio individual)');

    // ✅ Se já está tocando, não recriar
    if (this.draftAudio && !this.draftAudio.paused) {
      console.log('[AudioService] ✅ Já está tocando');
      return;
    }

    try {
      // ✅ Criar novo elemento de áudio
      if (!this.draftAudio) {
        this.draftAudio = new Audio('/sounds/draft.mp3');
        this.draftAudio.loop = true;
        this.draftAudio.volume = this.draftMuted ? 0 : 0.5;

        console.log('[AudioService] ✅ Audio element criado');
      }

      // ✅ Tocar do início
      this.draftAudio.currentTime = 0;
      await this.draftAudio.play();

      console.log('[AudioService] ✅ TOCANDO! Volume:', this.draftAudio.volume);
    } catch (error) {
      console.error('[AudioService] ❌ ERRO ao tocar:', error);
    }
  }

  /**
   * ✅ PARAR MÚSICA DO DRAFT
   */
  stopDraftMusic(): void {
    console.log('[AudioService] 🛑 Parando draft music');

    if (this.draftAudio) {
      this.draftAudio.pause();
      this.draftAudio.currentTime = 0;
      console.log('[AudioService] ✅ Draft music parado');
    }
  }

  /**
   * ✅ TOGGLE MUTE (ajusta o volume)
   */
  toggleDraftMute(): void {
    this.draftMuted = !this.draftMuted;
    const newVolume = this.draftMuted ? 0 : 0.5;

    console.log('[AudioService] 🔇 Toggle mute:', this.draftMuted, '- Novo volume:', newVolume);

    if (this.draftAudio) {
      this.draftAudio.volume = newVolume;
      console.log('[AudioService] ✅ Volume atualizado');
    } else {
      console.log('[AudioService] ⚠️ Audio element não existe ainda');
    }
  }

  isDraftMuted(): boolean {
    return this.draftMuted;
  }

  /**
   * ✅ SOM SIMPLES: Match Found
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
   * ✅ SOM SIMPLES: Your Turn
   */
  playYourTurn(): void {
    console.log('[AudioService] ⏰ Tocando your_turn');
    const audio = new Audio('/sounds/your_turn.mp3');
    audio.volume = 0.8;
    audio.play().catch(err => console.error('[AudioService] ❌ Erro your_turn:', err));
  }

  /**
   * ✅ CLEANUP
   */
  ngOnDestroy(): void {
    this.stopDraftMusic();

    if (this.draftAudio) {
      this.draftAudio = null;
    }

    console.log('[AudioService] 🔚 Serviço destruído');
  }
}
