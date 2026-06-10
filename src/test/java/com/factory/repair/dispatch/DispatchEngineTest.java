package com.factory.repair.dispatch;

import com.factory.repair.dispatch.rule.PriorityRule;
import com.factory.repair.dispatch.rule.SkillMatchRule;
import com.factory.repair.dispatch.rule.SparePartAvailabilityRule;
import com.factory.repair.dispatch.rule.WorkloadRule;
import com.factory.repair.mapper.SparePartMapper;
import com.factory.repair.model.dto.DispatchResultDTO;
import com.factory.repair.model.entity.FaultType;
import com.factory.repair.model.entity.RepairWorker;
import com.factory.repair.model.entity.WorkOrder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DispatchEngineTest {

    private DispatchEngine engine;
    private SparePartMapper sparePartMapper;

    @BeforeEach
    void setUp() {
        sparePartMapper = mock(SparePartMapper.class);
        when(sparePartMapper.selectByEquipmentType(anyString())).thenReturn(Collections.emptyList());

        SkillMatchRule skillRule = new SkillMatchRule();
        WorkloadRule workloadRule = new WorkloadRule();
        SparePartAvailabilityRule sparePartRule = new SparePartAvailabilityRule(sparePartMapper);
        PriorityRule priorityRule = new PriorityRule();

        engine = new DispatchEngine(skillRule, workloadRule, sparePartRule, priorityRule);
    }

    private RepairWorker createWorker(Long id, String name, String skills, int currentTasks, int maxTasks, int isOnline) {
        RepairWorker worker = new RepairWorker();
        worker.setId(id);
        worker.setWorkerName(name);
        worker.setSkills(skills);
        worker.setCurrentTasks(currentTasks);
        worker.setMaxTasks(maxTasks);
        worker.setIsOnline(isOnline);
        worker.setCrewId(1L);
        worker.setIsActive(1);
        return worker;
    }

    private DispatchContext createContext(List<RepairWorker> workers, List<String> requiredSkills) {
        WorkOrder wo = new WorkOrder();
        wo.setId(1L);
        wo.setOrderNo("WO001");
        wo.setFaultLevel(1);

        FaultType ft = new FaultType();
        ft.setId(1L);
        ft.setEquipmentType("CNC");
        ft.setRequiredSkills("[\"电气\",\"机械\"]");

        return DispatchContext.builder()
                .workOrder(wo)
                .faultType(ft)
                .requiredSkills(requiredSkills)
                .candidateWorkers(workers)
                .excludedWorkerIds(Collections.emptyList())
                .build();
    }

    @Test
    @DisplayName("无候选人时派工失败")
    void testNoCandidates() {
        DispatchContext ctx = createContext(Collections.emptyList(), List.of("电气"));
        DispatchResultDTO result = engine.dispatch(ctx);
        assertFalse(result.isSuccess());
    }

    @Test
    @DisplayName("选择技能匹配且负载最低的人员")
    void testSelectBestWorker() {
        RepairWorker w1 = createWorker(1L, "张三", "[\"电气\",\"机械\"]", 2, 3, 1);
        RepairWorker w2 = createWorker(2L, "李四", "[\"电气\",\"机械\"]", 0, 3, 1);

        DispatchContext ctx = createContext(Arrays.asList(w1, w2), List.of("电气", "机械"));
        DispatchResultDTO result = engine.dispatch(ctx);

        assertTrue(result.isSuccess());
        assertEquals(2L, result.getWorkerId()); // 李四负载更低
    }

    @Test
    @DisplayName("满载人员被淘汰")
    void testFullLoadEliminated() {
        RepairWorker w1 = createWorker(1L, "张三", "[\"电气\"]", 3, 3, 1);
        RepairWorker w2 = createWorker(2L, "李四", "[\"电气\"]", 1, 3, 1);

        DispatchContext ctx = createContext(Arrays.asList(w1, w2), List.of("电气"));
        DispatchResultDTO result = engine.dispatch(ctx);

        assertTrue(result.isSuccess());
        assertEquals(2L, result.getWorkerId());
    }

    @Test
    @DisplayName("技能不匹配被淘汰")
    void testNoSkillMatchEliminated() {
        RepairWorker w1 = createWorker(1L, "张三", "[\"焊接\"]", 0, 3, 1);

        DispatchContext ctx = createContext(List.of(w1), List.of("电气"));
        DispatchResultDTO result = engine.dispatch(ctx);

        assertFalse(result.isSuccess());
    }

    @Test
    @DisplayName("排除指定人员")
    void testExcludeWorkers() {
        RepairWorker w1 = createWorker(1L, "张三", "[\"电气\"]", 0, 3, 1);
        RepairWorker w2 = createWorker(2L, "李四", "[\"电气\"]", 0, 3, 1);

        DispatchContext ctx = DispatchContext.builder()
                .workOrder(new WorkOrder() {{ setId(1L); setFaultLevel(1); }})
                .faultType(new FaultType() {{ setEquipmentType("CNC"); }})
                .requiredSkills(List.of("电气"))
                .candidateWorkers(Arrays.asList(w1, w2))
                .excludedWorkerIds(List.of(1L))
                .build();

        DispatchResultDTO result = engine.dispatch(ctx);
        assertTrue(result.isSuccess());
        assertEquals(2L, result.getWorkerId());
    }
}
