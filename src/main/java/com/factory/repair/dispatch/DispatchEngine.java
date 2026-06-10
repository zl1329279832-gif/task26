package com.factory.repair.dispatch;

import com.factory.repair.dispatch.rule.PriorityRule;
import com.factory.repair.dispatch.rule.SkillMatchRule;
import com.factory.repair.dispatch.rule.SparePartAvailabilityRule;
import com.factory.repair.dispatch.rule.WorkloadRule;
import com.factory.repair.model.dto.DispatchResultDTO;
import com.factory.repair.model.entity.RepairWorker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Component
@RequiredArgsConstructor
public class DispatchEngine {

    private final SkillMatchRule skillMatchRule;
    private final WorkloadRule workloadRule;
    private final SparePartAvailabilityRule sparePartAvailabilityRule;
    private final PriorityRule priorityRule;

    @Value("${factory.dispatch.weights.skill:35}")
    private int skillWeight;
    @Value("${factory.dispatch.weights.workload:25}")
    private int workloadWeight;
    @Value("${factory.dispatch.weights.spare-part:20}")
    private int sparePartWeight;
    @Value("${factory.dispatch.weights.response:10}")
    private int responseWeight;
    @Value("${factory.dispatch.weights.priority:10}")
    private int priorityWeight;

    public DispatchResultDTO dispatch(DispatchContext context) {
        List<RepairWorker> candidates = context.getCandidateWorkers();
        if (candidates == null || candidates.isEmpty()) {
            return DispatchResultDTO.fail(context.getWorkOrder().getId(), "无可用维修人员");
        }

        // 排除已指定的人员
        if (context.getExcludedWorkerIds() != null && !context.getExcludedWorkerIds().isEmpty()) {
            candidates = candidates.stream()
                    .filter(w -> !context.getExcludedWorkerIds().contains(w.getId()))
                    .collect(Collectors.toList());
        }

        if (candidates.isEmpty()) {
            return DispatchResultDTO.fail(context.getWorkOrder().getId(), "排除后无可用维修人员");
        }

        // 计算每个候选人评分
        List<DispatchScorer> scorers = new ArrayList<>();
        for (RepairWorker worker : candidates) {
            DispatchScorer scorer = new DispatchScorer(worker);

            skillMatchRule.evaluate(scorer, context, skillWeight);
            if (scorer.isEliminated()) continue;

            workloadRule.evaluate(scorer, context, workloadWeight);
            if (scorer.isEliminated()) continue;

            sparePartAvailabilityRule.evaluate(scorer, context, sparePartWeight);
            priorityRule.evaluate(scorer, context, responseWeight);

            scorers.add(scorer);
        }

        if (scorers.isEmpty()) {
            return DispatchResultDTO.fail(context.getWorkOrder().getId(), "所有候选人员均被淘汰，需人工派工");
        }

        // 按得分降序，同分按当前任务数升序
        scorers.sort((a, b) -> {
            int cmp = b.getTotalScore().compareTo(a.getTotalScore());
            if (cmp != 0) return cmp;
            return Integer.compare(a.getWorker().getCurrentTasks(), b.getWorker().getCurrentTasks());
        });

        DispatchScorer best = scorers.get(0);
        RepairWorker bestWorker = best.getWorker();

        log.info("自动派工结果: workOrderId={}, workerId={}, workerName={}, score={}",
                context.getWorkOrder().getId(), bestWorker.getId(),
                bestWorker.getWorkerName(), best.getTotalScore());

        return DispatchResultDTO.success(
                context.getWorkOrder().getId(),
                bestWorker.getId(),
                bestWorker.getWorkerName(),
                bestWorker.getCrewId(),
                best.getTotalScore(),
                best.getScoreDetail()
        );
    }
}
