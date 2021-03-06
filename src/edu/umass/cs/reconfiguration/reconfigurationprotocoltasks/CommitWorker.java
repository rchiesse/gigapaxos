/* Copyright (c) 2015 University of Massachusetts
 * 
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 * 
 * Initial developer(s): V. Arun */
package edu.umass.cs.reconfiguration.reconfigurationprotocoltasks;

import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

import edu.umass.cs.reconfiguration.AbstractReplicaCoordinator;
import edu.umass.cs.reconfiguration.ReconfigurationConfig;
import edu.umass.cs.reconfiguration.Reconfigurator;
import edu.umass.cs.reconfiguration.RepliconfigurableReconfiguratorDB;
import edu.umass.cs.reconfiguration.interfaces.ReconfiguratorCallback;
import edu.umass.cs.reconfiguration.reconfigurationpackets.RCRecordRequest;
import edu.umass.cs.reconfiguration.reconfigurationpackets.StartEpoch;
import edu.umass.cs.utils.Config;
import edu.umass.cs.utils.Util;

/**
 * @author V. Arun
 * @param <NodeIDType>
 * 
 *            This class is not a protocol task, just a usual runnable worker.
 *            It is initiated at a reconfigurator in order to commit the
 *            coordinated RCRecordRequest packets. We need a task for this
 *            because simply invoking handleIncoming (that in turn calls paxos
 *            propose) does not suffice to ensure that the command will be
 *            eventually committed.
 */
public class CommitWorker<NodeIDType> implements Runnable {

	/** Map, not Set, only because otherwise we will get concurrent modification exception in the
	 * case of a single reconfigurator replica group.
	 */
	ConcurrentHashMap<RCRecordRequest<NodeIDType>,Boolean> pending = new ConcurrentHashMap<RCRecordRequest<NodeIDType>,Boolean>();
	Map<RCRecordRequest<NodeIDType>, Long> lastAttempts = new ConcurrentHashMap<RCRecordRequest<NodeIDType>, Long>();
	Set<RCRecordRequest<NodeIDType>> executed = new HashSet<RCRecordRequest<NodeIDType>>();
	ConcurrentHashMap<String, Long> nonDefaultRestartPeriods = new ConcurrentHashMap<String, Long>();

	public void run() {
		while (!closed) {
			this.coordinate();
			waitUntilNotified(RESTART_PERIOD);
		}
	}

	private boolean closed = false;

	/**
	 * 
	 */
	public void close() {
		closed = true;
	}

	/**
	 * @param request
	 * @return True if enqueued for coordination.
	 */
	public synchronized boolean enqueueForExecution(
			RCRecordRequest<NodeIDType> request) {
		boolean enqueued = false;
		if (!this.checkIfObviated(request)) {
			log.log(Level.FINEST, "{0} enqueueing request {1}", new Object[] {
					this, request.getSummary() });
			assert(request!=null);
			enqueued = this.pending.putIfAbsent(request, true)!=null;
		}
		log.log(Util.oneIn(10) ? Level.INFO : Level.FINE,
				"{0} pendingQSize = {1}; executedQSize = {2}\n {3}\n {4}",
				new Object[] { this, this.pending.size(), this.executed.size(),
						this.getSetSummary(this.pending.keySet()),
						this.getSetSummary(executed) });
		this.notify();
		return enqueued;
	}

	/**
	 * Should be used sparingly only for non-default restart periods, not for
	 * all names.
	 * 
	 * @param request
	 * @param restartPeriod
	 * @return True if enqueued for execution.
	 */
	public synchronized boolean enqueueForExecution(
			RCRecordRequest<NodeIDType> request, long restartPeriod) {
		this.nonDefaultRestartPeriods.put(request.getServiceName(),
				restartPeriod);
		return this.enqueueForExecution(request);
	}

	/**
	 * @param request
	 * @return True if a matching pending request was found.
	 */
	// need to implement equals() for RCRecordRequest
	public synchronized boolean executedCallback(
			RCRecordRequest<NodeIDType> request) {
		log.log(Level.FINEST, "{0} executed request {1}", new Object[] { this,
				request.getSummary(Level.FINEST) });
		// knock off exact match or lower in pending
		boolean equalRemovedFromPending = this.pending.remove(request)!=null;
		log.log(Level.FINEST, "{0} exact-matched and removed pending task {1}",
				new Object[] { this, request.getSummary(Level.FINEST) });
		this.lastAttempts.remove(request);
		this.knockOffLower(request, this.pending.keySet(), true);

		if (equalRemovedFromPending)
			return true;
		// else may need to enqueue possible early notify

		assert (!this.executed.contains(request)
		// merges can be successful multiple times and be early
		|| request.isReconfigurationMerge());
		this.addAssert(request, executed);

		// knock off lower and enqueue if necessary
		this.knockOffLower(request, this.executed, false);
		if (shouldEnqueueEarlyExecutedNotification(request))
			this.executed.add(request);
		return equalRemovedFromPending;
	}

