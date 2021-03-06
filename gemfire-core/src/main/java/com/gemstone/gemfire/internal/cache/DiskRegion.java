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
/*
 * Changes for SnappyData distributed computational and data platform.
 *
 * Portions Copyright (c) 2017 SnappyData, Inc. All rights reserved.
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

package com.gemstone.gemfire.internal.cache;

import java.util.EnumSet;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import com.gemstone.gemfire.CancelCriterion;
import com.gemstone.gemfire.cache.RegionAttributes;
import com.gemstone.gemfire.compression.Compressor;
import com.gemstone.gemfire.internal.cache.DiskInitFile.DiskRegionFlag;
import com.gemstone.gemfire.internal.cache.DiskStoreImpl.AsyncDiskEntry;
import com.gemstone.gemfire.internal.cache.InitialImageOperation.GIIStatus;
import com.gemstone.gemfire.internal.cache.LocalRegion.RegionEntryCallback;
import com.gemstone.gemfire.internal.cache.persistence.BytesAndBits;
import com.gemstone.gemfire.internal.cache.persistence.DiskExceptionHandler;
import com.gemstone.gemfire.internal.cache.persistence.DiskRecoveryStore;
import com.gemstone.gemfire.internal.cache.persistence.DiskStoreID;
import com.gemstone.gemfire.internal.cache.versions.RegionVersionVector;
import com.gemstone.gemfire.internal.cache.versions.VersionStamp;
import com.gemstone.gemfire.internal.util.concurrent.StoppableReentrantReadWriteLock;
import joptsimple.internal.Strings;

/**
 * Represents a (disk-based) persistent store for region data.
 * Used for both persistent recoverable regions and overflow-only regions.
 *
 * @author David Whitlock
 * @author Darrel Schneider
 * @author Mitul Bid
 * @author Asif
 *
 * @since 3.2
 */
public class DiskRegion extends AbstractDiskRegion {

  public static final long INVALID_ID = DiskStoreImpl.INVALID_ID;

  //////////////////////  Instance Fields  ///////////////////////

  /** The stats for this region */
  private final DiskRegionStats stats;

  /** True if overflow is enabled on this region */
  final boolean overflowEnabled;
  
  /** boolean to determine if region is closed* */
  private volatile boolean isRegionClosed = false;

  private final boolean isSync;
  
  private final String name;

  private final CancelCriterion cancel;

  private final DiskExceptionHandler exceptionHandler;
  
  // changed rwLock to lock to fix bug 41390
  // private final StoppableReentrantLock lock;
  private final StoppableReentrantReadWriteLock rwLock;
  
