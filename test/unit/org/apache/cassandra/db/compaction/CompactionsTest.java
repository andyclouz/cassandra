/*
* Licensed to the Apache Software Foundation (ASF) under one
* or more contributor license agreements.  See the NOTICE file
* distributed with this work for additional information
* regarding copyright ownership.  The ASF licenses this file
* to you under the Apache License, Version 2.0 (the
* "License"); you may not use this file except in compliance
* with the License.  You may obtain a copy of the License at
*
*    http://www.apache.org/licenses/LICENSE-2.0
*
* Unless required by applicable law or agreed to in writing,
* software distributed under the License is distributed on an
* "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
* KIND, either express or implied.  See the License for the
* specific language governing permissions and limitations
* under the License.
*/
package org.apache.cassandra.db.compaction;

import java.io.*;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.Test;
import static junit.framework.Assert.assertEquals;
import static junit.framework.Assert.assertNotNull;
import static junit.framework.Assert.assertTrue;

import org.apache.cassandra.CleanupHelper;
import org.apache.cassandra.Util;
import org.apache.cassandra.config.DatabaseDescriptor;
import org.apache.cassandra.db.*;
import org.apache.cassandra.db.compaction.OperationType;
import org.apache.cassandra.db.filter.QueryFilter;
import org.apache.cassandra.db.filter.QueryPath;
import org.apache.cassandra.io.sstable.*;
import org.apache.cassandra.io.util.FileUtils;
import org.apache.cassandra.utils.ByteBufferUtil;
import org.apache.cassandra.utils.FBUtilities;

public class CompactionsTest extends CleanupHelper
{
    public static final String TABLE1 = "Keyspace1";

    @Test
    public void testBlacklistingWithSizeTieredCompactionStrategy() throws Exception
    {
        testBlacklisting(SizeTieredCompactionStrategy.class.getCanonicalName());
    }

    @Test
    public void testBlacklistingWithLeveledCompactionStrategy() throws Exception
    {
        testBlacklisting(LeveledCompactionStrategy.class.getCanonicalName());
    }

    @Test
    public void testStandardColumnCompactions() throws IOException, ExecutionException, InterruptedException
    {
        // this test does enough rows to force multiple block indexes to be used
        Table table = Table.open(TABLE1);
        ColumnFamilyStore store = table.getColumnFamilyStore("Standard1");

        final int ROWS_PER_SSTABLE = 10;
        final int SSTABLES = DatabaseDescriptor.getIndexInterval() * 3 / ROWS_PER_SSTABLE;

        // disable compaction while flushing
        store.disableAutoCompaction();

        long maxTimestampExpected = Long.MIN_VALUE;
        Set<DecoratedKey> inserted = new HashSet<DecoratedKey>();
        for (int j = 0; j < SSTABLES; j++) {
            for (int i = 0; i < ROWS_PER_SSTABLE; i++) {
                DecoratedKey key = Util.dk(String.valueOf(i % 2));
                RowMutation rm = new RowMutation(TABLE1, key.key);
                long timestamp = j * ROWS_PER_SSTABLE + i;
                rm.add(new QueryPath("Standard1", null, ByteBufferUtil.bytes(String.valueOf(i / 2))),
                       ByteBufferUtil.EMPTY_BYTE_BUFFER,
                       timestamp);
                maxTimestampExpected = Math.max(timestamp, maxTimestampExpected);
                rm.apply();
                inserted.add(key);
            }
            store.forceBlockingFlush();
            assertMaxTimestamp(store, maxTimestampExpected);
            assertEquals(inserted.toString(), inserted.size(), Util.getRangeSlice(store).size());
        }

        forceCompactions(store);

        assertEquals(inserted.size(), Util.getRangeSlice(store).size());

        // make sure max timestamp of compacted sstables is recorded properly after compaction.
        assertMaxTimestamp(store, maxTimestampExpected);
        store.truncate();
    }

