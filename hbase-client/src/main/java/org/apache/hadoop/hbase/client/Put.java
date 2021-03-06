/*
 *
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

package org.apache.hadoop.hbase.client;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;
import java.util.UUID;
import org.apache.hadoop.hbase.Cell;
import org.apache.hadoop.hbase.CellUtil;
import org.apache.hadoop.hbase.HConstants;
import org.apache.hadoop.hbase.IndividualBytesFieldCell;
import org.apache.hadoop.hbase.KeyValue;
import org.apache.hadoop.hbase.io.HeapSize;
import org.apache.hadoop.hbase.security.access.Permission;
import org.apache.hadoop.hbase.security.visibility.CellVisibility;
import org.apache.hadoop.hbase.util.Bytes;
import org.apache.yetus.audience.InterfaceAudience;

/**
 * Used to perform Put operations for a single row.
 * <p>
 * To perform a Put, instantiate a Put object with the row to insert to, and
 * for each column to be inserted, execute {@link #addColumn(byte[], byte[],
 * byte[]) add} or {@link #addColumn(byte[], byte[], long, byte[]) add} if
 * setting the timestamp.
 */
@InterfaceAudience.Public
public class Put extends Mutation implements HeapSize, Comparable<Row> {
  /**
   * Create a Put operation for the specified row.
   * @param row row key
   */
  public Put(byte [] row) {
    this(row, HConstants.LATEST_TIMESTAMP);
  }

  /**
   * Create a Put operation for the specified row, using a given timestamp.
   *
   * @param row row key; we make a copy of what we are passed to keep local.
   * @param ts timestamp
   */
  public Put(byte[] row, long ts) {
    this(row, 0, row.length, ts);
  }

  /**
   * We make a copy of the passed in row key to keep local.
   * @param rowArray
   * @param rowOffset
   * @param rowLength
   */
  public Put(byte [] rowArray, int rowOffset, int rowLength) {
    this(rowArray, rowOffset, rowLength, HConstants.LATEST_TIMESTAMP);
  }

  /**
   * @param row row key; we make a copy of what we are passed to keep local.
   * @param ts  timestamp
   */
  public Put(ByteBuffer row, long ts) {
    if (ts < 0) {
      throw new IllegalArgumentException("Timestamp cannot be negative. ts=" + ts);
    }
    checkRow(row);
    this.row = new byte[row.remaining()];
    row.get(this.row);
    this.ts = ts;
  }

  /**
   * @param row row key; we make a copy of what we are passed to keep local.
   */
  public Put(ByteBuffer row) {
    this(row, HConstants.LATEST_TIMESTAMP);
  }

  /**
   * We make a copy of the passed in row key to keep local.
   * @param rowArray
   * @param rowOffset
   * @param rowLength
   * @param ts
   */
  public Put(byte [] rowArray, int rowOffset, int rowLength, long ts) {
    checkRow(rowArray, rowOffset, rowLength);
    this.row = Bytes.copy(rowArray, rowOffset, rowLength);
    this.ts = ts;
    if (ts < 0) {
      throw new IllegalArgumentException("Timestamp cannot be negative. ts=" + ts);
    }
  }

  /**
   * Create a Put operation for an immutable row key.
   *
   * @param row row key
   * @param rowIsImmutable whether the input row is immutable.
   *                       Set to true if the caller can guarantee that
   *                       the row will not be changed for the Put duration.
   */
  public Put(byte [] row, boolean rowIsImmutable) {
    this(row, HConstants.LATEST_TIMESTAMP, rowIsImmutable);
  }

  /**
   * Create a Put operation for an immutable row key, using a given timestamp.
   *
   * @param row row key
   * @param ts timestamp
   * @param rowIsImmutable whether the input row is immutable.
   *                       Set to true if the caller can guarantee that
   *                       the row will not be changed for the Put duration.
   */
  public Put(byte[] row, long ts, boolean rowIsImmutable) {
    // Check and set timestamp
    if (ts < 0) {
      throw new IllegalArgumentException("Timestamp cannot be negative. ts=" + ts);
    }
    this.ts = ts;

    // Deal with row according to rowIsImmutable
    checkRow(row);
    if (rowIsImmutable) {  // Row is immutable
      this.row = row;  // Do not make a local copy, but point to the provided byte array directly
    } else {  // Row is not immutable
      this.row = Bytes.copy(row, 0, row.length);  // Make a local copy
    }
  }

  /**
   * Copy constructor.  Creates a Put operation cloned from the specified Put.
   * @param putToCopy put to copy
   */
  public Put(Put putToCopy) {
    this(putToCopy.getRow(), putToCopy.ts);
    this.familyMap = new TreeMap<>(Bytes.BYTES_COMPARATOR);
    for(Map.Entry<byte [], List<Cell>> entry: putToCopy.getFamilyCellMap().entrySet()) {
      this.familyMap.put(entry.getKey(), new ArrayList<>(entry.getValue()));
    }
    this.durability = putToCopy.durability;
    for (Map.Entry<String, byte[]> entry : putToCopy.getAttributesMap().entrySet()) {
      this.setAttribute(entry.getKey(), entry.getValue());
    }
  }

  /**
   * Add the specified column and value to this Put operation.
   * @param family family name
   * @param qualifier column qualifier
   * @param value column value
   * @return this
   */
  public Put addColumn(byte [] family, byte [] qualifier, byte [] value) {
    return addColumn(family, qualifier, this.ts, value);
  }

