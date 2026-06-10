package com.factory.repair.exception;

public class DuplicateReportException extends BusinessException {

    private final String existingOrderNo;

    public DuplicateReportException(String existingOrderNo) {
        super(409, "该设备已有同类故障工单处理中，工单号: " + existingOrderNo);
        this.existingOrderNo = existingOrderNo;
    }

    public String getExistingOrderNo() {
        return existingOrderNo;
    }
}
