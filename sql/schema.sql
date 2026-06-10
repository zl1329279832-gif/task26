CREATE DATABASE IF NOT EXISTS factory_repair DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE factory_repair;

-- 设备表
CREATE TABLE equipment (
    id              BIGINT PRIMARY KEY AUTO_INCREMENT,
    equipment_code  VARCHAR(64)  NOT NULL COMMENT '设备编号',
    equipment_name  VARCHAR(128) NOT NULL COMMENT '设备名称',
    equipment_type  VARCHAR(64)  NOT NULL COMMENT '设备类型',
    location        VARCHAR(256)          COMMENT '安装位置/车间',
    status          TINYINT      NOT NULL DEFAULT 0 COMMENT '0-正常 1-故障停机 2-维修中 3-报废',
    hourly_loss     DECIMAL(12,2) NOT NULL DEFAULT 0 COMMENT '每小时停机损失(元)',
    created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_equipment_code (equipment_code),
    KEY idx_equipment_type (equipment_type),
    KEY idx_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='设备表';

-- 故障类型表
CREATE TABLE fault_type (
    id              BIGINT PRIMARY KEY AUTO_INCREMENT,
    fault_code      VARCHAR(64)  NOT NULL COMMENT '故障编码',
    fault_name      VARCHAR(128) NOT NULL COMMENT '故障名称',
    equipment_type  VARCHAR(64)  NOT NULL COMMENT '适用设备类型',
    fault_level     TINYINT      NOT NULL DEFAULT 1 COMMENT '故障等级 1-一般 2-重要 3-紧急',
    required_skills VARCHAR(512)          COMMENT '所需技能(JSON数组)',
    estimated_hours DECIMAL(5,2)          COMMENT '预估维修工时',
    created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_fault_code (fault_code),
    KEY idx_equipment_type (equipment_type),
    KEY idx_fault_level (fault_level)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='故障类型表';

-- 维修班组表
CREATE TABLE repair_crew (
    id              BIGINT PRIMARY KEY AUTO_INCREMENT,
    crew_code       VARCHAR(64)  NOT NULL COMMENT '班组编码',
    crew_name       VARCHAR(128) NOT NULL COMMENT '班组名称',
    skills          VARCHAR(512)          COMMENT '班组技能(JSON数组)',
    shift_type      VARCHAR(32)           COMMENT '班次类型',
    is_active       TINYINT      NOT NULL DEFAULT 1 COMMENT '是否启用',
    created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_crew_code (crew_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='维修班组表';

-- 维修人员表
CREATE TABLE repair_worker (
    id              BIGINT PRIMARY KEY AUTO_INCREMENT,
    worker_code     VARCHAR(64)  NOT NULL COMMENT '工号',
    worker_name     VARCHAR(64)  NOT NULL COMMENT '姓名',
    crew_id         BIGINT                COMMENT '所属班组',
    skills          VARCHAR(512)          COMMENT '个人技能(JSON数组)',
    phone           VARCHAR(32)           COMMENT '手机号',
    is_online       TINYINT      NOT NULL DEFAULT 0 COMMENT '是否在线',
    current_tasks   INT          NOT NULL DEFAULT 0 COMMENT '当前任务数',
    max_tasks       INT          NOT NULL DEFAULT 3 COMMENT '最大并行任务数',
    is_active       TINYINT      NOT NULL DEFAULT 1 COMMENT '是否启用',
    created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_worker_code (worker_code),
    KEY idx_crew (crew_id),
    KEY idx_online_tasks (is_online, current_tasks)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='维修人员表';

-- 维修工单表
CREATE TABLE work_order (
    id                  BIGINT PRIMARY KEY AUTO_INCREMENT,
    order_no            VARCHAR(64)   NOT NULL COMMENT '工单编号',
    equipment_id        BIGINT        NOT NULL COMMENT '设备ID',
    fault_type_id       BIGINT        NOT NULL COMMENT '故障类型ID',
    fault_description   TEXT                   COMMENT '故障描述',
    fault_level         TINYINT       NOT NULL DEFAULT 1 COMMENT '故障等级 1-一般 2-重要 3-紧急',
    status              VARCHAR(32)   NOT NULL DEFAULT 'REPORTED' COMMENT '工单状态',
    reporter_id         BIGINT                 COMMENT '上报人ID',
    assigned_worker_id  BIGINT                 COMMENT '当前指派维修人员ID',
    assigned_crew_id    BIGINT                 COMMENT '当前指派班组ID',
    priority            INT           NOT NULL DEFAULT 100 COMMENT '优先级(数值越小越高)',
    reported_at         DATETIME      NOT NULL COMMENT '故障上报时间',
    dispatched_at       DATETIME               COMMENT '派工时间',
    accepted_at         DATETIME               COMMENT '接单时间',
    arrived_at          DATETIME               COMMENT '到场时间',
    completed_at        DATETIME               COMMENT '完工时间',
    closed_at           DATETIME               COMMENT '关闭时间',
    parent_order_id     BIGINT                 COMMENT '关联父工单(返修场景)',
    remark              TEXT                   COMMENT '备注',
    version             INT           NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    created_at          DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_order_no (order_no),
    KEY idx_equipment_id (equipment_id),
    KEY idx_status (status),
    KEY idx_assigned_worker (assigned_worker_id),
    KEY idx_fault_level (fault_level),
    KEY idx_reported_at (reported_at),
    KEY idx_parent_order (parent_order_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='维修工单表';

-- 派工记录表
CREATE TABLE dispatch_record (
    id                  BIGINT PRIMARY KEY AUTO_INCREMENT,
    work_order_id       BIGINT       NOT NULL COMMENT '工单ID',
    worker_id           BIGINT       NOT NULL COMMENT '维修人员ID',
    crew_id             BIGINT                COMMENT '班组ID',
    dispatch_type       VARCHAR(32)  NOT NULL COMMENT 'AUTO/MANUAL/TRANSFER',
    dispatch_status     VARCHAR(32)  NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/ACCEPTED/REJECTED/TIMEOUT/CANCELLED',
    score               DECIMAL(8,2)          COMMENT '派工评分',
    score_detail        JSON                  COMMENT '评分明细',
    dispatched_at       DATETIME     NOT NULL COMMENT '派工时间',
    responded_at        DATETIME              COMMENT '响应时间',
    timeout_minutes     INT          NOT NULL DEFAULT 30 COMMENT '响应超时(分钟)',
    remark              TEXT                  COMMENT '备注',
    created_at          DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    KEY idx_work_order (work_order_id),
    KEY idx_worker (worker_id),
    KEY idx_dispatch_status (dispatch_status),
    KEY idx_dispatched_at (dispatched_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='派工记录表';

-- 备件库存表
CREATE TABLE spare_part (
    id              BIGINT PRIMARY KEY AUTO_INCREMENT,
    part_code       VARCHAR(64)   NOT NULL COMMENT '备件编码',
    part_name       VARCHAR(128)  NOT NULL COMMENT '备件名称',
    equipment_type  VARCHAR(64)            COMMENT '适用设备类型',
    total_qty       INT           NOT NULL DEFAULT 0 COMMENT '总库存数量',
    available_qty   INT           NOT NULL DEFAULT 0 COMMENT '可用数量',
    reserved_qty    INT           NOT NULL DEFAULT 0 COMMENT '已占用数量',
    min_stock       INT           NOT NULL DEFAULT 0 COMMENT '最低库存预警线',
    unit_price      DECIMAL(12,2) NOT NULL DEFAULT 0 COMMENT '单价(元)',
    version         INT           NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    created_at      DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_part_code (part_code),
    KEY idx_equipment_type (equipment_type),
    KEY idx_available_qty (available_qty)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='备件库存表';

-- 备件占用记录表
CREATE TABLE spare_part_reservation (
    id                  BIGINT PRIMARY KEY AUTO_INCREMENT,
    work_order_id       BIGINT       NOT NULL COMMENT '工单ID',
    spare_part_id       BIGINT       NOT NULL COMMENT '备件ID',
    quantity            INT          NOT NULL COMMENT '占用数量',
    status              VARCHAR(32)  NOT NULL DEFAULT 'RESERVED' COMMENT 'RESERVED/CONSUMED/RELEASED',
    reserved_at         DATETIME     NOT NULL COMMENT '占用时间',
    consumed_at         DATETIME              COMMENT '消耗确认时间',
    released_at         DATETIME              COMMENT '释放时间',
    expire_at           DATETIME     NOT NULL COMMENT '占用过期时间',
    release_reason      VARCHAR(256)           COMMENT '释放原因',
    version             INT          NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    created_at          DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    KEY idx_work_order (work_order_id),
    KEY idx_spare_part (spare_part_id),
    KEY idx_status (status),
    KEY idx_expire_at (expire_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='备件占用记录表';

-- 停机记录表
CREATE TABLE downtime_record (
    id                  BIGINT PRIMARY KEY AUTO_INCREMENT,
    equipment_id        BIGINT        NOT NULL COMMENT '设备ID',
    work_order_id       BIGINT                 COMMENT '关联工单ID',
    start_time          DATETIME      NOT NULL COMMENT '停机开始时间',
    end_time            DATETIME               COMMENT '停机结束时间',
    duration_minutes    INT                    COMMENT '停机时长(分钟)',
    hourly_loss         DECIMAL(12,2) NOT NULL COMMENT '每小时损失(快照)',
    total_loss          DECIMAL(14,2)          COMMENT '总损失金额(元)',
    created_at          DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    KEY idx_equipment (equipment_id),
    KEY idx_work_order (work_order_id),
    KEY idx_start_time (start_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='停机记录表';

-- 审计日志表
CREATE TABLE audit_log (
    id              BIGINT PRIMARY KEY AUTO_INCREMENT,
    entity_type     VARCHAR(64)   NOT NULL COMMENT '实体类型',
    entity_id       BIGINT        NOT NULL COMMENT '实体ID',
    action          VARCHAR(64)   NOT NULL COMMENT '操作类型',
    operator_id     BIGINT                 COMMENT '操作人ID',
    operator_name   VARCHAR(64)            COMMENT '操作人姓名',
    before_data     JSON                   COMMENT '操作前数据',
    after_data      JSON                   COMMENT '操作后数据',
    extra_info      JSON                   COMMENT '附加信息',
    created_at      DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    KEY idx_entity (entity_type, entity_id),
    KEY idx_operator (operator_id),
    KEY idx_action (action),
    KEY idx_created_at (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='审计日志表';
