package com.factory.repair.dispatch;

import com.factory.repair.model.entity.RepairWorker;
import lombok.Data;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

@Data
public class DispatchScorer {
    private RepairWorker worker;
    private BigDecimal totalScore = BigDecimal.ZERO;
    private Map<String, BigDecimal> scoreDetail = new LinkedHashMap<>();
    private boolean eliminated = false;
    private String eliminationReason;

    public DispatchScorer(RepairWorker worker) {
        this.worker = worker;
    }

    public void addScore(String dimension, BigDecimal score, int weight) {
        BigDecimal weighted = score.multiply(BigDecimal.valueOf(weight)).divide(BigDecimal.valueOf(100));
        scoreDetail.put(dimension, weighted);
        totalScore = totalScore.add(weighted);
    }

    public void eliminate(String reason) {
        this.eliminated = true;
        this.eliminationReason = reason;
    }
}
