/*
 * Copyright (c) 2010-2015 Pivotal Software, Inc. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you
 * may not use this file except in compliance with the License. You
 * may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 * implied. See the License for the specific language governing
 * permissions and limitations under the License. See accompanying
 * LICENSE file.
 */
   
   
package com.gemstone.gemfire.internal.admin.remote;

import com.gemstone.gemfire.distributed.internal.*;
//import com.gemstone.gemfire.*;
//import com.gemstone.gemfire.internal.*;
import java.io.*;


/**
 * A message to cause a remote application to release any snapshot info it
 * was holding on behalf of a console.
 */
public final class FlushAppCacheSnapshotMessage extends PooledDistributionMessage {

  public static FlushAppCacheSnapshotMessage create() {
    FlushAppCacheSnapshotMessage m = new FlushAppCacheSnapshotMessage();
    return m;
  }


  @Override
  protected void process(DistributionManager dm) {
//     try {
//       AppCacheSnapshotMessage.flushSnapshots(this.getSender());
//     } catch (Exception ex) {
//       LogWriterI18n logger = dm.getLogger();
//       if (logger != null)
//         logger.warning("Failed " + this, ex);
//     }
  }

  public int getDSFID() {
    return FLUSH_APP_CACHE_SNAPSHOT_MESSAGE;
  }

  @Override
  public void toData(DataOutput out) throws IOException {
    super.toData(out);
  }

  @Override
  public void fromData(DataInput in) throws IOException,
      ClassNotFoundException {
    super.fromData(in);
  }

  @Override
  public String toString() {
    return "FlushAppCacheSnapshotMessage from " + this.getSender();
  }
}