  /**
   * Creates a new <code>DiskRegion</code> that access disk on behalf of the
   * given region.
   */ 
  protected DiskRegion(DiskStoreImpl ds,
                       String name,
                       boolean isBucket,
                       boolean isPersistBackup,
                       boolean overflowEnabled,
                       boolean isSynchronous,
                       DiskRegionStats stats,
                       CancelCriterion cancel,
                       DiskExceptionHandler exceptionHandler,
                       RegionAttributes ra, EnumSet<DiskRegionFlag> flags,
                       long uuid, String partitionName, int startingBucketId,
                       String compressorClassName,  boolean enableOffHeapMemory) {
    super(ds, name, uuid);
    if(this.getPartitionName() != null){
      // I think this code is saying to prefer the recovered partitionName and startingBucketId.
      // Only use the passed in values of these if we have not already recovered this region from disk.
      if(this.getStartingBucketId() != startingBucketId || !this.getPartitionName().equals(partitionName)){
        partitionName = this.getPartitionName();
        startingBucketId = this.getStartingBucketId();
      }
    }

    this.name = name;
    
    if (isRecreated() && isBackup() && !isPersistBackup) {
      // We recovered a persistent region from disk and tried
      // to create an overflow only region of the same name on the same disk store.
      throw new IllegalStateException("The region \""
                                      + getName()
                                      + "\" has been persisted to disk so it can not be recreated on the same disk store without persistence. Either destroy the persistent region, recreate it as overflow and persistent, or create the overflow only region on a different disk store.");
    }
    if (isRecreated() && isBucket != isBucket()) {
      if (isBucket()) {
        throw new IllegalStateException("The region \""
                                        + getName()
                                        + "\" has been persisted to disk as a partition region bucket but is not being recreated as a bucket. This should not be possible.");
      } else {
        throw new IllegalStateException("The region \""
                                        + getName()
                                        + "\" has not been persisted to disk as a partition region bucket but is now being recreated as a bucket. This should not be possible.");
      }
    }
    
    setBackup(isPersistBackup);
//    EvictionAttributes ea = region.getAttributes().getEvictionAttributes();
//    this.overflowEnabled = ea != null && ea.getAction().isOverflowToDisk();
    this.overflowEnabled = overflowEnabled;
//    if (region instanceof BucketRegion) {
//      this.stats = internalRegionArgs.getPartitionedRegion()
//          .getDiskRegionStats();
//    }
//    else {
//      this.stats = new DiskRegionStats(factory, name);
//    }
    this.stats = stats;

    // start simple init

    this.isSync = isSynchronous;
    this.cancel = cancel;
    this.exceptionHandler = exceptionHandler;
    // this.lock = new StoppableReentrantLock(ds.getCancelCriterion());
    this.rwLock = new StoppableReentrantReadWriteLock(ds.getCancelCriterion());

    if (ra != null) {
      byte raLruAlgorithm = (byte)(ra.getEvictionAttributes().getAlgorithm().getValue());
      byte raLruAction = (byte)(ra.getEvictionAttributes().getAction().getValue());
      int raLruLimit = 0;
      if (!ra.getEvictionAttributes().getAlgorithm().isLRUHeap()) {
        raLruLimit = ra.getEvictionAttributes().getMaximum();
      }
//       GemFireCache.getInstance().getLogger()
//         .info("DEBUG isRecreated=" + isRecreated()
//               + " raLruLimit=" + raLruLimit
//               + " getLruLimit()=" + getLruLimit(),
//               new RuntimeException("STACK"));
      if (isRecreated()) {
        // check to see if recovered config differs from current config
        if (raLruAlgorithm != getLruAlgorithm()
            || raLruAction != getLruAction()
            || raLruLimit != getLruLimit()
            || ra.getConcurrencyLevel() != getConcurrencyLevel()
            || ra.getInitialCapacity() != getInitialCapacity()
            || ra.getLoadFactor() != getLoadFactor()
            || ra.getStatisticsEnabled() != getStatisticsEnabled()
            || !hasSameCompressor(ra)) { 
          if (getRecoveredEntryMap() != null) {
            getRecoveredEntryMap().lruCloseStats();
          }
          // Clear the lru stats. They will be recomputed when the copy to the new map is done
          // I want to clear the atomics but leave the stats alone since the place holder
          // did not change the stats during the early oplog recovery.
          super.incNumEntriesInVM(getNumEntriesInVM()*-1);
          super.incNumOverflowOnDisk(getNumOverflowOnDisk()*-1);
          super.incNumOverflowBytesOnDisk(getNumOverflowBytesOnDisk()*-1);
          setEntriesMapIncompatible(true);
          setEntriesIncompatible(true);
          setConfigChanged(true);
        }
        if (uuid != getUUID()) {
          setConfigChanged(true);
        }
        // keep the current flags
        if (getFlags() != null) {
          flags = null;
        }
      }
      setConfig(raLruAlgorithm, raLruAction, raLruLimit,
                ra.getConcurrencyLevel(),
                ra.getInitialCapacity(),
                ra.getLoadFactor(),
                ra.getStatisticsEnabled(),
                isBucket, flags, uuid, partitionName, startingBucketId,
                compressorClassName, enableOffHeapMemory);
    }
    
    if(!isBucket) {
      //Bucket should create data storage only when the actual bucket
      //is created.
      createDataStorage();
    }
  }

  static DiskRegion create(DiskStoreImpl dsi, String name,
                           boolean isBucket, boolean isPersistBackup,
                           boolean overflowEnabled, boolean isSynchronous,
                           DiskRegionStats stats, CancelCriterion cancel,
                           DiskExceptionHandler exceptionHandler,
                           RegionAttributes ra, EnumSet<DiskRegionFlag> flags,
                           long uuid, String partitionName, int startingBucketId,
                           Compressor compressor, boolean enableOffHeapMemory) {
    return dsi.getDiskInitFile().createDiskRegion(dsi, name, isBucket, isPersistBackup,
                                                  overflowEnabled, isSynchronous,
                                                  stats, cancel, exceptionHandler,
                                                  ra, flags, uuid,
                                                  partitionName, startingBucketId,
                                                  compressor, enableOffHeapMemory);
  }

