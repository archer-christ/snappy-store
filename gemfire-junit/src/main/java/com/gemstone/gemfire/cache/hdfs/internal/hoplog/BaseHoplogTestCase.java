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
package com.gemstone.gemfire.cache.hdfs.internal.hoplog;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map.Entry;
import java.util.Random;
import java.util.TreeMap;

import com.gemstone.gemfire.cache.*;
import com.gemstone.gemfire.cache.hdfs.HDFSStore;
import com.gemstone.gemfire.cache.hdfs.HDFSStoreFactory;
import com.gemstone.gemfire.cache.hdfs.HDFSStoreFactory.HDFSCompactionConfigFactory;
import com.gemstone.gemfire.cache.hdfs.HDFSStoreMutator;
import com.gemstone.gemfire.cache.hdfs.internal.HDFSStoreImpl;
import com.gemstone.gemfire.cache.hdfs.internal.SortedHDFSQueuePersistedEvent;
import com.gemstone.gemfire.cache.hdfs.internal.hoplog.HDFSRegionDirector.HdfsRegionManager;
import com.gemstone.gemfire.cache.hdfs.internal.hoplog.Hoplog.HoplogWriter;
import com.gemstone.gemfire.cache.hdfs.internal.hoplog.HoplogOrganizer.Compactor;
import com.gemstone.gemfire.distributed.DistributedMember;
import com.gemstone.gemfire.internal.cache.LocalRegion;
import com.gemstone.gemfire.internal.cache.TestUtils;
import com.gemstone.gemfire.internal.cache.persistence.soplog.HFileStoreStatistics;
import com.gemstone.gemfire.internal.cache.persistence.soplog.SortedOplogStatistics;
import com.gemstone.gemfire.internal.cache.versions.DiskVersionTag;
import com.gemstone.gemfire.internal.util.BlobHelper;
import junit.framework.TestCase;
import org.apache.commons.io.FileUtils;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.fs.PathFilter;
import org.apache.hadoop.hbase.io.hfile.BlockCache;
import org.apache.hadoop.hdfs.DFSConfigKeys;
import org.apache.hadoop.hdfs.HdfsConfiguration;
import org.apache.hadoop.hdfs.MiniDFSCluster;
import org.apache.hadoop.hdfs.MiniDFSCluster.Builder;

public abstract class BaseHoplogTestCase extends TestCase {
  public static final String HDFS_STORE_NAME = "hdfs";
  public static final Random rand = new Random(System.currentTimeMillis());
  protected Path testDataDir;
  protected Cache cache;
  
  protected HDFSRegionDirector director; 
  protected HdfsRegionManager regionManager;
  protected HDFSStoreFactory hsf;
  protected HDFSStoreImpl hdfsStore;
  protected RegionFactory<Object, Object> regionfactory;
  protected Region<Object, Object> region;
  protected SortedOplogStatistics stats;
  protected HFileStoreStatistics storeStats;
  protected BlockCache blockCache;

  @Override
  protected void setUp() throws Exception {
    super.setUp();
    System.setProperty(HDFSStoreImpl.ALLOW_STANDALONE_HDFS_FILESYSTEM_PROP, "true");
    
    //This is logged by HDFS when it is stopped.
    TestUtils.addExpectedException("sleep interrupted");
    TestUtils.addExpectedException("java.io.InterruptedIOException");
    
    testDataDir = new Path("test-case");

    cache = createCache();
    
    configureHdfsStoreFactory();
    hdfsStore = (HDFSStoreImpl) hsf.create(HDFS_STORE_NAME);

    regionfactory = cache.createRegionFactory(RegionShortcut.PARTITION_HDFS);
    regionfactory.setHDFSStoreName(HDFS_STORE_NAME);
    region = regionfactory.create(getName());
    
    // disable compaction by default and clear existing queues
    HDFSCompactionManager compactionManager = HDFSCompactionManager.getInstance(hdfsStore);
    compactionManager.reset();
    
    director = HDFSRegionDirector.getInstance();
    director.setCache(cache);
    regionManager = ((LocalRegion)region).getHdfsRegionManager();
    stats = director.getHdfsRegionStats("/" + getName());
    storeStats = hdfsStore.getStats();
    blockCache = hdfsStore.getBlockCache();
    AbstractHoplogOrganizer.JUNIT_TEST_RUN = true;
  }

  protected void configureHdfsStoreFactory() throws Exception {
    hsf = this.cache.createHDFSStoreFactory();
    hsf.setHomeDir(testDataDir.toString());
    HDFSCompactionConfigFactory cc = hsf.createCompactionConfigFactory(null);
    cc.setAutoCompaction(false);
    cc.setAutoMajorCompaction(false);
    hsf.setHDFSCompactionConfig(cc.create());
  }

  protected Cache createCache() {
    CacheFactory cf = new CacheFactory().set("mcast-port", "0")
        .set("log-level", "info")
        ;
    cache = cf.create();
    return cache;
  }

  @Override
  protected void tearDown() throws Exception {
    if (region != null) {
      region.destroyRegion();
    }
    
    if (hdfsStore != null) {
      hdfsStore.getFileSystem().delete(testDataDir, true);
      hdfsStore.destroy();
    }
    
    if (cache != null) {
      cache.close();
    }
    super.tearDown();
    TestUtils.removeExpectedException("sleep interrupted");
    TestUtils.removeExpectedException("java.io.InterruptedIOException");
  }

