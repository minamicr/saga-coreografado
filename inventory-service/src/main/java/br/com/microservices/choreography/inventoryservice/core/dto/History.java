package br.com.microservices.choreography.inventoryservice.core.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import br.com.microservices.choreography.inventoryservice.core.enums.EnumSagaStatus;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class History {
    private String source;
    private EnumSagaStatus status;
    private String message;
    private LocalDateTime createdAt;

}
