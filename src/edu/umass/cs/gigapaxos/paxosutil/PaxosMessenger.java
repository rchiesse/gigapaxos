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
package edu.umass.cs.gigapaxos.paxosutil;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Set;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import edu.umass.cs.gigapaxos.PaxosConfig.PC;
import edu.umass.cs.gigapaxos.PaxosManager;
import edu.umass.cs.gigapaxos.paxospackets.BatchedCommit;
import edu.umass.cs.gigapaxos.paxospackets.PaxosPacket;
import edu.umass.cs.gigapaxos.paxospackets.PaxosPacket.PaxosPacketType;
import edu.umass.cs.gigapaxos.paxospackets.RequestPacket;
import edu.umass.cs.nio.GenericMessagingTask;
import edu.umass.cs.nio.JSONMessenger;
import edu.umass.cs.nio.interfaces.InterfaceNIOTransport;
import edu.umass.cs.utils.Config;
import edu.umass.cs.utils.DelayProfiler;
import edu.umass.cs.utils.Util;

/**
 * @author V. Arun
 * @param <NodeIDType>
 * 
 *            This class is simply JSONMessenger adapted to use MessagingTask
 *            instead of GenericMessagingTask and to fix integer node IDs to
 *            NodeIDType as needed.
 * 
 *            It's a utility because there is nothing paxos specific here.
 */
@SuppressWarnings("javadoc")
public class PaxosMessenger<NodeIDType> extends JSONMessenger<NodeIDType> {

	private static final int NUM_MESSENGER_WORKERS = Config
			.getGlobalInt(PC.NUM_MESSENGER_WORKERS);

	public static final boolean ENABLE_INT_STRING_CONVERSION = true;// false;
	private final IntegerMap<NodeIDType> nodeMap;

	public PaxosMessenger(InterfaceNIOTransport<NodeIDType, JSONObject> niot,
			IntegerMap<NodeIDType> nodeMap) {
		this(niot, nodeMap, NUM_MESSENGER_WORKERS);
	}

	public PaxosMessenger(InterfaceNIOTransport<NodeIDType, JSONObject> niot,
			IntegerMap<NodeIDType> nodeMap, int numWorkers) {
		super(niot, numWorkers);
		this.nodeMap = nodeMap;
	}

	public PaxosMessenger(PaxosMessenger<NodeIDType> msgr) {
		this(msgr.getNIOTransport(), msgr.nodeMap, 0);
	}

	public void send(MessagingTask[] mtasks) throws JSONException, IOException {
		if (mtasks == null)
			return;
		for (MessagingTask mtask : mtasks)
			send(mtask);
	}

	// all send roads lead to here
	public void send(MessagingTask mtask) throws JSONException, IOException {
		if (mtask == null || mtask.isEmptyMessaging())
			return;
		// need to convert integers to NodeIDType.toString before sending
		super.send(toGeneric(mtask), useWorkers(mtask));
	}

	private boolean useWorkers(MessagingTask mtask) {
		return mtask != null
				&& !mtask.isEmptyMessaging()
				&& (mtask.msgs[0].getType() == PaxosPacketType.ACCEPT
						|| mtask.msgs[0].getType() == PaxosPacketType.DECISION || mtask.msgs[0]
						.getType() == PaxosPacketType.BATCHED_COMMIT);
	}

	private static boolean cacheStringifiedAccept() {
		return IntegerMap.allInt();
	}

	private Object toJSONObject(PaxosPacket msg) throws JSONException {
		if (cacheStringifiedAccept() && msg.getType() == PaxosPacketType.ACCEPT
				&& ((RequestPacket) msg).getStringifiedSelf() != null)
			return ((RequestPacket) msg).getStringifiedSelf();

		JSONObject jsonified = fixNodeIntToString(msg.toJSONObject());
		String stringified = jsonified.toString();

		if (cacheStringifiedAccept() && msg.getType() == PaxosPacketType.ACCEPT)
			((RequestPacket) msg).setStringifiedSelf(stringified);
		return stringified;
	}

	private static final boolean BYTEIFICATION = Config
			.getGlobalBoolean(PC.BYTEIFICATION);
	private static final boolean ENABLE_INSTRUMENTATION = Config
			.getGlobalBoolean(PC.ENABLE_INSTRUMENTATION);
	public static final boolean INSTRUMENT_SERIALIZATION = ENABLE_INSTRUMENTATION && Config
			.getGlobalBoolean(PC.INSTRUMENT_SERIALIZATION);

	private Object toJSONSmartObject(PaxosPacket msg) throws JSONException {
		if (cacheStringifiedAccept() && msg.getType() == PaxosPacketType.ACCEPT
				&& ((RequestPacket) msg).getStringifiedSelf() != null)
			return ((RequestPacket) msg).getStringifiedSelf();

		// FIXME: to test byteable
		if (BYTEIFICATION
				&& IntegerMap.allInt()
				&& (msg.getType() == PaxosPacket.PaxosPacketType.REQUEST || (msg
						.getType() == PaxosPacket.PaxosPacketType.ACCEPT))
				|| msg.getType() == PaxosPacketType.BATCHED_COMMIT
				|| msg.getType() == PaxosPacketType.BATCHED_ACCEPT_REPLY)
			return msg;

		long t = System.nanoTime();
		net.minidev.json.JSONObject jsonSmart = msg.toJSONSmart();
		assert (msg.getType() != PaxosPacketType.ACCEPT || jsonSmart != null);
		// fallback to JSONObject
		Object jsonified = jsonSmart == null ? fixNodeIntToString(msg
				.toJSONObject()) : fixNodeIntToString(jsonSmart);
		String stringified = jsonified.toString();

		if (Util.oneIn(100) && INSTRUMENT_SERIALIZATION && ENABLE_INSTRUMENTATION)
			if (msg.getType() == PaxosPacketType.ACCEPT)
				DelayProfiler.updateDelayNano("acceptStringification", t,
						((RequestPacket) msg).batchSize() + 1);
			else if (msg.getType() == PaxosPacketType.BATCHED_COMMIT)
				DelayProfiler.updateDelayNano("commitStringification", t,
						((BatchedCommit) msg).size() + 1);

		if (cacheStringifiedAccept() && msg.getType() == PaxosPacketType.ACCEPT)
			((RequestPacket) msg).setStringifiedSelf(stringified);
		return stringified;
	}

