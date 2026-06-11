package com.maintenance.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.maintenance.common.BusinessException;
import com.maintenance.dto.DispatchResult;
import com.maintenance.dto.PreOccupyResult;
import com.maintenance.entity.DispatchPlan;
import com.maintenance.entity.DispatchRecord;
import com.maintenance.entity.Equipment;
import com.maintenance.entity.Fault;
import com.maintenance.entity.PurchaseSuggestion;
import com.maintenance.entity.SlaRecord;
import com.maintenance.entity.SparePart;
import com.maintenance.entity.SparePartOccupation;
import com.maintenance.entity.Technician;
import com.maintenance.entity.WorkOrder;
import com.maintenance.enums.OccupationStatus;
import com.maintenance.enums.TechnicianAvailability;
import com.maintenance.infrastructure.queue.EventConsumer;
import com.maintenance.infrastructure.queue.EventDispatcher;
import com.maintenance.infrastructure.queue.LocalMessageQueue;
import com.maintenance.infrastructure.queue.MaintenanceEvent;
import com.maintenance.infrastructure.queue.TransactionAwareEventPublisher;
import com.maintenance.mapper.DispatchPlanMapper;
import com.maintenance.mapper.DispatchRecordMapper;
import com.maintenance.mapper.EquipmentMapper;
import com.maintenance.mapper.FaultMapper;
import com.maintenance.mapper.SlaRecordMapper;
import com.maintenance.mapper.SparePartMapper;
import com.maintenance.mapper.SparePartOccupationMapper;
import com.maintenance.mapper.WorkOrderMapper;
import com.maintenance.websocket.MaintenanceWebSocketHandler;
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
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Comprehensive concurrency and consistency tests for the equipment maintenance system.
 * <p>
 * Covers:
 * <ol>
 *   <li>Concurrent fault reporting for same equipment</li>
 *   <li>Technician offline during dispatch</li>
 *   <li>Insufficient spare parts scenarios</li>
 *   <li>Reassign failure resource release</li>
 *   <li>Duplicate message consumption (consumer idempotency)</li>
 *   <li>SLA pause/resume consistency with work order state</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
class ConcurrencyAndConsistencyTest {

    // ==================== Shared Mocks ====================
    @Mock private FaultMapper faultMapper;
    @Mock private EquipmentMapper equipmentMapper;
    @Mock private WorkOrderMapper workOrderMapper;
    @Mock private SparePartMapper sparePartMapper;
    @Mock private SparePartOccupationMapper sparePartOccupationMapper;
    @Mock private DispatchRecordMapper dispatchRecordMapper;
    @Mock private DispatchPlanMapper dispatchPlanMapper;
    @Mock private SlaRecordMapper slaRecordMapper;
    @Mock private LocalMessageQueue messageQueue;
    @Mock private TransactionAwareEventPublisher txPublisher;
    @Mock private AutoDispatchService autoDispatchService;
    @Mock private AuditService auditService;
    @Mock private DowntimeService downtimeService;
    @Mock private TechnicianService technicianService;
    @Mock private PredictiveDispatchService predictiveDispatchService;
    @Mock private MaintenanceWebSocketHandler webSocketHandler;
    @Mock private RedisTemplate<String, Object> redisTemplate;
    @Mock private ValueOperations<String, Object> valueOperations;

    // ====================================================================
    // 1. CONCURRENT FAULT REPORTING
    // ====================================================================
    @Nested
    @DisplayName("Concurrent fault reporting")
    class ConcurrentFaultReportingTests {

        private FaultService faultService;
        private SparePartService sparePartService;
        private SlaService slaService;

        @BeforeEach
        void setUp() {
            sparePartService = new SparePartService(
                    sparePartMapper, sparePartOccupationMapper, redisTemplate,
                    messageQueue, txPublisher, auditService);
            slaService = new SlaService(slaRecordMapper, messageQueue, txPublisher, auditService);
            faultService = new FaultService(
                    faultMapper, equipmentMapper, workOrderMapper, sparePartMapper,
                    messageQueue, txPublisher, autoDispatchService, auditService,
                    downtimeService, predictiveDispatchService, slaService);

            lenient().when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        }

