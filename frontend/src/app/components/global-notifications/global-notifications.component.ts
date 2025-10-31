import { Component, OnInit, OnDestroy } from '@angular/core';
import { CommonModule } from '@angular/common';
import { Subject } from 'rxjs';
import { takeUntil } from 'rxjs/operators';
import { GlobalNotificationService, GlobalNotification } from '../../services/global-notification.service';
import { trigger, transition, style, animate } from '@angular/animations';

/**
 * 🔔 Componente de Notificações Globais
 * 
 * Exibe notificações toast no canto superior direito da tela.
 * Usa animações para entrada e saída suave.
 */
@Component({
  selector: 'app-global-notifications',
  standalone: true,
  imports: [CommonModule],
  template: `
    <div class="notifications-container">
      <div 
        *ngFor="let notification of activeNotifications" 
        class="notification"
        [class.notification-info]="notification.type === 'info'"
        [class.notification-success]="notification.type === 'success'"
        [class.notification-warning]="notification.type === 'warning'"
        [class.notification-error]="notification.type === 'error'"
        [class.notification-session]="notification.type === 'session'"
        [@slideIn]
        (click)="dismissNotification(notification.id)">
        
        <div class="notification-header">
          <span class="notification-icon">{{ notification.icon }}</span>
          <span class="notification-title">{{ notification.title }}</span>
          <button class="notification-close" (click)="dismissNotification(notification.id)">×</button>
        </div>
        
        <div class="notification-message">{{ notification.message }}</div>
        
        <!-- ✅ Informações adicionais para notificações de sessão -->
        <div *ngIf="notification.type === 'session' && notification.data" class="notification-details">
          <div class="detail-line">
            <span class="detail-label">Custom ID:</span>
            <span class="detail-value">{{ notification.data.customSessionId }}</span>
          </div>
          <div class="detail-line" *ngIf="notification.data.randomSessionId">
            <span class="detail-label">Random ID:</span>
            <span class="detail-value">{{ formatSessionId(notification.data.randomSessionId) }}</span>
          </div>
        </div>
        
        <!-- Barra de progresso para auto-dismiss -->
        <div *ngIf="notification.duration" 
             class="notification-progress"
             [style.animation-duration.ms]="notification.duration"></div>
      </div>
    </div>
  `,
  styles: [`
    .notifications-container {
      position: fixed;
      top: 20px;
      right: 20px;
      z-index: 10000;
      max-width: 400px;
      pointer-events: none;
    }

    .notification {
      background: linear-gradient(135deg, #1a1a2e 0%, #16213e 100%);
      border: 1px solid rgba(255, 255, 255, 0.1);
      border-radius: 12px;
      padding: 16px;
      margin-bottom: 12px;
      box-shadow: 0 8px 32px rgba(0, 0, 0, 0.4);
      cursor: pointer;
      pointer-events: all;
      position: relative;
      overflow: hidden;
      backdrop-filter: blur(10px);
      transition: transform 0.2s ease, box-shadow 0.2s ease;
    }

    .notification:hover {
      transform: translateX(-4px);
      box-shadow: 0 12px 48px rgba(0, 0, 0, 0.6);
    }

    .notification-header {
      display: flex;
      align-items: center;
      gap: 8px;
      margin-bottom: 8px;
    }

    .notification-icon {
      font-size: 24px;
      line-height: 1;
    }

    .notification-title {
      flex: 1;
      font-size: 16px;
      font-weight: 600;
      color: #fff;
    }

    .notification-close {
      background: none;
      border: none;
      color: rgba(255, 255, 255, 0.6);
      font-size: 24px;
      line-height: 1;
      cursor: pointer;
      padding: 0;
      width: 24px;
      height: 24px;
      display: flex;
      align-items: center;
      justify-content: center;
      border-radius: 4px;
      transition: all 0.2s ease;
    }

    .notification-close:hover {
      background: rgba(255, 255, 255, 0.1);
      color: #fff;
    }

    .notification-message {
      color: rgba(255, 255, 255, 0.8);
      font-size: 14px;
      line-height: 1.5;
    }

    .notification-details {
      margin-top: 12px;
      padding-top: 12px;
      border-top: 1px solid rgba(255, 255, 255, 0.1);
    }

    .detail-line {
      display: flex;
      justify-content: space-between;
      align-items: center;
      font-size: 11px;
      color: rgba(255, 255, 255, 0.6);
      margin-bottom: 6px;
      gap: 12px;
    }

    .detail-line:last-child {
      margin-bottom: 0;
    }

    .detail-label {
      font-weight: 600;
      min-width: 80px;
      text-transform: uppercase;
      letter-spacing: 0.5px;
      font-size: 10px;
    }

    .detail-value {
      font-family: 'Courier New', monospace;
      color: rgba(255, 255, 255, 0.9);
      font-size: 11px;
      flex: 1;
      text-align: right;
      overflow: hidden;
      text-overflow: ellipsis;
      white-space: nowrap;
    }

    .notification-progress {
      position: absolute;
      bottom: 0;
      left: 0;
      height: 3px;
      background: linear-gradient(90deg, #4facfe 0%, #00f2fe 100%);
      animation: progress-shrink linear forwards;
    }

    @keyframes progress-shrink {
      from { width: 100%; }
      to { width: 0%; }
    }

    /* ✅ Cores específicas por tipo */
    .notification-info {
      border-left: 4px solid #4facfe;
    }

    .notification-success {
      border-left: 4px solid #00f2fe;
    }

    .notification-warning {
      border-left: 4px solid #f6d365;
    }

    .notification-error {
      border-left: 4px solid #fa709a;
    }

    .notification-session {
      border-left: 4px solid #a8edea;
      background: linear-gradient(135deg, #1a2a3e 0%, #162e3e 100%);
    }
  `],
  animations: [
    trigger('slideIn', [
      transition(':enter', [
        style({ transform: 'translateX(400px)', opacity: 0 }),
        animate('300ms ease-out', style({ transform: 'translateX(0)', opacity: 1 }))
      ]),
      transition(':leave', [
        animate('200ms ease-in', style({ transform: 'translateX(400px)', opacity: 0 }))
      ])
    ])
  ]
})
export class GlobalNotificationsComponent implements OnInit, OnDestroy {
  activeNotifications: GlobalNotification[] = [];
  private destroy$ = new Subject<void>();
  private dismissTimers = new Map<string, any>();

