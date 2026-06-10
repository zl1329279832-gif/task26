package com.factory.repair.service.impl;

import com.factory.repair.mapper.EquipmentMapper;
import com.factory.repair.model.entity.Equipment;
import com.factory.repair.service.EquipmentService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class EquipmentServiceImpl implements EquipmentService {

    private final EquipmentMapper equipmentMapper;

    @Override
    public Equipment getById(Long id) {
        return equipmentMapper.selectById(id);
    }

    @Override
    public Equipment getByCode(String code) {
        return equipmentMapper.selectByCode(code);
    }

    @Override
    public List<Equipment> listAll() {
        return equipmentMapper.selectAll();
    }

    @Override
    public void updateStatus(Long id, Integer status) {
        equipmentMapper.updateStatus(id, status);
    }
}
