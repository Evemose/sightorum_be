package com.rorm.viz.dto;

import com.fasterxml.jackson.annotation.JsonClassDescription;

@JsonClassDescription("""
    Severity level for advisories, callouts, and fired diagnostic checks.
    Frontend sorts fired checks HIGH -> MED -> LOW.""")
public enum Severity {
    HIGH,
    MED,
    LOW
}
