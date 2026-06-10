package com.factory.repair.controller;

import com.factory.repair.model.entity.SparePart;
import com.factory.repair.model.entity.SparePartReservation;
import com.factory.repair.service.SparePartReservationService;
import com.factory.repair.service.SparePartService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/spare-parts")
@RequiredArgsConstructor
public class SparePartController {

    private final SparePartService sparePartService;
    private final SparePartReservationService reservationService;

    @GetMapping
    public ResponseEntity<List<SparePart>> listAll() {
        return ResponseEntity.ok(sparePartService.listAll());
    }

    @GetMapping("/{id}")
    public ResponseEntity<SparePart> getById(@PathVariable Long id) {
        return ResponseEntity.ok(sparePartService.getById(id));
    }

    @GetMapping("/{id}/reservations")
    public ResponseEntity<List<SparePartReservation>> getReservations(@PathVariable Long id) {
        return ResponseEntity.ok(reservationService.getByWorkOrderId(id));
    }

    @GetMapping("/low-stock")
    public ResponseEntity<List<SparePart>> listLowStock() {
        return ResponseEntity.ok(sparePartService.listLowStock());
    }
}