    /**
     * Test to see if sstable has enough expired columns, it is compacted itself.
     */
    @Test
    public void testSingleSSTableCompaction() throws Exception
    {
        Table table = Table.open(TABLE1);
        ColumnFamilyStore store = table.getColumnFamilyStore("Standard1");
        store.clearUnsafe();
        store.metadata.gcGraceSeconds(5);

        // update SizeTieredCompactionStrategy's min_sstable_size to something small
        // to split bucket for ttl'd sstable from others
        Map<String, String> opts = new HashMap<String, String>();
        opts.put(SizeTieredCompactionStrategy.MIN_SSTABLE_SIZE_KEY, "512");
        store.metadata.compactionStrategyOptions(opts);
        store.setCompactionStrategyClass(SizeTieredCompactionStrategy.class.getCanonicalName());
        // disable compaction while flushing
        store.disableAutoCompaction();

        long timestamp = System.currentTimeMillis();
        for (int i = 0; i < 10; i++)
        {
            DecoratedKey key = Util.dk(Integer.toString(i));
            RowMutation rm = new RowMutation(TABLE1, key.key);
            for (int j = 0; j < 10; j++)
                rm.add(new QueryPath("Standard1", null, ByteBufferUtil.bytes(Integer.toString(j))),
                       ByteBufferUtil.EMPTY_BYTE_BUFFER,
                       timestamp,
                       j > 0 ? 3 : 0); // let first column never expire, since deleting all columns does not produce sstable
            rm.apply();
        }
        store.forceBlockingFlush();
        assertEquals(1, store.getSSTables().size());
        long originalSize = store.getSSTables().iterator().next().uncompressedLength();

        // wait enough to force single compaction
        TimeUnit.SECONDS.sleep(5);

        // enable compaction, submit background and wait for it to complete
        store.setMinimumCompactionThreshold(2);
        store.setMaximumCompactionThreshold(4);
        FBUtilities.waitOnFuture(CompactionManager.instance.submitBackground(store));
        while (CompactionManager.instance.getPendingTasks() > 0 || CompactionManager.instance.getActiveCompactions() > 0)
            TimeUnit.SECONDS.sleep(1);

        // and sstable with ttl should be compacted
        assertEquals(1, store.getSSTables().size());
        long size = store.getSSTables().iterator().next().uncompressedLength();
        assertTrue("should be less than " + originalSize + ", but was " + size, size < originalSize);

        // make sure max timestamp of compacted sstables is recorded properly after compaction.
        assertMaxTimestamp(store, timestamp);
    }

    @Test
    public void testSuperColumnCompactions() throws IOException, ExecutionException, InterruptedException
    {
        Table table = Table.open(TABLE1);
        ColumnFamilyStore store = table.getColumnFamilyStore("Super1");

        final int ROWS_PER_SSTABLE = 10;
        final int SSTABLES = DatabaseDescriptor.getIndexInterval() * 3 / ROWS_PER_SSTABLE;

        //disable compaction while flushing
        store.disableAutoCompaction();

        long maxTimestampExpected = Long.MIN_VALUE;
        Set<DecoratedKey> inserted = new HashSet<DecoratedKey>();
        ByteBuffer superColumn = ByteBufferUtil.bytes("TestSuperColumn");
        for (int j = 0; j < SSTABLES; j++)
        {
            for (int i = 0; i < ROWS_PER_SSTABLE; i++)
            {
                DecoratedKey key = Util.dk(String.valueOf(i % 2));
                RowMutation rm = new RowMutation(TABLE1, key.key);
                long timestamp = j * ROWS_PER_SSTABLE + i;
                rm.add(new QueryPath("Super1", superColumn, ByteBufferUtil.bytes((long)(i / 2))),
                       ByteBufferUtil.EMPTY_BYTE_BUFFER,
                       timestamp);
                maxTimestampExpected = Math.max(timestamp, maxTimestampExpected);
                rm.apply();
                inserted.add(key);
            }
            store.forceBlockingFlush();
            assertMaxTimestamp(store, maxTimestampExpected);
            assertEquals(inserted.toString(), inserted.size(), Util.getRangeSlice(store, superColumn).size());
        }

        forceCompactions(store);

        assertEquals(inserted.size(), Util.getRangeSlice(store, superColumn).size());

        // make sure max timestamp of compacted sstables is recorded properly after compaction.
        assertMaxTimestamp(store, maxTimestampExpected);
    }

    public void assertMaxTimestamp(ColumnFamilyStore store, long maxTimestampExpected)
    {
        long maxTimestampObserved = Long.MIN_VALUE;
        for (SSTableReader sstable : store.getSSTables())
            maxTimestampObserved = Math.max(sstable.getMaxTimestamp(), maxTimestampObserved);
        assertEquals(maxTimestampExpected, maxTimestampObserved);
    }

