package com.maintenance.service;

import com.maintenance.common.BusinessException;
import com.maintenance.entity.DowntimeRecord;
import com.maintenance.entity.SlaRecord;
import com.maintenance.entity.SparePart;
import com.maintenance.entity.SparePartOccupation;
import com.maintenance.entity.Technician;
import com.maintenance.entity.WorkOrder;
import com.maintenance.enums.OccupationStatus;
import com.maintenance.enums.TechnicianAvailability;
import com.maintenance.infrastructure.queue.LocalMessageQueue;
import com.maintenance.mapper.DispatchRecordMapper;
import com.maintenance.mapper.EquipmentMapper;
import com.maintenance.mapper.FaultMapper;
import com.maintenance.mapper.SlaRecordMapper;
import com.maintenance.mapper.SparePartMapper;
import com.maintenance.mapper.SparePartOccupationMapper;
import com.maintenance.mapper.WorkOrderMapper;
import com.maintenance.dto.FaultReportRequest;
import com.maintenance.entity.Equipment;
import com.maintenance.entity.Fault;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Tests verifying concurrency safety fixes:
 * 1. Redis lock prevents concurrent fault reporting for same equipment+level
 * 2. CAS-style state transitions detect concurrent modifications
 * 3. Suspend/resume coordinates SLA pause/resume
 * 4. Reassign releases parts directly (propagates failures)
 * 5. SparePartService idempotent occupy guard
 * 6. SlaService idempotent pause guard
 * 7. Consumer eviction prevents unbounded memory growth
 */
@ExtendWith(MockitoExtension.class)
class ConcurrencySafetyTest {

    // ================================================================
    // NESTED: FaultService concurrent reporting
    // ================================================================
    @Nested
    @DisplayName("FaultService concurrent reporting")
    class FaultServiceConcurrencyTest {

        @Mock private com.maintenance.mapper.FaultMapper faultMapper;
        @Mock private EquipmentMapper equipmentMapper;
        @Mock private WorkOrderMapper workOrderMapper;
        @Mock private SparePartMapper sparePartMapper;
        @Mock private LocalMessageQueue messageQueue;
        @Mock private AutoDispatchService autoDispatchService;
        @Mock private AuditService auditService;
        @Mock private DowntimeService downtimeService;
        @Mock private PredictiveDispatchService predictiveDispatchService;
        @Mock private SlaService slaService;
        @Mock private RedisTemplate<String, Object> redisTemplate;
        @Mock private ValueOperations<String, Object> valueOperations;

        private FaultService faultService;

        @BeforeEach
        void setUp() {
            lenient().when(redisTemplate.opsForValue()).thenReturn(valueOperations);
            faultService = new FaultService(
                    faultMapper, equipmentMapper, workOrderMapper, sparePartMapper,
                    messageQueue, autoDispatchService, auditService, downtimeService,
                    predictiveDispatchService, slaService, redisTemplate);
        }

        @Test
        @DisplayName("Concurrent fault report blocked by Redis lock throws BusinessException")
        void concurrentFaultReport_blockedByRedisLock() {
            Equipment equipment = new Equipment();
            equipment.setId(1L);
            equipment.setEquipmentName("CNC Machine");
            equipment.setEquipmentType("CNC");
            when(equipmentMapper.selectById(1L)).thenReturn(equipment);

            // Redis lock acquisition fails (another thread holds it)
            when(valueOperations.setIfAbsent(anyString(), anyString(), anyLong(), any(TimeUnit.class)))
                    .thenReturn(false);

            FaultReportRequest request = new FaultReportRequest();
            request.setEquipmentId(1L);
            request.setFaultLevel(2);
            request.setFaultDescription("Test");
            request.setReporter("Tester");
            request.setReporterPhone("123");

            BusinessException ex = assertThrows(BusinessException.class,
                    () -> faultService.reportFault(request));

            assertTrue(ex.getMessage().contains("Concurrent fault report"));
            // No fault should have been created
            verify(faultMapper, never()).insert(any());
        }

