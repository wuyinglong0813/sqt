package com.tradepass.framework.rpc.core;

import com.tradepass.framework.common.core.HostedRoles;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class HostedRolesTest {
    @Test void businessProcessHostsTradingDomainsAndNotIdentity() {
        for (String domain : new String[] {"contract", "trade", "settlement", "file"}) {
            assertTrue(HostedRoles.hosts("business", domain));
            assertFalse(HostedRoles.hosts("identity", domain));
        }
        assertTrue(HostedRoles.hosts("identity", "identity"));
        assertFalse(HostedRoles.hosts("business", "identity"));
        assertTrue(HostedRoles.hosts("contract", "contract"));
        assertFalse(HostedRoles.hosts("contract", "trade"));
    }
}