  /**
   * See {@link #addColumn(byte[], byte[], byte[])}. This version expects
   * that the underlying arrays won't change. It's intended
   * for usage internal HBase to and for advanced client applications.
   * @deprecated As of release 2.0.0, this will be removed in HBase 3.0.0.
   *             Use {@link #add(Cell)} and {@link org.apache.hadoop.hbase.CellBuilder} instead
   */
  @Deprecated
  public Put addImmutable(byte [] family, byte [] qualifier, byte [] value) {
    return addImmutable(family, qualifier, this.ts, value);
  }

  /**
   * Add the specified column and value, with the specified timestamp as
   * its version to this Put operation.
   * @param family family name
   * @param qualifier column qualifier
   * @param ts version timestamp
   * @param value column value
   * @return this
   */
  public Put addColumn(byte [] family, byte [] qualifier, long ts, byte [] value) {
    if (ts < 0) {
      throw new IllegalArgumentException("Timestamp cannot be negative. ts=" + ts);
    }
    List<Cell> list = getCellList(family);
    KeyValue kv = createPutKeyValue(family, qualifier, ts, value);
    list.add(kv);
    return this;
  }

  /**
   * See {@link #addColumn(byte[], byte[], long, byte[])}. This version expects
   * that the underlying arrays won't change. It's intended
   * for usage internal HBase to and for advanced client applications.
   * @deprecated As of release 2.0.0, this will be removed in HBase 3.0.0.
   *             Use {@link #add(Cell)} and {@link org.apache.hadoop.hbase.CellBuilder} instead
   */
  @Deprecated
  public Put addImmutable(byte [] family, byte [] qualifier, long ts, byte [] value) {
    // Family can not be null, otherwise NullPointerException is thrown when putting the cell into familyMap
    if (family == null) {
      throw new IllegalArgumentException("Family cannot be null");
    }

    // Check timestamp
    if (ts < 0) {
      throw new IllegalArgumentException("Timestamp cannot be negative. ts=" + ts);
    }

    List<Cell> list = getCellList(family);
    list.add(new IndividualBytesFieldCell(this.row, family, qualifier, ts, KeyValue.Type.Put, value));
    return this;
  }

  /**
   * Add the specified column and value, with the specified timestamp as
   * its version to this Put operation.
   * @param family family name
   * @param qualifier column qualifier
   * @param ts version timestamp
   * @param value column value
   * @return this
   */
  public Put addColumn(byte[] family, ByteBuffer qualifier, long ts, ByteBuffer value) {
    if (ts < 0) {
      throw new IllegalArgumentException("Timestamp cannot be negative. ts=" + ts);
    }
    List<Cell> list = getCellList(family);
    KeyValue kv = createPutKeyValue(family, qualifier, ts, value, null);
    list.add(kv);
    return this;
  }

  /**
   * See {@link #addColumn(byte[], ByteBuffer, long, ByteBuffer)}. This version expects
   * that the underlying arrays won't change. It's intended
   * for usage internal HBase to and for advanced client applications.
   * @deprecated As of release 2.0.0, this will be removed in HBase 3.0.0.
   *             Use {@link #add(Cell)} and {@link org.apache.hadoop.hbase.CellBuilder} instead
   */
  @Deprecated
  public Put addImmutable(byte[] family, ByteBuffer qualifier, long ts, ByteBuffer value) {
    if (ts < 0) {
      throw new IllegalArgumentException("Timestamp cannot be negative. ts=" + ts);
    }
    List<Cell> list = getCellList(family);
    KeyValue kv = createPutKeyValue(family, qualifier, ts, value, null);
    list.add(kv);
    return this;
  }

  /**
   * Add the specified KeyValue to this Put operation.  Operation assumes that
   * the passed KeyValue is immutable and its backing array will not be modified
   * for the duration of this Put.
   * @param kv individual KeyValue
   * @return this
   * @throws java.io.IOException e
   */
  public Put add(Cell kv) throws IOException {
    // Family can not be null, otherwise NullPointerException is thrown when putting
    // the cell into familyMap
    if (kv.getFamilyArray() == null) {
      throw new IllegalArgumentException("Family cannot be null");
    }

    // Check timestamp
    if (ts < 0) {
      throw new IllegalArgumentException("Timestamp cannot be negative. ts=" + ts);
    }

    byte [] family = CellUtil.cloneFamily(kv);
    List<Cell> list = getCellList(family);
    //Checking that the row of the kv is the same as the put
    if (!CellUtil.matchingRows(kv, this.row)) {
      throw new WrongRowIOException("The row in " + kv.toString() +
        " doesn't match the original one " +  Bytes.toStringBinary(this.row));
    }
    list.add(kv);
    return this;
  }

  @Override
  public Put setTimestamp(long timestamp) {
    super.setTimestamp(timestamp);
    return this;
  }

  @Override
  public Put setAttribute(String name, byte[] value) {
    return (Put) super.setAttribute(name, value);
  }

  @Override
  public Put setId(String id) {
    return (Put) super.setId(id);
  }

  @Override
  public Put setDurability(Durability d) {
    return (Put) super.setDurability(d);
  }

  @Override
  public Put setFamilyCellMap(NavigableMap<byte[], List<Cell>> map) {
    return (Put) super.setFamilyCellMap(map);
  }

  @Override
  public Put setClusterIds(List<UUID> clusterIds) {
    return (Put) super.setClusterIds(clusterIds);
  }

  @Override
  public Put setCellVisibility(CellVisibility expression) {
    return (Put) super.setCellVisibility(expression);
  }

  @Override
  public Put setACL(String user, Permission perms) {
    return (Put) super.setACL(user, perms);
  }

  @Override
  public Put setACL(Map<String, Permission> perms) {
    return (Put) super.setACL(perms);
  }

  @Override
  public Put setTTL(long ttl) {
    return (Put) super.setTTL(ttl);
  }
}
