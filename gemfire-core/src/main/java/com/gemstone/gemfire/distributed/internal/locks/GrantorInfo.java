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

package com.gemstone.gemfire.distributed.internal.locks;

import com.gemstone.gemfire.distributed.internal.membership.*;

/**
 * Used to provide information on a grantor request made to the elder.
 *
 * @since 4.0
 * @author Darrel Schneider
 */
public class GrantorInfo {
  private final InternalDistributedMember id;
  private final boolean needsRecovery;
  private final long versionId;
  private boolean initiatingTransfer;
  private final int serialNumber;
    
  public GrantorInfo(InternalDistributedMember id, long versionId, int serialNumber, boolean needsRecovery) {
    this.id = id;
    this.needsRecovery = needsRecovery;
    this.versionId = versionId;
    this.serialNumber = serialNumber;
  }
  
  /** Caller is sync'ed on ElderState  */
  public final void setInitiatingTransfer(boolean initiatingTransfer) {
    this.initiatingTransfer = initiatingTransfer;
  }
  
  /** Caller is sync'ed on ElderState  */
  public final boolean isInitiatingTransfer() {
    return this.initiatingTransfer;
  }
  
  /**
   * Gets the member id of this grantor.
   */
  public final InternalDistributedMember getId() {
    return this.id;
  }
  /**
   * Returns true if the current grantor needs to do lock recovery.
   */
  public final boolean needsRecovery() {
    return this.needsRecovery;
  }
  /**
   * Returns the elder version id of this grantor.
   */
  public final long getVersionId() {
    return this.versionId;
  }
  /**
   * Returns the DLockService serial number of this grantor.
   */
  public final int getSerialNumber() {
    return this.serialNumber;
  }
  
  /** Returns human readable String version of this object. */
  @Override
  public String toString() {
    return "<GrantorInfo id=" + this.id + 
           " versionId=" + this.versionId +
           " serialNumber=" + this.serialNumber +
           " needsRecovery=" + this.needsRecovery + 
           " initiatingTransfer=" + this.initiatingTransfer + ">";
  }
  
}