	// we explicitly
	private Object[] toObjects(PaxosPacket[] packets) throws JSONException {
		Object[] objects = new Object[packets.length];
		for (int i = 0; i < packets.length; i++) {
			objects[i] = USE_JSON_SMART ? toJSONSmartObject(packets[i])
					: toJSONObject(packets[i]);
			assert (!cacheStringifiedAccept()
					|| packets[i].getType() != PaxosPacketType.ACCEPT
					|| ((RequestPacket) packets[i]).getStringifiedSelf() != null || BYTEIFICATION);
		}
		return objects;
	}

	public static final boolean useJSONSmart() {
		return USE_JSON_SMART;
	}

	private static final boolean USE_JSON_SMART = !Config.getGlobalString(
			PC.JSON_LIBRARY).equals("org.json");

	private GenericMessagingTask<NodeIDType, ?> toGeneric(MessagingTask mtask)
			throws JSONException {
		Set<NodeIDType> nodes = this.nodeMap
				.getIntArrayAsNodeSet(mtask.recipients);
		return (new GenericMessagingTask<NodeIDType, String>(nodes.toArray(),
		// Util.intToIntegerArray(mtask.recipients)
				toObjects(mtask.msgs)));
	}

	// convert int to NodeIDType to String
	public JSONObject fixNodeIntToString(JSONObject json) throws JSONException {
		// long t = System.nanoTime();
		if (!ENABLE_INT_STRING_CONVERSION || IntegerMap.allInt())
			return json;
		if (json.has(PaxosPacket.NodeIDKeys.B.toString())) {
			// fix ballot string
			Ballot ballot = new Ballot(json.getString(PaxosPacket.NodeIDKeys.B
					.toString()));
			json.put(PaxosPacket.NodeIDKeys.B.toString(), Ballot
					.getBallotString(ballot.ballotNumber,
							intToString(ballot.coordinatorID)));
		} else if (json.has(PaxosPacket.NodeIDKeys.GROUP.toString())) {
			// fix group JSONArray
			JSONArray jsonArray = json
					.getJSONArray(PaxosPacket.NodeIDKeys.GROUP.toString());
			for (int i = 0; i < jsonArray.length(); i++) {
				int member = jsonArray.getInt(i);
				jsonArray.put(i, intToString(member));
			}
			json.put(PaxosPacket.NodeIDKeys.GROUP.toString(), jsonArray);
		} else
			for (PaxosPacket.NodeIDKeys key : PaxosPacket.NodeIDKeys.values()) {
				if (json.has(key.toString())) {
					// simple default int->string fix
					int id = json.getInt(key.toString());
					json.put(key.toString(), intToString(id));
				}
			}
		// if(Util.oneIn(100))
		// DelayProfiler.updateDelayNano("fixNodeIntToString", t);
		return json;
	}

	public net.minidev.json.JSONObject fixNodeIntToString(
			net.minidev.json.JSONObject jsonSmart) {
		// long t = System.nanoTime();
		if (!ENABLE_INT_STRING_CONVERSION || IntegerMap.allInt())
			return jsonSmart;
		if (jsonSmart.containsKey(PaxosPacket.NodeIDKeys.B.toString())) {
			// fix ballot string
			Ballot ballot = new Ballot(
					(String) jsonSmart.get(PaxosPacket.NodeIDKeys.B.toString()));
			jsonSmart.put(PaxosPacket.NodeIDKeys.B.toString(), Ballot
					.getBallotString(ballot.ballotNumber,
							intToString(ballot.coordinatorID)));
		} else if (jsonSmart.containsKey(PaxosPacket.NodeIDKeys.GROUP
				.toString())) {
			// fix group JSONArray
			Collection<?> jsonArray = (Collection<?>) jsonSmart
					.get(PaxosPacket.NodeIDKeys.GROUP.toString());
			ArrayList<String> nodes = new ArrayList<String>();
			for (Object element : jsonArray) {
				int member = (Integer) element;
				nodes.add(intToString(member));
				// jsonArray.put(i, intToString(member));
			}
			jsonSmart.put(PaxosPacket.NodeIDKeys.GROUP.toString(), nodes);
		} else
			for (PaxosPacket.NodeIDKeys key : PaxosPacket.NodeIDKeys.values()) {
				if (jsonSmart.containsKey(key.toString())) {
					// simple default int->string fix
					int id = (Integer) jsonSmart.get(key.toString());
					jsonSmart.put(key.toString(), intToString(id));
				}
			}
		// if(Util.oneIn(100))
		// DelayProfiler.updateDelayNano("fixNodeIntToString", t);
		return jsonSmart;
	}

	private String intToString(int id) {
		return this.nodeMap.get(id).toString();
	}
}
