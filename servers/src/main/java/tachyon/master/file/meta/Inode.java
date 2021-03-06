/*
 * Licensed to the University of California, Berkeley under one or more contributor license
 * agreements. See the NOTICE file distributed with this work for additional information regarding
 * copyright ownership. The ASF licenses this file to You under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance with the License. You may obtain a
 * copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */

package tachyon.master.file.meta;

import tachyon.master.journal.JournalEntryRepresentable;
import tachyon.thrift.FileInfo;

/**
 * <code>Inode</code> is an abstract class, with information shared by all types of Inodes.
 */
public abstract class Inode implements JournalEntryRepresentable {
  public abstract static class Builder<T> {
    private long mCreationTimeMs;
    protected boolean mDirectory;
    protected long mId;
    private String mName;
    private long mParentId;
    private boolean mPersisted;

    public Builder() {
      mCreationTimeMs = System.currentTimeMillis();
      mDirectory = false;
      mId = 0;
      mName = null;
      mParentId = InodeTree.NO_PARENT;
    }

    public T setCreationTimeMs(long creationTimeMs) {
      mCreationTimeMs = creationTimeMs;
      return (T) this;
    }

    public T setId(long id) {
      mId = id;
      return (T) this;
    }

    public T setName(String name) {
      mName = name;
      return (T) this;
    }

    public T setParentId(long parentId) {
      mParentId = parentId;
      return (T) this;
    }

    public T setPersisted(boolean persisted) {
      mPersisted = persisted;
      return (T) this;
    }

    /**
     * Builds a new instance of {@link Inode}.
     *
     * @return a {@link Inode} instance
     */
    public abstract Inode build();
  }

  private final long mCreationTimeMs;
  protected final boolean mDirectory;

  private final long mId;
  private String mName;
  private long mParentId;

  /**
   * A pinned file is never evicted from memory. Folders are not pinned in memory; however, new
   * files and folders will inherit this flag from their parents.
   */
  private boolean mPinned = false;

  private boolean mPersisted = false;

  /**
   * The last modification time of this inode, in milliseconds.
   */
  private long mLastModificationTimeMs;

  /**
   * Indicates whether an inode is deleted or not.
   */
  private boolean mDeleted = false;

  protected Inode(Builder builder) {
    mCreationTimeMs = builder.mCreationTimeMs;
    mDirectory = builder.mDirectory;
    mLastModificationTimeMs = builder.mCreationTimeMs;
    mId = builder.mId;
    mName = builder.mName;
    mPersisted = builder.mPersisted;
    mParentId = builder.mParentId;
  }

  /**
   * Marks the inode as deleted
   */
  public synchronized void delete() {
    mDeleted = true;
  }

  @Override
  public synchronized boolean equals(Object o) {
    if (!(o instanceof Inode)) {
      return false;
    }
    return mId == ((Inode) o).mId;
  }

  /**
   * Generates a FileInfo of the file or folder.
   *
   * @param path The path of the file
   * @return generated FileInfo
   */
  public abstract FileInfo generateClientFileInfo(String path);

  /**
   * @return the create time, in milliseconds
   */
  public long getCreationTimeMs() {
    return mCreationTimeMs;
  }

  /**
   * @return the id of the inode
   */
  public synchronized long getId() {
    return mId;
  }

  /**
   * @return the last modification time, in milliseconds
   */
  public synchronized long getLastModificationTimeMs() {
    return mLastModificationTimeMs;
  }

  /**
   * @return the name of the inode
   */
  public synchronized String getName() {
    return mName;
  }

  /**
   * @return the id of the parent folder
   */
  public synchronized long getParentId() {
    return mParentId;
  }

  @Override
  public synchronized int hashCode() {
    return ((Long) mId).hashCode();
  }

  /**
   * @return true if the inode is deleted, false otherwise
   */
  public boolean isDeleted() {
    return mDeleted;
  }

  /**
   * @return true if the inode is a directory, false otherwise
   */
  public boolean isDirectory() {
    return mDirectory;
  }

  /**
   * @return true if the inode is a file, false otherwise
   */
  public boolean isFile() {
    return !mDirectory;
  }

  /**
   * @return true if the inode is pinned, false otherwise
   */
  public synchronized boolean isPinned() {
    return mPinned;
  }

  /**
   * @return true if the file has persisted, false otherwise
   */
  public synchronized boolean isPersisted() {
    return mPersisted;
  }

  /**
   * Restores a deleted inode.
   */
  public synchronized void restore() {
    mDeleted = false;
  }

  /**
   * Sets the last modification time of the inode
   *
   * @param lastModificationTimeMs The last modification time, in milliseconds
   */
  public synchronized void setLastModificationTimeMs(long lastModificationTimeMs) {
    mLastModificationTimeMs = lastModificationTimeMs;
  }

  /**
   * Sets the name of the inode
   *
   * @param name The new name of the inode
   */
  public synchronized void setName(String name) {
    mName = name;
  }

  /**
   * Sets the parent folder of the inode
   *
   * @param parentId The new parent
   */
  public synchronized void setParentId(long parentId) {
    mParentId = parentId;
  }

  /**
   * Sets the persisted flag for the file.
   *
   * @param persisted if true, the file is persisted
   */
  public synchronized void setPersisted(boolean persisted) {
    mPersisted = persisted;
  }

  /**
   * Sets the pinned flag of the inode
   *
   * @param pinned If true, the inode need pinned, and a pinned file is never evicted from memory
   */
  public synchronized void setPinned(boolean pinned) {
    mPinned = pinned;
  }

  @Override
  public synchronized String toString() {
    return new StringBuilder("Inode(").append("ID:").append(mId).append(", NAME:").append(mName)
        .append(", PARENT_ID:").append(mParentId).append(", CREATION_TIME_MS:")
        .append(mCreationTimeMs).append(", PINNED:").append(mPinned).append("DELETED:")
        .append(mDeleted).append(", LAST_MODIFICATION_TIME_MS:").append(mLastModificationTimeMs)
        .append(")").toString();
  }
}