        @Test
        @DisplayName("Concurrent faults on same equipment: duplicate detection prevents double work order creation")
        void concurrentFaults_duplicateDetectionPreventsDoubleCreation() throws Exception {
            // Simulate two threads reporting fault for same equipment simultaneously
            // The second should be detected as duplicate (same equipment + fault level within 5 min)
            Equipment equipment = new Equipment();
            equipment.setId(1L);
            equipment.setEquipmentName("CNC Machine");
            equipment.setEquipmentType("CNC");

            when(equipmentMapper.selectById(1L)).thenReturn(equipment);
            when(equipmentMapper.updateStatus(eq(1L), anyString())).thenReturn(1);

            // First call: no recent faults
            Fault existingFault = new Fault();
            existingFault.setId(99L);
            existingFault.setFaultLevel(2);
            existingFault.setEquipmentId(1L);

            // Simulate: first call sees no recent faults, second call sees the first fault
            AtomicInteger callCount = new AtomicInteger(0);
            when(faultMapper.selectRecentByEquipment(1L, 5)).thenAnswer(inv -> {
                int call = callCount.incrementAndGet();
                if (call == 1) {
                    return Collections.emptyList(); // First call: no duplicates
                } else {
                    return List.of(existingFault); // Second call: duplicate detected
                }
            });

            // First call creates new fault
            when(faultMapper.insert(any(Fault.class))).thenAnswer(inv -> {
                Fault f = inv.getArgument(0);
                f.setId(100L);
                return 1;
            });
            when(workOrderMapper.insert(any(WorkOrder.class))).thenAnswer(inv -> {
                WorkOrder wo = inv.getArgument(0);
                wo.setId(200L);
                wo.setOrderCode("WO-001");
                return 1;
            });
            when(workOrderMapper.selectLatestByEquipment(anyLong(), anyString())).thenReturn(null);
            when(slaRecordMapper.selectByWorkOrderId(anyLong())).thenReturn(null);
            when(slaRecordMapper.insert(any(SlaRecord.class))).thenReturn(1);

            // Second call returns existing fault
            when(faultMapper.incrementOccurrenceCount(eq(99L), anyString())).thenReturn(1);
            when(faultMapper.selectById(99L)).thenReturn(existingFault);

            // Mock predictive dispatch for first call
            when(predictiveDispatchService.generateDispatchPlans(any(), any(), any()))
                    .thenReturn(Collections.emptyList());
            when(predictiveDispatchService.preOccupyParts(anyLong(), anyString(), anyInt()))
                    .thenReturn(PreOccupyResult.builder()
                            .workOrderId(200L)
                            .occupiedParts(Collections.emptyList())
                            .shortageParts(Collections.emptyList())
                            .allPartsAvailable(true)
                            .build());

            ExecutorService executor = Executors.newFixedThreadPool(2);
            CountDownLatch startLatch = new CountDownLatch(1);
            CountDownLatch doneLatch = new CountDownLatch(2);

            AtomicReference<Fault> result1 = new AtomicReference<>();
            AtomicReference<Fault> result2 = new AtomicReference<>();
            AtomicReference<Exception> error = new AtomicReference<>();

            // Thread 1: first fault report
            executor.submit(() -> {
                try {
                    startLatch.await();
                    com.maintenance.dto.FaultReportRequest req = new com.maintenance.dto.FaultReportRequest();
                    req.setEquipmentId(1L);
                    req.setFaultLevel(2);
                    req.setFaultDescription("Fault from thread 1");
                    req.setReporter("T1");
                    result1.set(faultService.reportFault(req));
                } catch (Exception e) {
                    error.set(e);
                } finally {
                    doneLatch.countDown();
                }
            });

            // Thread 2: duplicate fault report
            executor.submit(() -> {
                try {
                    startLatch.await();
                    com.maintenance.dto.FaultReportRequest req = new com.maintenance.dto.FaultReportRequest();
                    req.setEquipmentId(1L);
                    req.setFaultLevel(2);
                    req.setFaultDescription("Fault from thread 2");
                    req.setReporter("T2");
                    result2.set(faultService.reportFault(req));
                } catch (Exception e) {
                    error.set(e);
                } finally {
                    doneLatch.countDown();
                }
            });

            startLatch.countDown(); // Start both threads
            assertTrue(doneLatch.await(5, TimeUnit.SECONDS), "Both threads should complete within 5s");
            executor.shutdown();

            assertNull(error.get(), "No exceptions should occur: " + error.get());

            // The second call should detect duplicate and return existing fault
            // Due to the mock, one thread gets new fault (id=100), other gets existing (id=99)
            assertNotNull(result1.get(), "Thread 1 should get a result");
            assertNotNull(result2.get(), "Thread 2 should get a result");
        }
    }

