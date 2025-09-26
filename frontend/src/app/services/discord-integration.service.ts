import { Injectable } from '@angular/core';
import { BehaviorSubject, Observable } from 'rxjs';
import { ApiService } from './api';

@Injectable({
  providedIn: 'root'
})
export class DiscordIntegrationService {
  private isBackendConnected = false;
  private discordUsersOnline: any[] = [];
  private currentDiscordUser: any = null;
  private isInDiscordChannel = false;

  // Observables para componentes
  private usersSubject = new BehaviorSubject<any[]>([]);
  private connectionSubject = new BehaviorSubject<boolean>(false);

  // Contador de instâncias para debug
  private static instanceCount = 0;
  private instanceId: number;

  // Throttling simplificado - apenas proteção básica contra spam
  private lastStatusRequest = 0;
  private readonly STATUS_REQUEST_COOLDOWN = 2000;

  // Sistema de atualizações automáticas via WebSocket
  private autoUpdateInterval?: number;
  private readonly AUTO_UPDATE_INTERVAL = 60000;
  private lastAutoUpdate = 0;

  // ✅ NOVO: Referência para o ApiService para repassar mensagens
  private apiService: ApiService;
  private baseUrl: string;

  constructor(apiService: ApiService) {
    this.apiService = apiService;
    this.baseUrl = this.apiService.getBaseUrl();
    DiscordIntegrationService.instanceCount++;
    this.instanceId = DiscordIntegrationService.instanceCount;
    console.log(`🔧 [DiscordService] Instância #${this.instanceId} criada (Total: ${DiscordIntegrationService.instanceCount})`);

    // ✅ CORREÇÃO: Usar WebSocket do ApiService em vez de criar conexões conflitantes
    console.log(`🔧 [DiscordService #${this.instanceId}] Usando WebSocket do ApiService`);

    // Escutar mensagens WebSocket do ApiService
    this.apiService.onWebSocketMessage().subscribe({
      next: (message) => {
        const t = (message && typeof message.type === 'string') ? message.type : '';
        if (t && (t.startsWith('discord_') || t.includes('user'))) {
          this.handleBotMessage(message);
        }
      },
      error: (error) => {
        console.error(`❌ [DiscordService #${this.instanceId}] Erro no WebSocket:`, error);
        this.isBackendConnected = false;
        this.connectionSubject.next(false);
      }
    });

    // Verificar se ApiService WebSocket está conectado
    setTimeout(() => {
      if (this.apiService.isWebSocketConnected()) {
        this.isBackendConnected = true;
        this.connectionSubject.next(true);
        this.requestDiscordStatus();
      }
    }, 1000);
  }

  private handleBotMessage(data: any) {
    console.log(`🔍 [DiscordService #${this.instanceId}] Processando mensagem:`, data.type, data);

    switch (data.type) {
      case 'discord_users_online':
        console.log(`👥 [DiscordService #${this.instanceId}] Usuários Discord online recebidos:`, data.users?.length || 0, 'usuários');
        this.discordUsersOnline = data.users || [];
        this.usersSubject.next(this.discordUsersOnline);
        this.lastAutoUpdate = Date.now();

        if (data.critical) {
          console.log(`🚨 [DiscordService #${this.instanceId}] Broadcast CRÍTICO recebido - atualização imediata`);
        }

        if (data.currentUser) {
          console.log(`👤 [DiscordService #${this.instanceId}] Usuário atual recebido via WebSocket:`, data.currentUser);
          this.currentDiscordUser = data.currentUser;
        }
        break;

      case 'discord_current_user':
        console.log(`👤 [DiscordService #${this.instanceId}] Usuário atual recebido:`, data.currentUser);
        this.currentDiscordUser = data.currentUser;
        break;

      case 'lcu_data_updated':
        console.log(`✅ [DiscordService #${this.instanceId}] Dados do LCU atualizados com sucesso`);
        break;

      case 'discord_links_update':
        console.log(`🔗 [DiscordService #${this.instanceId}] Vinculações Discord atualizadas:`, data.links?.length || 0, 'links');
        break;

      case 'discord_status':
        console.log(`🎮 [DiscordService #${this.instanceId}] Status do Discord recebido:`, data);
        this.isInDiscordChannel = data.inChannel || false;
        this.currentDiscordUser = null;

        if (data.isConnected !== undefined && data.isConnected !== null) {
          console.log(`🎮 [DiscordService #${this.instanceId}] Atualizando status de conexão para:`, data.isConnected);
          this.isBackendConnected = data.isConnected;
          this.connectionSubject.next(data.isConnected);
        }
        break;

      case 'discord_channel_status':
        console.log(`🔍 [DiscordService #${this.instanceId}] Status do canal Discord recebido:`, data);
        this.isInDiscordChannel = data.inChannel || false;
        console.log(`🔍 [DiscordService #${this.instanceId}] Usuários no canal: ${data.usersCount}, inChannel: ${data.inChannel}`);
        break;

      default:
        console.log(`⚠️ [DiscordService #${this.instanceId}] Tipo de mensagem não reconhecido:`, data.type, data);
    }
  }

