package com.factory.repair.dispatch.rule;

import com.factory.repair.dispatch.DispatchContext;
import com.factory.repair.dispatch.DispatchScorer;
import com.factory.repair.model.entity.RepairWorker;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;

@Slf4j
@Component
public class SkillMatchRule {

    private final ObjectMapper objectMapper = new ObjectMapper();

    public void evaluate(DispatchScorer scorer, DispatchContext context, int weight) {
        RepairWorker worker = scorer.getWorker();
        List<String> workerSkills = parseSkills(worker.getSkills());
        List<String> required = context.getRequiredSkills();

        if (required == null || required.isEmpty()) {
            scorer.addScore("skill", BigDecimal.valueOf(100), weight);
            return;
        }

        long matchCount = required.stream().filter(workerSkills::contains).count();
        if (matchCount == 0) {
            scorer.eliminate("技能完全不匹配");
            return;
        }

        BigDecimal score = BigDecimal.valueOf(matchCount * 100.0 / required.size());
        scorer.addScore("skill", score, weight);
    }

    private List<String> parseSkills(String skillsJson) {
        if (skillsJson == null || skillsJson.isBlank()) {
            return Collections.emptyList();
        }
        try {
            return objectMapper.readValue(skillsJson, new TypeReference<>() {});
        } catch (Exception e) {
            log.warn("解析技能JSON失败: {}", skillsJson, e);
            return Collections.emptyList();
        }
    }
}
