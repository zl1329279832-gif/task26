package com.maintenance.service;

import com.maintenance.common.BusinessException;
import com.maintenance.entity.SparePart;
import com.maintenance.entity.SparePartOccupation;
import com.maintenance.entity.WorkOrder;
import com.maintenance.enums.EventType;
import com.maintenance.enums.OccupationStatus;
import com.maintenance.enums.WorkOrderStatus;
import com.maintenance.infrastructure.queue.LocalMessageQueue;
import com.maintenance.mapper.SparePartMapper;
import com.maintenance.mapper.SparePartOccupationMapper;
import com.maintenance.mapper.WorkOrderMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
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

@ExtendWith(MockitoExtension.class)
class SparePartServiceTest {

    @Mock
    private SparePartMapper sparePartMapper;

    @Mock
    private SparePartOccupationMapper sparePartOccupationMapper;

    @Mock
    private WorkOrderMapper workOrderMapper;

    @Mock
    private RedisTemplate<String, Object> redisTemplate;

    @Mock
    private ValueOperations<String, Object> valueOperations;

    @Mock
    private LocalMessageQueue messageQueue;

    @Mock
    private AuditService auditService;

    @InjectMocks
    private SparePartService sparePartService;

    private static final Long WORK_ORDER_ID = 100L;
    private static final Long PART_ID = 200L;
    private static final int QUANTITY = 5;

