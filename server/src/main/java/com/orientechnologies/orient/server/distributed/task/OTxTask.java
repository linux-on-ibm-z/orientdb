/*
 *
 *  *  Copyright 2014 Orient Technologies LTD (info(at)orientechnologies.com)
 *  *
 *  *  Licensed under the Apache License, Version 2.0 (the "License");
 *  *  you may not use this file except in compliance with the License.
 *  *  You may obtain a copy of the License at
 *  *
 *  *       http://www.apache.org/licenses/LICENSE-2.0
 *  *
 *  *  Unless required by applicable law or agreed to in writing, software
 *  *  distributed under the License is distributed on an "AS IS" BASIS,
 *  *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  *  See the License for the specific language governing permissions and
 *  *  limitations under the License.
 *  *
 *  * For more information: http://www.orientechnologies.com
 *
 */
package com.orientechnologies.orient.server.distributed.task;

import com.orientechnologies.common.concur.ONeedRetryException;
import com.orientechnologies.common.log.OLogManager;
import com.orientechnologies.orient.core.command.OCommandDistributedReplicateRequest;
import com.orientechnologies.orient.core.config.OGlobalConfiguration;
import com.orientechnologies.orient.core.db.ODatabaseRecordThreadLocal;
import com.orientechnologies.orient.core.db.document.ODatabaseDocumentTx;
import com.orientechnologies.orient.core.db.record.OPlaceholder;
import com.orientechnologies.orient.core.db.record.ORecordLazyMultiValue;
import com.orientechnologies.orient.core.db.record.ORecordOperation;
import com.orientechnologies.orient.core.exception.ORecordNotFoundException;
import com.orientechnologies.orient.core.exception.OTransactionException;
import com.orientechnologies.orient.core.id.ORID;
import com.orientechnologies.orient.core.id.ORecordId;
import com.orientechnologies.orient.core.record.ORecord;
import com.orientechnologies.orient.core.record.impl.ODocument;
import com.orientechnologies.orient.core.storage.ORecordDuplicatedException;
import com.orientechnologies.orient.core.tx.OTransactionOptimistic;
import com.orientechnologies.orient.server.OServer;
import com.orientechnologies.orient.server.distributed.ODistributedDatabase;
import com.orientechnologies.orient.server.distributed.ODistributedRequest;
import com.orientechnologies.orient.server.distributed.ODistributedServerLog;
import com.orientechnologies.orient.server.distributed.ODistributedServerLog.DIRECTION;
import com.orientechnologies.orient.server.distributed.ODistributedServerManager;

import java.io.IOException;
import java.io.ObjectInput;
import java.io.ObjectOutput;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;

/**
 * Distributed transaction task.
 *
 * @author Luca Garulli (l.garulli--at--orientechnologies.com)
 *
 */
public class OTxTask extends OAbstractReplicatedTask {
  private static final long                   serialVersionUID = 1L;

  private List<OAbstractRecordReplicatedTask> tasks            = new ArrayList<OAbstractRecordReplicatedTask>();
  private transient OTxTaskResult             result;
  private transient boolean                   lockRecord       = true;

  public OTxTask() {
  }

  public void add(final OAbstractRecordReplicatedTask iTask) {
    iTask.setInTx(true);
    tasks.add(iTask);
  }