        @Test
        @DisplayName("Redis lock is released in finally block even when exception occurs")
        void redisLock_releasedOnException() {
            Equipment equipment = new Equipment();
            equipment.setId(1L);
            equipment.setEquipmentName("CNC Machine");
            equipment.setEquipmentType("CNC");
            when(equipmentMapper.selectById(1L)).thenReturn(equipment);

            // Lock acquired
            when(valueOperations.setIfAbsent(anyString(), anyString(), anyLong(), any(TimeUnit.class)))
                    .thenReturn(true);
            // Simulate internal failure
            when(faultMapper.selectRecentByEquipment(1L, 5))
                    .thenThrow(new RuntimeException("DB failure"));

            FaultReportRequest request = new FaultReportRequest();
            request.setEquipmentId(1L);
            request.setFaultLevel(2);
            request.setFaultDescription("Test");
            request.setReporter("Tester");
            request.setReporterPhone("123");

            assertThrows(RuntimeException.class, () -> faultService.reportFault(request));

            // Lock should still be released
            verify(redisTemplate).delete(contains("fault:report:lock:1:2"));
        }
    }

    // ================================================================
    // NESTED: WorkOrderService CAS state transitions
    // ================================================================
    @Nested
    @DisplayName("WorkOrderService CAS state transitions")
    class WorkOrderCasTest {

        @Mock private WorkOrderMapper workOrderMapper;
        @Mock private DispatchRecordMapper dispatchRecordMapper;
        @Mock private FaultMapper faultMapper;
        @Mock private EquipmentMapper equipmentMapper;
        @Mock private TechnicianService technicianService;
        @Mock private SparePartService sparePartService;
        @Mock private DowntimeService downtimeService;
        @Mock private LocalMessageQueue messageQueue;
        @Mock private AuditService auditService;
        @Mock private PredictiveDispatchService predictiveDispatchService;
        @Mock private SlaService slaService;

        private WorkOrderService workOrderService;

        @BeforeEach
        void setUp() {
            workOrderService = new WorkOrderService(
                    workOrderMapper, dispatchRecordMapper, faultMapper, equipmentMapper,
                    technicianService, sparePartService, downtimeService,
                    messageQueue, auditService, predictiveDispatchService, slaService);
        }

        private WorkOrder createWorkOrder(Long id, String status) {
            WorkOrder wo = new WorkOrder();
            wo.setId(id);
            wo.setOrderCode("WO-TEST-" + id);
            wo.setStatus(status);
            wo.setTechnicianId(100L);
            wo.setEquipmentId(50L);
            wo.setFaultId(10L);
            wo.setPriority(2);
            wo.setReassignCount(0);
            wo.setEscalateCount(0);
            return wo;
        }

        @Test
        @DisplayName("CAS detects concurrent accept: throws when status already changed")
        void accept_casDetectsConcurrentChange() {
            WorkOrder order = createWorkOrder(1L, "CREATED");
            when(workOrderMapper.selectById(1L)).thenReturn(order);
            // CAS returns 0 rows = another thread already changed the status
            when(workOrderMapper.update(any(), any())).thenReturn(0);

            BusinessException ex = assertThrows(BusinessException.class,
                    () -> workOrderService.accept(1L, 100L));

            assertTrue(ex.getMessage().contains("Concurrent status change"));
        }

        @Test
        @DisplayName("CAS detects concurrent complete: throws when status already changed")
        void complete_casDetectsConcurrentChange() {
            WorkOrder order = createWorkOrder(1L, "REPAIRING");
            when(workOrderMapper.selectById(1L)).thenReturn(order);
            when(workOrderMapper.update(any(), any())).thenReturn(0);

            BusinessException ex = assertThrows(BusinessException.class,
                    () -> workOrderService.complete(1L, "notes", BigDecimal.TEN));

            assertTrue(ex.getMessage().contains("Concurrent status change"));
        }

        @Test
        @DisplayName("CAS detects concurrent suspend: throws when status already changed")
        void suspend_casDetectsConcurrentChange() {
            WorkOrder order = createWorkOrder(1L, "REPAIRING");
            when(workOrderMapper.selectById(1L)).thenReturn(order);
            when(workOrderMapper.update(any(), any())).thenReturn(0);

            BusinessException ex = assertThrows(BusinessException.class,
                    () -> workOrderService.suspend(1L, "waiting"));

            assertTrue(ex.getMessage().contains("Concurrent status change"));
        }

