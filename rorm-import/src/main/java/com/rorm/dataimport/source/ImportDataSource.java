package com.rorm.dataimport.source;

import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

public interface ImportDataSource extends AutoCloseable {

    String getRootName();

    List<String> getColumnNames();

    Stream<Map<String, String>> stream();

    /// Close the data source and release any held resources.
    /// Some datasource may allow reopening after close by calling [ImportDataSource#stream] again.
    @Override
    void close();
}
