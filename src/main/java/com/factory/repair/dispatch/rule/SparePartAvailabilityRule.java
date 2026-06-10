package com.factory.repair.dispatch.rule;

import com.factory.repair.dispatch.DispatchContext;
import com.factory.repair.dispatch.DispatchScorer;
import com.factory.repair.mapper.SparePartMapper;
import com.factory.repair.model.entity.Equipment;
import com.factory.repair.model.entity.SparePart;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;

@Component
@RequiredArgsConstructor
public class SparePartAvailabilityRule {

    private final SparePartMapper sparePartMapper;

    public void evaluate(DispatchScorer scorer, DispatchContext context, int weight) {
        String equipmentType = context.getFaultType().getEquipmentType();
        List<SparePart> parts = sparePartMapper.selectByEquipmentType(equipmentType);

        if (parts.isEmpty()) {
            scorer.addScore("sparePart", BigDecimal.valueOf(100), weight);
            return;
        }

        long availableCount = parts.stream().filter(p -> p.getAvailableQty() > 0).count();
        BigDecimal score = BigDecimal.valueOf(availableCount * 100.0 / parts.size());
        scorer.addScore("sparePart", score, weight);
    }
}
