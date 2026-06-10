package com.factory.repair.service;

import com.factory.repair.model.entity.SparePart;
import java.util.List;

public interface SparePartService {
    SparePart getById(Long id);
    List<SparePart> listAll();
    List<SparePart> listByEquipmentType(String equipmentType);
    List<SparePart> listLowStock();
}
