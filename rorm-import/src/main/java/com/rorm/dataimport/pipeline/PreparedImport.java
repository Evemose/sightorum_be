package com.rorm.dataimport.pipeline;

import com.rorm.metamodel.ModelSpace;

public record PreparedImport(
    ImportRequest request,
    ModelSpace modelSpace
) {}
