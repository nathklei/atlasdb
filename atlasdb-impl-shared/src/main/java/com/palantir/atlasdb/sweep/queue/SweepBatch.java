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

import java.util.Collection;
import java.util.List;

import org.immutables.value.Value;

/**
 * Contains information on a batch to sweep: a possibly empty list of WriteInfos to sweep for and the maximum timestamp
 * guaranteed to have been swept once the batch is processed.
 */
@Value.Immutable
public interface SweepBatch {
    List<WriteInfo> writes();
    long lastSweptTimestamp();

    static SweepBatch of(Collection<WriteInfo> writes, long timestamp) {
        return ImmutableSweepBatch.builder().writes(writes).lastSweptTimestamp(timestamp).build();
    }
}
