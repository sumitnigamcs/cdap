package com.continuuity.data.table.handles;
/*
 * com.continuuity.data.table.handles - Copyright (c) 2012 Continuuity Inc. All rights reserved.
 */

import com.continuuity.data.engine.memory.MemoryOVCTable;
import com.continuuity.data.operation.executor.omid.TimestampOracle;
import com.continuuity.data.table.OrderedVersionedColumnarTable;
import com.google.inject.Inject;

public class MemoryOVCTableHandle extends SimpleOVCTableHandle {

  @Inject
  private TimestampOracle timeOracle;
  
  @Override
  public OrderedVersionedColumnarTable createNewTable(byte[] tableName) {
    return new MemoryOVCTable(tableName, timeOracle);
  }
}
