package br.com.lolmatchmaking.backend.dto;

import lombok.Builder;
import lombok.Data;
import java.util.List;

@Data
@Builder
public class QueueStatusDTO {
    private int playersInQueue;
    private List<QueuePlayerInfoDTO> playersInQueueList;
    private long averageWaitTime;
    private long estimatedMatchTime;
    private boolean isActive;
}