  constructor(private notificationService: GlobalNotificationService) {}

  ngOnInit(): void {
    this.notificationService.notifications$
      .pipe(takeUntil(this.destroy$))
      .subscribe(notification => {
        this.addNotification(notification);
      });

    console.log('✅ [GlobalNotifications] Componente inicializado');
  }

  ngOnDestroy(): void {
    this.destroy$.next();
    this.destroy$.complete();
    
    // Limpar todos os timers
    this.dismissTimers.forEach(timer => clearTimeout(timer));
    this.dismissTimers.clear();
  }

  private addNotification(notification: GlobalNotification): void {
    // Adicionar notificação ao topo da lista
    this.activeNotifications.unshift(notification);

    // Limitar a 5 notificações visíveis
    if (this.activeNotifications.length > 5) {
      const removed = this.activeNotifications.pop();
      if (removed) {
        const timer = this.dismissTimers.get(removed.id);
        if (timer) {
          clearTimeout(timer);
          this.dismissTimers.delete(removed.id);
        }
      }
    }

    // Auto-dismiss se tiver duração definida
    if (notification.duration) {
      const timer = setTimeout(() => {
        this.dismissNotification(notification.id);
      }, notification.duration);
      
      this.dismissTimers.set(notification.id, timer);
    }

    console.log('🔔 [GlobalNotifications] Notificação adicionada:', notification.title);
  }

  dismissNotification(id: string): void {
    const index = this.activeNotifications.findIndex(n => n.id === id);
    if (index !== -1) {
      this.activeNotifications.splice(index, 1);
      
      // Limpar timer se existir
      const timer = this.dismissTimers.get(id);
      if (timer) {
        clearTimeout(timer);
        this.dismissTimers.delete(id);
      }

      console.log('🔔 [GlobalNotifications] Notificação removida:', id);
    }
  }

  /**
   * Formata o randomSessionId para exibição (primeiros 8 caracteres)
   */
  formatSessionId(sessionId: string): string {
    if (!sessionId) return 'N/A';
    return sessionId.length > 12 
      ? `${sessionId.substring(0, 12)}...` 
      : sessionId;
  }
}

