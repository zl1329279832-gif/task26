package com.factory.repair.statemachine;

public enum WorkOrderEvent {
    DISPATCH,
    ACCEPT,
    REJECT,
    ARRIVE,
    START_REPAIR,
    APPLY_SPARE_PART,
    PAUSE,
    RESUME,
    COMPLETE,
    TRANSFER,
    ESCALATE,
    CLOSE,
    CANCEL,
    REOPEN
}
