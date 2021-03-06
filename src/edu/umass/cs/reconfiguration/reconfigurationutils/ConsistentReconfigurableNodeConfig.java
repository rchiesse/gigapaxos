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
package edu.umass.cs.reconfiguration.reconfigurationutils;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

import org.json.JSONArray;
import org.json.JSONException;

import edu.umass.cs.nio.nioutils.RTTEstimator;
import edu.umass.cs.reconfiguration.ReconfigurationConfig;
import edu.umass.cs.reconfiguration.Reconfigurator;
import edu.umass.cs.reconfiguration.ReconfigurationConfig.RC;
import edu.umass.cs.reconfiguration.interfaces.ModifiableActiveConfig;
import edu.umass.cs.reconfiguration.interfaces.ModifiableRCConfig;
import edu.umass.cs.reconfiguration.interfaces.ReconfigurableNodeConfig;
import edu.umass.cs.utils.Config;

/**
 * @author arun
 *
 * @param <NodeIDType>
 * 
 *            * This class is a wrapper around NodeConfig to ensure that it is
 *            consistent, i.e., it returns consistent results even if it changes
 *            midway. In particular, it does not allow the use of a method like
 *            getNodeIDs().
 * 
 *            It also has consistent hashing utility methods.
 */
public class ConsistentReconfigurableNodeConfig<NodeIDType> extends
		ConsistentNodeConfig<NodeIDType> implements
		ModifiableActiveConfig<NodeIDType>, ModifiableRCConfig<NodeIDType>,
		InterfaceGetActiveIPs {
	private final SimpleReconfiguratorNodeConfig<NodeIDType> nodeConfig;
	private Set<NodeIDType> activeReplicas; // most recent cached copy
	private Set<NodeIDType> reconfigurators; // most recent cached copy

	// need to refresh when nodeConfig changes
	private final ConsistentHashing<NodeIDType> CH_RC;
	// need to refresh when nodeConfig changes
	private final ConsistentHashing<NodeIDType> CH_AR;

	/* We need to track reconfigurators slated for removal separately because we
	 * still need ID to socket address mappings for deleted nodes (e.g., in
	 * order to do networking for completing deletion operations) but not
	 * include deleted nodes in the consistent hash ring. Thus, the ring
	 * transitions immediately to the new ring when reconfigurators are added or
	 * slated for removal, but socket addresses for removal-slated nodes are
	 * maintained until explicitly told to garbage collect them. */
	private Set<NodeIDType> reconfiguratorsSlatedForRemoval = new HashSet<NodeIDType>();
	private Set<NodeIDType> activesSlatedForRemoval = new HashSet<NodeIDType>();

	/**
	 * @param nc
	 */
	public ConsistentReconfigurableNodeConfig(
			ReconfigurableNodeConfig<NodeIDType> nc) {
		super(nc);
		this.nodeConfig = new SimpleReconfiguratorNodeConfig<NodeIDType>(nc);
		this.activeReplicas = this.nodeConfig.getActiveReplicas();
		this.reconfigurators = this.nodeConfig.getReconfigurators();
		this.CH_RC = new ConsistentHashing<NodeIDType>(this.reconfigurators);
		/* The true flag means replicate_all, i.e., number of active replicas
		 * chosen initially will be the set of all active replicas at that time. */
		this.CH_AR = new ConsistentHashing<NodeIDType>(this.activeReplicas,
				Config.getGlobalBoolean(RC.REPLICATE_ALL));
	}

	@Override
	public Set<NodeIDType> getValuesFromStringSet(Set<String> strNodes) {
		return this.nodeConfig.getValuesFromStringSet(strNodes);
	}

	@Override
	public Set<NodeIDType> getValuesFromJSONArray(JSONArray array)
			throws JSONException {
		return this.nodeConfig.getValuesFromJSONArray(array);
	}

	@Override
	public Set<NodeIDType> getNodeIDs() {
		throw new RuntimeException("The use of this method is not permitted");
	}

	@Override
	public Set<NodeIDType> getActiveReplicas() {
		return this.nodeConfig.getActiveReplicas();
	}

	/**
	 * @return Socket addresses of active replicas. Used by reconfigurator to
	 *         respond to broadcast queries.
	 */
	public Set<InetSocketAddress> getActiveReplicaSocketAddresses() {
		return this.getActiveReplicaSocketAddresses(Integer.MAX_VALUE);
	}

	/**
	 * This method tries to return a limited number of active replica socket
	 * addresses that to the extent possible have distinct IPs. The socket
	 * addresses are a randomly chosen subset of size up to limit from the set
	 * of all active replicas.
	 * 
	 * @param limit
	 * @return Socket addresses of active replicas. Used by reconfigurator to
	 *         respond to broadcast queries.
	 */
	public Set<InetSocketAddress> getActiveReplicaSocketAddresses(int limit) {
		Set<InetSocketAddress> actives = new HashSet<InetSocketAddress>();
		Set<Integer> activeIPs = new HashSet<Integer>();
		ArrayList<InetSocketAddress> activeNodes = new ArrayList<InetSocketAddress>();
		ArrayList<InetSocketAddress> duplicateIPs = new ArrayList<InetSocketAddress>();

		/* Create up to limit-sized ArrayList for shuffling. First add socket
		 * addresses corresponding to distinct /24 IP prefixes. */
		for (NodeIDType node : this.getActiveReplicas()) {
			InetSocketAddress sockAddr = this.getNodeSocketAddress(node);
			if (!activeIPs.contains(RTTEstimator.addrToPrefixInt(sockAddr
					.getAddress()))) {
				activeNodes.add(sockAddr);
				activeIPs.add(RTTEstimator.addrToPrefixInt(sockAddr
						.getAddress()));
			} else
				duplicateIPs.add(sockAddr);
			if (activeNodes.size() >= limit)
				break;
		}
		/* Then check if we can add back some of the socket addresses with
		 * duplicate /24 prefixes up to limit. */
		if (activeNodes.size() < limit) {
			for (InetSocketAddress duplicate : duplicateIPs) {
				activeNodes.add(duplicate);
				if (activeNodes.size() >= limit)
					break;
			}
		}

		// shuffle to return actives in random order
		Collections.shuffle(activeNodes);
		for (InetSocketAddress sockAddr : activeNodes) {
			actives.add(sockAddr);
			if (actives.size() >= limit)
				break;
		}
		return actives;
	}

	@Override
	public Set<NodeIDType> getReconfigurators() {
		return this.nodeConfig.getReconfigurators();
	}

	/**
	 * Consistent coz it always consults nodeConfig.
	 * 
	 * @param nodeIDs
	 * @return Array of IP addresses corresponding to {@code nodeIDs}.
	 */
	public ArrayList<InetAddress> getNodeIPs(Set<NodeIDType> nodeIDs) {
		ArrayList<InetAddress> addresses = new ArrayList<InetAddress>();
		for (NodeIDType id : nodeIDs) {
			addresses.add(this.nodeConfig.getNodeAddress(id));
		}
		assert (addresses != null);
		return addresses;
	}

	@Override
	public ArrayList<InetAddress> getActiveIPs() {
		return getNodeIPs(getActiveReplicas());
	}

	// refresh before returning
	/**
	 * @param name
	 * @return Set of consecutive reconfigurators to which {@code name} hashes
	 *         on the consistent hash ring.
	 */
	public Set<NodeIDType> getReplicatedReconfigurators(String name) {
		this.refreshReconfigurators();
		return this.CH_RC.getReplicatedServers(name);
	}

	/**
	 * @param name
	 * @return Reconfigurator addresses responsible for name.
	 */
	public Set<InetSocketAddress> getReconfiguratorsAsAddresses(String name) {
		Set<NodeIDType> nodes = getReplicatedReconfigurators(name);
		Set<InetSocketAddress> addrs = new HashSet<InetSocketAddress>();
		for (NodeIDType node : nodes)
			addrs.add(this.getNodeSocketAddress(node));
		return addrs;
	}

	/**
	 * @param name
	 * @return First reconfigurator to which {@code name} hashes on the
	 *         consistent hash ring.
	 */
	// refresh before returning
	public NodeIDType getFirstReconfigurator(String name) {
		this.refreshReconfigurators();
		return this.CH_RC.getNode(name);
	}

	// NOT USED IN NEW APP. FOR BACKWARDS COMPATIBILITY WITH OLD APP.
	// WILL BE REMOVED AFTER NEW APP IS TESTED.
	/**
	 * Returns the hash for this name.
	 * 
	 * @param name
	 * @return Returns the node id to which name consistent-hashes.
	 */
	@Deprecated
	public NodeIDType getReconfiguratorHash(String name) {
		return this.getFirstReconfigurator(name);
	}

	// refresh before returning
	/**
	 * @param name
	 * @return Set of active replica nodes to which {@code name} hashes on the
	 *         consistent hash ring of all active replica nodes.
	 */
	public Set<NodeIDType> getReplicatedActives(String name) {
		this.refreshActives();
		return this.CH_AR.getReplicatedServers(name);
	}

	/**
	 * @param name
	 * @return Set of active replica IPs to which {@code name} hashes on the
	 *         consistent hash ring of all active replica nodes.
	 */
	public ArrayList<InetAddress> getReplicatedActivesIPs(String name) {
		return this.getNodeIPs(this.getReplicatedActives(name));
	}

	/**
	 * @param name
	 * @param limit
	 * @return Active replica addresses.
	 */
	private Set<InetSocketAddress> getReplicatedActivesAddresses(String name,
			int limit) {
		Set<NodeIDType> actives = this.getReplicatedActives(name);
		Set<InetSocketAddress> addresses = new HashSet<InetSocketAddress>();
		int count = 0;
		for (NodeIDType node : actives) {
			addresses.add(this.getNodeSocketAddress(node));
			if (++count == limit)
				break;
		}
		return addresses;
	}

	/**
	 * @return Random active replica address.
	 */
	public Set<InetSocketAddress> getRandomActiveReplica() {
		return this.getReplicatedActivesAddresses("" + Math.random(), 1);
	}

	/**
	 * This method maps a set of addresses, newAddresses, to a set of nodes such
	 * that there is maximal overlap with the specified set of nodes, oldNodes.
	 * It is somewhat nontrivial only because there is a many-to-one mapping
	 * from nodes to addresses, so a simple reverse lookup is not meaningful.
	 * 
	 * @param newAddresses
	 * @param oldNodes
	 * @return Set of active replica IPs corresponding to {@code newAddresses}
	 *         that have high overlap with the set of old active replica nodes
	 *         {@code oldNodes}.
	 */
	public Set<NodeIDType> getIPToActiveReplicaIDs(
			ArrayList<InetAddress> newAddresses, Set<NodeIDType> oldNodes) {
		Set<NodeIDType> newNodes = new HashSet<NodeIDType>(); // return value
		ArrayList<InetAddress> unassigned = new ArrayList<InetAddress>();
		for (InetAddress address : newAddresses)
			unassigned.add(address);
		// assign old nodes first if they match any new address
		for (NodeIDType oldNode : oldNodes) {
			InetAddress oldAddress = this.nodeConfig.getNodeAddress(oldNode);
			if (unassigned.contains(oldAddress)) {
				newNodes.add(oldNode);
				unassigned.remove(oldAddress);
			}
		}
		// assign any node to unassigned addresses
		for (NodeIDType node : this.nodeConfig.getActiveReplicas()) {
			if (this.activesSlatedForRemoval.contains(node))
				continue;
			InetAddress address = this.nodeConfig.getNodeAddress(node);
			if (unassigned.contains(address)) {
				newNodes.add(node);
				unassigned.remove(address);
			}
		}
		return newNodes;
	}

	/**
	 * This method should not be used. Reconfigurator uses this at exactly one
	 * place, namely, to encapsulate itself inside ActiveReplica so as to be
	 * itself reconfigurable.
	 * 
	 * @return Underlying node config object.
	 */
	public ReconfigurableNodeConfig<NodeIDType> getUnderlyingNodeConfig() {
		return this.nodeConfig;
	}

	// refresh consistent hash structure if changed
	private synchronized boolean refreshActives() {
		Set<NodeIDType> curActives = this.nodeConfig.getActiveReplicas();
		// remove those slated for removal for CH ring purposes
		curActives.removeAll(this.activesSlatedForRemoval);
		if (curActives.equals(this.getLastActives()))
			return false;
		this.setLastActives(curActives);
		this.CH_AR.refresh(curActives);
		return true;
	}

	// refresh consistent hash structure if changed
	private synchronized boolean refreshReconfigurators() {
		Set<NodeIDType> curReconfigurators = this.nodeConfig
				.getReconfigurators();
		// remove those slated for removal for CH ring purposes
		curReconfigurators.removeAll(this.reconfiguratorsSlatedForRemoval);
		if (curReconfigurators.equals(this.getLastReconfigurators()))
			return false;
		this.setLastReconfigurators(curReconfigurators);
		this.CH_RC.refresh(curReconfigurators);
		return true;
	}

	private synchronized Set<NodeIDType> getLastActives() {
		return this.activeReplicas;
	}

	private synchronized Set<NodeIDType> getLastReconfigurators() {
		return this.reconfigurators;
	}

	private synchronized Set<NodeIDType> setLastActives(
			Set<NodeIDType> curActives) {
		return this.activeReplicas = curActives;
	}

	private synchronized Set<NodeIDType> setLastReconfigurators(
			Set<NodeIDType> curReconfigurators) {
		return this.reconfigurators = curReconfigurators;
	}

	@Override
	public InetSocketAddress addReconfigurator(NodeIDType id,
			InetSocketAddress sockAddr) {
		InetSocketAddress isa = this.nodeConfig.addReconfigurator(id, sockAddr);
		assert (this.nodeConfig.getNodeAddress(id) != null);
		return isa;
	}

	/**
	 * Returns the socket address of the public host corresponding to this id.
	 * 
	 * @param id
	 * @return Socket address corresponding to node {@code id}.
	 */
	public InetSocketAddress getNodeSocketAddress(NodeIDType id) {
		InetAddress ip = this.nodeConfig.getNodeAddress(id);
		int port = this.nodeConfig.getNodePort(id);
		if (port == -1 && !this.isSlatedForRemoval(id))
			Reconfigurator.getLogger()
					.warning("No port found for nodeID " + id);
		return (ip != null ? new InetSocketAddress(ip, port) : null);
	}

	/**
	 * Returns the bindable socket address of the public host corresponding to
	 * this id. This code be a private address in the case of where we are
	 * inside a NAT.
	 * 
	 * @param id
	 * @return Bind socket address.
	 */
	public InetSocketAddress getBindSocketAddress(NodeIDType id) {
		InetAddress ip = this.nodeConfig.getBindAddress(id);
		int port = this.nodeConfig.getNodePort(id);
		if (port == -1 && !this.isSlatedForRemoval(id))
			Reconfigurator.getLogger()
					.warning("No port found for nodeID " + id);
		return (ip != null ? new InetSocketAddress(ip, port) : null);
	}

	private boolean isSlatedForRemoval(NodeIDType id) {
		return this.activesSlatedForRemoval.contains(id)
				|| this.reconfiguratorsSlatedForRemoval.contains(id);
	}

	@Override
	public InetSocketAddress removeReconfigurator(NodeIDType id) {
		return this.nodeConfig.removeReconfigurator(id);
	}

	private InetSocketAddress removeActive(NodeIDType id) {
		return this.nodeConfig.removeActiveReplica(id);
	}

	/**
	 * A utility method to split a collection of names into batches wherein
	 * names in each batch map to the same reconfigurator group. The set of
	 * reconfigurators can be specified either as a NodeIDType or a String set
	 * but not as an InetSocketAddress set (unless NodeIDType is
	 * InetSocketAddress).
	 * 
	 * @param names
	 * @param reconfigurators
	 * @return A set of batches of names wherein names in each batch map to the
	 *         same reconfigurator group.
	 */
	public static Collection<Set<String>> splitIntoRCGroups(Set<String> names,
			Set<?> reconfigurators) {
		if (reconfigurators.isEmpty())
			throw new RuntimeException(
					"A nonempty set of reconfigurators must be specified.");
		ConsistentHashing<?> ch = new ConsistentHashing<>(reconfigurators);
		Map<String, Set<String>> batches = new HashMap<String, Set<String>>();
		for (String name : names) {
			String rc = ch.getNode(name).toString();
			if (!batches.containsKey(rc))
				batches.put(rc, new HashSet<String>());
			batches.get(rc).add(name); // no need to put again
		}
		return batches.values();
	}

	/**
	 * @param names
	 * @return True if all names map to the same reconfigurator group.
	 */
	public boolean checkSameGroup(Set<String> names) {
		NodeIDType rc = null;
		for (String name : names)
			if (rc == null)
				rc = this.getFirstReconfigurator(name);
			else if (!rc.equals(this.getFirstReconfigurator(name)))
				return false;
		return true;
	}

	/**
	 * @param id
	 * @return IP address of id being slated for removal.
	 */
	public InetSocketAddress slateForRemovalReconfigurator(NodeIDType id) {
		this.reconfiguratorsSlatedForRemoval.add(id);
		return this.getNodeSocketAddress(id);
	}

	/**
	 * @param id
	 * @return IP address of id being slated for removal.
	 */
	public InetSocketAddress slateForRemovalActive(NodeIDType id) {
		this.activesSlatedForRemoval.add(id);
		return this.getNodeSocketAddress(id);
	}

	/**
	 * @return True if all nodes slated for removal have been successfully
	 *         removed.
	 */
	public boolean removeReconfiguratorsSlatedForRemoval() {
		boolean allRemoved = true;
		for (Iterator<NodeIDType> nodeIter = this.reconfiguratorsSlatedForRemoval
				.iterator(); nodeIter.hasNext();) {
			NodeIDType slated = nodeIter.next();
			if (this.removeReconfigurator(slated) != null)
				nodeIter.remove();
			else
				allRemoved = false;
		}
		return allRemoved;
	}

	/**
	 * @return True if all nodes slated for removal have been successfully
	 *         removed.
	 */
	public boolean removeActivesSlatedForRemoval() {
		boolean allRemoved = true;
		for (Iterator<NodeIDType> nodeIter = this.activesSlatedForRemoval
				.iterator(); nodeIter.hasNext();) {
			NodeIDType slated = nodeIter.next();
			if (this.removeActive(slated) != null) {
				assert (!this.nodeExists(slated)) : slated;
				nodeIter.remove();
			} else
				allRemoved = false;
		}
		return allRemoved;
	}

	@Override
	public InetSocketAddress addActiveReplica(NodeIDType id,
			InetSocketAddress sockAddr) {
		return this.nodeConfig.addActiveReplica(id, sockAddr);
	}

	@Override
	public InetSocketAddress removeActiveReplica(NodeIDType id) {
		return this.nodeConfig.removeActiveReplica(id);
	}

	@Override
	public long getVersion() {
		return this.nodeConfig.getVersion();
	}

	public String toString() {
		String s = "";
		for (NodeIDType id : this.nodeConfig.getActiveReplicas()) {
			s += id + ":" + this.getNodeSocketAddress(id) + " ";
		}
		s += "\n";
		for (NodeIDType id : this.nodeConfig.getReconfigurators()) {
			s += id + ":" + this.getNodeSocketAddress(id) + " ";
		}

		return s;
	}
}