    private void forceCompactions(ColumnFamilyStore store) throws ExecutionException, InterruptedException
    {
        // re-enable compaction with thresholds low enough to force a few rounds
        store.setMinimumCompactionThreshold(2);
        store.setMaximumCompactionThreshold(4);

        // loop submitting parallel compactions until they all return 0
        do
        {
            ArrayList<Future<?>> compactions = new ArrayList<Future<?>>();
            for (int i = 0; i < 10; i++)
                compactions.add(CompactionManager.instance.submitBackground(store));
            // another compaction attempt will be launched in the background by
            // each completing compaction: not much we can do to control them here
            FBUtilities.waitOnFutures(compactions);
        } while (CompactionManager.instance.getPendingTasks() > 0 || CompactionManager.instance.getActiveCompactions() > 0);

        if (store.getSSTables().size() > 1)
        {
            CompactionManager.instance.performMaximal(store);
        }
    }

    @Test
    public void testEchoedRow() throws IOException, ExecutionException, InterruptedException
    {
        // This test check that EchoedRow doesn't skipp rows: see CASSANDRA-2653

        Table table = Table.open(TABLE1);
        ColumnFamilyStore store = table.getColumnFamilyStore("Standard2");

        // disable compaction while flushing
        store.disableAutoCompaction();

        // Insert 4 keys in two sstables. We need the sstables to have 2 rows
        // at least to trigger what was causing CASSANDRA-2653
        for (int i=1; i < 5; i++)
        {
            DecoratedKey key = Util.dk(String.valueOf(i));
            RowMutation rm = new RowMutation(TABLE1, key.key);
            rm.add(new QueryPath("Standard2", null, ByteBufferUtil.bytes(String.valueOf(i))), ByteBufferUtil.EMPTY_BYTE_BUFFER, i);
            rm.apply();

            if (i % 2 == 0)
                store.forceBlockingFlush();
        }
        Collection<SSTableReader> toCompact = store.getSSTables();
        assert toCompact.size() == 2;

        // Reinserting the same keys. We will compact only the previous sstable, but we need those new ones
        // to make sure we use EchoedRow, otherwise it won't be used because purge can be done.
        for (int i=1; i < 5; i++)
        {
            DecoratedKey key = Util.dk(String.valueOf(i));
            RowMutation rm = new RowMutation(TABLE1, key.key);
            rm.add(new QueryPath("Standard2", null, ByteBufferUtil.bytes(String.valueOf(i))), ByteBufferUtil.EMPTY_BYTE_BUFFER, i);
            rm.apply();
        }
        store.forceBlockingFlush();
        SSTableReader tmpSSTable = null;
        for (SSTableReader sstable : store.getSSTables())
            if (!toCompact.contains(sstable))
                tmpSSTable = sstable;
        assert tmpSSTable != null;

        // Force compaction on first sstables. Since each row is in only one sstable, we will be using EchoedRow.
        Util.compact(store, toCompact, false);
        assertEquals(2, store.getSSTables().size());

        // Now, we remove the sstable that was just created to force the use of EchoedRow (so that it doesn't hide the problem)
        store.markCompacted(Collections.singleton(tmpSSTable), OperationType.UNKNOWN);
        assertEquals(1, store.getSSTables().size());

        // Now assert we do have the 4 keys
        assertEquals(4, Util.getRangeSlice(store).size());
    }

    @Test
    public void testDontPurgeAccidentaly() throws IOException, ExecutionException, InterruptedException
    {
        // Testing with and without forcing deserialization. Without deserialization, EchoedRow will be used.
        testDontPurgeAccidentaly("test1", "Super5", false);
        testDontPurgeAccidentaly("test2", "Super5", true);

        // Use CF with gc_grace=0, see last bug of CASSANDRA-2786
        testDontPurgeAccidentaly("test1", "SuperDirectGC", false);
        testDontPurgeAccidentaly("test2", "SuperDirectGC", true);
    }