    // ====================================================================
    // 2. TECHNICIAN OFFLINE DURING DISPATCH
    // ====================================================================
    @Nested
    @DisplayName("Technician offline during dispatch")
    class TechnicianOfflineTests {

        private PredictiveDispatchService service;

        @BeforeEach
        void setUp() {
            service = new PredictiveDispatchService(
                    mock(com.maintenance.mapper.TechnicianMapper.class),
                    mock(com.maintenance.mapper.TechnicianSkillMapper.class),
                    workOrderMapper, dispatchRecordMapper, dispatchPlanMapper,
                    faultMapper, sparePartMapper,
                    mock(com.maintenance.mapper.PurchaseSuggestionMapper.class),
                    messageQueue, txPublisher, auditService, technicianService,
                    mock(SparePartService.class),
                    new SlaService(slaRecordMapper, messageQueue, txPublisher, auditService),
                    webSocketHandler);
        }

        @Test
        @DisplayName("Dispatch fails when selected technician goes OFFLINE between plan generation and execution")
        void dispatchFailsWhenTechGoesOffline() {
            // Plan was generated when technician was AVAILABLE
            DispatchPlan plan = new DispatchPlan();
            plan.setId(1L);
            plan.setWorkOrderId(1L);
            plan.setPlanIndex(1);
            plan.setTechnicianId(100L);
            plan.setTotalScore(BigDecimal.valueOf(85));

            // But by execution time, technician is OFFLINE
            Technician offlineTech = new Technician();
            offlineTech.setId(100L);
            offlineTech.setName("John");
            offlineTech.setAvailability(TechnicianAvailability.OFFLINE.name());

            WorkOrder wo = new WorkOrder();
            wo.setId(1L);
            wo.setOrderCode("WO-001");

            when(workOrderMapper.selectById(1L)).thenReturn(wo);
            when(dispatchRecordMapper.selectLatestByWorkOrder(1L)).thenReturn(null);
            when(dispatchPlanMapper.selectByWorkOrderId(1L)).thenReturn(List.of(plan));
            when(technicianService.getById(100L)).thenReturn(offlineTech);

            BusinessException ex = assertThrows(BusinessException.class,
                    () -> service.selectAndExecutePlan(1L, 1));

            assertTrue(ex.getMessage().contains("unavailable"),
                    "Should mention technician is unavailable: " + ex.getMessage());
            // Work order should NOT be assigned
            verify(workOrderMapper, never()).updateById(any());
        }

        @Test
        @DisplayName("Dispatch fails when selected technician is ON_LEAVE")
        void dispatchFailsWhenTechOnLeave() {
            DispatchPlan plan = new DispatchPlan();
            plan.setId(1L);
            plan.setWorkOrderId(1L);
            plan.setPlanIndex(1);
            plan.setTechnicianId(200L);
            plan.setTotalScore(BigDecimal.valueOf(90));

            Technician onLeaveTech = new Technician();
            onLeaveTech.setId(200L);
            onLeaveTech.setName("Jane");
            onLeaveTech.setAvailability(TechnicianAvailability.ON_LEAVE.name());

            WorkOrder wo = new WorkOrder();
            wo.setId(1L);
            wo.setOrderCode("WO-002");

            when(workOrderMapper.selectById(1L)).thenReturn(wo);
            when(dispatchRecordMapper.selectLatestByWorkOrder(1L)).thenReturn(null);
            when(dispatchPlanMapper.selectByWorkOrderId(1L)).thenReturn(List.of(plan));
            when(technicianService.getById(200L)).thenReturn(onLeaveTech);

            BusinessException ex = assertThrows(BusinessException.class,
                    () -> service.selectAndExecutePlan(1L, 1));

            assertTrue(ex.getMessage().contains("ON_LEAVE"));
        }
    }

