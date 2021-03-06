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

package tachyon.master;

import java.io.IOException;
import java.util.concurrent.ExecutorService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Preconditions;

import tachyon.Constants;
import tachyon.master.journal.Journal;
import tachyon.master.journal.JournalEntry;
import tachyon.master.journal.JournalInputStream;
import tachyon.master.journal.JournalOutputStream;
import tachyon.master.journal.JournalTailer;
import tachyon.master.journal.JournalTailerThread;
import tachyon.master.journal.JournalWriter;
import tachyon.master.journal.ReadWriteJournal;

/**
 * This is the base class for all masters, and contains common functionality. Common functionality
 * mostly consists of journal operations, like initialization, journal tailing when in standby mode,
 * or journal writing when the master is the leader.
 */
public abstract class MasterBase implements Master {
  private static final Logger LOG = LoggerFactory.getLogger(Constants.LOGGER_TYPE);

  /** The executor used for running maintenance threads for the master. */
  private final ExecutorService mExecutorService;

  /** A handler to the journal for this master. */
  private Journal mJournal;
  /** true if this master is in leader mode, and not standby mode. */
  private boolean mIsLeader = false;
  /** The thread that tails the journal when the master is in standby mode. */
  private JournalTailerThread mStandbyJournalTailer = null;
  /** The journal writer for when the master is the leader. */
  private JournalWriter mJournalWriter = null;

  protected MasterBase(Journal journal, ExecutorService executorService) {
    mJournal = Preconditions.checkNotNull(journal);
    mExecutorService = Preconditions.checkNotNull(executorService);
  }

  @Override
  public void processJournalCheckpoint(JournalInputStream inputStream) throws IOException {
    JournalEntry entry;
    try {
      while ((entry = inputStream.getNextEntry()) != null) {
        processJournalEntry(entry);
      }
    } finally {
      inputStream.close();
    }
  }

  @Override
  public void start(boolean isLeader) throws IOException {
    mIsLeader = isLeader;
    LOG.info(getServiceName() + ": Starting " + (mIsLeader ? "leader" : "standby") + " master.");
    if (mIsLeader) {
      Preconditions.checkState(mJournal instanceof ReadWriteJournal);
      mJournalWriter = ((ReadWriteJournal) mJournal).getNewWriter();

      /**
       * The sequence for dealing with the journal before starting as the leader:
       *
       * Phase 1. Mark all the logs as completed. Since this master is the leader, it is allowed to
       * write the journal, so it can mark the current log as completed. After this step, the
       * current log file will not exist, and all logs will be complete.
       *
       * Phase 2. Reconstruct the state from the journal. This uses the JournalTailer to process all
       * of the checkpoint and the complete log files. Since all logs are complete, after this step,
       * the master will reflect the state of all of the journal entries.
       *
       * Phase 3. Write out the checkpoint file. Since this master is completely up-to-date, it
       * writes out the checkpoint file. When the checkpoint file is closed, it will then delete the
       * complete log files.
       *
       * Since this method is called before the master RPC server starts serving, there is no
       * concurrent access to the master during these phases.
       */

      // Phase 1: Mark all logs as complete, including the current log. After this call, the current
      // log should not exist, and all the log files will be complete.
      mJournalWriter.completeAllLogs();

      // Phase 2: Replay all the state of the checkpoint and the completed log files.
      JournalTailer catchupTailer;
      if (mStandbyJournalTailer != null && mStandbyJournalTailer.getLatestJournalTailer() != null
          && mStandbyJournalTailer.getLatestJournalTailer().isValid()) {
        // This master was previously in standby mode, and processed some of the journal. Re-use the
        // same tailer (still valid) to continue processing any remaining journal entries.
        LOG.info(getServiceName()
            + ": finish processing remaining journal entries (standby -> master).");
        catchupTailer = mStandbyJournalTailer.getLatestJournalTailer();
        catchupTailer.processNextJournalLogFiles();
      } else {
        // This master has not successfully processed any of the journal, so create a fresh tailer
        // to process the entire journal.
        catchupTailer = new JournalTailer(this, mJournal);
        if (catchupTailer.checkpointExists()) {
          LOG.info(getServiceName() + ": process entire journal before becoming leader master.");
          catchupTailer.processJournalCheckpoint(true);
          catchupTailer.processNextJournalLogFiles();
        } else {
          LOG.info(getServiceName() + ": journal checkpoint does not exist, nothing to process.");
        }
      }
      long latestSequenceNumber = catchupTailer.getLatestSequenceNumber();

      // Phase 3: initialize the journal and write out the checkpoint file (the state of all
      // completed logs).
      JournalOutputStream checkpointStream =
          mJournalWriter.getCheckpointOutputStream(latestSequenceNumber);
      streamToJournalCheckpoint(checkpointStream);
      checkpointStream.close();
    } else {
      // This master is in standby mode. Start the journal tailer thread. Since the master is in
      // standby mode, its RPC server is NOT serving. Therefore, the only thread modifying the
      // master is this journal tailer thread (no concurrent access).
      mStandbyJournalTailer = new JournalTailerThread(this, mJournal);
      mStandbyJournalTailer.start();
    }
  }

  @Override
  public void stop() throws IOException {
    LOG.info(getServiceName() + ": Stopping " + (mIsLeader ? "leader" : "standby") + " master.");
    if (mIsLeader) {
      // Stop this leader master.
      if (mJournalWriter != null) {
        mJournalWriter.close();
        mJournalWriter = null;
      }
    } else {
      if (mStandbyJournalTailer != null) {
        // stop and wait for the journal tailer thread.
        mStandbyJournalTailer.shutdownAndJoin();
      }
    }
  }

  @Override
  public void upgradeToReadWriteJournal(ReadWriteJournal journal) {
    mJournal = Preconditions.checkNotNull(journal);
  }

  protected boolean isLeaderMode() {
    return mIsLeader;
  }

  protected boolean isStandbyMode() {
    return !mIsLeader;
  }

  /**
   * Writes a {@link JournalEntry} to the journal. Does NOT flush the journal.
   *
   * @param entry the {@link JournalEntry} to write to the journal
   */
  protected void writeJournalEntry(JournalEntry entry) {
    Preconditions.checkNotNull(mJournalWriter, "Cannot write entry: journal writer is null.");
    try {
      mJournalWriter.getEntryOutputStream().writeEntry(entry);
    } catch (IOException ioe) {
      throw new RuntimeException(ioe);
    }
  }

  /**
   * Flushes the journal.
   */
  protected void flushJournal() {
    Preconditions.checkNotNull(mJournalWriter, "Cannot write entry: journal writer is null.");
    try {
      mJournalWriter.getEntryOutputStream().flush();
    } catch (IOException ioe) {
      throw new RuntimeException(ioe);
    }
  }

  /**
   * @return the executor service for this master
   */
  protected ExecutorService getExecutorService() {
    return mExecutorService;
  }
}