    private void testDontPurgeAccidentaly(String k, String cfname, boolean forceDeserialize) throws IOException, ExecutionException, InterruptedException
    {
        // This test catches the regression of CASSANDRA-2786
        Table table = Table.open(TABLE1);
        ColumnFamilyStore store = table.getColumnFamilyStore(cfname);

        // disable compaction while flushing
        store.clearUnsafe();
        store.disableAutoCompaction();

        // Add test row
        DecoratedKey key = Util.dk(k);
        RowMutation rm = new RowMutation(TABLE1, key.key);
        rm.add(new QueryPath(cfname, ByteBufferUtil.bytes("sc"), ByteBufferUtil.bytes("c")), ByteBufferUtil.EMPTY_BYTE_BUFFER, 0);
        rm.apply();

        store.forceBlockingFlush();

        Collection<SSTableReader> sstablesBefore = store.getSSTables();

        QueryFilter filter = QueryFilter.getIdentityFilter(key, new QueryPath(cfname, null, null));
        assert !store.getColumnFamily(filter).isEmpty();

        // Remove key
        rm = new RowMutation(TABLE1, key.key);
        rm.delete(new QueryPath(cfname, null, null), 2);
        rm.apply();

        ColumnFamily cf = store.getColumnFamily(filter);
        assert cf == null || cf.isEmpty() : "should be empty: " + cf;

        store.forceBlockingFlush();

        Collection<SSTableReader> sstablesAfter = store.getSSTables();
        Collection<SSTableReader> toCompact = new ArrayList<SSTableReader>();
        for (SSTableReader sstable : sstablesAfter)
            if (!sstablesBefore.contains(sstable))
                toCompact.add(sstable);

        Util.compact(store, toCompact, forceDeserialize);

        cf = store.getColumnFamily(filter);
        assert cf == null || cf.isEmpty() : "should be empty: " + cf;
    }

    public void testBlacklisting(String compactionStrategy) throws Exception
    {
        // this test does enough rows to force multiple block indexes to be used
        Table table = Table.open(TABLE1);
        final ColumnFamilyStore store = table.getColumnFamilyStore("Standard1");

        final int ROWS_PER_SSTABLE = 10;
        final int SSTABLES = DatabaseDescriptor.getIndexInterval() * 2 / ROWS_PER_SSTABLE;

        store.setCompactionStrategyClass(compactionStrategy);

        // disable compaction while flushing
        store.disableAutoCompaction();
        //test index corruption
        //now create a few new SSTables
        long maxTimestampExpected = Long.MIN_VALUE;
        Set<DecoratedKey> inserted = new HashSet<DecoratedKey>();
        for (int j = 0; j < SSTABLES; j++)
        {
            for (int i = 0; i < ROWS_PER_SSTABLE; i++)
            {
                DecoratedKey key = Util.dk(String.valueOf(i % 2));
                RowMutation rm = new RowMutation(TABLE1, key.key);
                long timestamp = j * ROWS_PER_SSTABLE + i;
                rm.add(new QueryPath("Standard1", null, ByteBufferUtil.bytes(String.valueOf(i / 2))),
                        ByteBufferUtil.EMPTY_BYTE_BUFFER,
                        timestamp);
                maxTimestampExpected = Math.max(timestamp, maxTimestampExpected);
                rm.apply();
                inserted.add(key);
            }
            store.forceBlockingFlush();
            assertMaxTimestamp(store, maxTimestampExpected);
            assertEquals(inserted.toString(), inserted.size(), Util.getRangeSlice(store).size());
        }

        Collection<SSTableReader> sstables = store.getSSTables();
        int currentSSTable = 0;
        int sstablesToCorrupt = 8;

        // corrupt first 'sstablesToCorrupt' SSTables
        for (SSTableReader sstable : sstables)
        {
            if(currentSSTable + 1 > sstablesToCorrupt)
                break;

            RandomAccessFile raf = null;

            try
            {
                raf = new RandomAccessFile(sstable.getFilename(), "rw");
                assertNotNull(raf);
                raf.write(0xFFFFFF);
            }
            finally
            {
                FileUtils.closeQuietly(raf);
            }

            currentSSTable++;
        }

        int failures = 0;

        // close error output steam to avoid printing ton of useless RuntimeException
        System.err.close();

        try
        {
            // in case something will go wrong we don't want to loop forever using for (;;)
            for (int i = 0; i < sstables.size(); i++)
            {
                try
                {
                    store.forceMajorCompaction();
                }
                catch (Exception e)
                {
                    failures++;
                    continue;
                }

                assertEquals(sstablesToCorrupt + 1, store.getSSTables().size());
                break;
            }
        }
        finally
        {
            System.setErr(new PrintStream(new ByteArrayOutputStream()));
        }


        store.truncate();
        assertEquals(failures, sstablesToCorrupt);
    }
}