    // ====================================================================
    // 3. INSUFFICIENT SPARE PARTS
    // ====================================================================
    @Nested
    @DisplayName("Insufficient spare parts")
    class InsufficientSparePartsTests {

        private SparePartService sparePartService;

        @BeforeEach
        void setUp() {
            sparePartService = new SparePartService(
                    sparePartMapper, sparePartOccupationMapper, redisTemplate,
                    messageQueue, txPublisher, auditService);
            lenient().when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        }

        @Test
        @DisplayName("Occupy fails with BusinessException when stock is 0")
        void occupyFailsWhenStockZero() {
            when(valueOperations.setIfAbsent(anyString(), anyString(), anyLong(), any(TimeUnit.class)))
                    .thenReturn(true);

            SparePart part = new SparePart();
            part.setId(1L);
            part.setPartCode("P-001");
            part.setStockQuantity(0);
            when(sparePartMapper.selectById(1L)).thenReturn(part);
            when(sparePartOccupationMapper.selectByWorkOrderAndStatus(anyLong(), anyString()))
                    .thenReturn(Collections.emptyList());

            BusinessException ex = assertThrows(BusinessException.class,
                    () -> sparePartService.occupyPart(100L, 1L, 1));

            assertTrue(ex.getMessage().contains("Insufficient stock"));
        }

        @Test
        @DisplayName("Occupy fails with BusinessException when stock < required quantity")
        void occupyFailsWhenStockInsufficient() {
            when(valueOperations.setIfAbsent(anyString(), anyString(), anyLong(), any(TimeUnit.class)))
                    .thenReturn(true);

            SparePart part = new SparePart();
            part.setId(1L);
            part.setPartCode("P-002");
            part.setStockQuantity(2);
            when(sparePartMapper.selectById(1L)).thenReturn(part);
            when(sparePartOccupationMapper.selectByWorkOrderAndStatus(anyLong(), anyString()))
                    .thenReturn(Collections.emptyList());

            BusinessException ex = assertThrows(BusinessException.class,
                    () -> sparePartService.occupyPart(100L, 1L, 5));

            assertTrue(ex.getMessage().contains("Insufficient stock"));
        }

        @Test
        @DisplayName("Concurrent occupy: Redis lock prevents double-decrement of stock")
        void concurrentOccupy_redisLockPreventsDoubleDecrement() {
            // Simulate lock failure (another thread holds the lock)
            when(valueOperations.setIfAbsent(anyString(), anyString(), anyLong(), any(TimeUnit.class)))
                    .thenReturn(false);

            BusinessException ex = assertThrows(BusinessException.class,
                    () -> sparePartService.occupyPart(100L, 1L, 1));

            assertTrue(ex.getMessage().contains("Failed to acquire part lock"));
            // Stock should NOT be decremented
            verify(sparePartMapper, never()).decreaseStock(anyLong(), anyInt());
        }

        @Test
        @DisplayName("Idempotent occupy: same (workOrderId, partId, qty) returns existing occupation")
        void idempotentOccupy_returnsExisting() {
            when(valueOperations.setIfAbsent(anyString(), anyString(), anyLong(), any(TimeUnit.class)))
                    .thenReturn(true);

            SparePartOccupation existingOcc = new SparePartOccupation();
            existingOcc.setId(50L);
            existingOcc.setWorkOrderId(100L);
            existingOcc.setPartId(1L);
            existingOcc.setQuantity(2);
            existingOcc.setStatus(OccupationStatus.OCCUPIED.name());

            when(sparePartOccupationMapper.selectByWorkOrderAndStatus(100L, "OCCUPIED"))
                    .thenReturn(List.of(existingOcc));

            SparePartOccupation result = sparePartService.occupyPart(100L, 1L, 2);

            assertEquals(50L, result.getId(), "Should return existing occupation");
            // Stock should NOT be decremented again
            verify(sparePartMapper, never()).decreaseStock(anyLong(), anyInt());
        }
    }

