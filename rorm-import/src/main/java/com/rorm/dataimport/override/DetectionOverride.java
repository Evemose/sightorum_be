package com.rorm.dataimport.override;

import com.rorm.dataimport.hierarchical.HierarchicalOverride;

/**
 * Marker interface for detection override types.
 * <p>
 * This sealed interface serves as a common ancestor for all override types
 * used during schema detection, enabling unified handling of both flat
 * (CSV) and hierarchical (JSON/YAML) data source overrides.
 */
public sealed interface DetectionOverride permits SchemaOverride, HierarchicalOverride {
}