  public CancelCriterion getCancelCriterion() {
    return cancel;
  }
  
  public DiskExceptionHandler getExceptionHandler() {
    return exceptionHandler;
  }

  //////////////////////  Instance Methods  //////////////////////

  private boolean hasSameCompressor(final RegionAttributes<?,?> ra) {
    Compressor raCompressor = ra.getCompressor();
    if (raCompressor == null) {
      return Strings.isNullOrEmpty(getCompressorClassName()) ? true : false;
    }
    return raCompressor.getClass().getName().equals(getCompressorClassName());
  }
  
  protected void register() {
    getDiskStore().addDiskRegion(this);
  }

  @Override
  public final String getName() {
    return this.name;
  }

  /**
   * Returns the <code>DiskRegionStats</code> for this disk region
   */
  public DiskRegionStats getStats() {
    return this.stats;
  }

  @Override
  public void incNumOverflowOnDisk(long delta) {
    getStats().incNumOverflowOnDisk(delta);
    super.incNumOverflowOnDisk(delta);
  }

  @Override
  public void incNumEntriesInVM(long delta) {
    getStats().incNumEntriesInVM(delta);
    super.incNumEntriesInVM(delta);
  }

  /**
   * Initializes the contents of the region that owns this disk region.
   * Currently the only time this method does any work is when backup is true
   * and recovery data was discovered when this disk region was created.
   */
  final void initializeOwner(LocalRegion drs,
      InternalRegionArguments internalRegionArgs) {
    getDiskStore().initializeOwner(drs, internalRegionArgs);
  }

  final void finishInitializeOwner(LocalRegion drs, GIIStatus giiStatus) {
    if (isReadyForRecovery()) {
      //this.scheduleCompaction();
      if (GIIStatus.didFullGII(giiStatus)) {
        destroyRemainingRecoveredEntries(drs);
      } else if (GIIStatus.didDeltaGII(giiStatus)) {
        // TODO: not sure if we should destroy old tombstones for deltaGII
      } else if(getRegionVersionVector() != null){
        destroyOldTomstones(drs);
      }
      releaseRecoveryData();
    }
    if (isBackup() && !this.isRegionClosed() && !this.getRVVTrusted()) {
      if(!giiStatus.didGII()) {
        //If we did not do a GII, but we are still recovering using
        //an untrusted RVV, that means that the RVV may not reflect
        //what is in the region. We need to fix the RVV before
        //we mark the RVV as trusted and allow the region to recover.
        drs.repairRVV();
      }
      
      // since rvvTrust will be true, so persist disk region rvv directly. It does not care inmemory rvv
      // GemFireXD does not have versions yet even for persistent regions
      if (drs.getConcurrencyChecksEnabled()) {
        if (this.isSync()) {
          writeRVV(null, true);
          writeRVVGC(drs);
        } else {
          // put RVV and RVVGC into asyncQueue
          this.getDiskStore().addDiskRegionToQueue(drs);
        }
      }
    }
  }

  private void destroyOldTomstones(final DiskRecoveryStore drs) {
 // iterate over all region entries in drs
    drs.foreachRegionEntry(new RegionEntryCallback() {
        public void handleRegionEntry(RegionEntry re) {
          DiskEntry de = (DiskEntry)re;
          synchronized (de) {
            DiskId id = de.getDiskId();
            if (id != null && re.isTombstone()) {
              VersionStamp stamp = re.getVersionStamp();
              if(getRegionVersionVector().isTombstoneTooOld(stamp.getMemberID(), stamp.getRegionVersion())) {
                drs.destroyRecoveredEntry(de.getKeyCopy());
              }
            }
          }
        }
      });
    
  }

  private void destroyRemainingRecoveredEntries(final DiskRecoveryStore drs) {
    // iterate over all region entries in drs
    drs.foreachRegionEntry(new RegionEntryCallback() {
        public void handleRegionEntry(RegionEntry re) {
          DiskEntry de = (DiskEntry)re;
          synchronized (de) {
            DiskId id = de.getDiskId();
            if (id != null) {
              if (EntryBits.isRecoveredFromDisk(id.getUserBits())) {
                drs.destroyRecoveredEntry(de.getKeyCopy());
              }
            }
          }
        }
      });
  }