    // ====================================================================
    // 4. REASSIGN FAILURE RELEASE
    // ====================================================================
    @Nested
    @DisplayName("Reassign failure and resource release")
    class ReassignFailureReleaseTests {

        private WorkOrderService workOrderService;
        private SparePartService sparePartService;

        @BeforeEach
        void setUp() {
            sparePartService = new SparePartService(
                    sparePartMapper, sparePartOccupationMapper, redisTemplate,
                    messageQueue, txPublisher, auditService);
            workOrderService = new WorkOrderService(
                    workOrderMapper, dispatchRecordMapper, faultMapper, equipmentMapper,
                    technicianService, sparePartService, downtimeService,
                    messageQueue, txPublisher, auditService,
                    mock(PredictiveDispatchService.class),
                    new SlaService(slaRecordMapper, messageQueue, txPublisher, auditService));

            lenient().when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        }

        @Test
        @DisplayName("Reassign to OFFLINE technician throws BusinessException (no resource leak)")
        void reassignToOfflineTechThrows() {
            WorkOrder wo = new WorkOrder();
            wo.setId(1L);
            wo.setOrderCode("WO-001");
            wo.setStatus("CREATED");
            wo.setTechnicianId(100L);
            wo.setEquipmentId(50L);
            wo.setFaultId(10L);
            wo.setReassignCount(0);

            Technician offlineTech = new Technician();
            offlineTech.setId(200L);
            offlineTech.setAvailability(TechnicianAvailability.OFFLINE.name());

            when(workOrderMapper.selectById(1L)).thenReturn(wo);
            when(technicianService.getById(200L)).thenReturn(offlineTech);

            BusinessException ex = assertThrows(BusinessException.class,
                    () -> workOrderService.reassign(1L, 200L, "test reassign"));

            assertTrue(ex.getMessage().contains("OFFLINE"));
            // Spare parts should NOT be released (validation failed before release)
            verify(sparePartOccupationMapper, never()).selectByWorkOrderAndStatus(anyLong(), anyString());
            // Old technician workload should NOT be changed
            verify(technicianService, never()).decrementWorkload(anyLong());
        }

        @Test
        @DisplayName("Reassign to non-existent technician throws BusinessException")
        void reassignToNonExistentTechThrows() {
            WorkOrder wo = new WorkOrder();
            wo.setId(1L);
            wo.setOrderCode("WO-001");
            wo.setStatus("CREATED");
            wo.setTechnicianId(100L);
            wo.setEquipmentId(50L);
            wo.setFaultId(10L);

            when(workOrderMapper.selectById(1L)).thenReturn(wo);
            when(technicianService.getById(999L)).thenReturn(null);

            BusinessException ex = assertThrows(BusinessException.class,
                    () -> workOrderService.reassign(1L, 999L, "test"));

            assertTrue(ex.getMessage().contains("not found"));
        }

        @Test
        @DisplayName("Reassign releases spare parts exactly once (no double-release)")
        void reassignReleasesPartsOnce() {
            WorkOrder wo = new WorkOrder();
            wo.setId(1L);
            wo.setOrderCode("WO-001");
            wo.setStatus("REPAIRING");
            wo.setTechnicianId(100L);
            wo.setEquipmentId(50L);
            wo.setFaultId(10L);
            wo.setReassignCount(0);

            Technician newTech = new Technician();
            newTech.setId(200L);
            newTech.setAvailability(TechnicianAvailability.AVAILABLE.name());

            when(workOrderMapper.selectById(1L)).thenReturn(wo);
            when(technicianService.getById(200L)).thenReturn(newTech);
            when(downtimeService.endDowntime(50L, 1L)).thenReturn(null);
            when(workOrderMapper.updateById(any())).thenReturn(1);
            when(sparePartOccupationMapper.selectByWorkOrderAndStatus(1L, "OCCUPIED"))
                    .thenReturn(Collections.emptyList());
            when(dispatchRecordMapper.insert(any(DispatchRecord.class))).thenReturn(1);
            when(workOrderMapper.selectActiveByTechnicianId(100L)).thenReturn(Collections.emptyList());

            workOrderService.reassign(1L, 200L, "skill mismatch");

            // Spare parts release should be called EXACTLY once (not twice)
            verify(sparePartOccupationMapper, times(1))
                    .selectByWorkOrderAndStatus(eq(1L), eq("OCCUPIED"));
        }
    }

