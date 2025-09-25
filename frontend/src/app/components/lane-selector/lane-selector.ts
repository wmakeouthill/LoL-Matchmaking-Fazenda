import { Component, Input, Output, EventEmitter, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Lane, QueuePreferences } from '../../interfaces';

@Component({
  selector: 'app-lane-selector',
  standalone: true,
  imports: [CommonModule, FormsModule],
  templateUrl: './lane-selector.html',
  styleUrl: './lane-selector.scss'
})
export class LaneSelectorComponent implements OnInit {
  @Input() isVisible = false;
  @Input() currentPreferences: QueuePreferences = {
    primaryLane: '',
    secondaryLane: '',
    autoAccept: false
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
  autoAccept = false;

  ngOnInit() {
    this.selectedPrimary = this.currentPreferences.primaryLane;
    this.selectedSecondary = this.currentPreferences.secondaryLane;
    this.autoAccept = this.currentPreferences.autoAccept || false;
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
        secondaryLane: this.selectedSecondary,
        autoAccept: this.autoAccept
      });
    }
  }

  onClose() {
    this.close.emit();
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