	/**
	 * We process some failed (handled = false) execution notifications to solve
	 * the early notify issue with complete requests for service names, i.e.,
	 * the notify arrives before the pending request is enqueued but the pending
	 * request has been obviated anyway.
	 * 
	 * @param request
	 * @param handled
	 * @return Refer {@link #executedCallback(RCRecordRequest)}.
	 */
	public synchronized boolean executedCallback(
			RCRecordRequest<NodeIDType> request, boolean handled) {
		if (handled
		/* merges should be knocked off even if they fail because Reconfigurator
		 * will respond appropriately to failed merges by restarting the
		 * corresponding WaitAckStopEpoch sequence. */
		// || (this.pending.contains(request) &&
		// request.isReconfigurationMerge())
		)
			return this.executedCallback(request);
		else
		/* For service names, it is possible (but very unlikely) that an
		 * executed notification arrives before a pending enqueue by a secondary
		 * reconfigurator task. If so, if we don't enqueue the executed
		 * notification, the enqueued pending request may be attempted forever
		 * as another exact-match or higher executed notification will never
		 * arrive. So we check if we are the initiator for complete requests and
		 * invoke the callback even if handled is false. That still won't
		 * enqueue the executed notification if there is no corresponding
		 * pending request, but for service names, it is not possible for a node
		 * to issue a RECONFIGURATION_COMPLETE request too early (as it is
		 * issued only upon committing the intent and then receiving startEpoch
		 * acks), so handled=false can only mean obviation, so the executed
		 * notification can still be used to obviate a matching or lower pending
		 * request. */
		if (request.getInitiator().equals(this.coordinator.getMyID())
				&& (request.isReconfigurationComplete() || request
						.isReconfigurationPrevDropComplete())
				&& !this.isRCGroupName(request)
				&& !this.executed.contains(request))
			return this.executedCallback(request);

		return false;
	}

	// may also update executed set
	private boolean checkIfObviated(RCRecordRequest<NodeIDType> request) {
		if (this.executed.remove(request))
			return true;
		for (RCRecordRequest<NodeIDType> executedReq : this.executed)
			if (request.lessThan(executedReq))
				return true;
		return false;
	}

	// knocks off elements in set strictly lower than request
	private boolean knockOffLower(RCRecordRequest<NodeIDType> request,
			Set<RCRecordRequest<NodeIDType>> set, boolean isPending) {
		boolean lowerRemoved = false;
		RCRecordRequest<NodeIDType> lower = null;
		for (Iterator<RCRecordRequest<NodeIDType>> reqIter = set.iterator(); reqIter
				.hasNext();)
			if ((lower = reqIter.next()).lessThan(request)
					&& (lowerRemoved = true)) {
				log.log(Level.FINEST, "{0} knocked off lower request {1}",
						new Object[] { this, lower.getSummary(Level.FINEST) });
				reqIter.remove();
				if (isPending)
					this.lastAttempts.remove(request);
			}
		return lowerRemoved;
	}

	@SuppressWarnings("unchecked")
	private boolean isRCGroupName(RCRecordRequest<NodeIDType> request) {
		return (this.coordinator instanceof RepliconfigurableReconfiguratorDB) ? (((RepliconfigurableReconfiguratorDB<NodeIDType>) this.coordinator)
				.isRCGroupName(request.getServiceName())) : false;
	}

	private boolean shouldEnqueueEarlyExecutedNotification(
			RCRecordRequest<NodeIDType> request) {
		return this.isRCGroupName(request)

		/* Split intents are not coordinated at all. */
		// && !request.isSplitIntent()

		/* Merges are tricky coz although they are idempotent, they can be
		 * multiply successful, i.e., return handled=true (e.g., when merging
		 * multiple RC groups sequentially). This means that merge execution
		 * notifications may never be garbage collected if enqueued. If not
		 * enqueued however, we may keep trying the merge forever here and be
		 * unsuccessful even though the merge has been obviated (say because the
		 * RC record has actually moved on). The only way to be certain of
		 * obviation is to check the DB. We just enqueue for now to ensure
		 * progress coz merges are rare operations anyway, so some uncollected
		 * garbage is okay. */

		// && !request.isReconfigurationMerge()
		;
	}

	private void waitUntilNotified(long timeout) {
		synchronized (this) {
			try {
				wait(timeout);
			} catch (InterruptedException e) {
				e.printStackTrace();
			}
		}
	}

	private synchronized long coordinate() {
		RCRecordRequest<NodeIDType> head = null;
		long oldestAttempt = Long.MAX_VALUE;
		for (Iterator<RCRecordRequest<NodeIDType>> reqIter = this.pending
				.keySet().iterator(); reqIter.hasNext();) {
			RCRecordRequest<NodeIDType> request = reqIter.next();
			head = head == null ? request : head;
			// try coordinate and set last attempted timestamp
			if (repeatable(request)) {
				Level level = request.isReconfigurationMerge() ? Level.FINEST
						: Level.FINEST;
				log.log(level, "{0} coordinating request {1}", new Object[] {
						this, request.getSummary(level) });

				this.coordinate(request);
				this.lastAttempts.put(request, System.currentTimeMillis());
				oldestAttempt = Math.min(oldestAttempt,
						this.getLastAttempt(request));
			} else if (this.removable(request))
				reqIter.remove();
		}
		return getWaitTime(oldestAttempt);
	}

