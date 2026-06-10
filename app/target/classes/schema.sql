-- 设备表
CREATE TABLE IF NOT EXISTS equipment (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    equipment_code VARCHAR(50) NOT NULL UNIQUE COMMENT '设备编码',
    equipment_name VARCHAR(100) NOT NULL COMMENT '设备名称',
    equipment_type VARCHAR(50) NOT NULL COMMENT '设备类型',
    location VARCHAR(100) COMMENT '设备位置',
    status VARCHAR(20) NOT NULL DEFAULT 'RUNNING' COMMENT '状态: RUNNING/FAULT/MAINTENANCE/STOPPED',
    purchase_date DATE COMMENT '购入日期',
    last_maintenance_date DATETIME COMMENT '上次维保时间',
    downtime_cost_per_hour DECIMAL(10,2) DEFAULT 0 COMMENT '每小时停机损失(元)',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_type (equipment_type),
    INDEX idx_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='设备表';

-- 维修人员表
CREATE TABLE IF NOT EXISTS technician (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    employee_code VARCHAR(50) NOT NULL UNIQUE COMMENT '工号',
    name VARCHAR(50) NOT NULL COMMENT '姓名',
    phone VARCHAR(20) COMMENT '手机号',
    team_code VARCHAR(50) COMMENT '班组编码',
    skill_level INT DEFAULT 1 COMMENT '技能等级1-5',
    availability VARCHAR(20) NOT NULL DEFAULT 'AVAILABLE' COMMENT 'AVAILABLE/BUSY/OFFLINE/ON_LEAVE',
    current_workload INT DEFAULT 0 COMMENT '当前任务数',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_team (team_code),
    INDEX idx_availability (availability)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='维修人员表';

-- 维修人员技能表
CREATE TABLE IF NOT EXISTS technician_skill (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    technician_id BIGINT NOT NULL COMMENT '维修人员ID',
    equipment_type VARCHAR(50) NOT NULL COMMENT '设备类型',
    proficiency INT DEFAULT 1 COMMENT '熟练度1-5',
    certified_fault_level INT DEFAULT 1 COMMENT '可处理最高故障等级1-4',
    UNIQUE KEY uk_tech_type (technician_id, equipment_type),
    INDEX idx_type (equipment_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='维修人员技能表';

-- 故障上报表
CREATE TABLE IF NOT EXISTS fault (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    fault_code VARCHAR(50) NOT NULL UNIQUE COMMENT '故障编号',
    equipment_id BIGINT NOT NULL COMMENT '设备ID',
    equipment_type VARCHAR(50) NOT NULL COMMENT '设备类型',
    fault_level INT NOT NULL DEFAULT 1 COMMENT '故障等级: 1一般 2中等 3严重 4紧急',
    fault_description TEXT COMMENT '故障描述',
    reporter VARCHAR(50) COMMENT '上报人',
    reporter_phone VARCHAR(20) COMMENT '上报人联系方式',
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/PROCESSING/RESOLVED/CLOSED',
    occurrence_count INT DEFAULT 1 COMMENT '重复上报次数',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_equipment (equipment_id),
    INDEX idx_level (fault_level),
    INDEX idx_status (status),
    INDEX idx_created (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='故障上报表';

-- 维修工单表
CREATE TABLE IF NOT EXISTS work_order (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    order_code VARCHAR(50) NOT NULL UNIQUE COMMENT '工单编号',
    fault_id BIGINT NOT NULL COMMENT '故障ID',
    equipment_id BIGINT NOT NULL COMMENT '设备ID',
    technician_id BIGINT COMMENT '维修人员ID',
    status VARCHAR(30) NOT NULL DEFAULT 'CREATED' COMMENT '状态',
    priority INT NOT NULL DEFAULT 1 COMMENT '优先级1-4',
    fault_description TEXT COMMENT '故障描述',
    repair_notes TEXT COMMENT '维修记录',
    reassign_count INT DEFAULT 0 COMMENT '转派次数',
    escalate_count INT DEFAULT 0 COMMENT '升级次数',
    is_rerepair TINYINT DEFAULT 0 COMMENT '是否返修',
    original_order_id BIGINT COMMENT '原工单ID(返修时)',
    parts_cost DECIMAL(10,2) DEFAULT 0 COMMENT '备件费用',
    labor_cost DECIMAL(10,2) DEFAULT 0 COMMENT '人工费用',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    accepted_at DATETIME COMMENT '接单时间',
    arrived_at DATETIME COMMENT '到场时间',
    completed_at DATETIME COMMENT '完工时间',
    suspended_at DATETIME COMMENT '暂停时间',
    sla_deadline DATETIME COMMENT 'SLA截止时间',
    sla_paused_at DATETIME COMMENT 'SLA暂停时间',
    sla_paused_duration_minutes INT DEFAULT 0 COMMENT 'SLA累计暂停时长(分钟)',
    dispatch_plan_id BIGINT COMMENT '选中的派工方案ID',
    INDEX idx_fault (fault_id),
    INDEX idx_equipment (equipment_id),
    INDEX idx_technician (technician_id),
    INDEX idx_status (status),
    INDEX idx_priority (priority)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='维修工单表';

-- 派工记录表
CREATE TABLE IF NOT EXISTS dispatch_record (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    work_order_id BIGINT NOT NULL COMMENT '工单ID',
    technician_id BIGINT NOT NULL COMMENT '维修人员ID',
    dispatch_type VARCHAR(20) NOT NULL COMMENT 'AUTO/MANUAL/REASSIGN',
    dispatch_score DECIMAL(5,2) COMMENT '派工评分',
    is_accepted TINYINT DEFAULT 0 COMMENT '是否已接单',
    response_time INT COMMENT '响应时间(秒)',
    rejected_reason VARCHAR(500) COMMENT '拒绝原因',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    accepted_at DATETIME COMMENT '接单时间',
    INDEX idx_order (work_order_id),
    INDEX idx_technician (technician_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='派工记录表';

-- 备件库存表
CREATE TABLE IF NOT EXISTS spare_part (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    part_code VARCHAR(50) NOT NULL UNIQUE COMMENT '备件编码',
    part_name VARCHAR(100) NOT NULL COMMENT '备件名称',
    part_type VARCHAR(50) COMMENT '备件类型',
    applicable_equipment_types VARCHAR(500) COMMENT '适用设备类型(JSON数组)',
    stock_quantity INT NOT NULL DEFAULT 0 COMMENT '库存数量',
    min_stock INT DEFAULT 0 COMMENT '最低库存',
    location VARCHAR(100) COMMENT '仓库位置',
    unit_price DECIMAL(10,2) DEFAULT 0 COMMENT '单价',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_type (part_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='备件库存表';

-- 备件占用表
CREATE TABLE IF NOT EXISTS spare_part_occupation (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    work_order_id BIGINT NOT NULL COMMENT '工单ID',
    part_id BIGINT NOT NULL COMMENT '备件ID',
    quantity INT NOT NULL COMMENT '占用数量',
    status VARCHAR(20) NOT NULL DEFAULT 'OCCUPIED' COMMENT 'OCCUPIED/CONSUMED/RELEASED',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    consumed_at DATETIME COMMENT '消耗时间',
    released_at DATETIME COMMENT '释放时间',
    INDEX idx_order (work_order_id),
    INDEX idx_part (part_id),
    INDEX idx_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='备件占用表';

-- 停机记录表
CREATE TABLE IF NOT EXISTS downtime_record (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    equipment_id BIGINT NOT NULL COMMENT '设备ID',
    work_order_id BIGINT COMMENT '工单ID',
    fault_id BIGINT COMMENT '故障ID',
    start_time DATETIME NOT NULL COMMENT '停机开始',
    end_time DATETIME COMMENT '停机结束',
    duration_minutes INT COMMENT '停机时长(分钟)',
    downtime_loss DECIMAL(12,2) COMMENT '停机损失(元)',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_equipment (equipment_id),
    INDEX idx_order (work_order_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='停机记录表';

-- 派工方案表
CREATE TABLE IF NOT EXISTS dispatch_plan (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    work_order_id BIGINT NOT NULL COMMENT '工单ID',
    fault_id BIGINT NOT NULL COMMENT '故障ID',
    technician_id BIGINT NOT NULL COMMENT '候选维修人员ID',
    plan_rank INT NOT NULL COMMENT '方案排名(1=最优)',
    total_score DECIMAL(7,2) NOT NULL COMMENT '综合评分',
    skill_score DECIMAL(5,2) DEFAULT 0 COMMENT '技能匹配分',
    cert_score DECIMAL(5,2) DEFAULT 0 COMMENT '资质认证分',
    availability_score DECIMAL(5,2) DEFAULT 0 COMMENT '可用性分',
    workload_score DECIMAL(5,2) DEFAULT 0 COMMENT '工作负载分',
    history_score DECIMAL(5,2) DEFAULT 0 COMMENT '历史绩效分',
    parts_score DECIMAL(5,2) DEFAULT 0 COMMENT '备件可用分',
    downtime_cost_score DECIMAL(5,2) DEFAULT 0 COMMENT '停机成本分',
    sla_score DECIMAL(5,2) DEFAULT 0 COMMENT 'SLA紧迫度分',
    estimated_repair_minutes INT COMMENT '预计维修时长(分钟)',
    estimated_downtime_loss DECIMAL(12,2) COMMENT '预计停机损失(元)',
    sla_remaining_minutes INT COMMENT '生成时SLA剩余时间(分钟)',
    recommendation_reason TEXT COMMENT '推荐原因',
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/SELECTED/REJECTED/EXPIRED',
    selected_at DATETIME COMMENT '选中时间',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_work_order (work_order_id),
    INDEX idx_fault (fault_id),
    INDEX idx_technician (technician_id),
    INDEX idx_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='派工方案表';

-- 采购建议表
CREATE TABLE IF NOT EXISTS purchase_suggestion (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    work_order_id BIGINT NOT NULL COMMENT '触发工单ID',
    part_id BIGINT NOT NULL COMMENT '备件ID',
    suggested_quantity INT NOT NULL COMMENT '建议采购数量',
    urgency_level VARCHAR(20) NOT NULL DEFAULT 'NORMAL' COMMENT 'NORMAL/HIGH/CRITICAL',
    current_stock INT NOT NULL COMMENT '建议时库存',
    required_quantity INT NOT NULL COMMENT '需求数量',
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/APPROVED/ORDERED/FULFILLED/CANCELLED',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_work_order (work_order_id),
    INDEX idx_part (part_id),
    INDEX idx_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='采购建议表';

-- 审计日志表
CREATE TABLE IF NOT EXISTS audit_log (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    module VARCHAR(50) COMMENT '模块',
    action VARCHAR(50) COMMENT '操作',
    target_type VARCHAR(50) COMMENT '目标类型',
    target_id BIGINT COMMENT '目标ID',
    operator VARCHAR(50) COMMENT '操作人',
    detail TEXT COMMENT '详情',
    ip_address VARCHAR(50) COMMENT 'IP地址',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_module (module),
    INDEX idx_target (target_type, target_id),
    INDEX idx_created (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='审计日志表';