        @Test
        @DisplayName("CAS detects concurrent resume: throws when status already changed")
        void resume_casDetectsConcurrentChange() {
            WorkOrder order = createWorkOrder(1L, "SUSPENDED");
            when(workOrderMapper.selectById(1L)).thenReturn(order);
            when(workOrderMapper.update(any(), any())).thenReturn(0);

            BusinessException ex = assertThrows(BusinessException.class,
                    () -> workOrderService.resume(1L));

            assertTrue(ex.getMessage().contains("Concurrent status change"));
        }

        @Test
        @DisplayName("Suspend coordinates SLA pause")
        void suspend_pausesSla() {
            WorkOrder order = createWorkOrder(1L, "REPAIRING");
            when(workOrderMapper.selectById(1L)).thenReturn(order);
            when(workOrderMapper.update(any(), any())).thenReturn(1);
            when(downtimeService.endDowntime(50L, 1L)).thenReturn(new DowntimeRecord());

            workOrderService.suspend(1L, "waiting for parts");

            verify(slaService).pauseSla(eq(1L), contains("工单暂停"));
            verify(downtimeService).endDowntime(50L, 1L);
        }

        @Test
        @DisplayName("Resume coordinates SLA resume")
        void resume_resumesSla() {
            WorkOrder order = createWorkOrder(1L, "SUSPENDED");
            when(workOrderMapper.selectById(1L)).thenReturn(order);
            when(workOrderMapper.update(any(), any())).thenReturn(1);
            when(downtimeService.startDowntime(50L, 1L, 10L)).thenReturn(new DowntimeRecord());

            workOrderService.resume(1L);

            verify(slaService).resumeSla(1L);
            verify(downtimeService).startDowntime(50L, 1L, 10L);
        }

        @Test
        @DisplayName("Reassign releases parts directly via sparePartService (not predictiveDispatch)")
        void reassign_releasesPartsDirectly() {
            WorkOrder order = createWorkOrder(1L, "REPAIRING");
            Technician newTech = new Technician();
            newTech.setId(200L);
            newTech.setAvailability(TechnicianAvailability.AVAILABLE.name());

            when(workOrderMapper.selectById(1L)).thenReturn(order);
            when(technicianService.getById(200L)).thenReturn(newTech);
            when(downtimeService.endDowntime(50L, 1L)).thenReturn(new DowntimeRecord());
            when(dispatchRecordMapper.insert(any())).thenReturn(1);
            when(workOrderMapper.updateById(any())).thenReturn(1);
            when(workOrderMapper.selectActiveByTechnicianId(100L)).thenReturn(Collections.emptyList());

            workOrderService.reassign(1L, 200L, "skill mismatch");

            // Parts released directly (failures propagate, trigger rollback)
            verify(sparePartService).releaseOccupationsByWorkOrder(1L);
            // Not via predictiveDispatchService (which silently catches)
            verify(predictiveDispatchService, never()).releasePreOccupiedParts(anyLong());
        }

        @Test
        @DisplayName("CloseAbnormal releases parts directly via sparePartService")
        void closeAbnormal_releasesPartsDirectly() {
            WorkOrder order = createWorkOrder(1L, "REPAIRING");

            when(workOrderMapper.selectById(1L)).thenReturn(order);
            when(workOrderMapper.updateById(any())).thenReturn(1);
            when(workOrderMapper.selectActiveByTechnicianId(100L)).thenReturn(Collections.emptyList());
            when(downtimeService.endDowntime(50L, 1L)).thenReturn(new DowntimeRecord());
            when(faultMapper.updateStatus(anyLong(), anyString())).thenReturn(1);

            workOrderService.closeAbnormal(1L, "equipment scrapped");

            verify(sparePartService).releaseOccupationsByWorkOrder(1L);
            verify(predictiveDispatchService, never()).releasePreOccupiedParts(anyLong());
        }

        @Test
        @DisplayName("Complete finalizes SLA after work order completion")
        void complete_finalizesSla() {
            WorkOrder order = createWorkOrder(1L, "REPAIRING");

            when(workOrderMapper.selectById(1L)).thenReturn(order);
            when(workOrderMapper.update(any(), any())).thenReturn(1);
            when(sparePartService.getOccupationsByWorkOrder(1L)).thenReturn(Collections.emptyList());
            when(workOrderMapper.selectActiveByTechnicianId(100L)).thenReturn(Collections.emptyList());
            when(downtimeService.endDowntime(50L, 1L)).thenReturn(new DowntimeRecord());
            when(faultMapper.updateStatus(anyLong(), anyString())).thenReturn(1);
            when(equipmentMapper.updateStatus(anyLong(), anyString())).thenReturn(1);

            workOrderService.complete(1L, "fixed", BigDecimal.TEN);

            verify(slaService).finalizeSla(1L);
        }

