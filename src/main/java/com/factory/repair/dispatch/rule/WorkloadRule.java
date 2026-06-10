package com.factory.repair.dispatch.rule;

import com.factory.repair.dispatch.DispatchContext;
import com.factory.repair.dispatch.DispatchScorer;
import com.factory.repair.model.entity.RepairWorker;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

@Component
public class WorkloadRule {

    public void evaluate(DispatchScorer scorer, DispatchContext context, int weight) {
        RepairWorker worker = scorer.getWorker();

        if (worker.getCurrentTasks() >= worker.getMaxTasks()) {
            scorer.eliminate("已满载");
            return;
        }

        double ratio = 1.0 - (double) worker.getCurrentTasks() / worker.getMaxTasks();
        scorer.addScore("workload", BigDecimal.valueOf(ratio * 100), weight);
    }
}
