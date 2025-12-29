package com.rorm.dataimport.source;

import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

public interface ImportDataSource extends AutoCloseable {

    String getRootName();

    List<String> getColumnNames();

    Stream<Map<String, String>> stream();

    /// Peek at the first n records without consuming them from the main stream.
    Stream<Map<String, String>> peekStream(int n);

    /// Close the data source and release any held resources.
    /// Some datasource may allow reopening after close by calling [ImportDataSource#stream] again.
    @Override
    void close();
}