  // Solicitar status atual do Discord (com throttling e validação)
  requestDiscordStatus() {
    if (!this.apiService.isWebSocketConnected()) {
      console.warn(`⚠️ [DiscordService #${this.instanceId}] WebSocket não está conectado, não é possível solicitar status`);
      return;
    }

    const now = Date.now();
    if (now - this.lastStatusRequest < this.STATUS_REQUEST_COOLDOWN) {
      console.log(`⏱️ [DiscordService #${this.instanceId}] Solicitação ignorada (throttling): ${now - this.lastStatusRequest}ms desde última solicitação`);
      return;
    }

    this.lastStatusRequest = now;
    console.log(`🔍 [DiscordService #${this.instanceId}] Solicitando status do Discord...`);

    try {
      const messages = [
        { type: 'get_discord_status' },
        { type: 'get_discord_users_online' }
      ];

      messages.forEach(msg => {
        if (this.apiService.isWebSocketConnected()) {
          console.log(`📤 [DiscordService #${this.instanceId}] Enviando:`, msg.type);
          this.apiService.sendWebSocketMessage(msg);
        } else {
          console.warn(`⚠️ [DiscordService #${this.instanceId}] WebSocket desconectou durante envio de ${msg.type}`);
        }
      });
    } catch (error) {
      console.error(`❌ [DiscordService #${this.instanceId}] Erro ao enviar solicitações de status:`, error);
    }
  }

  // Sistema de atualização automática como backup
  private startAutoUpdate() {
    this.stopAutoUpdate();

    this.autoUpdateInterval = window.setInterval(() => {
      if (this.apiService.isWebSocketConnected()) {
        const now = Date.now();
        const timeSinceLastUpdate = now - this.lastAutoUpdate;

        if (timeSinceLastUpdate > 120000) {
          console.log(`🔄 [DiscordService #${this.instanceId}] Atualização automática (backup) - última atualização há ${Math.floor(timeSinceLastUpdate / 1000)}s`);
          this.requestDiscordStatus();
        }
      } else {
        console.log(`⚠️ [DiscordService #${this.instanceId}] WebSocket não conectado durante auto-update, ignorando`);
      }
    }, this.AUTO_UPDATE_INTERVAL);
  }

  private stopAutoUpdate() {
    if (this.autoUpdateInterval) {
      clearInterval(this.autoUpdateInterval);
      this.autoUpdateInterval = undefined;
    }
  }

