package com.factory.repair.service;

import com.factory.repair.model.entity.Equipment;
import java.util.List;

public interface EquipmentService {
    Equipment getById(Long id);
    Equipment getByCode(String code);
    List<Equipment> listAll();
    void updateStatus(Long id, Integer status);
}
