package com.factory.repair.service.impl;

import com.factory.repair.mapper.RepairCrewMapper;
import com.factory.repair.mapper.RepairWorkerMapper;
import com.factory.repair.model.entity.RepairCrew;
import com.factory.repair.model.entity.RepairWorker;
import com.factory.repair.service.CrewService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class CrewServiceImpl implements CrewService {

    private final RepairCrewMapper repairCrewMapper;
    private final RepairWorkerMapper repairWorkerMapper;
    private final StringRedisTemplate stringRedisTemplate;

    @Override
    public RepairCrew getById(Long id) {
        return repairCrewMapper.selectById(id);
    }

    @Override
    public List<RepairCrew> listActive() {
        return repairCrewMapper.selectActive();
    }

    @Override
    public List<RepairWorker> getWorkersByCrewId(Long crewId) {
        return repairWorkerMapper.selectByCrewId(crewId);
    }

    @Override
    public void updateWorkerOnlineStatus(Long workerId, boolean online) {
        repairWorkerMapper.updateOnlineStatus(workerId, online ? 1 : 0);
        if (online) {
            stringRedisTemplate.opsForSet().add("worker:online_set", workerId.toString());
        } else {
            stringRedisTemplate.opsForSet().remove("worker:online_set", workerId.toString());
        }
    }
}
