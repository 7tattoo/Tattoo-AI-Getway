package com.aigetway.tattoo7.gateway;

/** 请求解析异常。 */
public class BadRequestException extends Exception {
    public BadRequestException(String msg) {
        super(msg);
    }
}