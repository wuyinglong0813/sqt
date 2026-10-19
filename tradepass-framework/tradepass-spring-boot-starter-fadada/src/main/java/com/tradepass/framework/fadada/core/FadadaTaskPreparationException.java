package com.tradepass.framework.fadada.core;

import com.tradepass.framework.common.exception.BusinessException;

/** No create-sign-task request has been issued; uploaded files alone are not a task. */
public class FadadaTaskPreparationException extends BusinessException {
    public FadadaTaskPreparationException(String message) { super(message); }
}
