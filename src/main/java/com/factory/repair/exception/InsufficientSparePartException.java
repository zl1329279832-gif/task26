package com.factory.repair.exception;

import lombok.Getter;

@Getter
public class InsufficientSparePartException extends BusinessException {

    private final int availableQty;

    public InsufficientSparePartException(String message, int availableQty) {
        super(422, message);
        this.availableQty = availableQty;
    }
}
