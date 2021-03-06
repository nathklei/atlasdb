/*
 * Copyright 2018 Palantir Technologies, Inc. All rights reserved.
 *
 * Licensed under the BSD-3 License (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://opensource.org/licenses/BSD-3-Clause
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.palantir.atlasdb.sweep.queue;

import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterables;
import com.palantir.atlasdb.AtlasDbConstants;
import com.palantir.atlasdb.keyvalue.api.Cell;
import com.palantir.atlasdb.keyvalue.api.CheckAndSetException;
import com.palantir.atlasdb.keyvalue.api.CheckAndSetRequest;
import com.palantir.atlasdb.keyvalue.api.KeyValueService;
import com.palantir.atlasdb.keyvalue.api.TableReference;
import com.palantir.atlasdb.keyvalue.api.Value;
import com.palantir.atlasdb.schema.generated.SweepShardProgressTable;
import com.palantir.atlasdb.schema.generated.TargetedSweepTableFactory;
import com.palantir.logsafe.SafeArg;
import com.palantir.util.PersistableBoolean;

public class ShardProgress {
    private static final Logger log = LoggerFactory.getLogger(ShardProgress.class);
    private static final TableReference TABLE_REF = TargetedSweepTableFactory.of()
            .getSweepShardProgressTable(null).getTableRef();

    private static final int SHARD_COUNT_INDEX = -1;

    private final KeyValueService kvs;

    public ShardProgress(KeyValueService kvs) {
        this.kvs = kvs;
    }

    /**
     * Returns the persisted number of shards for the sweep queue.
     */
    public int getNumberOfShards() {
        return (int) getOrReturnInitial(ShardAndStrategy.conservative(SHARD_COUNT_INDEX),
                AtlasDbConstants.DEFAULT_SWEEP_QUEUE_SHARDS);
    }

    /**
     * Updates the persisted number of shards to newNumber, if newNumber is greater than the currently persisted number
     * of shards.
     *
     * @param newNumber the desired new number of shards
     * @return the latest known persisted number of shards, which may be greater than newNumber
     */
    public int updateNumberOfShards(int newNumber) {
        Preconditions.checkArgument(newNumber <= AtlasDbConstants.MAX_SWEEP_QUEUE_SHARDS);
        return (int) increaseValueToAtLeast(ShardAndStrategy.conservative(SHARD_COUNT_INDEX), newNumber);
    }

    /**
     * Returns the last swept timestamp for the given shard and strategy.
     */
    public long getLastSweptTimestamp(ShardAndStrategy shardAndStrategy) {
        return getOrReturnInitial(shardAndStrategy, SweepQueueUtils.INITIAL_TIMESTAMP);
    }

    /**
     * Updates the persisted last swept timestamp for the given shard and strategy to timestamp if it is greater than
     * the currently persisted last swept timestamp.
     *
     * @param shardAndStrategy shard and strategy to update for
     * @param timestamp timestamp to update to
     * @return the latest known persisted sweep timestamp for the shard and strategy
     */
    public long updateLastSweptTimestamp(ShardAndStrategy shardAndStrategy, long timestamp) {
        return increaseValueToAtLeast(shardAndStrategy, timestamp);
    }

    private long getOrReturnInitial(ShardAndStrategy shardAndStrategy, long initialValue) {
        Map<Cell, Value> result = getEntry(shardAndStrategy);
        if (result.isEmpty()) {
            return initialValue;
        }
        return getValue(result);
    }

    private Map<Cell, Value> getEntry(ShardAndStrategy shardAndStrategy) {
        return kvs.get(TABLE_REF, ImmutableMap.of(cellForShard(shardAndStrategy), SweepQueueUtils.READ_TS));
    }

    private Cell cellForShard(ShardAndStrategy shardAndStrategy) {
        SweepShardProgressTable.SweepShardProgressRow row = SweepShardProgressTable.SweepShardProgressRow.of(
                shardAndStrategy.shard(),
                PersistableBoolean.of(shardAndStrategy.isConservative()).persistToBytes());
        return Cell.create(row.persistToBytes(),
                SweepShardProgressTable.SweepShardProgressNamedColumn.VALUE.getShortName());
    }

    private long getValue(Map<Cell, Value> entry) {
        SweepShardProgressTable.Value value = SweepShardProgressTable.Value.BYTES_HYDRATOR.hydrateFromBytes(
                Iterables.getOnlyElement(entry.values()).getContents());
        return value.getValue();
    }

    private long increaseValueToAtLeast(ShardAndStrategy shardAndStrategy, long newVal) {
        long oldVal = getLastSweptTimestamp(shardAndStrategy);
        byte[] colValNew = SweepShardProgressTable.Value.of(newVal).persistValue();

        while (oldVal < newVal) {
            CheckAndSetRequest casRequest = createRequest(shardAndStrategy, oldVal, colValNew);
            try {
                kvs.checkAndSet(casRequest);
                return newVal;
            } catch (CheckAndSetException e) {
                log.info("Failed to check and set from expected old value {} to new value {}. Retrying if the old "
                        + "value changed under us.",
                        SafeArg.of("old value", oldVal),
                        SafeArg.of("new value", newVal));
                oldVal = updateOrRethrowIfNoChange(shardAndStrategy, oldVal, e);
            }
        }
        return oldVal;
    }

    private long updateOrRethrowIfNoChange(ShardAndStrategy shardAndStrategy, long oldVal, CheckAndSetException ex) {
        long updatedOldVal = getValue(getEntry(shardAndStrategy));
        if (updatedOldVal == oldVal) {
            throw ex;
        }
        return updatedOldVal;
    }

    private CheckAndSetRequest createRequest(ShardAndStrategy shardAndStrategy, long oldVal, byte[] colValNew) {
        if (oldVal == SweepQueueUtils.INITIAL_TIMESTAMP) {
            return CheckAndSetRequest.newCell(TABLE_REF, cellForShard(shardAndStrategy), colValNew);
        }
        byte[] colValOld = SweepShardProgressTable.Value.of(oldVal).persistValue();
        return CheckAndSetRequest.singleCell(TABLE_REF, cellForShard(shardAndStrategy), colValOld, colValNew);
    }
}