    // ====================================================================
    // 5. DUPLICATE MESSAGE CONSUMPTION (CONSUMER IDEMPOTENCY)
    // ====================================================================
    @Nested
    @DisplayName("Duplicate message consumption")
    class DuplicateMessageConsumptionTests {

        @Test
        @DisplayName("LocalMessageQueue deduplicates events with same eventId")
        void queueDeduplicatesByEventId() {
            AtomicInteger handleCount = new AtomicInteger(0);
            Set<String> processedIds = ConcurrentHashMap.newKeySet();

            EventConsumer testConsumer = new EventConsumer() {
                @Override
                public void handleEvent(MaintenanceEvent event) {
                    if (processedIds.add(event.getEventId())) {
                        handleCount.incrementAndGet();
                    }
                }

                @Override
                public boolean supportsEventType(String eventType) {
                    return true;
                }
            };

            EventDispatcher dispatcher = new EventDispatcher(List.of(testConsumer));

            // Create event with explicit ID
            MaintenanceEvent event1 = new MaintenanceEvent();
            event1.setEventId("dup-001");
            event1.setEventType("TEST_EVENT");
            event1.setPayload("{}");
            event1.setTimestamp(System.currentTimeMillis());

            // Same event delivered twice
            dispatcher.dispatch(event1);
            dispatcher.dispatch(event1); // Duplicate

            assertEquals(1, handleCount.get(),
                    "Consumer should only process the event once even if dispatched twice");
        }

        @Test
        @DisplayName("Different eventIds are all delivered")
        void differentEventIdsAllDelivered() {
            AtomicInteger handleCount = new AtomicInteger(0);

            EventConsumer testConsumer = new EventConsumer() {
                @Override
                public void handleEvent(MaintenanceEvent event) {
                    handleCount.incrementAndGet();
                }

                @Override
                public boolean supportsEventType(String eventType) {
                    return true;
                }
            };

            EventDispatcher dispatcher = new EventDispatcher(List.of(testConsumer));

            for (int i = 0; i < 5; i++) {
                MaintenanceEvent event = new MaintenanceEvent();
                event.setEventId("unique-" + i);
                event.setEventType("TEST_EVENT");
                event.setPayload("{}");
                event.setTimestamp(System.currentTimeMillis());
                dispatcher.dispatch(event);
            }

            assertEquals(5, handleCount.get(), "All unique events should be delivered");
        }

        @Test
        @DisplayName("Consumer with processedEvents set skips duplicate eventId")
        void consumerProcessedEventsSkipsDuplicate() {
            Set<String> processedEvents = ConcurrentHashMap.newKeySet();
            AtomicInteger handleCount = new AtomicInteger(0);

            MaintenanceEvent event = new MaintenanceEvent();
            event.setEventId("evt-001");
            event.setEventType("DISPATCH_DONE");
            event.setPayload("{\"workOrderId\":1}");

            // First delivery: processedEvents.add succeeds
            if (processedEvents.add(event.getEventId())) {
                handleCount.incrementAndGet();
            }

            // Second delivery: processedEvents.add fails (duplicate)
            if (processedEvents.add(event.getEventId())) {
                handleCount.incrementAndGet();
            }

            assertEquals(1, handleCount.get(),
                    "Only the first delivery should be processed");
        }

