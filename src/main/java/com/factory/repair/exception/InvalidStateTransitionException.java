package com.factory.repair.exception;

public class InvalidStateTransitionException extends BusinessException {

    public InvalidStateTransitionException(String currentState, String event) {
        super(400, "非法状态流转: 当前状态[" + currentState + "]不允许执行操作[" + event + "]");
    }
}
