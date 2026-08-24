package com.example.search.application.sync;

import java.util.List;

public interface SearchIndexWriter {
    List<IndexWriteResult> bulkWrite(String target, List<IndexMutation> mutations);
}