  @Override
  public Object execute(final OServer iServer, ODistributedServerManager iManager, final ODatabaseDocumentTx database)
      throws Exception {
    ODistributedServerLog.debug(this, iManager.getLocalNodeName(), getNodeSource(), DIRECTION.IN,
        "committing transaction against db=%s...", database.getName());

    ODatabaseRecordThreadLocal.INSTANCE.set(database);

    try {
      database.begin();
      final OTransactionOptimistic tx = (OTransactionOptimistic) database.getTransaction();

      result = new OTxTaskResult();

      final ODistributedDatabase ddb = iManager.getMessageService().getDatabase(database.getName());

      try {
        // REGISTER CREATE FIRST TO RESOLVE TEMP RIDS
        for (OAbstractRecordReplicatedTask task : tasks) {
          if (task instanceof OCreateRecordTask) {
            final OCreateRecordTask createRT = (OCreateRecordTask) task;
            final int clId = createRT.clusterId > -1 ? createRT.clusterId
                : createRT.getRid().isValid() ? createRT.getRid().getClusterId() : -1;
            final String clusterName = clId > -1 ? database.getClusterNameById(clId) : null;
            tx.addRecord(createRT.getRecord(), ORecordOperation.CREATED, clusterName);
          } else {
            // UPDATE & DELETE: TRY EARLY LOCKING RECORD
            final ORID rid = task.getRid();
            if (lockRecord && !ddb.lockRecord(rid, nodeSource))
              throw new ODistributedRecordLockedException(rid);

            result.locks.add(rid);
          }
        }

        for (OAbstractRecordReplicatedTask task : tasks) {
          final ORecord record = task.getRecord();

          if (record instanceof ODocument) {
            // ASSURE ALL RIDBAGS ARE UNMARSHALLED TO AVOID STORING TEMP RIDS
            for (String f : ((ODocument) record).fieldNames()) {
              final Object fValue = ((ODocument) record).field(f);
              if (fValue instanceof ORecordLazyMultiValue)
                // DESERIALIZE IT TO ASSURE TEMPORARY RIDS ARE TREATED CORRECTLY
                ((ORecordLazyMultiValue) fValue).convertLinks2Records();
              else if (fValue instanceof ORecordId)
                ((ODocument) record).field(f, ((ORecordId) fValue).getRecord());
            }
          }
        }

        for (OAbstractRecordReplicatedTask task : tasks) {
          final Object taskResult = task.execute(iServer, iManager, database);
          result.results.add(taskResult);
        }

        database.commit();

        for (int i = 0; i < result.results.size(); i++) {
          Object res = result.results.get(i);
          if (res instanceof OUpdateRecordTask.VersionPlaceholder) {
            result.results.set(i, ((OUpdateRecordTask.VersionPlaceholder) res).getVersion());
          }
        }

        // SEND BACK CHANGED VALUE TO UPDATE
        for (int i = 0; i < result.results.size(); ++i) {
          final Object o = result.results.get(i);

          final OAbstractRecordReplicatedTask task = tasks.get(i);
          if (task instanceof OCreateRecordTask) {
            // SEND RID + VERSION
            final OCreateRecordTask t = (OCreateRecordTask) task;
            result.results.set(i, new OPlaceholder(t.getRecord()));
          } else if (task instanceof OUpdateRecordTask) {
            // SEND VERSION
            if (((Integer) o) < 0) {
              result.results.set(i, task.getRid().getRecord().reload().getVersion());
            } else
              result.results.set(i, o);
          }
        }
      } catch (Exception t) {
        // RESET ANY ASSIGNED CLUSTER ID
        for (OAbstractRecordReplicatedTask task : tasks) {
          if (task instanceof OCreateRecordTask) {
            final OCreateRecordTask createRT = (OCreateRecordTask) task;
            createRT.resetRecord();
          }
        }

        // EXCEPTION: ASSURE ALL LOCKS ARE FREED
        for (ORID r : result.locks)
          ddb.unlockRecord(r);
        // RETHROW IT
        throw t;
      }

      return result;

    } catch (ONeedRetryException e) {
      database.rollback();
      return e;
    } catch (OTransactionException e) {
      database.rollback();
      return e;
    } catch (ORecordDuplicatedException e) {
      database.rollback();
      return e;
    } catch (ORecordNotFoundException e) {
      database.rollback();
      return e;
    } catch (Exception e) {
      database.rollback();
      OLogManager.instance().error(this, "Error on distributed transaction commit", e);
      return e;
    }
  }

  @Override
  public OCommandDistributedReplicateRequest.QUORUM_TYPE getQuorumType() {
    return OCommandDistributedReplicateRequest.QUORUM_TYPE.WRITE;
  }