  // Entrar na fila Discord
  joinDiscordQueue(primaryLane: string, secondaryLane: string, username: string, lcuData?: { gameName: string, tagLine: string }) {
    console.log('🎮 [DiscordService] === ENTRADA NA FILA DISCORD ===');

    if (!this.apiService.isWebSocketConnected()) {
      console.error('❌ [DiscordService] WebSocket não conectado');
      return false;
    }

    if (!lcuData || !lcuData.gameName || !lcuData.tagLine) {
      console.error('❌ [DiscordService] Dados do LCU não disponíveis');
      return false;
    }

    const lcuFullName = `${lcuData.gameName}#${lcuData.tagLine}`;
    console.log('🔍 [DiscordService] Procurando usuário Discord para:', lcuFullName);

    const matchingUser = this.discordUsersOnline.find(user => {
      if (user.linkedNickname && user.linkedNickname.gameName && user.linkedNickname.tagLine) {
        const discordFullName = `${user.linkedNickname.gameName}#${user.linkedNickname.tagLine}`;
        return discordFullName === lcuFullName;
      }
      return false;
    });

    if (!matchingUser) {
      console.error('❌ [DiscordService] Usuário Discord não encontrado para:', lcuFullName);
      return false;
    }

    const message = {
      type: 'join_discord_queue',
      data: {
        discordId: matchingUser.id,
        gameName: lcuData.gameName,
        tagLine: lcuData.tagLine,
        lcuData: lcuData,
        linkedNickname: matchingUser.linkedNickname,
        preferences: {
          primaryLane: primaryLane,
          secondaryLane: secondaryLane
        }
      }
    };

    console.log('🎯 [DiscordService] Enviando entrada na fila Discord:', message);
    this.apiService.sendWebSocketMessage(message);
    return true;
  }

  leaveDiscordQueue() {
    if (!this.apiService.isWebSocketConnected()) {
      console.warn('⚠️ WebSocket não está conectado');
      return;
    }

    const message = { type: 'leave_queue' };
    this.apiService.sendWebSocketMessage(message);
    console.log('👋 Saindo da fila Discord');
  }

  // Estados e verificações
  isConnected(): boolean {
    return this.apiService.isWebSocketConnected() && this.isBackendConnected;
  }

  isDiscordBackendConnected(): boolean {
    return this.apiService.isWebSocketConnected() && this.isBackendConnected;
  }

  isInChannel(): boolean {
    return this.isInDiscordChannel;
  }

  getCurrentDiscordUser(): any {
    return this.currentDiscordUser;
  }

  getDiscordUsersOnline(): any[] {
    return this.discordUsersOnline;
  }

  // Observables
  onUsersUpdate(): Observable<any[]> {
    return this.usersSubject.asObservable();
  }

  onConnectionChange(): Observable<boolean> {
    return this.connectionSubject.asObservable();
  }

  checkConnection(): void {
    console.log(`🔍 [DiscordService #${this.instanceId}] Verificando conexão...`);
    if (this.apiService.isWebSocketConnected()) {
      this.requestDiscordStatus();
    } else {
      console.log(`⚠️ [DiscordService #${this.instanceId}] WebSocket não conectado`);
    }
  }

  // Método para enviar dados do LCU para identificação no backend
  sendLCUData(lcuData: { gameName: string, tagLine: string } | { displayName: string }): boolean {
    if (!this.apiService.isWebSocketConnected()) {
      console.error('❌ WebSocket não conectado para enviar dados do LCU');
      return false;
    }

    console.log('🎮 [DiscordService] Enviando dados do LCU para identificação:', lcuData);

    const message = {
      type: 'update_lcu_data',
      lcuData: lcuData
    };

    this.apiService.sendWebSocketMessage(message);
    return true;
  }

  // Cleanup
  ngOnDestroy() {
    console.log(`🛑 [DiscordService #${this.instanceId}] Destruindo instância...`);
    this.stopAutoUpdate();
    this.usersSubject.complete();
    this.connectionSubject.complete();
    this.isBackendConnected = false;
    this.discordUsersOnline = [];
    this.currentDiscordUser = null;
    this.isInDiscordChannel = false;
    console.log(`✅ [DiscordService #${this.instanceId}] Instância destruída com sucesso`);
  }
}
