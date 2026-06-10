package com.factory.repair.model.dto;

import lombok.Data;
import java.math.BigDecimal;
import java.util.Map;

@Data
public class DispatchResultDTO {
    private boolean success;
    private Long workOrderId;
    private Long workerId;
    private String workerName;
    private Long crewId;
    private BigDecimal score;
    private Map<String, BigDecimal> scoreDetail;
    private String message;

    public static DispatchResultDTO success(Long workOrderId, Long workerId, String workerName,
                                            Long crewId, BigDecimal score, Map<String, BigDecimal> scoreDetail) {
        DispatchResultDTO dto = new DispatchResultDTO();
        dto.setSuccess(true);
        dto.setWorkOrderId(workOrderId);
        dto.setWorkerId(workerId);
        dto.setWorkerName(workerName);
        dto.setCrewId(crewId);
        dto.setScore(score);
        dto.setScoreDetail(scoreDetail);
        return dto;
    }

    public static DispatchResultDTO fail(Long workOrderId, String message) {
        DispatchResultDTO dto = new DispatchResultDTO();
        dto.setSuccess(false);
        dto.setWorkOrderId(workOrderId);
        dto.setMessage(message);
        return dto;
    }
}
