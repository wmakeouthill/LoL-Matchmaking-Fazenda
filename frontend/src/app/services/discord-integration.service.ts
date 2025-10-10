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
  private readonly usersSubject = new BehaviorSubject<any[]>([]);
  private readonly connectionSubject = new BehaviorSubject<boolean>(false);

  // Contador de instâncias para debug
  private static instanceCount = 0;
  private readonly instanceId: number;

  // Throttling simplificado - apenas proteção básica contra spam
  private lastStatusRequest = 0;
  private readonly STATUS_REQUEST_COOLDOWN = 2000;

  // Sistema de atualizações automáticas via WebSocket
  private autoUpdateInterval?: number;
  private readonly AUTO_UPDATE_INTERVAL = 60000;
  private lastAutoUpdate = 0;

  // ✅ NOVO: Sistema de dados "stale" para evitar perda durante reconexões
  private isDataStale = false;
  private lastDataUpdate = 0;
  private readonly STALE_DATA_THRESHOLD = 30000; // 30 segundos

  // ✅ NOVO: Referência para o ApiService para repassar mensagens
  private readonly apiService: ApiService;
  private readonly baseUrl: string;

  constructor(apiService: ApiService) {
    this.apiService = apiService;
    this.baseUrl = this.apiService.getBaseUrl();
    DiscordIntegrationService.instanceCount++;
    this.instanceId = DiscordIntegrationService.instanceCount;
    console.log(`🔧 [DiscordService] Instância #${this.instanceId} criada (Total: ${DiscordIntegrationService.instanceCount})`);

    // ✅ NOVO: Recuperar dados persistidos na inicialização
    this.restoreDiscordData();

    // ✅ CORREÇÃO: Usar WebSocket do ApiService em vez de criar conexões conflitantes
    console.log(`🔧 [DiscordService #${this.instanceId}] Usando WebSocket do ApiService`);

    // Escutar mensagens WebSocket do ApiService
    this.apiService.onWebSocketMessage().subscribe({
      next: (message: any) => {
        const t = (message && typeof message.type === 'string') ? message.type : '';
        console.log(`🔍 [DiscordService #${this.instanceId}] Mensagem WebSocket recebida:`, t, message);

        // ✅ CORREÇÃO: Só processar mensagens de Discord, MAS não bloquear outras mensagens
        if (t && (t.startsWith('discord_') || t === 'discord_users' || t === 'discord_status')) {
          console.log(`✅ [DiscordService #${this.instanceId}] Mensagem Discord detectada, processando...`);
          this.handleBotMessage(message);
        } else {
          // ✅ Mensagens não-Discord são ignoradas aqui, mas NÃO bloqueadas
          // O ApiService já as envia para todos os outros subscribers
          console.log(`⏭️ [DiscordService #${this.instanceId}] Mensagem não-Discord ignorada (será processada por outros serviços):`, t);
        }
      },
      error: (error: any) => {
        console.error(`❌ [DiscordService #${this.instanceId}] Erro no WebSocket:`, error);
        this.isBackendConnected = false;
        this.connectionSubject.next(false);
      }
    });

    // ✅ NOVO: Forçar conexão WebSocket se não estiver conectado
    setTimeout(() => {
      if (this.apiService.isWebSocketConnected()) {
        this.isBackendConnected = true;
        this.connectionSubject.next(true);
        this.requestDiscordStatus();
      } else {
        console.log(`🔄 [DiscordService #${this.instanceId}] WebSocket não conectado, forçando conexão...`);
        this.apiService.connect().subscribe({
          next: () => {
            console.log(`✅ [DiscordService #${this.instanceId}] WebSocket conectado via força`);
            this.isBackendConnected = true;
            this.connectionSubject.next(true);
            this.requestDiscordStatus();
          },
          error: (err) => {
            console.warn(`⚠️ [DiscordService #${this.instanceId}] Erro ao conectar WebSocket:`, err);
          }
        });
      }
    }, 1000);
  }

  private handleBotMessage(data: any) {
    console.log(`🔍 [DiscordService #${this.instanceId}] Processando mensagem:`, data.type, data);

    switch (data.type) {
      case 'discord_users':
        console.log(`👥 [DiscordService #${this.instanceId}] Usuários Discord recebidos:`, data.users?.length || 0, 'usuários');

        // ✅ CORREÇÃO: Mesclar dados em vez de substituir completamente
        if (data.users && data.users.length > 0) {
          this.discordUsersOnline = data.users;
          this.usersSubject.next(this.discordUsersOnline);
          console.log(`✅ [DiscordService #${this.instanceId}] Usuários atualizados:`, this.discordUsersOnline.length, 'usuários');
        } else {
          // Só limpar se explicitamente indicado (ex: canal vazio)
          console.log(`⚠️ [DiscordService #${this.instanceId}] Lista vazia recebida, mantendo dados existentes`);
        }

        this.lastAutoUpdate = Date.now();

        // ✅ NOVO: Marcar dados como atualizados e não stale
        this.lastDataUpdate = Date.now();
        this.isDataStale = false;

        // ❌ REMOVIDO: localStorage cache
        // Backend (Redis) é fonte única via WebSocket

        if (data.critical) {
          console.log(`🚨 [DiscordService #${this.instanceId}] Broadcast CRÍTICO recebido - atualização imediata`);
        }

        if (data.currentUser) {
          console.log(`👤 [DiscordService #${this.instanceId}] Usuário atual recebido via WebSocket:`, data.currentUser);
          this.currentDiscordUser = data.currentUser;
          // ✅ NOVO: Persistir usuário atual também
          this.persistCurrentUser();
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
    const now = Date.now();
    if (now - this.lastStatusRequest < this.STATUS_REQUEST_COOLDOWN) {
      console.log(`⏱️ [DiscordService #${this.instanceId}] Solicitação ignorada (throttling): ${now - this.lastStatusRequest}ms desde última solicitação`);
      return;
    }

    this.lastStatusRequest = now;
    console.log(`🔍 [DiscordService #${this.instanceId}] Solicitando status do Discord via backend...`);

    try {
      // Use backend REST API instead of gateway
      this.apiService.getDiscordStatus().subscribe({
        next: (status: any) => {
          console.log(`🎮 [DiscordService #${this.instanceId}] Status do Discord recebido via backend:`, status);
          this.isBackendConnected = status.isConnected || false;
          this.connectionSubject.next(this.isBackendConnected);

          // Solicitar usuários também
          this.requestDiscordUsers();
        },
        error: (err: any) => {
          console.error(`❌ [DiscordService #${this.instanceId}] Erro ao obter status via backend:`, err);
        }
      });

      this.apiService.getDiscordUsers().subscribe({
        next: (response: any) => {
          const users = response.users || response.data || [];
          console.log(`👥 [DiscordService #${this.instanceId}] Usuários Discord recebidos via backend:`, users.length, 'usuários');
          this.discordUsersOnline = users;
          this.usersSubject.next(this.discordUsersOnline);
          this.lastAutoUpdate = Date.now();
        },
        error: (err: any) => {
          console.error(`❌ [DiscordService #${this.instanceId}] Erro ao obter usuários via backend:`, err);
        }
      });
    } catch (error) {
      console.error(`❌ [DiscordService #${this.instanceId}] Erro ao solicitar status via backend:`, error);
    }
  }

  // ✅ NOVO: Método para solicitar usuários do Discord via REST
  requestDiscordUsers() {
    try {
      this.apiService.getDiscordUsers().subscribe({
        next: (response: any) => {
          const users = response.users || response.data || [];
          console.log(`👥 [DiscordService #${this.instanceId}] Usuários Discord recebidos via REST:`, users.length, 'usuários');

          this.discordUsersOnline = users;
          this.usersSubject.next(this.discordUsersOnline);
        },
        error: (error: any) => {
          console.error(`❌ [DiscordService #${this.instanceId}] Erro ao obter usuários via REST:`, error);
        }
      });
    } catch (error) {
      console.error(`❌ [DiscordService #${this.instanceId}] Erro ao solicitar usuários:`, error);
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

  // ✅ NOVO: Métodos de persistência de dados
  private persistDiscordData(): void {
    try {
      const data = {
        users: this.discordUsersOnline,
        timestamp: Date.now()
      };
      // ❌ REMOVIDO: localStorage cache
      console.log(`✅ [DiscordService #${this.instanceId}] Dados do Discord atualizados do backend:`, this.discordUsersOnline.length, 'usuários');
    } catch (error) {
      console.warn(`⚠️ [DiscordService #${this.instanceId}] Falha ao persistir dados do Discord:`, error);
    }
  }

  private persistCurrentUser(): void {
    try {
      if (this.currentDiscordUser) {
        // ❌ REMOVIDO: localStorage cache
        console.log(`✅ [DiscordService #${this.instanceId}] Usuário atual atualizado do backend:`, this.currentDiscordUser);
      }
    } catch (error) {
      console.warn(`⚠️ [DiscordService #${this.instanceId}] Falha ao persistir usuário atual:`, error);
    }
  }

  private restoreDiscordData(): void {
    try {
      // ❌ REMOVIDO: localStorage cache
      // Backend via WebSocket é fonte única
      console.log(`✅ [DiscordService #${this.instanceId}] Aguardando dados do backend via WebSocket`);
    } catch (error) {
      console.warn(`⚠️ [DiscordService #${this.instanceId}] Falha ao restaurar dados do Discord:`, error);
    }
  }

  // ✅ NOVO: Verificar se os dados estão stale e implementar fallback
  private checkDataStaleness(): void {
    const now = Date.now();
    const timeSinceLastUpdate = now - this.lastDataUpdate;

    if (timeSinceLastUpdate > this.STALE_DATA_THRESHOLD && !this.isDataStale) {
      this.isDataStale = true;
      console.log(`⚠️ [DiscordService #${this.instanceId}] Dados marcados como stale (${Math.round(timeSinceLastUpdate / 1000)}s sem atualização)`);

      // Tentar restaurar dados do cache se não há conexão
      if (!this.isBackendConnected) {
        this.restoreDiscordData();
      }
    }
  }

  // ✅ NOVO: Obter usuários com fallback para dados stale
  getUsersWithFallback(): any[] {
    this.checkDataStaleness();

    // Se há dados stale mas não há conexão, mostrar dados cached
    if (this.isDataStale && !this.isBackendConnected && this.discordUsersOnline.length > 0) {
      console.log(`🔄 [DiscordService #${this.instanceId}] Usando dados cached (stale) durante reconexão`);
      return this.discordUsersOnline;
    }

    return this.discordUsersOnline;
  }

  // ✅ NOVO: Verificar se deve mostrar indicador de dados stale
  isDataStaleIndicator(): boolean {
    return this.isDataStale && !this.isBackendConnected;
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
