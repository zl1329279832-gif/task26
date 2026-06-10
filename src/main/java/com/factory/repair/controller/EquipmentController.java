package com.factory.repair.controller;

import com.factory.repair.model.entity.Equipment;
import com.factory.repair.service.EquipmentService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/equipment")
@RequiredArgsConstructor
public class EquipmentController {

    private final EquipmentService equipmentService;

    @GetMapping
    public ResponseEntity<List<Equipment>> listAll() {
        return ResponseEntity.ok(equipmentService.listAll());
    }

    @GetMapping("/{id}")
    public ResponseEntity<Equipment> getById(@PathVariable Long id) {
        return ResponseEntity.ok(equipmentService.getById(id));
    }
}
