/*
 * Copyright (c) 2017 SnappyData, Inc. All rights reserved.
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

package com.pivotal.gemfirexd.internal.catalog;

import java.util.HashMap;
import java.util.List;

import com.gemstone.gemfire.internal.cache.ExternalTableMetaData;

/**
 * Need to keep GemXD independent of any snappy/spark/hive related
 * classes. An implementation of this can be made which adheres to this
 * interface and can be instantiated when the snappy embedded cluster
 * initializes and set into the GemFireStore instance.
 */
public interface ExternalCatalog {

  /**
   * Will be used by the execution engine to route to JobServer
   * when it finds out that this table is a column table.
   *
   * @return true if the table is column table, false if row/ref table
   */
  boolean isColumnTable(String schema, String tableName, boolean skipLocks);

  /**
   * Will be used by the execution engine to execute query in gemfirexd
   * if tablename is of a row table.
   *
   * @return true if the table is column table, false if row/ref table
   */
  boolean isRowTable(String schema, String tableName, boolean skipLocks);

  /**
   * Get the schema for a column table in Json format (as in Spark).
   */
  String getColumnTableSchemaAsJson(String schema, String tableName, boolean skipLocks);

  /**
   * Retruns a map of DBs to list of store tables(those tables that
   * are in store DD) in catalog
   */
  HashMap<String, List<String>> getAllStoreTablesInCatalog(boolean skipLocks);

  /**
   *  Removes a table from the external catalog
   */
  boolean removeTable(String schema, String table, boolean skipLocks);

  /**
   * Returns the schema in which this catalog is created
   * @return
   */
  public String catalogSchemaName();

  Object getTable(String schema, String tableName, boolean skipLocks);

  /**
   * Get the metadata for all external hive tables (including all their columns).
   */
  public List<ExternalTableMetaData> getHiveTables(boolean skipLocks);

  /**
   * Returns the meta data of the Hive Table
   */
  public ExternalTableMetaData getHiveTableMetaData(String schema, String tableName,
      boolean skipLocks);

  void stop();
}