  @Override
  public List<OAbstractRemoteTask> getFixTask(final ODistributedRequest iRequest, OAbstractRemoteTask iOriginalTask,
      final Object iBadResponse, final Object iGoodResponse, String executorNodeName, ODistributedServerManager dManager) {
    if (!(iBadResponse instanceof OTxTaskResult)) {
      // TODO: MANAGE ERROR ON LOCAL NODE
      ODistributedServerLog.debug(this, getNodeSource(), null, DIRECTION.NONE,
          "Error on creating fix-task for request: '%s' because bad response is not expected type: %s", iRequest, iBadResponse);
      return Collections.EMPTY_LIST;
    }

    if (!(iGoodResponse instanceof OTxTaskResult)) {
      // TODO: MANAGE ERROR ON LOCAL NODE
      ODistributedServerLog.debug(this, getNodeSource(), null, DIRECTION.NONE,
          "Error on creating fix-task for request: '%s' because good response is not expected type: %s", iRequest, iBadResponse);
      return Collections.EMPTY_LIST;
    }

    final List<OAbstractRemoteTask> fixTasks = new ArrayList<OAbstractRemoteTask>();

    final OFixTxTask fixTask = new OFixTxTask(((OTxTaskResult) iBadResponse).locks);
    fixTasks.add(fixTask);

    for (int i = 0; i < tasks.size(); ++i) {
      final OAbstractRecordReplicatedTask t = tasks.get(i);

      final Object badResult = ((OTxTaskResult) iBadResponse).results.get(i);
      final Object goodResult = ((OTxTaskResult) iGoodResponse).results.get(i);

      final List<OAbstractRemoteTask> tasks = t.getFixTask(iRequest, t, badResult, goodResult, executorNodeName, dManager);
      if (tasks != null)
        fixTask.addAll(tasks);
    }

    return fixTasks;
  }

  @Override
  public OAbstractRemoteTask getUndoTask(final ODistributedRequest iRequest, final Object iBadResponse) {
    if (result == null)
      // NO RESULT: NO UNDO NEEDED
      return null;

    return getUndoTaskForLocalStorage(iBadResponse);
  }

  public OAbstractRemoteTask getUndoTaskForLocalStorage(final Object iBadResponse) {
    final OFixTxTask fixTask = new OFixTxTask(result != null ? result.locks : new HashSet<ORID>());

    for (int i = 0; i < tasks.size(); ++i) {
      final OAbstractRecordReplicatedTask t = tasks.get(i);

      final OAbstractRemoteTask undoTask;
      if (iBadResponse instanceof List)
        undoTask = t.getUndoTask(null, ((List<Object>) iBadResponse).get(i));
      else
        undoTask = t.getUndoTask(null, iBadResponse);

      if (undoTask != null)
        fixTask.add(undoTask);
    }

    return fixTask;
  }

  public boolean isLockRecord() {
    return lockRecord;
  }

  public void setLockRecord(final boolean lockRecord) {
    this.lockRecord = lockRecord;
  }

  @Override
  public void writeExternal(final ObjectOutput out) throws IOException {
    out.writeInt(tasks.size());
    for (OAbstractRecordReplicatedTask task : tasks)
      out.writeObject(task);
  }

  @Override
  public void readExternal(final ObjectInput in) throws IOException, ClassNotFoundException {
    final int size = in.readInt();
    for (int i = 0; i < size; ++i)
      tasks.add((OAbstractRecordReplicatedTask) in.readObject());
  }

  /**
   * Computes the timeout according to the transaction size.
   * 
   * @return
   */
  @Override
  public long getDistributedTimeout() {
    final long to = OGlobalConfiguration.DISTRIBUTED_CRUD_TASK_SYNCH_TIMEOUT.getValueAsLong();
    return to + ((to / 2) * tasks.size());
  }

  @Override
  public String getName() {
    return "tx";
  }

  @Override
  public String getPayload() {
    return null;
  }

  public List<OAbstractRecordReplicatedTask> getTasks() {
    return tasks;
  }

  /**
   * Returns the ".insert" queue if there is at least 1 CREATE RECORD TASK INSIDE
   *
   * @param iOriginalQueueName
   * @return
   */
  // @Override
  // public String getQueueName(final String iOriginalQueueName) {
  // for (int i = 0; i < tasks.size(); ++i) {
  // final OAbstractRecordReplicatedTask t = tasks.get(i);
  // if (t instanceof OCreateRecordTask)
  // return t.getQueueName(iOriginalQueueName);
  // }
  //
  // return iOriginalQueueName;
  // }
}
