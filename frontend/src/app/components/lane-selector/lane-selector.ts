import { Component, Input, Output, EventEmitter, OnInit, OnChanges, SimpleChanges, OnDestroy } from '@angular/core';
import { CommonModule } from '@angular/common';
import { Lane, QueuePreferences } from '../../interfaces';

@Component({
  selector: 'app-lane-selector',
  standalone: true,
  imports: [CommonModule],
  templateUrl: './lane-selector.html',
  styleUrl: './lane-selector.scss'
})
export class LaneSelectorComponent implements OnInit, OnChanges, OnDestroy {
  @Input() isVisible = false;
  @Input() currentPreferences: QueuePreferences = {
    primaryLane: '',
    secondaryLane: ''
  };

  @Output() close = new EventEmitter<void>();
  @Output() confirm = new EventEmitter<QueuePreferences>();

  lanes: Lane[] = [
    {
      id: 'top',
      name: 'Topo',
      icon: '🛡️',
      description: 'Tanques e Lutadores'
    },
    {
      id: 'jungle',
      name: 'Selva',
      icon: '🌲',
      description: 'Controle de Objetivos'
    },
    {
      id: 'mid',
      name: 'Meio',
      icon: '⚡',
      description: 'Magos e Assassinos'
    },
    {
      id: 'bot',
      name: 'Atirador',
      icon: '🏹',
      description: 'Dano Sustentado'
    },
    {
      id: 'support',
      name: 'Suporte',
      icon: '🛡️',
      description: 'Proteção e Utilidade'
    }
  ];

  selectedPrimary = '';
  selectedSecondary = '';

  ngOnInit() {
    console.log('🎯 [LaneSelector] Componente inicializado');
    console.log('🎯 [LaneSelector] Estado inicial:', {
      isVisible: this.isVisible,
      currentPreferences: this.currentPreferences
    });
    this.selectedPrimary = this.currentPreferences.primaryLane;
    this.selectedSecondary = this.currentPreferences.secondaryLane;
  }

  ngOnChanges(changes: SimpleChanges) {
    if (changes['isVisible']) {
      console.log('🎯 [LaneSelector] isVisible mudou:', {
        previousValue: changes['isVisible'].previousValue,
        currentValue: changes['isVisible'].currentValue,
        modalAberto: changes['isVisible'].currentValue
      });

      if (changes['isVisible'].currentValue === true) {
        console.log('🎯 [LaneSelector] MODAL DEVE ESTAR VISÍVEL AGORA!');
        // Forçar detecção de mudanças após um pequeno delay
        setTimeout(() => {
          console.log('🎯 [LaneSelector] Verificando se modal está realmente visível no DOM...');
        }, 100);
      }
    }
  }

  selectPrimaryLane(laneId: string) {
    this.selectedPrimary = laneId;
    // Se a lane secundária for igual à primária, limpar
    if (this.selectedSecondary === laneId) {
      this.selectedSecondary = '';
    }
  }

  selectSecondaryLane(laneId: string) {
    // Não permitir selecionar a mesma lane como secundária
    if (laneId !== this.selectedPrimary) {
      this.selectedSecondary = laneId;
    }
  }

  isValidSelection(): boolean {
    return this.selectedPrimary !== '' && this.selectedSecondary !== '';
  }

  onConfirm() {
    if (this.isValidSelection()) {
      this.confirm.emit({
        primaryLane: this.selectedPrimary,
        secondaryLane: this.selectedSecondary
      });
    }
  }

  onClose() {
    console.log('🎯 [LaneSelector] Fechando modal...');
    this.close.emit();
  }

  ngOnDestroy() {
    console.log('🎯 [LaneSelector] Componente destruído');
  }

  getLaneName(laneId: string): string {
    const lane = this.lanes.find(l => l.id === laneId);
    return lane ? lane.name : laneId;
  }

  getLaneIcon(laneId: string): string {
    const lane = this.lanes.find(l => l.id === laneId);
    return lane ? lane.icon : '❓';
  }
}
