package com.factory.repair.controller;

import com.factory.repair.model.dto.DowntimeLossDTO;
import com.factory.repair.service.DowntimeService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/downtime")
@RequiredArgsConstructor
public class DowntimeController {

    private final DowntimeService downtimeService;

    @GetMapping("/equipment/{equipmentId}")
    public ResponseEntity<List<DowntimeLossDTO>> getByEquipment(@PathVariable Long equipmentId) {
        return ResponseEntity.ok(downtimeService.getByEquipmentId(equipmentId));
    }

    @GetMapping("/statistics")
    public ResponseEntity<DowntimeLossDTO> getStatistics(@RequestParam Long equipmentId) {
        return ResponseEntity.ok(downtimeService.getStatistics(equipmentId));
    }
}