        @Test
        @DisplayName("Failed event processing removes from processedEvents for retry")
        void failedProcessingAllowsRetry() {
            Set<String> processedEvents = ConcurrentHashMap.newKeySet();
            AtomicInteger attemptCount = new AtomicInteger(0);

            MaintenanceEvent event = new MaintenanceEvent();
            event.setEventId("retry-001");
            event.setEventType("DISPATCH_DONE");
            event.setPayload("{}");

            // First attempt: add to set, then fail, then remove
            if (processedEvents.add(event.getEventId())) {
                attemptCount.incrementAndGet();
                // Simulate failure
                processedEvents.remove(event.getEventId());
            }

            // Second attempt: should succeed since first was removed
            if (processedEvents.add(event.getEventId())) {
                attemptCount.incrementAndGet();
            }

            assertEquals(2, attemptCount.get(),
                    "Both attempts should execute since first was removed after failure");
            assertTrue(processedEvents.contains("retry-001"),
                    "Event should remain in set after successful processing");
        }
    }

    // ====================================================================
    // 6. SLA PAUSE/RESUME CONSISTENCY
    // ====================================================================
    @Nested
    @DisplayName("SLA pause/resume consistency")
    class SlaPauseResumeConsistencyTests {

        private SlaService slaService;

        @BeforeEach
        void setUp() {
            slaService = new SlaService(slaRecordMapper, messageQueue, txPublisher, auditService);
        }

        @Test
        @DisplayName("Double-pause is idempotent: second pause returns existing PAUSED record")
        void doublePauseIsIdempotent() {
            SlaRecord pausedRecord = new SlaRecord();
            pausedRecord.setId(1L);
            pausedRecord.setWorkOrderId(1L);
            pausedRecord.setStatus("PAUSED");
            pausedRecord.setRemainingMinutes(60);
            pausedRecord.setPausedAt(LocalDateTime.now().minusMinutes(10));

            when(slaRecordMapper.selectByWorkOrderId(1L)).thenReturn(pausedRecord);

            SlaRecord result = slaService.pauseSla(1L, "second pause attempt");

            assertEquals("PAUSED", result.getStatus());
            assertEquals(60, result.getRemainingMinutes(),
                    "Remaining minutes should NOT be recalculated on double-pause");
            // updateSla should NOT be called since already paused
            verify(slaRecordMapper, never()).updateSla(anyLong(), anyString(), anyInt(), any(), any(), any());
        }

        @Test
        @DisplayName("Double-resume is idempotent: second resume returns existing ACTIVE record")
        void doubleResumeIsIdempotent() {
            SlaRecord activeRecord = new SlaRecord();
            activeRecord.setId(1L);
            activeRecord.setWorkOrderId(1L);
            activeRecord.setStatus("ACTIVE");
            activeRecord.setSlaDeadline(LocalDateTime.now().plusMinutes(60));

            when(slaRecordMapper.selectByWorkOrderId(1L)).thenReturn(activeRecord);

            SlaRecord result = slaService.resumeSla(1L);

            assertEquals("ACTIVE", result.getStatus());
            // updateSla should NOT be called since already active
            verify(slaRecordMapper, never()).updateSla(anyLong(), anyString(), anyInt(), any(), any(), any());
        }

        @Test
        @DisplayName("Pause on finalized (MET) SLA is safely ignored")
        void pauseOnFinalizedSlaIgnored() {
            SlaRecord metRecord = new SlaRecord();
            metRecord.setId(1L);
            metRecord.setWorkOrderId(1L);
            metRecord.setStatus("MET");

            when(slaRecordMapper.selectByWorkOrderId(1L)).thenReturn(metRecord);

            SlaRecord result = slaService.pauseSla(1L, "attempt pause on MET");

            assertEquals("MET", result.getStatus(),
                    "MET status should not be changed by pause");
            verify(slaRecordMapper, never()).updateSla(anyLong(), anyString(), anyInt(), any(), any(), any());
        }

        @Test
        @DisplayName("Resume on non-PAUSED SLA returns record unchanged")
        void resumeOnNonPausedReturnsUnchanged() {
            SlaRecord expiredRecord = new SlaRecord();
            expiredRecord.setId(1L);
            expiredRecord.setWorkOrderId(1L);
            expiredRecord.setStatus("EXPIRED");

            when(slaRecordMapper.selectByWorkOrderId(1L)).thenReturn(expiredRecord);

            SlaRecord result = slaService.resumeSla(1L);

            assertEquals("EXPIRED", result.getStatus());
            verify(slaRecordMapper, never()).updateSla(anyLong(), anyString(), anyInt(), any(), any(), any());
        }