  /**
   * Remark all entries as "Recovered From Disk" in preparation for
   * a new GII. This will allow us to destroy those entries
   * if we do not receive them as part of a new GII.
   */
  public void resetRecoveredEntries(final DiskRecoveryStore drs) {
    // iterate over all region entries in drs
    drs.foreachRegionEntry(new RegionEntryCallback() {
        public void handleRegionEntry(RegionEntry re) {
          DiskEntry de = (DiskEntry)re;
          synchronized (de) {
            DiskId id = de.getDiskId();
            if (id != null) {
              id.setRecoveredFromDisk(true);
            }
          }
        }
      });
  }

  public final boolean isOverflowEnabled() {
    return this.overflowEnabled;
  }
  
  /**
   * Stores a key/value pair from a region entry on disk. Updates all of the
   * necessary {@linkplain DiskRegionStats statistics}and invokes
   * {@link Oplog#create}or {@link Oplog#modify}.
   *
   * @param entry
   *          The entry which is going to be written to disk
   * @param value
   *          The <code>ValueWrapper</code> for the byte data
   * @throws RegionClearedException
   *                 If a clear operation completed before the put operation
   *                 completed successfully, resulting in the put operation to
   *                 abort.
   * @throws IllegalArgumentException
   *         If <code>id</code> is less than zero
   */
  final void put(DiskEntry entry, LocalRegion region,
      DiskEntry.Helper.ValueWrapper value, boolean async) throws  RegionClearedException {
    getDiskStore().put(region, entry, value, async);
  }

  /**
   * Returns the value of the key/value pair with the given diskId. Updates all
   * of the necessary {@linkplain DiskRegionStats statistics}
   * 
   * @see #getBytesAndBitsWithoutLock(DiskId, boolean, boolean)
   */
  final Object get(DiskId id) {
    return getDiskStore().get(this, id);
  }

  /**
   * Gets the Object from the OpLog . It can be invoked from OpLog , if by
   * the time a get operation reaches the OpLog, the entry gets compacted
   * or if we allow concurrent put & get operations. It will also minimize the
   * synch lock on DiskId
   * 
   * @param id
   *          DiskId object for the entry
   * @return value of the entry
   */
  final BytesAndBits getBytesAndBitsWithoutLock(DiskId id, boolean faultIn,
                                                boolean bitOnly) {
    return getDiskStore().getBytesAndBitsWithoutLock(this, id, faultIn, bitOnly);
  }

  /**
   * @since 3.2.1
   */
  final BytesAndBits getBytesAndBits(DiskId id) {
    return getBytesAndBits(id, true);
   }
  
  final BytesAndBits getBytesAndBits(DiskId id, boolean faultingIn) {
    return getDiskStore().getBytesAndBits(this, id, faultingIn);
  }

  /**
   * @since 3.2.1
   */
  final byte getBits(DiskId id) {
    return getDiskStore().getBits(this, id);
  }

  /**
   * Asif: THIS SHOULD ONLY BE USED FOR TESTING PURPOSES AS IT IS NOT THREAD
   * SAFE
   * 
   * Returns the object stored on disk with the given id. This method is used
   * for testing purposes only. As such, it bypasses the buffer and goes
   * directly to the disk. This is not a thread safe function , in the sense, it
   * is possible that by the time the OpLog is queried , data might move HTree
   * with the oplog being destroyed
   * 
   * @return null if entry has nothing stored on disk (id == INVALID_ID)
   * @throws IllegalArgumentException
   *        If <code>id</code> is less than zero, no action is taken.
   */
  public final Object getNoBuffer(DiskId id) {
    return getDiskStore().getNoBuffer(this, id);
  }

  /**
   * Removes the key/value pair with the given id on disk.
   *
   * @throws RegionClearedException
   *                 If a clear operation completed before the put operation
   *                 completed successfully, resulting in the put operation to
   *                 abort.
   * @throws IllegalArgumentException
   *           If <code>id</code> is {@linkplain #INVALID_ID invalid}or is
   *           less than zero, no action is taken.
   */
  final void remove(LocalRegion region, DiskEntry entry) throws RegionClearedException {
    getDiskStore().remove(region, entry, false, false);
  }
  final void remove(LocalRegion region, DiskEntry entry, boolean async, boolean isClear) throws RegionClearedException {
    getDiskStore().remove(region, entry, async, isClear);
  }

