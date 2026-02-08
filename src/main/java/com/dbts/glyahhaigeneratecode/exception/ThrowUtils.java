package com.dbts.glyahhaigeneratecode.exception;


public class ThrowUtils {
    /**
     * 条件成立则抛异常
     * @param condition
     * @param errorCode
     */
    public static void throwIf (boolean condition, RuntimeException errorCode) {
        if (condition) {
            throw new RuntimeException("异常code: " + errorCode + "\t异常信息: " + errorCode.getMessage());
        }
    }

    public static void throwIf (boolean condition, ErrorCode errorCode) {
        throwIf(condition, new MyException(errorCode));
    }

    public static void throwIf (boolean condition, ErrorCode errorCode, String message) {
        throwIf(condition, new MyException(errorCode, message));
    }

    public static void throwIfNull (Object object, ErrorCode errorCode) {
        throwIf(object == null, errorCode);
    }
}
