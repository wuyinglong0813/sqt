package com.tradepass.module.contract.framework.callback;

/** Dispatches the ID of an event already persisted by the existing callback acceptance flow. */
@FunctionalInterface
public interface CallbackDispatcher {
    void dispatch(Long eventId);
}