  //////////////////////  Access Methods for DiskRegionSegment ///////////////

  public void forceRolling() {
    getDiskStore().forceRolling(this);
  }
  
  public boolean forceCompaction() {
    return getDiskStore().forceCompaction(this);
  }
  
  /**
   * Get serialized form of data off the disk
   * @param id
   * @since gemfire5.7_hotfix
   */
  public Object getSerializedData(DiskId id) {
    return getDiskStore().getSerializedData(this, id);
  }

  /**
   * @since prPersistSprint1
   */
  public void scheduleAsyncWrite(AsyncDiskEntry ade) {
    getDiskStore().scheduleAsyncWrite(ade);
  }
  /**
   * @since prPersistSprint1
   */
  public void unscheduleAsyncWrite(DiskId did) {
    getDiskStore().unscheduleAsyncWrite(did);
  }

  public boolean testWaitForAsyncFlusherThread(int waitMs) {
    return getDiskStore().testWaitForAsyncFlusherThread(waitMs);
  }
  
  /**
   * force a flush but do it async (don't wait for the flush to complete).
   */
  public void asynchForceFlush() {
    getDiskStore().asynchForceFlush();
  }

  public void forceFlush() {
    getDiskStore().forceFlush();
  }

  /**
   * The diskStats are at PR level.Hence if the region is a bucket region, the
   * stats should not be closed, but the figures of entriesInVM and
   * overflowToDisk contributed by that bucket need to be removed from the stats .
   */
  private void statsClose(LocalRegion region) {
    if (region instanceof BucketRegion) {
//       region.getGemFireCache().getLogger().info("DEBUG statsClose br= " + region.getFullPath()
//                                                + " inVm=" + owner.getNumEntriesInVM()
//                                                + " onDisk=" + owner.getNumOverflowOnDisk());
      statsClear(region);
    } else {
//       region.getGemFireCache().getLogger().info("DEBUG statsClose r=" + region.getFullPath());
      this.stats.close();
    }
  }

  void statsClear(LocalRegion region) {
    if (region instanceof BucketRegion) {
      BucketRegion owner=(BucketRegion)region;
      long curInVM = owner.getNumEntriesInVM()*-1;
      long curOnDisk = owner.getNumOverflowOnDisk()*-1;
      long curBytesOnDisk = owner.getNumOverflowBytesOnDisk()*-1;
      incNumEntriesInVM(curInVM);
      incNumOverflowOnDisk(curOnDisk);
      incNumOverflowBytesOnDisk(curBytesOnDisk);
      owner.incNumEntriesInVM(curInVM);
      owner.incNumOverflowOnDisk(curOnDisk);
      owner.incNumOverflowBytesOnDisk(curBytesOnDisk);
    } else {
      // set them both to zero
      incNumEntriesInVM(getNumEntriesInVM()*-1);
      incNumOverflowOnDisk(getNumOverflowOnDisk()*-1);
      incNumOverflowBytesOnDisk(getNumOverflowBytesOnDisk()*-1);
    }
  }

  /**
   * Returns true if the state of the specified entry was recovered from disk.
   * If so it will also set it to no longer be recovered.
   * @since prPersistSprint1
   */
  public boolean testIsRecovered(RegionEntry re, boolean clear) {
    DiskEntry de = (DiskEntry)re;
    return testIsRecovered(de.getDiskId(), clear);
  }

  public boolean testIsRecovered(DiskId id, boolean clear) {
    if (!isReadyForRecovery())
      return false;
    if (id == null)
      return false;
    synchronized (id) {
      byte bits = id.getUserBits();
      if (EntryBits.isRecoveredFromDisk(bits)) {
        if (clear) {
          bits = EntryBits.setRecoveredFromDisk(bits, false);
          id.setUserBits(bits);
        }
        return true;
      }
    }
    return false;
  }

  /**
   * returns the active child
   */
  final Oplog testHook_getChild() {
    return getDiskStore().persistentOplogs.getChild();
  }
  