    @BeforeEach
    void setUp() {
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOperations);
    }

    // ======================== Helper methods ========================

    private WorkOrder createWorkOrder(String status) {
        WorkOrder wo = new WorkOrder();
        wo.setId(WORK_ORDER_ID);
        wo.setStatus(status);
        return wo;
    }

    private SparePart createSparePart(int stockQuantity) {
        SparePart part = new SparePart();
        part.setId(PART_ID);
        part.setPartCode("SP-001");
        part.setPartName("Bearing");
        part.setStockQuantity(stockQuantity);
        part.setUnitPrice(new BigDecimal("49.99"));
        return part;
    }

    private SparePartOccupation createOccupation(Long id, Long partId, int quantity) {
        SparePartOccupation occ = new SparePartOccupation();
        occ.setId(id);
        occ.setWorkOrderId(WORK_ORDER_ID);
        occ.setPartId(partId);
        occ.setQuantity(quantity);
        occ.setStatus(OccupationStatus.OCCUPIED.name());
        occ.setCreatedAt(LocalDateTime.now());
        return occ;
    }

    private void stubLockSuccess() {
        when(valueOperations.setIfAbsent(anyString(), any(), anyLong(), any(TimeUnit.class)))
                .thenReturn(true);
    }

    private void stubLockFailure() {
        when(valueOperations.setIfAbsent(anyString(), any(), anyLong(), any(TimeUnit.class)))
                .thenReturn(false);
    }

    // ======================== occupyPart - status rejection tests ========================

    @Test
    void occupyPart_rejectsWhenWorkOrderStatusIsCreated() {
        when(workOrderMapper.selectById(WORK_ORDER_ID))
                .thenReturn(createWorkOrder(WorkOrderStatus.CREATED.name()));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> sparePartService.occupyPart(WORK_ORDER_ID, PART_ID, QUANTITY));

        assertTrue(ex.getMessage().contains("Cannot occupy parts"));
        assertTrue(ex.getMessage().contains(WorkOrderStatus.CREATED.name()));
        verify(workOrderMapper).selectById(WORK_ORDER_ID);
        verifyNoInteractions(sparePartMapper);
        verifyNoInteractions(sparePartOccupationMapper);
        verifyNoInteractions(messageQueue);
    }

    @Test
    void occupyPart_rejectsWhenWorkOrderStatusIsCompleted() {
        when(workOrderMapper.selectById(WORK_ORDER_ID))
                .thenReturn(createWorkOrder(WorkOrderStatus.COMPLETED.name()));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> sparePartService.occupyPart(WORK_ORDER_ID, PART_ID, QUANTITY));

        assertTrue(ex.getMessage().contains("Cannot occupy parts"));
        assertTrue(ex.getMessage().contains(WorkOrderStatus.COMPLETED.name()));
        verify(workOrderMapper).selectById(WORK_ORDER_ID);
        verifyNoInteractions(sparePartMapper);
        verifyNoInteractions(messageQueue);
    }

    @Test
    void occupyPart_rejectsWhenWorkOrderStatusIsSuspended() {
        when(workOrderMapper.selectById(WORK_ORDER_ID))
                .thenReturn(createWorkOrder(WorkOrderStatus.SUSPENDED.name()));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> sparePartService.occupyPart(WORK_ORDER_ID, PART_ID, QUANTITY));

        assertTrue(ex.getMessage().contains("Cannot occupy parts"));
        assertTrue(ex.getMessage().contains(WorkOrderStatus.SUSPENDED.name()));
        verify(workOrderMapper).selectById(WORK_ORDER_ID);
        verifyNoInteractions(sparePartMapper);
        verifyNoInteractions(messageQueue);
    }

    // ======================== occupyPart - successful status tests ========================

    @Test
    void occupyPart_allowsWhenStatusIsAccepted() {
        WorkOrder workOrder = createWorkOrder(WorkOrderStatus.ACCEPTED.name());
        SparePart part = createSparePart(20);

        when(workOrderMapper.selectById(WORK_ORDER_ID)).thenReturn(workOrder);
        stubLockSuccess();
        when(sparePartMapper.selectById(PART_ID)).thenReturn(part);
        when(sparePartMapper.decreaseStock(PART_ID, QUANTITY)).thenReturn(1);
        when(sparePartOccupationMapper.insert(any(SparePartOccupation.class))).thenReturn(1);

        SparePartOccupation result = sparePartService.occupyPart(WORK_ORDER_ID, PART_ID, QUANTITY);

        assertNotNull(result);
        assertEquals(WORK_ORDER_ID, result.getWorkOrderId());
        assertEquals(PART_ID, result.getPartId());
        assertEquals(QUANTITY, result.getQuantity());
        assertEquals(OccupationStatus.OCCUPIED.name(), result.getStatus());

        verify(sparePartMapper).decreaseStock(PART_ID, QUANTITY);
        verify(sparePartOccupationMapper).insert(any(SparePartOccupation.class));
        verify(messageQueue).publish(eq(EventType.PART_REQUESTED.name()), anyMap());
        verify(auditService).log(eq("SPARE_PART"), eq("OCCUPY"), eq("WorkOrder"),
                eq(WORK_ORDER_ID), eq("SYSTEM"), contains("SP-001"));
        verify(redisTemplate).delete("part:lock:" + PART_ID);
    }

    @Test
    void occupyPart_allowsWhenStatusIsRepairing() {
        WorkOrder workOrder = createWorkOrder(WorkOrderStatus.REPAIRING.name());
        SparePart part = createSparePart(10);

        when(workOrderMapper.selectById(WORK_ORDER_ID)).thenReturn(workOrder);
        stubLockSuccess();
        when(sparePartMapper.selectById(PART_ID)).thenReturn(part);
        when(sparePartMapper.decreaseStock(PART_ID, QUANTITY)).thenReturn(1);
        when(sparePartOccupationMapper.insert(any(SparePartOccupation.class))).thenReturn(1);

        SparePartOccupation result = sparePartService.occupyPart(WORK_ORDER_ID, PART_ID, QUANTITY);

        assertNotNull(result);
        assertEquals(OccupationStatus.OCCUPIED.name(), result.getStatus());
        assertEquals(WORK_ORDER_ID, result.getWorkOrderId());
        assertEquals(PART_ID, result.getPartId());
        assertEquals(QUANTITY, result.getQuantity());

        verify(sparePartMapper).decreaseStock(PART_ID, QUANTITY);
        verify(sparePartOccupationMapper).insert(any(SparePartOccupation.class));
        verify(messageQueue).publish(eq(EventType.PART_REQUESTED.name()), anyMap());
        verify(auditService).log(eq("SPARE_PART"), eq("OCCUPY"), eq("WorkOrder"),
                eq(WORK_ORDER_ID), eq("SYSTEM"), anyString());
        verify(redisTemplate).delete("part:lock:" + PART_ID);
    }

    // ======================== occupyPart - stock and concurrency tests ========================

    @Test
    void occupyPart_throwsWhenInsufficientStock() {
        WorkOrder workOrder = createWorkOrder(WorkOrderStatus.ACCEPTED.name());
        SparePart part = createSparePart(2); // stock=2 < quantity=5

        when(workOrderMapper.selectById(WORK_ORDER_ID)).thenReturn(workOrder);
        stubLockSuccess();
        when(sparePartMapper.selectById(PART_ID)).thenReturn(part);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> sparePartService.occupyPart(WORK_ORDER_ID, PART_ID, QUANTITY));

        assertTrue(ex.getMessage().contains("Insufficient stock"));
        assertTrue(ex.getMessage().contains("SP-001"));
        verify(sparePartMapper).selectById(PART_ID);
        verify(sparePartMapper, never()).decreaseStock(anyLong(), anyInt());
        verifyNoInteractions(sparePartOccupationMapper);
        verifyNoInteractions(messageQueue);
        // Lock should still be released in finally block
        verify(redisTemplate).delete("part:lock:" + PART_ID);
    }

    @Test
    void occupyPart_throwsWhenConcurrentConflict() {
        WorkOrder workOrder = createWorkOrder(WorkOrderStatus.ARRIVED.name());
        SparePart part = createSparePart(10); // stock appears sufficient

        when(workOrderMapper.selectById(WORK_ORDER_ID)).thenReturn(workOrder);
        stubLockSuccess();
        when(sparePartMapper.selectById(PART_ID)).thenReturn(part);
        when(sparePartMapper.decreaseStock(PART_ID, QUANTITY)).thenReturn(0); // concurrent conflict

        BusinessException ex = assertThrows(BusinessException.class,
                () -> sparePartService.occupyPart(WORK_ORDER_ID, PART_ID, QUANTITY));

        assertTrue(ex.getMessage().contains("concurrent conflict"));
        verify(sparePartMapper).decreaseStock(PART_ID, QUANTITY);
        verifyNoInteractions(sparePartOccupationMapper);
        verifyNoInteractions(messageQueue);
        // Lock should still be released in finally block
        verify(redisTemplate).delete("part:lock:" + PART_ID);
    }

    @Test
    void occupyPart_throwsWhenLockAcquisitionFails() {
        WorkOrder workOrder = createWorkOrder(WorkOrderStatus.ACCEPTED.name());

        when(workOrderMapper.selectById(WORK_ORDER_ID)).thenReturn(workOrder);
        stubLockFailure();

        BusinessException ex = assertThrows(BusinessException.class,
                () -> sparePartService.occupyPart(WORK_ORDER_ID, PART_ID, QUANTITY));

        assertTrue(ex.getMessage().contains("Failed to acquire part lock"));
        verify(valueOperations).setIfAbsent(eq("part:lock:" + PART_ID), eq("1"),
                eq(10L), eq(TimeUnit.SECONDS));
        verify(sparePartMapper, never()).selectById(anyLong());
        verify(sparePartMapper, never()).decreaseStock(anyLong(), anyInt());
        verifyNoInteractions(sparePartOccupationMapper);
        verifyNoInteractions(messageQueue);
        // Lock was not acquired, so delete should NOT be called
        verify(redisTemplate, never()).delete(anyString());
    }

    // ======================== releaseOccupationsByWorkOrder tests ========================

    @Test
    void releaseOccupationsByWorkOrder_returnsStockAndPublishesEvents() {
        Long partId1 = 301L;
        Long partId2 = 302L;
        SparePartOccupation occ1 = createOccupation(1L, partId1, 3);
        SparePartOccupation occ2 = createOccupation(2L, partId2, 7);
        List<SparePartOccupation> occupiedList = List.of(occ1, occ2);

        when(sparePartOccupationMapper.selectByWorkOrderAndStatus(WORK_ORDER_ID,
                OccupationStatus.OCCUPIED.name())).thenReturn(occupiedList);
        stubLockSuccess();

        sparePartService.releaseOccupationsByWorkOrder(WORK_ORDER_ID);

        // Verify stock was returned for each part
        verify(sparePartMapper).increaseStock(partId1, 3);
        verify(sparePartMapper).increaseStock(partId2, 7);

        // Verify status was updated to RELEASED for each occupation
        verify(sparePartOccupationMapper).updateStatus(eq(1L),
                eq(OccupationStatus.RELEASED.name()), any(LocalDateTime.class));
        verify(sparePartOccupationMapper).updateStatus(eq(2L),
                eq(OccupationStatus.RELEASED.name()), any(LocalDateTime.class));

        // Verify PART_RELEASED events were published for each
        verify(messageQueue, times(2)).publish(eq(EventType.PART_RELEASED.name()), anyMap());

        // Verify audit log was recorded once for the batch
        verify(auditService).log(eq("SPARE_PART"), eq("RELEASE_ALL"), eq("WorkOrder"),
                eq(WORK_ORDER_ID), eq("SYSTEM"), contains("2"));

        // Verify locks were acquired and released for each part
        verify(valueOperations).setIfAbsent(eq("part:lock:" + partId1), eq("1"),
                eq(10L), eq(TimeUnit.SECONDS));
        verify(valueOperations).setIfAbsent(eq("part:lock:" + partId2), eq("1"),
                eq(10L), eq(TimeUnit.SECONDS));
        verify(redisTemplate).delete("part:lock:" + partId1);
        verify(redisTemplate).delete("part:lock:" + partId2);
    }

    @Test
    void releaseOccupationsByWorkOrder_handlesEmptyOccupationList() {
        when(sparePartOccupationMapper.selectByWorkOrderAndStatus(WORK_ORDER_ID,
                OccupationStatus.OCCUPIED.name())).thenReturn(Collections.emptyList());

        assertDoesNotThrow(() -> sparePartService.releaseOccupationsByWorkOrder(WORK_ORDER_ID));

        verify(sparePartOccupationMapper).selectByWorkOrderAndStatus(WORK_ORDER_ID,
                OccupationStatus.OCCUPIED.name());
        verifyNoInteractions(sparePartMapper);
        verifyNoMoreInteractions(sparePartOccupationMapper);
        verifyNoInteractions(messageQueue);
        verifyNoInteractions(auditService);
        verify(redisTemplate, never()).delete(anyString());
    }
}
