package com.factory.repair.service;

import com.factory.repair.model.entity.RepairCrew;
import com.factory.repair.model.entity.RepairWorker;

import java.util.List;

public interface CrewService {
    RepairCrew getById(Long id);
    List<RepairCrew> listActive();
    List<RepairWorker> getWorkersByCrewId(Long crewId);
    void updateWorkerOnlineStatus(Long workerId, boolean online);
}
