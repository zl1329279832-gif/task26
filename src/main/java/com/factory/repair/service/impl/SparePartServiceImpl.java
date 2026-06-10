package com.factory.repair.service.impl;

import com.factory.repair.mapper.SparePartMapper;
import com.factory.repair.model.entity.SparePart;
import com.factory.repair.service.SparePartService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class SparePartServiceImpl implements SparePartService {

    private final SparePartMapper sparePartMapper;

    @Override
    public SparePart getById(Long id) {
        return sparePartMapper.selectById(id);
    }

    @Override
    public List<SparePart> listAll() {
        return sparePartMapper.selectAll();
    }

    @Override
    public List<SparePart> listByEquipmentType(String equipmentType) {
        return sparePartMapper.selectByEquipmentType(equipmentType);
    }

    @Override
    public List<SparePart> listLowStock() {
        return sparePartMapper.selectLowStock();
    }
}