  /** For Testing * */
//   void addToOplogSet(long oplogID, File opFile, DirectoryHolder dirHolder) {
//     getDiskStore().addToOplogSet(oplogID, opFile, dirHolder);
//   }

//   /** For Testing * */
//   void setIsRecovering(boolean isRecovering) {
//     this.isRecovering = isRecovering;
//   }

  public void flushForTesting() {
    getDiskStore().flushForTesting();
  }
  public void pauseFlusherForTesting() {
    getDiskStore().pauseFlusherForTesting();
  }

  public boolean isSync() {
    return this.isSync;
  }

  /**
   * Stops the compactor without taking a write lock. Then it invokes appropriate
   * methods of super & current class to clear the Oplogs & once done restarts
   * the compactor.
   */
  void clear(LocalRegion region, RegionVersionVector rvv) {
    getDiskStore().clear(region, this, rvv);
  }
  
  /**
   * stops the compactor outside the write lock. Once stopped then it proceeds to
   * close the current * old oplogs
   */
  void close(LocalRegion region) {
    try {
      getDiskStore().close(region, this, false);
    } finally {
      statsClose(region);
    }
  }
  
  /**
   * stops the compactor outside the write lock. Once stopped then it proceeds to
   * close the current * old oplogs
   */
  void close(LocalRegion region, boolean closeDataOnly) {
    try {
      getDiskStore().close(region, this, closeDataOnly);
    } finally {
      statsClose(region);
    }
  }

  @Override
  void beginDestroyRegion(LocalRegion region) {
    try {
      getDiskStore().beginDestroyRegion(region, this);
    } finally {
      statsClose(region);
    }
  }

  private final AtomicInteger clearCount = new AtomicInteger();

  /** ThreadLocal to be used for maintaining consistency during clear* */
  private final ThreadLocal<Integer> childReference = new ThreadLocal<Integer>();

  final void incClearCount() {
    this.clearCount.incrementAndGet();
  }

  public boolean didClearCountChange() {
    Integer i = childReference.get();
    boolean result = i != null && i.intValue() != this.clearCount.get();
//     // now that we get a readLock it should not be possible for the lock to change
//     assert !result;
    return result;
  }
  
  void removeClearCountReference() {
//     releaseReadLock();
    childReference.remove();
  }

  void setClearCountReference() {
//     acquireReadLock();
    if (LocalRegion.ISSUE_CALLBACKS_TO_CACHE_OBSERVER) {
      CacheObserverHolder.getInstance().beforeSettingDiskRef();
      childReference.set(Integer.valueOf(this.clearCount.get()));
      CacheObserverHolder.getInstance().afterSettingDiskRef();
    }else{
      childReference.set(Integer.valueOf(this.clearCount.get()));
    }
  }
  /**
   * Note that this is no longer implemented by getting a write lock
   * but instead locks the same lock that acquireReadLock does.
   */
  void acquireWriteLock() {
    this.rwLock.writeLock().lock();
    // basicAcquireLock();
  }
  /**
   * Note that this is no longer implemented by getting a read lock
   * but instead locks the same lock that acquireWriteLock does.
   */
  void releaseWriteLock() {
    this.rwLock.writeLock().unlock();
    // this.lock.unlock();
  }
  public void acquireReadLock() {
    getDiskStore().acquireReadLock(this);
  }
  public void releaseReadLock() {
    getDiskStore().releaseReadLock(this);
  }
  void basicAcquireReadLock() {
    this.rwLock.readLock().lock();
    // basicAcquireLock();
  }
  void basicReleaseReadLock() {
    this.rwLock.readLock().unlock();
    // basicReleaseLock();
  }
/*
  private void basicAcquireLock() {
    this.lock.lock();
  }
  private void basicReleaseLock() {
    // It is assumed that releasing the lock will not throw any
    // ShutdownException
    this.lock.unlock();
  }
*/

  boolean isCompactionPossible() {
    return getDiskStore().isCompactionPossible();
  }
  void cleanupFailedInitialization(LocalRegion region) {
    if (isRecreated() 
        && !this.wasAboutToDestroy()
        && !this.wasAboutToDestroyDataStorage()) {
      close(region, isBucket());
    } else {
      if(this.isBucket() && !this.wasAboutToDestroy()) {
        //Fix for 48642
        //If this is a bucket, only destroy the data, if required.
        beginDestroyDataStorage();
      }
      endDestroy(region);
    }  
  }
  void prepareForClose(LocalRegion region) {
    getDiskStore().prepareForClose(region, this);
  }
  public boolean isRegionClosed() {
    return this.isRegionClosed;
  }
  void setRegionClosed(boolean v) {
    this.isRegionClosed = v;
  }
  