        @Test
        @DisplayName("Complete uses deterministic event ID for REPAIR_COMPLETED")
        void complete_usesDeterministicEventId() {
            WorkOrder order = createWorkOrder(1L, "REPAIRING");

            when(workOrderMapper.selectById(1L)).thenReturn(order);
            when(workOrderMapper.update(any(), any())).thenReturn(1);
            when(sparePartService.getOccupationsByWorkOrder(1L)).thenReturn(Collections.emptyList());
            when(workOrderMapper.selectActiveByTechnicianId(100L)).thenReturn(Collections.emptyList());
            when(downtimeService.endDowntime(50L, 1L)).thenReturn(new DowntimeRecord());
            when(faultMapper.updateStatus(anyLong(), anyString())).thenReturn(1);
            when(equipmentMapper.updateStatus(anyLong(), anyString())).thenReturn(1);

            workOrderService.complete(1L, "fixed", BigDecimal.TEN);

            verify(messageQueue).publishWithId(
                    eq("REPAIR_COMPLETED:1"), eq("REPAIR_COMPLETED"), any());
        }
    }

    // ================================================================
    // NESTED: SparePartService idempotent occupation
    // ================================================================
    @Nested
    @DisplayName("SparePartService idempotent occupation")
    class SparePartIdempotencyTest {

        @Mock private SparePartMapper sparePartMapper;
        @Mock private SparePartOccupationMapper sparePartOccupationMapper;
        @Mock private RedisTemplate<String, Object> redisTemplate;
        @Mock private ValueOperations<String, Object> valueOperations;
        @Mock private LocalMessageQueue messageQueue;
        @Mock private AuditService auditService;

        private SparePartService sparePartService;

        @BeforeEach
        void setUp() {
            sparePartService = new SparePartService(
                    sparePartMapper, sparePartOccupationMapper, redisTemplate,
                    messageQueue, auditService);
            lenient().when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        }

        @Test
        @DisplayName("Idempotent guard: duplicate occupy returns existing occupation without stock change")
        void occupyPart_duplicateReturnsExisting() {
            // Lock succeeds
            when(valueOperations.setIfAbsent(anyString(), anyString(), anyLong(), any(TimeUnit.class)))
                    .thenReturn(true);

            // Existing occupation for same workOrder+partId
            SparePartOccupation existing = new SparePartOccupation();
            existing.setId(99L);
            existing.setWorkOrderId(1L);
            existing.setPartId(10L);
            existing.setQuantity(2);
            existing.setStatus(OccupationStatus.OCCUPIED.name());

            when(sparePartOccupationMapper.selectByWorkOrderAndStatus(1L, "OCCUPIED"))
                    .thenReturn(List.of(existing));

            SparePartOccupation result = sparePartService.occupyPart(1L, 10L, 2);

            assertEquals(99L, result.getId(), "Should return existing occupation");
            // Stock should NOT be decreased again
            verify(sparePartMapper, never()).decreaseStock(anyLong(), anyInt());
            // No duplicate event
            verify(messageQueue, never()).publishWithId(anyString(), anyString(), any());
        }

        @Test
        @DisplayName("Deferred events: all PART_RELEASED events published after all releases succeed")
        void releaseOccupations_deferredEventPublishing() {
            SparePartOccupation occ1 = new SparePartOccupation();
            occ1.setId(1L);
            occ1.setPartId(10L);
            occ1.setQuantity(2);
            occ1.setStatus(OccupationStatus.OCCUPIED.name());

            SparePartOccupation occ2 = new SparePartOccupation();
            occ2.setId(2L);
            occ2.setPartId(20L);
            occ2.setQuantity(3);
            occ2.setStatus(OccupationStatus.OCCUPIED.name());

            when(sparePartOccupationMapper.selectByWorkOrderAndStatus(1L, "OCCUPIED"))
                    .thenReturn(List.of(occ1, occ2));
            when(valueOperations.setIfAbsent(anyString(), anyString(), anyLong(), any(TimeUnit.class)))
                    .thenReturn(true);
            when(sparePartMapper.increaseStock(anyLong(), anyInt())).thenReturn(1);
            when(sparePartOccupationMapper.updateStatus(anyLong(), anyString(), any())).thenReturn(1);

            sparePartService.releaseOccupationsByWorkOrder(1L);

            // Both events should be published (deferred until after all DB ops succeed)
            verify(messageQueue).publishWithId(eq("PART_RELEASED:1:10"), eq("PART_RELEASED"), any());
            verify(messageQueue).publishWithId(eq("PART_RELEASED:1:20"), eq("PART_RELEASED"), any());
        }

