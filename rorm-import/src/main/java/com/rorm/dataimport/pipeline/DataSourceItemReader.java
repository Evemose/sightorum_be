package com.rorm.dataimport.pipeline;

import com.rorm.dataimport.source.ImportDataSource;
import org.jspecify.annotations.Nullable;
import org.springframework.batch.item.ItemReader;

import java.util.Iterator;
import java.util.Map;

class DataSourceItemReader implements ItemReader<Map<String, Object>> {

    private final ImportDataSource dataSource;
    @Nullable
    private Iterator<Map<String, Object>> iterator;

    DataSourceItemReader(ImportDataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    @Nullable
    public Map<String, Object> read() {
        if (iterator == null) {
            iterator = dataSource.stream().iterator();
        }
        return iterator.hasNext() ? iterator.next() : null;
    }
}
