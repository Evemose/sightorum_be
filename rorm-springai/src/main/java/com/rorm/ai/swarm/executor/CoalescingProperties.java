package com.rorm.ai.swarm.executor;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;
import java.util.List;

/**
 * Cost-optimisation gate for swarm step execution. Agents of the same
 * role that arrive within {@link #window} of each other are coalesced
 * into one batch; the first agent runs, and its first response token
 * releases the rest. Set {@code window} to {@code PT0S} to disable.
 */
@ConfigurationProperties(prefix = "rorm.ai.coalescing")
public record CoalescingProperties(
    @DefaultValue("PT0M") Duration window,
    List<String> excludedRoles
) {

    public CoalescingProperties {
        if (excludedRoles == null || excludedRoles.isEmpty()) {
            excludedRoles = List.of(
                "scout", "domain-researcher", "judge",
                "generator", "rebuttal", "sceptic"
            );
        }
        excludedRoles = List.copyOf(excludedRoles);
    }

}