  // test hook
  public void forceIFCompaction() {
    getDiskStore().forceIFCompaction();
  }

  // unit test access
  
  void addToBeCompacted(Oplog oplog) {
    getOplogSet().addToBeCompacted(oplog);
  }
  CompactableOplog[] getOplogToBeCompacted() {
    return getDiskStore().getOplogToBeCompacted();
  }
  Oplog removeOplog(long id) {
    return getOplogSet().removeOplog(id);
  }
  DirectoryHolder getNextDir() {
    return getOplogSet().getNextDir();
  }
  final long newOplogEntryId() {
    return getOplogSet().newOplogEntryId();
  }
  void setChild(Oplog oplog) {
    getOplogSet().setChild(oplog);
  }
  DirectoryHolder getInfoFileDir() {
    return getDiskStore().getInfoFileDir();
  }
  public DirectoryHolder[] getDirectories() {
    return getDiskStore().directories;
  }
  Map<Long, Oplog> getOplogIdToOplog() {
    return getOplogSet().oplogIdToOplog;
  }
  void testHookCloseAllOverflowChannels() {
    getDiskStore().testHookCloseAllOverflowChannels();
  }
  void testHookCloseAllOverflowOplogs() {
    getDiskStore().testHookCloseAllOverflowOplogs();
  }
  /**
   * Only called on overflow-only regions.
   * Needs to take every entry currently using disk storage and free up that storage
   */
  void freeAllEntriesOnDisk(final LocalRegion region) {
    if(region == null) {
      return;
    }
    region.foreachRegionEntry(new RegionEntryCallback() {
        public void handleRegionEntry(RegionEntry re) {
          DiskEntry de = (DiskEntry)re;
          DiskId id = de.getDiskId();
          if (id != null) {
            synchronized (id) {
              // GemFireXD: give a chance to copy key from value bytes when key
              // is just a pointer to value row
              re.setValueToNull(region); // TODO why call _setValue twice in a row?
              re.removePhase2(region);
              id.unmarkForWriting();
              if (EntryBits.isNeedsValue(id.getUserBits())) {
                long oplogId = id.getOplogId();
                long offset = id.getOffsetInOplog();
                //int length = id.getValueLength();
                if (oplogId != -1 && offset != -1) {
                  id.setOplogId(-1);
                  OverflowOplog oplog = getDiskStore().overflowOplogs.getChild((int)oplogId);
                  if (oplog != null) {
                    oplog.freeEntry(de);
                  }
                }
              }
            }
          }
        }
      });
  }
  
  public void finishPendingDestroy() {
    boolean wasFullDestroy = wasAboutToDestroy();
    super.endDestroy(null);
    if(wasFullDestroy) {
      // now do some recreate work
      setRegionClosed(false);
      register();
    }
  }

  public DiskStoreID getDiskStoreID() {
    return getDiskStore().getDiskStoreID();
  }

  public void waitForAsyncRecovery() {
    getDiskStore().waitForAsyncRecovery(this);
    
  }

  public void endRead(long start, long end, long bytesRead) {
    getStats().endRead(start, end, bytesRead);
  }
  /**
   * Record that we have done tombstone garbage collection to disk.
   * On recovery or compaction, we will discard tombstones less than
   * the GC RVV. 
   */
  public void writeRVVGC(LocalRegion region) {
    getDiskStore().writeRVVGC(this, region);
    
  }
  
  /**
   * Record current RVV to disk and update into disk region RVV. 
   */
  public void writeRVV(LocalRegion region, Boolean isRVVTrusted) {
    getDiskStore().writeRVV(this, region, isRVVTrusted);
  }
  
  public void replaceIncompatibleEntry(DiskEntry old, DiskEntry repl) {
    acquireReadLock();
    try { 
      getOplogSet().getChild().replaceIncompatibleEntry(this, old, repl);
    } finally {
      releaseReadLock();
    }
  }
}
