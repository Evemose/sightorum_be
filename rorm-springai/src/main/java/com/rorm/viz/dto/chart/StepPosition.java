package com.rorm.viz.dto.chart;

import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonClassDescription("""
    Step-line riser position relative to the data point:
    - 'start'  -> riser at the left edge of the step.
    - 'middle' -> riser at the midpoint of the step.
    - 'end'    -> riser at the right edge of the step.""")
public enum StepPosition {
    @JsonProperty("start") START,
    @JsonProperty("middle") MIDDLE,
    @JsonProperty("end") END
}