        @Test
        @DisplayName("ConsumeAll uses batch update from OCCUPIED to CONSUMED")
        void consumeAll_usesBatchUpdate() {
            when(sparePartOccupationMapper.batchUpdateStatus(eq(1L), eq("OCCUPIED"), eq("CONSUMED"), any()))
                    .thenReturn(0);

            sparePartService.consumeAllByWorkOrder(1L);

            verify(sparePartOccupationMapper).batchUpdateStatus(eq(1L), eq("OCCUPIED"), eq("CONSUMED"), any());
        }
    }

    // ================================================================
    // NESTED: SlaService idempotent pause/resume
    // ================================================================
    @Nested
    @DisplayName("SlaService idempotent pause/resume")
    class SlaIdempotencyTest {

        @Mock private SlaRecordMapper slaRecordMapper;
        @Mock private LocalMessageQueue messageQueue;
        @Mock private AuditService auditService;

        private SlaService slaService;

        @BeforeEach
        void setUp() {
            slaService = new SlaService(slaRecordMapper, messageQueue, auditService);
        }

        @Test
        @DisplayName("Idempotent pause: already-PAUSED SLA returns existing without re-publishing event")
        void pauseSla_alreadyPaused_returnsExisting() {
            // No active record
            when(slaRecordMapper.selectActiveByWorkOrder(1L)).thenReturn(null);

            // Already paused
            SlaRecord pausedRecord = new SlaRecord();
            pausedRecord.setId(1L);
            pausedRecord.setWorkOrderId(1L);
            pausedRecord.setStatus("PAUSED");
            pausedRecord.setRemainingMinutes(60);
            when(slaRecordMapper.selectByWorkOrderId(1L)).thenReturn(pausedRecord);

            SlaRecord result = slaService.pauseSla(1L, "duplicate pause");

            assertEquals("PAUSED", result.getStatus());
            assertEquals(1L, result.getId());
            // Should NOT publish a duplicate event
            verify(messageQueue, never()).publishWithId(anyString(), anyString(), any());
            verify(messageQueue, never()).publish(anyString(), any());
            // Should NOT update the record
            verify(slaRecordMapper, never()).updateSla(anyLong(), anyString(), anyInt(), any(), any(), any());
        }

        @Test
        @DisplayName("Resume non-paused SLA returns record unchanged without publishing event")
        void resumeSla_nonPaused_noEvent() {
            SlaRecord activeRecord = new SlaRecord();
            activeRecord.setId(1L);
            activeRecord.setWorkOrderId(1L);
            activeRecord.setStatus("ACTIVE");

            when(slaRecordMapper.selectByWorkOrderId(1L)).thenReturn(activeRecord);

            SlaRecord result = slaService.resumeSla(1L);

            assertEquals("ACTIVE", result.getStatus());
            verify(messageQueue, never()).publishWithId(anyString(), anyString(), any());
            verify(messageQueue, never()).publish(anyString(), any());
        }

