package com.rorm.ai.swarm.agents;

import com.rorm.ai.swarm.SwarmEvent;
import com.rorm.ai.swarm.SwarmEvent.ScoutFinished;
import com.rorm.ai.swarm.SwarmEvent.ScoutStarted;
import com.rorm.ai.swarm.dto.ScoutOverviewDTO;
import reactor.core.publisher.Sinks.Many;

/**
 * Scout agent - performs initial reconnaissance of data landscape
 */
public class ScoutSwarmAgent extends SwarmAgent {
    private final FirstLevelSwarmAgent scout;

    public ScoutSwarmAgent(FirstLevelSwarmAgent scout, SecondarySwarmAgent summarizer) {
        super(summarizer);
        this.scout = scout;
    }

    public ScoutOverviewDTO execute(String input, Many<SwarmEvent> eventSink) {
        return streamAndStructurize(
            StepParams.<ScoutOverviewDTO>builder()
                .agent(scout)
                .userPrompt(input)
                .responseType(ScoutOverviewDTO.class)
                .eventId("scout")
                .startEventFactory(ScoutStarted::new)
                .endEventFactory(ScoutFinished::new)
                .build(),
            eventSink
        );
    }
}