	/**
	 * @param request
	 * @return True if coordinated.
	 */
	public boolean coordinate(RCRecordRequest<NodeIDType> request) {
		try {
			return this.coordinator.coordinateRequest(request, callback);
		} catch (Exception e) {
			e.printStackTrace();
			// continue
		}
		return false;
	}

	private static final long MAX_PREV_DROP_COMMIT_ATTEMPT_TIME = 32 * WaitAckStopEpoch.RESTART_PERIOD;

	private boolean repeatable(RCRecordRequest<NodeIDType> request) {
		// else
		return System.currentTimeMillis() - this.getLastAttempt(request) > (this.nonDefaultRestartPeriods
				.containsKey(request.getServiceName()) ? this.nonDefaultRestartPeriods
				.get(request.getServiceName()) : RESTART_PERIOD);
	}

	private boolean removable(RCRecordRequest<NodeIDType> request) {
		if (request.isReconfigurationPrevDropComplete()
				&& System.currentTimeMillis()
						- request.startEpoch.getInitTime() < MAX_PREV_DROP_COMMIT_ATTEMPT_TIME) {
			return true;
		}
		return false;
	}

	private long getWaitTime(long oldestAttempt) {
		return Math.max(RESTART_PERIOD
				- (System.currentTimeMillis() - oldestAttempt), 0);
	}

	private long getLastAttempt(RCRecordRequest<NodeIDType> request) {
		Long last = this.lastAttempts.get(request);
		return last != null ? last : 0;
	}

	private String getSetSummary(Set<RCRecordRequest<NodeIDType>> set) {
		String s = "[";
		for (RCRecordRequest<NodeIDType> request : set)
			s += request.getSummary() + " ";
		return s + "]";
	}

	// for testing
	private void addAssert(RCRecordRequest<NodeIDType> request,
			Set<RCRecordRequest<NodeIDType>> set) {
		boolean contains = set.contains(request);
		if (!contains)
			for (RCRecordRequest<NodeIDType> member : set)
				assert (!member.equals(request)) : request
						+ "\n    !=     \njava.lang.AssertionError: " + member
						+ "\n " + request.hashCode() + " ?= "
						+ member.hashCode() + " .equals returns "
						+ member.equals(request) + " and "
						+ request.equals(member);
	}

	// ///////////////////////////////////////////////////////////////

	private final long RESTART_PERIOD = Config
			.getGlobalLong(ReconfigurationConfig.RC.COMMIT_WORKER_RESTART_PERIOD);

	private final AbstractReplicaCoordinator<?> coordinator;
	private final ReconfiguratorCallback callback;

	private static final Logger log = (Reconfigurator.getLogger());

	/**
	 * @param coordinator
	 * @param callback
	 */
	public CommitWorker(AbstractReplicaCoordinator<?> coordinator,
			ReconfiguratorCallback callback) {
		this.coordinator = coordinator;
		this.callback = callback;
		Thread me;
		(me = new Thread(this)).start();
		me.setName(CommitWorker.class.getSimpleName() + ":"
				+ coordinator.getMyID());
	}

	public String toString() {
		return this.getClass().getSimpleName() + this.coordinator.getMyID();
	}

	/**
	 * @return The re-attempt wait interval.
	 */
	public long getPeriod() {
		return RESTART_PERIOD;
	}

	/**
	 * Sanity checking equals and hashCode for RCRecordRequest.
	 * 
	 * @param args
	 */
	public static void main(String[] args) {

		Set<Integer> prevNodes = new HashSet<Integer>();
		Set<Integer> curNodes = new HashSet<Integer>();
		curNodes.add(96);
		StartEpoch<Integer> se1 = new StartEpoch<Integer>(23, "name", (int) 3,
				prevNodes, curNodes, null);
		StartEpoch<Integer> se2 = new StartEpoch<Integer>(27, "name", (int) 3,
				curNodes, curNodes, null);
		@SuppressWarnings("deprecation")
		RCRecordRequest<Integer> rc1 = new RCRecordRequest<Integer>(23, se1,
				RCRecordRequest.RequestTypes.RECONFIGURATION_MERGE);
		@SuppressWarnings("deprecation")
		RCRecordRequest<Integer> rc2 = new RCRecordRequest<Integer>(92, se2,
				RCRecordRequest.RequestTypes.RECONFIGURATION_MERGE);

		assert (rc1.equals(rc2));
		Set<RCRecordRequest<Integer>> set = new HashSet<RCRecordRequest<Integer>>();
		set.add(rc1);
		set.add(rc1);
		assert (set.size() == 1);
		assert (rc1.hashCode() == rc2.hashCode());
	}
}
