package com.factory.repair.service;

import com.factory.repair.model.enums.WorkOrderStatus;
import com.factory.repair.statemachine.WorkOrderEvent;
import com.factory.repair.statemachine.WorkOrderStateMachine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class StateMachineTest {

    private WorkOrderStateMachine stateMachine;

    @BeforeEach
    void setUp() {
        stateMachine = new WorkOrderStateMachine();
    }

    @Test
    @DisplayName("正常流程: REPORTED -> DISPATCHED -> ACCEPTED -> ARRIVED -> REPAIRING -> COMPLETED -> CLOSED")
    void testNormalFlow() {
        assertEquals(WorkOrderStatus.DISPATCHED, stateMachine.fire(WorkOrderStatus.REPORTED, WorkOrderEvent.DISPATCH));
        assertEquals(WorkOrderStatus.ACCEPTED, stateMachine.fire(WorkOrderStatus.DISPATCHED, WorkOrderEvent.ACCEPT));
        assertEquals(WorkOrderStatus.ARRIVED, stateMachine.fire(WorkOrderStatus.ACCEPTED, WorkOrderEvent.ARRIVE));
        assertEquals(WorkOrderStatus.REPAIRING, stateMachine.fire(WorkOrderStatus.ARRIVED, WorkOrderEvent.START_REPAIR));
        assertEquals(WorkOrderStatus.COMPLETED, stateMachine.fire(WorkOrderStatus.REPAIRING, WorkOrderEvent.COMPLETE));
        assertEquals(WorkOrderStatus.CLOSED, stateMachine.fire(WorkOrderStatus.COMPLETED, WorkOrderEvent.CLOSE));
    }

    @Test
    @DisplayName("暂停和恢复")
    void testPauseAndResume() {
        assertEquals(WorkOrderStatus.PAUSED, stateMachine.fire(WorkOrderStatus.REPAIRING, WorkOrderEvent.PAUSE));
        assertEquals(WorkOrderStatus.REPAIRING, stateMachine.fire(WorkOrderStatus.PAUSED, WorkOrderEvent.RESUME));
    }

    @Test
    @DisplayName("转派流转")
    void testTransfer() {
        assertEquals(WorkOrderStatus.DISPATCHED, stateMachine.fire(WorkOrderStatus.DISPATCHED, WorkOrderEvent.TRANSFER));
        assertEquals(WorkOrderStatus.DISPATCHED, stateMachine.fire(WorkOrderStatus.ACCEPTED, WorkOrderEvent.TRANSFER));
        assertEquals(WorkOrderStatus.DISPATCHED, stateMachine.fire(WorkOrderStatus.ARRIVED, WorkOrderEvent.TRANSFER));
        assertEquals(WorkOrderStatus.DISPATCHED, stateMachine.fire(WorkOrderStatus.REPAIRING, WorkOrderEvent.TRANSFER));
        assertEquals(WorkOrderStatus.DISPATCHED, stateMachine.fire(WorkOrderStatus.PAUSED, WorkOrderEvent.TRANSFER));
    }

    @Test
    @DisplayName("升级流转")
    void testEscalate() {
        assertEquals(WorkOrderStatus.REPORTED, stateMachine.fire(WorkOrderStatus.REPAIRING, WorkOrderEvent.ESCALATE));
    }

    @Test
    @DisplayName("拒单回退")
    void testReject() {
        assertEquals(WorkOrderStatus.REPORTED, stateMachine.fire(WorkOrderStatus.DISPATCHED, WorkOrderEvent.REJECT));
    }

    @Test
    @DisplayName("返修")
    void testReopen() {
        assertEquals(WorkOrderStatus.REPORTED, stateMachine.fire(WorkOrderStatus.COMPLETED, WorkOrderEvent.REOPEN));
        assertEquals(WorkOrderStatus.REPORTED, stateMachine.fire(WorkOrderStatus.CLOSED, WorkOrderEvent.REOPEN));
    }

    @Test
    @DisplayName("取消")
    void testCancel() {
        assertEquals(WorkOrderStatus.CANCELLED, stateMachine.fire(WorkOrderStatus.REPORTED, WorkOrderEvent.CANCEL));
        assertEquals(WorkOrderStatus.CANCELLED, stateMachine.fire(WorkOrderStatus.DISPATCHED, WorkOrderEvent.CANCEL));
        assertEquals(WorkOrderStatus.CANCELLED, stateMachine.fire(WorkOrderStatus.ACCEPTED, WorkOrderEvent.CANCEL));
        assertEquals(WorkOrderStatus.CANCELLED, stateMachine.fire(WorkOrderStatus.PAUSED, WorkOrderEvent.CANCEL));
    }

    @Test
    @DisplayName("非法流转应抛异常")
    void testInvalidTransition() {
        assertThrows(Exception.class, () -> stateMachine.fire(WorkOrderStatus.CLOSED, WorkOrderEvent.ACCEPT));
        assertThrows(Exception.class, () -> stateMachine.fire(WorkOrderStatus.CANCELLED, WorkOrderEvent.DISPATCH));
        assertThrows(Exception.class, () -> stateMachine.fire(WorkOrderStatus.REPORTED, WorkOrderEvent.COMPLETE));
    }

    @Test
    @DisplayName("canFire检查")
    void testCanFire() {
        assertTrue(stateMachine.canFire(WorkOrderStatus.REPORTED, WorkOrderEvent.DISPATCH));
        assertFalse(stateMachine.canFire(WorkOrderStatus.REPORTED, WorkOrderEvent.COMPLETE));
    }

    @Test
    @DisplayName("可用事件查询")
    void testAvailableEvents() {
        Set<WorkOrderEvent> events = stateMachine.availableEvents(WorkOrderStatus.REPAIRING);
        assertTrue(events.contains(WorkOrderEvent.APPLY_SPARE_PART));
        assertTrue(events.contains(WorkOrderEvent.PAUSE));
        assertTrue(events.contains(WorkOrderEvent.COMPLETE));
        assertTrue(events.contains(WorkOrderEvent.TRANSFER));
        assertTrue(events.contains(WorkOrderEvent.ESCALATE));
        assertFalse(events.contains(WorkOrderEvent.ACCEPT));
    }

    @Test
    @DisplayName("备件申请不改变状态")
    void testApplySparePartKeepsState() {
        assertEquals(WorkOrderStatus.REPAIRING, stateMachine.fire(WorkOrderStatus.REPAIRING, WorkOrderEvent.APPLY_SPARE_PART));
    }
}
