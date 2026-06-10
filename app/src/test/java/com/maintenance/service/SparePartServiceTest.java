package com.maintenance.service;

import com.maintenance.common.BusinessException;
import com.maintenance.entity.SparePartOccupation;
import com.maintenance.enums.OccupationStatus;
import com.maintenance.infrastructure.queue.LocalMessageQueue;
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
}
