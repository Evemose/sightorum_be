package com.rorm.dataimport.source;

import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

public interface ImportDataSource extends AutoCloseable {

    String getRootName();

    List<String> getColumnNames();

    Stream<Map<String, String>> stream();

    @Override
    void close();
}
