package com.maintenance.dto;

import lombok.Data;

@Data
public class PartQuery {

    /** 备件类型 */
    private String partType;

    /** 适用设备类型 */
    private String equipmentType;

    /** 仅查低库存 */
    private Boolean lowStockOnly;

    private Integer pageNum = 1;

    private Integer pageSize = 20;
}
