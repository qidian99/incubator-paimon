/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.paimon.utils;

import it.unimi.dsi.fastutil.ints.IntArrays;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;

/** A hash set for ints. */
public class IntHashSet {

    private final IntOpenHashSet set;

    public IntHashSet() {
        this.set = new IntOpenHashSet();
    }

    public IntHashSet(int expected) {
        this.set = new IntOpenHashSet(expected);
    }

    public void add(int value) {
        set.add(value);
    }

    public int[] toSortedInts() {
        int[] ints = set.toIntArray();
        IntArrays.stableSort(ints);
        return ints;
    }
}