        @Test
        @DisplayName("Pause then resume maintains SLA consistency")
        void pauseThenResume_maintainsConsistency() {
            // Active SLA with 100 minutes remaining
            SlaRecord activeRecord = new SlaRecord();
            activeRecord.setId(1L);
            activeRecord.setWorkOrderId(1L);
            activeRecord.setStatus("ACTIVE");
            activeRecord.setFaultLevel(2);
            activeRecord.setSlaDeadline(LocalDateTime.now().plusMinutes(100));

            when(slaRecordMapper.selectActiveByWorkOrder(1L)).thenReturn(activeRecord);
            when(slaRecordMapper.updateSla(eq(1L), eq("PAUSED"), anyInt(), any(), any(), any()))
                    .thenReturn(1);

            // Pause
            SlaRecord paused = slaService.pauseSla(1L, "waiting for parts");
            assertEquals("PAUSED", paused.getStatus());
            assertTrue(paused.getRemainingMinutes() > 0);
            verify(messageQueue).publishWithId(eq("SLA_PAUSED:1"), eq("SLA_PAUSED"), any());

            // Prepare for resume
            paused.setPausedAt(LocalDateTime.now().minusMinutes(30));
            when(slaRecordMapper.selectByWorkOrderId(1L)).thenReturn(paused);
            when(slaRecordMapper.updateSla(eq(1L), eq("ACTIVE"), anyInt(), any(), any(), any()))
                    .thenReturn(1);

            // Resume
            SlaRecord resumed = slaService.resumeSla(1L);
            assertEquals("ACTIVE", resumed.getStatus());
            assertNotNull(resumed.getSlaDeadline());
            // New deadline should be in the future
            assertTrue(resumed.getSlaDeadline().isAfter(LocalDateTime.now()));
            verify(messageQueue).publishWithId(eq("SLA_RESUMED:1"), eq("SLA_RESUMED"), any());
        }
    }

    // ================================================================
    // NESTED: Consumer eviction safety
    // ================================================================
    @Nested
    @DisplayName("Consumer eviction prevents OOM")
    class ConsumerEvictionTest {

        @Mock private com.maintenance.websocket.MaintenanceWebSocketHandler wsHandler;
        @Mock private AuditService auditService;

        @Test
        @DisplayName("Consumer evicts old entries when processedEvents exceeds MAX_PROCESSED_EVENTS")
        void eviction_preventsUnboundedGrowth() {
            com.maintenance.consumer.PredictiveDispatchConsumer consumer =
                    new com.maintenance.consumer.PredictiveDispatchConsumer(wsHandler, auditService);

            // Process more than MAX_PROCESSED_EVENTS (5000) events
            for (int i = 0; i < 5100; i++) {
                com.maintenance.infrastructure.queue.MaintenanceEvent event =
                        new com.maintenance.infrastructure.queue.MaintenanceEvent();
                event.setEventId("eviction-test-" + i);
                event.setEventType("DISPATCH_PLANS_GENERATED");
                event.setPayload("{\"workOrderId\":" + i + ",\"planCount\":1}");
                consumer.handleEvent(event);
            }

            // Now send one of the early events again - it should be processed
            // (because eviction removed it from the set)
            com.maintenance.infrastructure.queue.MaintenanceEvent retryEvent =
                    new com.maintenance.infrastructure.queue.MaintenanceEvent();
            retryEvent.setEventId("eviction-test-0");
            retryEvent.setEventType("DISPATCH_PLANS_GENERATED");
            retryEvent.setPayload("{\"workOrderId\":0,\"planCount\":1}");
            consumer.handleEvent(retryEvent);

            // Should have broadcast 5101 times total (5100 initial + 1 retry of evicted)
            // The key assertion: early events were evicted and can be re-processed
            verify(wsHandler, atLeast(5101)).broadcast(eq("DISPATCH_PLANS"), any());
        }

        @Test
        @DisplayName("Failed event is removed from processedEvents set for retry")
        void failedEvent_removedForRetry() {
            com.maintenance.consumer.DowntimeEventConsumer consumer =
                    new com.maintenance.consumer.DowntimeEventConsumer(
                            mock(DowntimeService.class));

            DowntimeService mockService = mock(DowntimeService.class);
            // Create a consumer with a service that throws
            consumer = new com.maintenance.consumer.DowntimeEventConsumer(mockService);

            com.maintenance.infrastructure.queue.MaintenanceEvent event =
                    new com.maintenance.infrastructure.queue.MaintenanceEvent();
            event.setEventId("retry-test-001");
            event.setEventType("DOWNTIME_FORCE_END");
            event.setPayload("{\"equipmentId\":10,\"workOrderId\":1}");

            // First attempt fails
            doThrow(new RuntimeException("DB error")).when(mockService).endDowntime(10L, 1L);
            consumer.handleEvent(event);

            // Second attempt succeeds (event was removed from processedEvents on failure)
            doReturn(new DowntimeRecord()).when(mockService).endDowntime(10L, 1L);
            consumer.handleEvent(event);

            // endDowntime should have been called twice (once failed, once succeeded)
            verify(mockService, times(2)).endDowntime(10L, 1L);
        }
    }
}
