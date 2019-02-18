/*
 * Copyright © 2019 Cask Data, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package co.cask.cdap.logging.meta;

import co.cask.cdap.spi.data.StructuredRow;
import co.cask.cdap.spi.data.StructuredTable;
import co.cask.cdap.spi.data.table.field.Field;
import co.cask.cdap.spi.data.table.field.Fields;
import co.cask.cdap.spi.data.transaction.TransactionRunner;
import co.cask.cdap.spi.data.transaction.TransactionRunners;
import co.cask.cdap.store.StoreDefinition;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import javax.annotation.Nullable;

/**
 * Abstract checkpoint manager.
 * @param <T> type of the offset
 */
public abstract class AbstractCheckpointManager<T> implements CheckpointManager<T> {
  private static final Logger LOG = LoggerFactory.getLogger(AbstractCheckpointManager.class);

  private final String rowKeyPrefix;
  private final TransactionRunner transactionRunner;

  private Map<Integer, Checkpoint<T>> lastCheckpoint;

  @VisibleForTesting
  public AbstractCheckpointManager(TransactionRunner transactionRunner, String prefix) {
    this.rowKeyPrefix = prefix;
    this.lastCheckpoint = new HashMap<>();
    this.transactionRunner = transactionRunner;
  }

  @Override
  public void saveCheckpoints(final Map<Integer, ? extends Checkpoint<T>> checkpoints) throws IOException {
    // if the checkpoints have not changed, we skip writing to table and return.
    if (lastCheckpoint.equals(checkpoints)) {
      return;
    }

    lastCheckpoint = TransactionRunners.run(transactionRunner, context -> {
      Map<Integer, Checkpoint<T>> result = new HashMap<>();
      StructuredTable table = context.getTable(StoreDefinition.LogCheckpointStore.LOG_CHECKPOINT_TABLE);
      for (Map.Entry<Integer, ? extends Checkpoint<T>> entry : checkpoints.entrySet()) {
        Checkpoint<T> checkpoint = entry.getValue();
        List<Field<?>> fields = ImmutableList.<Field<?>>builder()
          .addAll(getKeyFields(rowKeyPrefix, entry.getKey()))
          .add(Fields.bytesField(StoreDefinition.LogCheckpointStore.CHECKPOINT_FIELD, serializeCheckpoint(checkpoint)))
          .build();
        table.upsert(fields);
        result.put(entry.getKey(), new Checkpoint<>(checkpoint.getOffset(), checkpoint.getMaxEventTime()));
      }
      return result;

    }, IOException.class);

    LOG.trace("Saved checkpoints for partitions {}", checkpoints);
  }

  @Override
  public Map<Integer, Checkpoint<T>> getCheckpoint(final Set<Integer> partitions) throws IOException {
    return TransactionRunners.run(transactionRunner, context -> {
      Map<Integer, Checkpoint<T>> checkpoints = new HashMap<>();
      StructuredTable table = context.getTable(StoreDefinition.LogCheckpointStore.LOG_CHECKPOINT_TABLE);
      for (final int partition : partitions) {
        List<Field<?>> keyFields = ImmutableList.<Field<?>>builder()
          .addAll(getKeyFields(rowKeyPrefix, partition))
          .build();
        Optional<StructuredRow> optionalRow = table.read(keyFields);
        StructuredRow row = optionalRow.orElse(null);
        checkpoints.put(partition, fromRow(row));
      }
      return checkpoints;
    }, IOException.class);
  }

  @Override
  public Checkpoint<T> getCheckpoint(final int partition) throws IOException {
    Checkpoint<T> checkpoint = TransactionRunners.run(transactionRunner, context -> {
      StructuredTable table = context.getTable(StoreDefinition.LogCheckpointStore.LOG_CHECKPOINT_TABLE);
      List<Field<?>> keyFields = ImmutableList.<Field<?>>builder()
        .addAll(getKeyFields(rowKeyPrefix, partition))
        .build();
      Optional<StructuredRow> optionalRow = table.read(keyFields);
      StructuredRow row = optionalRow.orElse(null);
      return fromRow(row);
    }, IOException.class);

    LOG.trace("Read checkpoint {} for partition {}", checkpoint, partition);
    return checkpoint;
  }

  private Checkpoint<T> fromRow(@Nullable StructuredRow row) throws IOException {
    byte[] offset = null;
    if (row != null) {
      offset = row.getBytes(StoreDefinition.LogCheckpointStore.CHECKPOINT_FIELD);
    }
    return deserializeCheckpoint(offset);
  }

  /**
   * Serialize the checkpoint.
   * @param checkpoint checkpoint to be serialized
   * @return serialized checkpoint byte array
   * @throws IOException if error while serializing checkpoint
   */
  protected abstract byte[] serializeCheckpoint(Checkpoint<T> checkpoint) throws IOException;

  /**
   * Deserialize the checkpoint bytes. If checkpoint bytes are null, then return {@code -1} for all the numeric
   * values of checkpoint such as maxEventTime.
   * @param checkpoint checkpoint bytes to be deserialized
   * @return deserialized checkpoint
   * @throws IOException if error while deserializing checkpoint bytes
   */
  protected abstract Checkpoint<T> deserializeCheckpoint(@Nullable byte[] checkpoint) throws IOException;

  private Collection<Field<?>> getKeyFields(String rowPrefix, int partition) {
    return Arrays.asList(Fields.stringField(StoreDefinition.LogCheckpointStore.ROW_PREFIX_FIELD, rowPrefix),
                         Fields.intField(StoreDefinition.LogCheckpointStore.PARTITION_FIELD, partition));
  }
}