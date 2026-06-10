package com.factory.repair.dispatch.rule;

import com.factory.repair.dispatch.DispatchContext;
import com.factory.repair.dispatch.DispatchScorer;
import com.factory.repair.model.entity.RepairWorker;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

@Component
public class PriorityRule {

    public void evaluate(DispatchScorer scorer, DispatchContext context, int weight) {
        RepairWorker worker = scorer.getWorker();

        // 在线状态作为响应能力基础分
        BigDecimal responseScore = worker.getIsOnline() == 1 ? BigDecimal.valueOf(100) : BigDecimal.ZERO;
        scorer.addScore("response", responseScore, weight);
    }
}
