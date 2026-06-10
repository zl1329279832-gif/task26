package com.maintenance.service;

import com.maintenance.common.BusinessException;
import com.maintenance.entity.SparePart;
import com.maintenance.entity.SparePartOccupation;
import com.maintenance.enums.OccupationStatus;
import com.maintenance.infrastructure.queue.LocalMessageQueue;
import com.maintenance.mapper.PurchaseSuggestionMapper;
import com.maintenance.mapper.SparePartMapper;
import com.maintenance.mapper.SparePartOccupationMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Tests for SparePartService focusing on:
 * 1. Release with lock retry logic (no silent skip)
 * 2. Release failure triggers BusinessException (triggers rollback)
 * 3. Successful release of all occupations
 */
@ExtendWith(MockitoExtension.class)
class SparePartServiceTest {

    @Mock private SparePartMapper sparePartMapper;
    @Mock private SparePartOccupationMapper sparePartOccupationMapper;
    @Mock private RedisTemplate<String, Object> redisTemplate;
    @Mock private ValueOperations<String, Object> valueOperations;
    @Mock private PurchaseSuggestionMapper purchaseSuggestionMapper;
    @Mock private LocalMessageQueue messageQueue;
    @Mock private AuditService auditService;

    private SparePartService sparePartService;

    @BeforeEach
    void setUp() {
        sparePartService = new SparePartService(
                sparePartMapper, sparePartOccupationMapper, purchaseSuggestionMapper,
                redisTemplate, messageQueue, auditService);
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOperations);
    }

    // ========================================================
    // TEST: Release all parts successfully
    // ========================================================
    @Test
    @DisplayName("Release all OCCUPIED parts for a work order succeeds")
    void releaseOccupations_allSucceed() {
        SparePartOccupation occ1 = new SparePartOccupation();
        occ1.setId(1L);
        occ1.setPartId(10L);
        occ1.setQuantity(2);
        occ1.setStatus(OccupationStatus.OCCUPIED.name());

        SparePartOccupation occ2 = new SparePartOccupation();
        occ2.setId(2L);
        occ2.setPartId(20L);
        occ2.setQuantity(1);
        occ2.setStatus(OccupationStatus.OCCUPIED.name());

        when(sparePartOccupationMapper.selectByWorkOrderAndStatus(1L, "OCCUPIED"))
                .thenReturn(Arrays.asList(occ1, occ2));
        when(valueOperations.setIfAbsent(anyString(), anyString(), anyLong(), any(TimeUnit.class)))
                .thenReturn(true);
        when(sparePartMapper.increaseStock(anyLong(), anyInt())).thenReturn(1);
        when(sparePartOccupationMapper.updateStatus(anyLong(), anyString(), any())).thenReturn(1);

        sparePartService.releaseOccupationsByWorkOrder(1L);

        // Both parts should be released
        verify(sparePartMapper).increaseStock(10L, 2);
        verify(sparePartMapper).increaseStock(20L, 1);
        verify(sparePartOccupationMapper).updateStatus(eq(1L), eq("RELEASED"), any());
        verify(sparePartOccupationMapper).updateStatus(eq(2L), eq("RELEASED"), any());
    }

    // ========================================================
    // TEST: Lock failure after retries throws BusinessException
    // ========================================================
    @Test
    @DisplayName("BUG FIX: Lock failure after retries throws BusinessException (triggers rollback)")
    void releaseOccupations_lockFailure_throws() {
        SparePartOccupation occ = new SparePartOccupation();
        occ.setId(1L);
        occ.setPartId(10L);
        occ.setQuantity(2);
        occ.setStatus(OccupationStatus.OCCUPIED.name());

        when(sparePartOccupationMapper.selectByWorkOrderAndStatus(1L, "OCCUPIED"))
                .thenReturn(Collections.singletonList(occ));
        // Lock always fails
        when(valueOperations.setIfAbsent(anyString(), anyString(), anyLong(), any(TimeUnit.class)))
                .thenReturn(false);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> sparePartService.releaseOccupationsByWorkOrder(1L));

        assertTrue(ex.getMessage().contains("Failed to acquire part lock"));
        // Stock should NOT have been increased
        verify(sparePartMapper, never()).increaseStock(anyLong(), anyInt());
    }

    // ========================================================
    // TEST: No occupied parts - no error
    // ========================================================
    @Test
    @DisplayName("Release with no occupied parts completes without error")
    void releaseOccupations_noOccupations_noError() {
        when(sparePartOccupationMapper.selectByWorkOrderAndStatus(1L, "OCCUPIED"))
                .thenReturn(Collections.emptyList());

        // Should not throw
        sparePartService.releaseOccupationsByWorkOrder(1L);

        verify(sparePartMapper, never()).increaseStock(anyLong(), anyInt());
    }

    // ========================================================
    // TEST: Lock succeeds on retry
    // ========================================================
    @Test
    @DisplayName("Lock succeeds on second retry attempt")
    void releaseOccupations_lockSucceedsOnRetry() {
        SparePartOccupation occ = new SparePartOccupation();
        occ.setId(1L);
        occ.setPartId(10L);
        occ.setQuantity(2);
        occ.setStatus(OccupationStatus.OCCUPIED.name());

        when(sparePartOccupationMapper.selectByWorkOrderAndStatus(1L, "OCCUPIED"))
                .thenReturn(Collections.singletonList(occ));
        // First attempt fails, second succeeds
        when(valueOperations.setIfAbsent(anyString(), anyString(), anyLong(), any(TimeUnit.class)))
                .thenReturn(false)
                .thenReturn(true);
        when(sparePartMapper.increaseStock(10L, 2)).thenReturn(1);
        when(sparePartOccupationMapper.updateStatus(eq(1L), eq("RELEASED"), any())).thenReturn(1);

        sparePartService.releaseOccupationsByWorkOrder(1L);

        // Should have retried and succeeded
        verify(sparePartMapper).increaseStock(10L, 2);
        verify(sparePartOccupationMapper).updateStatus(eq(1L), eq("RELEASED"), any());
    }

    // ========================================================
    // TEST: Pre-reserve parts when stock is sufficient
    // ========================================================
    @Test
    @DisplayName("Pre-reserve parts: sufficient stock results in successful reservation")
    void preReserveParts_sufficientStock() {
        SparePart part = new SparePart();
        part.setId(1L);
        part.setPartCode("P001");
        part.setPartName("Bearing");
        part.setStockQuantity(10);
        part.setEquipmentType("CNC");

        when(sparePartMapper.selectByEquipmentType("CNC")).thenReturn(Collections.singletonList(part));
        when(valueOperations.setIfAbsent(anyString(), anyString(), anyLong(), any(TimeUnit.class)))
                .thenReturn(true);
        when(sparePartMapper.selectById(1L)).thenReturn(part);
        when(sparePartMapper.decreaseStock(1L, 1)).thenReturn(1);
        doAnswer(inv -> { SparePartOccupation occ = inv.getArgument(0); occ.setId(1L); return 1; })
                .when(sparePartOccupationMapper).insert(any(SparePartOccupation.class));

        // faultLevel=2 -> requiredQty=1
        List<SparePartOccupation> result = sparePartService.preReserveParts(1L, "CNC", 2);

        assertFalse(result.isEmpty());
        verify(sparePartMapper).decreaseStock(eq(1L), eq(1));
        verify(sparePartOccupationMapper).insert(argThat(occ ->
                OccupationStatus.PRE_RESERVED.name().equals(occ.getStatus())));
    }

    // ========================================================
    // TEST: Pre-reserve parts - insufficient stock publishes failure
    // ========================================================
    @Test
    @DisplayName("Pre-reserve parts: insufficient stock publishes failure event")
    void preReserveParts_insufficientStock() {
        SparePart part = new SparePart();
        part.setId(1L);
        part.setPartCode("P001");
        part.setPartName("Bearing");
        part.setStockQuantity(0);
        part.setEquipmentType("CNC");

        SparePart freshPart = new SparePart();
        freshPart.setId(1L);
        freshPart.setPartCode("P001");
        freshPart.setStockQuantity(0);

        when(sparePartMapper.selectByEquipmentType("CNC")).thenReturn(Collections.singletonList(part));
        when(valueOperations.setIfAbsent(anyString(), anyString(), anyLong(), any(TimeUnit.class)))
                .thenReturn(true);
        when(sparePartMapper.selectById(1L)).thenReturn(freshPart);

        List<SparePartOccupation> result = sparePartService.preReserveParts(1L, "CNC", 2);

        assertTrue(result.isEmpty());
        verify(messageQueue).publish(eq("PART_PRE_RESERVE_FAILED"), any());
        verify(sparePartMapper, never()).decreaseStock(anyLong(), anyInt());
    }

    // ========================================================
    // TEST: Release PRE_RESERVED parts successfully
    // ========================================================
    @Test
    @DisplayName("Release PRE_RESERVED parts restores stock and updates status")
    void releasePreReservationsByWorkOrder_success() {
        SparePartOccupation occ = new SparePartOccupation();
        occ.setId(1L);
        occ.setPartId(10L);
        occ.setQuantity(2);
        occ.setStatus(OccupationStatus.PRE_RESERVED.name());

        when(sparePartOccupationMapper.selectByWorkOrderAndStatus(1L, "PRE_RESERVED"))
                .thenReturn(Collections.singletonList(occ));
        when(valueOperations.setIfAbsent(anyString(), anyString(), anyLong(), any(TimeUnit.class)))
                .thenReturn(true);
        when(sparePartMapper.increaseStock(10L, 2)).thenReturn(1);
        when(sparePartOccupationMapper.updateStatus(eq(1L), eq("RELEASED"), any())).thenReturn(1);

        sparePartService.releasePreReservationsByWorkOrder(1L);

        verify(sparePartMapper).increaseStock(10L, 2);
        verify(sparePartOccupationMapper).updateStatus(eq(1L), eq("RELEASED"), any());
    }

    // ========================================================
    // TEST: Promote PRE_RESERVED to OCCUPIED
    // ========================================================
    @Test
    @DisplayName("Promote PRE_RESERVED to OCCUPIED updates status and logs audit")
    void promotePreReservationsToOccupied_success() {
        when(sparePartOccupationMapper.batchUpdateStatus(eq(1L), eq("PRE_RESERVED"), eq("OCCUPIED"), any()))
                .thenReturn(3);

        sparePartService.promotePreReservationsToOccupied(1L);

        verify(sparePartOccupationMapper).batchUpdateStatus(eq(1L), eq("PRE_RESERVED"), eq("OCCUPIED"), any());
        verify(auditService).log(eq("SPARE_PART"), eq("PROMOTE_PRE_RESERVED"), eq("WorkOrder"), eq(1L), eq("SYSTEM"), anyString());
    }
}