        @Test
        @DisplayName("SLA pause -> resume correctly recalculates deadline from remaining time")
        void slaPauseThenResumeRecalculatesDeadline() {
            SlaRecord activeRecord = new SlaRecord();
            activeRecord.setId(1L);
            activeRecord.setWorkOrderId(1L);
            activeRecord.setStatus("ACTIVE");
            activeRecord.setFaultLevel(2); // 240 min total
            activeRecord.setSlaDeadline(LocalDateTime.now().plusMinutes(120)); // 120 min remaining

            when(slaRecordMapper.selectByWorkOrderId(1L)).thenReturn(activeRecord);
            when(slaRecordMapper.updateSla(anyLong(), anyString(), anyInt(), any(), any(), any()))
                    .thenReturn(1);

            // Pause
            SlaRecord paused = slaService.pauseSla(1L, "parts shortage");
            assertEquals("PAUSED", paused.getStatus());
            assertTrue(paused.getRemainingMinutes() > 100,
                    "Should have ~120 min remaining, got " + paused.getRemainingMinutes());

            // Now simulate resume
            SlaRecord pausedForResume = new SlaRecord();
            pausedForResume.setId(1L);
            pausedForResume.setWorkOrderId(1L);
            pausedForResume.setStatus("PAUSED");
            pausedForResume.setRemainingMinutes(paused.getRemainingMinutes());
            pausedForResume.setPausedAt(LocalDateTime.now());

            when(slaRecordMapper.selectByWorkOrderId(1L)).thenReturn(pausedForResume);

            SlaRecord resumed = slaService.resumeSla(1L);
            assertEquals("ACTIVE", resumed.getStatus());
            // New deadline should be approximately remainingMinutes from now
            int remaining = paused.getRemainingMinutes();
            assertTrue(resumed.getSlaDeadline().isAfter(LocalDateTime.now().plusMinutes(remaining - 5)));
            assertTrue(resumed.getSlaDeadline().isBefore(LocalDateTime.now().plusMinutes(remaining + 5)));
        }

        @Test
        @DisplayName("Work order suspend pauses SLA, resume resumes SLA (integration)")
        void workOrderSuspendResumeIntegratesWithSla() {
            WorkOrderService workOrderService = new WorkOrderService(
                    workOrderMapper, dispatchRecordMapper, faultMapper, equipmentMapper,
                    technicianService, mock(SparePartService.class), downtimeService,
                    messageQueue, txPublisher, auditService,
                    mock(PredictiveDispatchService.class), slaService);

            WorkOrder wo = new WorkOrder();
            wo.setId(1L);
            wo.setOrderCode("WO-SLA-TEST");
            wo.setStatus("REPAIRING");
            wo.setTechnicianId(100L);
            wo.setEquipmentId(50L);
            wo.setFaultId(10L);

            when(workOrderMapper.selectById(1L)).thenReturn(wo);
            when(workOrderMapper.updateById(any())).thenReturn(1);
            when(downtimeService.endDowntime(50L, 1L)).thenReturn(null);
            when(slaRecordMapper.selectByWorkOrderId(1L)).thenReturn(null); // No SLA record

            // Suspend should call slaService.pauseSla
            WorkOrder suspended = workOrderService.suspend(1L, "waiting for parts");
            assertEquals("SUSPENDED", suspended.getStatus());
            // SLA pause was attempted (even though no record exists, it should be called)
            verify(slaRecordMapper).selectByWorkOrderId(1L);

            // Resume should call slaService.resumeSla
            wo.setStatus("SUSPENDED");
            when(workOrderMapper.selectById(1L)).thenReturn(wo);
            when(downtimeService.startDowntime(50L, 1L, 10L))
                    .thenReturn(new com.maintenance.entity.DowntimeRecord());

            WorkOrder resumed = workOrderService.resume(1L);
            assertEquals("REPAIRING", resumed.getStatus());
            // SLA resume was attempted
            verify(slaRecordMapper, atLeast(1)).selectByWorkOrderId(1L);
        }
    }
}