  /**
   * creates a hoplog file with numKeys records. Keys follow key-X pattern and values follow value-X
   * pattern where X=0 to X is = numKeys -1
   * 
   * @return the sorted map of inserted KVs
   */
  protected TreeMap<String, String> createHoplog(int numKeys, Hoplog oplog) throws IOException {
    int offset = (numKeys > 10 ? 100000 : 0);
    
    HoplogWriter writer = oplog.createWriter(numKeys);
    TreeMap<String, String> map = new TreeMap<String, String>();
    for (int i = offset; i < (numKeys + offset); i++) {
      String key = ("key-" + i);
      String value = ("value-" + System.nanoTime());
      writer.append(key.getBytes(), value.getBytes());
      map.put(key, value);
    }
    writer.close();
    return map;
  }
  
  protected FileStatus[] getBucketHoplogs(String regionAndBucket, final String type)
      throws IOException {
    return getBucketHoplogs(hdfsStore.getFileSystem(), regionAndBucket, type);
  }
  
  protected FileStatus[] getBucketHoplogs(FileSystem fs, String regionAndBucket, final String type)
      throws IOException {
    FileStatus[] hoplogs = fs.listStatus(
        new Path(testDataDir, regionAndBucket), new PathFilter() {
          @Override
          public boolean accept(Path file) {
            return file.getName().endsWith(type);
          }
        });
    return hoplogs;
  }

  protected String getRandomHoplogName() {
    String hoplogName = "hoplog-" + System.nanoTime() + "-" + rand.nextInt(10000) + ".hop";
    return hoplogName;
  }
  
  public static MiniDFSCluster initMiniCluster(int port, int numDN) throws Exception {
    HashMap<String, String> map = new HashMap<String, String>();
    map.put(DFSConfigKeys.DFS_REPLICATION_KEY, "1");
    return initMiniCluster(port, numDN, map);
  }

  public static MiniDFSCluster initMiniCluster(int port, int numDN, HashMap<String, String> map) throws Exception {
    System.setProperty("test.build.data", "hdfs-test-cluster");
    Configuration hconf = new HdfsConfiguration();
    for (Entry<String, String> entry : map.entrySet()) {
      hconf.set(entry.getKey(), entry.getValue());
    }

    hconf.set("dfs.namenode.fs-limits.min-block-size", "1024");
    
    Builder builder = new MiniDFSCluster.Builder(hconf);
    builder.numDataNodes(numDN);
    builder.nameNodePort(port);
    MiniDFSCluster cluster = builder.build();
    return cluster;
  }

  public static void setConfigFile(HDFSStoreFactory factory, File configFile, String config)
      throws Exception {
    BufferedWriter bw = new BufferedWriter(new FileWriter(configFile));
    bw.write(config);
    bw.close();
    factory.setHDFSClientConfigFile(configFile.getName());
  }
  
  public static void alterMajorCompaction(HDFSStoreImpl store, boolean enable) {
    HDFSStoreMutator mutator = store.createHdfsStoreMutator();
    mutator.getCompactionConfigMutator().setAutoMajorCompaction(enable);
    store.alter(mutator);
  }
  
  public static void alterMinorCompaction(HDFSStoreImpl store, boolean enable) {
    HDFSStoreMutator mutator = store.createHdfsStoreMutator();
    mutator.getCompactionConfigMutator().setAutoCompaction(enable);
    store.alter(mutator);
  }
  
  public void deleteMiniClusterDir() throws Exception {
    File clusterDir = new File("hdfs-test-cluster");
    if (clusterDir.exists()) {
      FileUtils.deleteDirectory(clusterDir);
    }
  }
  
  public static class TestEvent extends SortedHDFSQueuePersistedEvent {
    Object key;
    
    public TestEvent(String k, String v) throws Exception {
      this(k, v, Operation.PUT_IF_ABSENT);
    }

    public TestEvent(String k, String v, Operation op) throws Exception {
      super(v, op, (byte) 0x02, false, new DiskVersionTag(), BlobHelper.serializeToBlob(k), 0);
      this.key = k; 
    }

    public Object getKey() {
      return key;
      
    }

    public Object getNewValue() {
      return valueObject;
    }

    public Operation getOperation() {
      return op;
    }
    
    public Region<Object, Object> getRegion() {
      return null;
    }

    public Object getCallbackArgument() {
      return null;
    }

    public boolean isCallbackArgumentAvailable() {
      return false;
    }

    public boolean isOriginRemote() {
      return false;
    }

    public DistributedMember getDistributedMember() {
      return null;
    }

    public boolean isExpiration() {
      return false;
    }

    public boolean isDistributed() {
      return false;
    }

    public Object getOldValue() {
      return null;
    }

    public SerializedCacheValue<Object> getSerializedOldValue() {
      return null;
    }

    public SerializedCacheValue<Object> getSerializedNewValue() {
      return null;
    }

    public boolean isLocalLoad() {
      return false;
    }

    public boolean isNetLoad() {
      return false;
    }

    public boolean isLoad() {
      return false;
    }

    public boolean isNetSearch() {
      return false;
    }

    public TransactionId getTransactionId() {
      return null;
    }

    public boolean isBridgeEvent() {
      return false;
    }

    public boolean hasClientOrigin() {
      return false;
    }

    public boolean isOldValueAvailable() {
      return false;
    }
  }
  
  public abstract class AbstractCompactor implements Compactor {
    @Override
    public HDFSStore getHdfsStore() {
      return hdfsStore;
    }

    public void suspend() {
    }

    public void resume() {
    }

    public boolean isBusy(boolean isMajor) {
      return false;
    }
  }
}
