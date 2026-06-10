package com.factory.repair.controller;

import com.factory.repair.model.entity.RepairCrew;
import com.factory.repair.model.entity.RepairWorker;
import com.factory.repair.service.CrewService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/crews")
@RequiredArgsConstructor
public class CrewController {

    private final CrewService crewService;

    @GetMapping
    public ResponseEntity<List<RepairCrew>> listActive() {
        return ResponseEntity.ok(crewService.listActive());
    }

    @GetMapping("/{id}/workers")
    public ResponseEntity<List<RepairWorker>> getWorkers(@PathVariable Long id) {
        return ResponseEntity.ok(crewService.getWorkersByCrewId(id));
    }

    @PostMapping("/workers/{workerId}/online")
    public ResponseEntity<Map<String, Object>> goOnline(@PathVariable Long workerId) {
        crewService.updateWorkerOnlineStatus(workerId, true);
        Map<String, Object> result = new HashMap<>();
        result.put("code", 200);
        result.put("message", "上线成功");
        return ResponseEntity.ok(result);
    }

    @PostMapping("/workers/{workerId}/offline")
    public ResponseEntity<Map<String, Object>> goOffline(@PathVariable Long workerId) {
        crewService.updateWorkerOnlineStatus(workerId, false);
        Map<String, Object> result = new HashMap<>();
        result.put("code", 200);
        result.put("message", "下线成功");
        return ResponseEntity.ok(result);
    }
}
