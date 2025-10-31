import { Injectable } from '@angular/core';
import { Subject } from 'rxjs';

export interface GlobalNotification {
  id: string;
  type: 'info' | 'success' | 'warning' | 'error' | 'session';
  title: string;
  message: string;
  duration?: number; // em ms, undefined = não desaparece automaticamente
  icon?: string; // emoji ou classe de ícone
  data?: any; // dados adicionais
  timestamp: number;
}

/**
 * 🔔 Serviço de Notificações Globais
 * 
 * Gerencia notificações toast que aparecem no canto superior direito da tela.
 * Usado para notificar sobre:
 * - Reconexões de sessão
 * - Sincronizações de dados
 * - Eventos importantes do sistema
 * - Erros e avisos
 */
@Injectable({
  providedIn: 'root'
})
export class GlobalNotificationService {
  private notificationsSubject = new Subject<GlobalNotification>();
  public notifications$ = this.notificationsSubject.asObservable();

  private notificationIdCounter = 0;

  constructor() {
    console.log('✅ [GlobalNotification] Serviço inicializado');
  }

  /**
   * Mostra uma notificação genérica
   */
  show(notification: Omit<GlobalNotification, 'id' | 'timestamp'>): void {
    const fullNotification: GlobalNotification = {
      ...notification,
      id: `notification-${++this.notificationIdCounter}-${Date.now()}`,
      timestamp: Date.now(),
      duration: notification.duration ?? 5000 // 5 segundos por padrão
    };

    this.notificationsSubject.next(fullNotification);
    console.log('🔔 [GlobalNotification] Notificação emitida:', fullNotification);
  }

  /**
   * Mostra uma notificação de sessão atualizada
   */
  showSessionUpdate(data: {
    eventType: 'connected' | 'reconnected' | 'synced' | 'desync';
    summonerName: string;
    customSessionId: string;
    randomSessionId?: string;
    isReconnection?: boolean;
  }): void {
    const icons = {
      connected: '🔗',
      reconnected: '🔄',
      synced: '✅',
      desync: '⚠️'
    };

    const titles = {
      connected: 'Conectado',
      reconnected: 'Reconectado',
      synced: 'Sessão Sincronizada',
      desync: 'Sessão Dessincronizada'
    };

    const randomIdPreview = data.randomSessionId 
      ? ` (${data.randomSessionId.substring(0, 8)}...)` 
      : '';
    
    const messages = {
      connected: `Bem-vindo, ${data.summonerName}!${randomIdPreview}`,
      reconnected: `Você foi reconectado, ${data.summonerName}!${randomIdPreview}`,
      synced: `Sessão sincronizada para ${data.summonerName}${randomIdPreview}`,
      desync: `Problemas de sincronização detectados`
    };

    this.show({
      type: 'session',
      title: titles[data.eventType],
      message: messages[data.eventType],
      icon: icons[data.eventType],
      duration: data.eventType === 'desync' ? 10000 : 5000, // Desync fica mais tempo
      data: data
    });
  }

  /**
   * Mostra uma notificação de informação
   */
  info(title: string, message: string, duration?: number): void {
    this.show({
      type: 'info',
      title,
      message,
      icon: 'ℹ️',
      duration
    });
  }

  /**
   * Mostra uma notificação de sucesso
   */
  success(title: string, message: string, duration?: number): void {
    this.show({
      type: 'success',
      title,
      message,
      icon: '✅',
      duration
    });
  }

  /**
   * Mostra uma notificação de aviso
   */
  warning(title: string, message: string, duration?: number): void {
    this.show({
      type: 'warning',
      title,
      message,
      icon: '⚠️',
      duration
    });
  }

  /**
   * Mostra uma notificação de erro
   */
  error(title: string, message: string, duration?: number): void {
    this.show({
      type: 'error',
      title,
      message,
      icon: '❌',
      duration: duration ?? 10000 // Erros ficam mais tempo
    });
  }
}

