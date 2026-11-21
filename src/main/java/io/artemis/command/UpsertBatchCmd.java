package io.artemis.command;

import io.artemis.api.model.*;
import java.util.*;

/** Raw vectors are copied here; authoritative metric validation/normalization occurs on apply. */
public record UpsertBatchCmd(String collection, List<VectorRecord> records) implements Command<BatchUpsertResult> {
    public UpsertBatchCmd {
        records = copy(records);
    }

    @Override public List<VectorRecord> records() { return copy(records); }

    private static List<VectorRecord> copy(List<VectorRecord> records) {
        if (records == null) return null;
        var result = new ArrayList<VectorRecord>(records.size());
        for (var row : records) {
            result.add(row == null ? null : new VectorRecord(row.id(),
                    row.vector() == null ? null : row.vector().clone(),
                    row.metadata() == null ? null : Collections.unmodifiableMap(new HashMap<>(row.metadata()))));
        }
        return Collections.unmodifiableList(result);
    }
}
