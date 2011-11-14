package org.sakaiproject.delegatedaccess.logic;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreeModel;

import lombok.Getter;
import lombok.Setter;

import org.apache.log4j.Logger;
import org.sakaiproject.authz.api.AuthzGroup;
import org.sakaiproject.authz.api.Role;
import org.sakaiproject.delegatedaccess.model.HierarchyNodeSerialized;
import org.sakaiproject.delegatedaccess.model.NodeModel;
import org.sakaiproject.delegatedaccess.model.SearchResult;
import org.sakaiproject.delegatedaccess.model.ToolSerialized;
import org.sakaiproject.delegatedaccess.util.DelegatedAccessConstants;
import org.sakaiproject.delegatedaccess.util.DelegatedAccessMutableTreeNode;
import org.sakaiproject.hierarchy.HierarchyService;
import org.sakaiproject.hierarchy.model.HierarchyNode;
import org.sakaiproject.tool.api.Session;
import org.sakaiproject.tool.api.Tool;
import org.sakaiproject.user.api.User;


/**
 * Implementation of {@link ProjectLogic}
 * 
 * @author Bryan Holladay (holladay@longsight.com)
 *
 */
public class ProjectLogicImpl implements ProjectLogic {

	private static final Logger log = Logger.getLogger(ProjectLogicImpl.class);
	@Getter @Setter
	private SakaiProxy sakaiProxy;
	@Getter @Setter
	private HierarchyService hierarchyService;



	/**
	 * init - perform any actions required here for when this bean starts up
	 */
	public void init() {
		log.info("init");
	}

	/**
	 * returns the node for this id
	 * @param id
	 * @return
	 */
	public HierarchyNodeSerialized getNode(String id){
		return new HierarchyNodeSerialized(hierarchyService.getNodeById(id));
	}


	/**
	 * {@inheritDoc}
	 */
	public void updateNodePermissionsForUser(NodeModel nodeModel, String userId){
		//first step, remove all permissions so you can have a clear palet
		removeAllUserPermissions(nodeModel.getNodeId(), userId);

		if(nodeModel.isDirectAccess()){
			//if direct access, add permissions, otherwise, leave it blank

			//site access permission
			hierarchyService.assignUserNodePerm(userId, nodeModel.getNodeId(), DelegatedAccessConstants.NODE_PERM_SITE_VISIT, false);

			//realm & role permissions
			saveRealmAndRoleAccess(userId, nodeModel.getRealm(), nodeModel.getRole(), nodeModel.getNodeId());

			//tool permissions:
			List<String> restrictedTools = new ArrayList<String>();
			for(ToolSerialized tool : nodeModel.getRestrictedTools()){
				if(tool.isSelected()){
					restrictedTools.add(tool.getToolId());
				}
			}
			if(!restrictedTools.isEmpty()){
				saveRestrictedToolsForUser(userId, nodeModel.getNodeId(), restrictedTools);
			}
		}


		if(nodeModel.isDirectAccess()){
			sakaiProxy.postEvent(DelegatedAccessConstants.EVENT_ADD_USER_PERMS, "/user/" + userId + "/node/" + nodeModel.getNodeId() + "/realm/" + nodeModel.getRealm() + "/role/" + nodeModel.getRole(), true);
		}else{
			sakaiProxy.postEvent(DelegatedAccessConstants.EVENT_DELETE_USER_PERMS, "/user/" + userId + "/node/" + nodeModel.getNodeId(), true);
		}
	}

	private void removeAllUserPermissions(String nodeId, String userId){
		for(String perm : hierarchyService.getPermsForUserNodes(userId, new String[]{nodeId})){
			hierarchyService.removeUserNodePerm(userId, nodeId, perm, false);
		}
	}

	/**
	 * returns a list of nodes the user has site.access permission (aka access).  Only direct nodes, nothing inherited.
	 * @return
	 */
	public Set<HierarchyNodeSerialized> getNodesForUser(String userId) {
		return convertToSerializedNodeSet(hierarchyService.getNodesForUserPerm(userId, DelegatedAccessConstants.NODE_PERM_SITE_VISIT));
	}

	/**
	 * returns a serialized version for Hierarchy nodes.
	 * 
	 * @param nodeSet
	 * @return
	 */
	private Set<HierarchyNodeSerialized> convertToSerializedNodeSet(Set<HierarchyNode> nodeSet){
		Set<HierarchyNodeSerialized> nodesForUserSerialized = new HashSet<HierarchyNodeSerialized>();
		if(nodeSet != null){
			for(HierarchyNode node : nodeSet){
				nodesForUserSerialized.add(new HierarchyNodeSerialized(node));
			}
		}
		return nodesForUserSerialized;
	}

	/**
	 * {@inheritDoc}
	 */
	public void initializeDelegatedAccessSession(String userId){
		if(userId != null && !"".equals(userId)){
			Session session = sakaiProxy.getCurrentSession();
			Map<String, String[]> accessMap = new HashMap<String, String[]>();
			Map<String, String[]> toolMap = new HashMap<String, String[]>();
			TreeModel userTreeModel = createTreeModelForUser(userId, false, true);
			if(userTreeModel != null){
				List<NodeModel> siteNodes = getSiteNodes(((DefaultMutableTreeNode) userTreeModel.getRoot()));
				for(NodeModel nodeModel : siteNodes){
					accessMap.put(nodeModel.getNode().description, nodeModel.getNodeAccessRealmRole());
					toolMap.put(nodeModel.getNode().description, nodeModel.getNodeRestrictedTools());
				}
			}
			session.setAttribute(DelegatedAccessConstants.SESSION_ATTRIBUTE_ACCESS_MAP, accessMap);
			session.setAttribute(DelegatedAccessConstants.SESSION_ATTRIBUTE_DENIED_TOOLS, toolMap);
			sakaiProxy.refreshCurrentUserAuthz();
		}
	}

	private List<NodeModel> getSiteNodes(DefaultMutableTreeNode treeNode){
		List<NodeModel> returnList = new ArrayList<NodeModel>();
		if(treeNode != null){
			if(((NodeModel) treeNode.getUserObject()).getNode().description.startsWith("/site/")){
				returnList.add((NodeModel) treeNode.getUserObject());
			}
			//check the rest of the children:
			for(int i = 0; i < treeNode.getChildCount(); i++){
				returnList.addAll(getSiteNodes((DefaultMutableTreeNode)treeNode.getChildAt(i)));
			}
		}

		return returnList;
	}

	/**
	 * {@inheritDoc}
	 */
	public void grantAccessToSite(NodeModel nodeModel){
		Session session = sakaiProxy.getCurrentSession();
		Object sessionDelegatedAccessMap = session.getAttribute(DelegatedAccessConstants.SESSION_ATTRIBUTE_ACCESS_MAP);
		Map<String, String[]> delegatedAccessMap = new HashMap<String, String[]>();
		if(sessionDelegatedAccessMap != null){
			delegatedAccessMap = (Map<String, String[]>) sessionDelegatedAccessMap;
		}
		String[] access = nodeModel.getNodeAccessRealmRole();
		if (access != null && access.length == 2
				&& access[0] != null
				&& access[1] != null
				&& !"".equals(access[0])
				&& !"".equals(access[1])) {
			delegatedAccessMap.put(nodeModel.getNode().description, access);
		}
		else{
			delegatedAccessMap.put(nodeModel.getNode().description, new String[]{"", ""});
		}
		session.setAttribute(DelegatedAccessConstants.SESSION_ATTRIBUTE_ACCESS_MAP, delegatedAccessMap);

		//Denied Tools List
		Map<String, String[]> deniedToolsMap = new HashMap<String, String[]>();
		Object sessionDeniedToolsMap = session.getAttribute(DelegatedAccessConstants.SESSION_ATTRIBUTE_DENIED_TOOLS);
		if(sessionDeniedToolsMap != null){
			deniedToolsMap = (Map<String, String[]>) sessionDeniedToolsMap;
		}

		String[] deniedTools = nodeModel.getNodeRestrictedTools();
		if(deniedTools != null){
			deniedToolsMap.put(nodeModel.getNode().description, deniedTools);
		}else{
			deniedToolsMap.put(nodeModel.getNode().description, new String[0]);
		}

		session.setAttribute(DelegatedAccessConstants.SESSION_ATTRIBUTE_DENIED_TOOLS, deniedToolsMap);


		sakaiProxy.refreshCurrentUserAuthz();
	}

	private HierarchyNodeSerialized getRootNode(){
		return new HierarchyNodeSerialized(hierarchyService.getRootNode(DelegatedAccessConstants.HIERARCHY_ID));
	}

	/**
	 * {@inheritDoc}
	 */
	public List<SearchResult> searchUsers(String search, int first, int last) {
		List<User> searchResult = sakaiProxy.searchUsers(search, first, last);
		List<SearchResult> returnList = new ArrayList<SearchResult>();
		for(User user : searchResult){
			returnList.add(getSearchResult(user));
		}

		return returnList;
	}

	/**
	 * {@inheritDoc}
	 */
	private SearchResult getSearchResult(User user){
		if(user != null){
			return new SearchResult(user);
		}else{
			return null;
		}
	}

	/**
	 * {@inheritDoc}
	 */
	private void saveRealmAndRoleAccess(String userId, String realmId, String role, String nodeId){
		if(realmId != null && role != null && !"".equals(realmId) && !"".equals(role)){
			hierarchyService.assignUserNodePerm(userId, nodeId, DelegatedAccessConstants.NODE_PERM_REALM_PREFIX +realmId, false);
			hierarchyService.assignUserNodePerm(userId, nodeId, DelegatedAccessConstants.NODE_PERM_ROLE_PREFIX +role, false);
		}
	}

	/**
	 * returns the user's realm and role information for the given node.  Doesn't include inherited information, will return
	 * a "" if not found.
	 * @param userId
	 * @param nodeId
	 * @return
	 */
	private String[] getAccessRealmRole(String userId, String nodeId){
		Set<String> perms = hierarchyService.getPermsForUserNodes(userId, new String[]{nodeId});
		String realmId = "";
		String roleId = "";
		for(String perm : perms){
			if(perm.startsWith(DelegatedAccessConstants.NODE_PERM_REALM_PREFIX)){
				realmId = perm.substring(DelegatedAccessConstants.NODE_PERM_REALM_PREFIX.length());
			}else if(perm.startsWith(DelegatedAccessConstants.NODE_PERM_ROLE_PREFIX)){
				roleId = perm.substring(DelegatedAccessConstants.NODE_PERM_ROLE_PREFIX.length());
			}
		}
		return new String[]{realmId, roleId};
	}

	/**
	 * Returns a list of ToolSerialized that initialized the selected field
	 * @param userId
	 * @param nodeId
	 * @return
	 */
	public List<ToolSerialized> getRestrictedToolSerializedList(String userId, String nodeId){
		List<ToolSerialized> returnList = new ArrayList<ToolSerialized>();
		List<String> restrictedTools = getRestrictedToolsForUser(userId, nodeId);
		for(Tool tool : sakaiProxy.getAllTools()){
			returnList.add(new ToolSerialized(tool.getId(), tool.getTitle() + "(" + tool.getId() + ")", restrictedTools.contains(tool.getId())));
		}
		Collections.sort(returnList, new Comparator<ToolSerialized>() {
			public int compare(ToolSerialized arg0, ToolSerialized arg1) {
				return arg0.getToolName().compareTo(arg1.getToolName());
			}
		});
		return returnList;
	}

	private List<String> getRestrictedToolsForUser(String userId, String nodeId){
		List<String> returnList = new ArrayList<String>();
		Set<String> userPerms = hierarchyService.getPermsForUserNodes(userId, new String[]{nodeId});
		for(String userPerm : userPerms){
			if(userPerm.startsWith(DelegatedAccessConstants.NODE_PERM_DENY_TOOL_PREFIX)){
				returnList.add(userPerm.substring(DelegatedAccessConstants.NODE_PERM_DENY_TOOL_PREFIX.length()));
			}
		}
		return returnList;
	}

	private void saveRestrictedToolsForUser(String userId, String nodeId, List<String> toolIds){
		//add new tools:
		for(String newTool : toolIds){
			hierarchyService.assignUserNodePerm(userId, nodeId, DelegatedAccessConstants.NODE_PERM_DENY_TOOL_PREFIX + newTool, false);
		}
	}

	public List<NodeModel> searchUserSites(String search, TreeModel treeModel){
		if(search == null){
			search = "";
		}
		return searchUserTree(search, (DefaultMutableTreeNode) treeModel.getRoot());
	}

	private List<NodeModel> searchUserTree(String search, DefaultMutableTreeNode node){
		List<NodeModel> returnList = new ArrayList<NodeModel>();
		if(node != null){
			NodeModel nodeModel = (NodeModel) node.getUserObject();
			if(nodeModel.getNode().description.startsWith("/site/")){
				if("".equals(search) || nodeModel.getNode().title.toLowerCase().contains(search.toLowerCase())
						|| nodeModel.getNode().description.substring(6).toLowerCase().contains(search.toLowerCase())){
					returnList.add(nodeModel);
				}

			}
			for(int i = 0; i < node.getChildCount(); i++){
				returnList.addAll(searchUserTree(search, (DefaultMutableTreeNode) node.getChildAt(i)));
			}
		}
		return returnList;
	}

	private String[] convertToArray(List<String> list){
		String[] returnArray = new String[]{};
		if(!list.isEmpty()){
			returnArray = new String[list.size()];
			for(int i = 0; i < list.size(); i++){
				returnArray[i] = list.get(i);
			}
		}
		return returnArray;
	}



	//TREE MODEL FUNCTIONS:

	//NodeCache stores HierarchyNodeSerialed nodes for faster lookups
	private Map<String,HierarchyNodeSerialized> nodeCache = new HashMap<String, HierarchyNodeSerialized>();
	private List<String> accessNodes = new ArrayList<String>();
	/**
	 * Creates the model that feeds the tree.
	 * 
	 * @return New instance of tree model.
	 */
	public TreeModel createTreeModelForUser(String userId, boolean addDirectChildren, boolean cascade)
	{
		//Returns a List that represents the tree/node architecture:
		//  List{ List{node, List<children>}, List{node, List<children>}, ...}.
		accessNodes = new ArrayList<String>();
		List<List> l1 = getTreeListForUser(userId, addDirectChildren, cascade, getNodesForUser(userId));
		//order tree model:
		orderTreeModel(l1);

		return convertToTreeModel(l1, userId);
	}

	/**
	 * Takes a list representation of a tree and creates the TreeModel
	 * 
	 * @param map
	 * @param userId
	 * @return
	 */
	private TreeModel convertToTreeModel(List<List> map, String userId)
	{
		TreeModel model = null;
		if(!map.isEmpty() && map.size() == 1){

			DefaultMutableTreeNode rootNode = add(null, map, getRealmMap(), userId);
			model = new DefaultTreeModel(rootNode);
		}
		return model;
	}

	/**
	 * Adds node to parent and creates the NodeModel to store in the tree
	 * @param parent
	 * @param sub
	 * @param realmMap
	 * @param userId
	 * @return
	 */
	private DefaultMutableTreeNode add(DefaultMutableTreeNode parent, List<List> sub, Map<String, List<String>> realmMap, String userId)
	{
		DefaultMutableTreeNode root = null;
		for (List nodeList : sub)
		{
			HierarchyNodeSerialized node = (HierarchyNodeSerialized) nodeList.get(0);
			List children = (List) nodeList.get(1);
			String realm = "";
			String role = "";
			if(accessNodes.contains(node.id)){
				String[] realmRole = getAccessRealmRole(userId, node.id);
				realm = realmRole[0];
				role = realmRole[1];
			}
			NodeModel parentNodeModel = null;
			if(parent != null){
				parentNodeModel = ((NodeModel) parent.getUserObject());
			}
			DefaultMutableTreeNode child = new DelegatedAccessMutableTreeNode();
			child.setUserObject(new NodeModel(node.id, node, accessNodes
					.contains(node.id), realmMap, realm, role, parentNodeModel,
					getRestrictedToolSerializedList(userId, node.id)));
			if(parent == null){
				//we have the root, set it
				root = child;
			}else{
				parent.add(child);
			}
			if(!children.isEmpty()){
				add(child, children, realmMap, userId);
			}
		}
		return root;
	}

	/**
	 * returns a map of all realms and their roles from sakaiProxy.getSiteTemplates()
	 * 
	 * @return
	 */
	private Map<String, List<String>> getRealmMap(){
		List<AuthzGroup> siteTemplates = sakaiProxy.getSiteTemplates();
		final Map<String, List<String>> realmMap = new HashMap<String, List<String>>();
		for(AuthzGroup group : siteTemplates){
			List<String> roles = new ArrayList<String>();
			for(Role role : group.getRoles()){
				roles.add(role.getId());
			}
			realmMap.put(group.getId(), roles);
		}
		return realmMap;
	}

	/**
	 * takes a list representation of the tree and orders it Alphabetically
	 * @param hierarchy
	 */
	private void orderTreeModel(List<List> hierarchy){
		if(hierarchy != null){
			for(List nodeList : hierarchy){
				orderTreeModel((List)nodeList.get(1));
			}
			Collections.sort(hierarchy, new NodeListComparator());
		}
	}

	/**
	 * This is a simple comparator to order the tree nodes alphabetically
	 *
	 */
	private class NodeListComparator implements Comparator<List>{
		public int compare(List o1, List o2) {
			return ((HierarchyNodeSerialized) o1.get(0)).title.compareToIgnoreCase(((HierarchyNodeSerialized) o2.get(0)).title);
		}
	}

	private List<List> getTreeListForUser(String userId, boolean addDirectChildren, boolean cascade, Set<HierarchyNodeSerialized> nodes){
		List<List> l1 = new ArrayList<List>();
		List<List> currentLevel = l1;

		for(HierarchyNodeSerialized node : nodes){
			for(String parentId : node.parentNodeIds){
				HierarchyNodeSerialized parentNode = getCachedNode(parentId);

				if(!hasNode(parentNode, currentLevel)){
					List newNode = new ArrayList();
					newNode.add(parentNode);
					newNode.add(new ArrayList());
					currentLevel.add(newNode);
				}
				currentLevel = getChildrenForNode(parentNode.id, currentLevel);
				if(addDirectChildren){
					for(List nodeList : getDirectChildren(parentNode)){
						if(!hasNode((HierarchyNodeSerialized) nodeList.get(0), currentLevel)){
							currentLevel.add(nodeList);
						}
					}
				}
			}
			if(!hasNode(node, currentLevel)){
				List child = new ArrayList();
				child.add(node);
				child.add(new ArrayList());
				currentLevel.add(child);
			}
			if(cascade){
				//we need to grab all children (children of children, ect) for this node since this an access node
				getCascadingChildren(node, getChildrenForNode(node.id, currentLevel));
			}

			accessNodes.add(node.id);

			currentLevel = l1;
		}
		if(l1.isEmpty() && addDirectChildren){
			//since we want direct children, include the root's direct children (when the node model is empty)
			HierarchyNodeSerialized root = getRootNode();
			if(root != null && root.id != null && !"".equals(root.id)){
				List child = new ArrayList();
				child.add(root);
				child.add(getDirectChildren(root));
				l1.add(child);
			}
		}

		return l1;
	}


	/**
	 * Checks nodeCache for node with given id.  If not found,
	 * looks up the node in the db and saves it in the cache
	 * 
	 * @param id
	 * @return
	 */
	private HierarchyNodeSerialized getCachedNode(String id){
		HierarchyNodeSerialized node = nodeCache.get(id);
		if(node == null){
			node = getNode(id);
			nodeCache.put(id, node);
		}
		return node;
	}

	/**
	 * returns the children for this node
	 * 
	 * @param id
	 * @param level
	 * @return
	 */
	private List<List> getChildrenForNode(String id, List<List> level){
		for(List nodeList : level){
			HierarchyNodeSerialized n = (HierarchyNodeSerialized) nodeList.get(0);
			if(n.id.equals(id)){
				return (List<List>) nodeList.get(1);
			}
		}
		return null;
	}

	/**
	 * returns direct children for the parent.  Children will have empty lists.
	 * 
	 * @param parent
	 * @return
	 */
	private List<List> getDirectChildren(HierarchyNodeSerialized parent){
		List<List>returnList = new ArrayList<List>();

		if(parent != null){
			Set<String> parentChildren = parent.directChildNodeIds;
			for(String childId : parentChildren){
				List child = new ArrayList();
				child.add(getCachedNode(childId));
				child.add(new ArrayList());
				returnList.add(child);
			}
		}
		return returnList;
	}

	/**
	 * Finds all children of chilren and returns the hierarchy
	 * 
	 * @param parent
	 * @param children
	 * @return
	 */
	private List<List> getCascadingChildren(HierarchyNodeSerialized parent, List<List> children){
		Set<String> parentChildren = parent.directChildNodeIds;
		for(String childId : parentChildren){
			HierarchyNodeSerialized childNode = getCachedNode(childId);

			List childMap = getChildrenForNode(childNode.id, children);
			if(childMap == null){
				childMap = new ArrayList();
			}

			childMap = getCascadingChildren(childNode, childMap);
			if(!hasNode(childNode, children)){
				List childList = new ArrayList();
				childList.add(childNode);
				childList.add(childMap);
				children.add(childList);
			}
		}

		return children;
	}

	/**
	 * checks if the node exist in the list
	 * 
	 * @param node
	 * @param level
	 * @return
	 */
	private boolean hasNode(HierarchyNodeSerialized node, List<List> level){
		for(List nodeList : level){
			HierarchyNodeSerialized n = (HierarchyNodeSerialized) nodeList.get(0);
			if(n.id.equals(node.id)){
				return true;
			}
		}
		return false;
	}

	/**
	 * Adds children node to a node that hasn't had it's children populated.  This is used to increase the efficiency
	 * of the tree so you can create the structure on the fly with ajax
	 * 
	 * @param node
	 * @param tree
	 * @param target
	 * @param userId
	 * @return
	 */
	public boolean addChildrenNodes(Object node, String userId){
		boolean anyAdded = false;
		DefaultMutableTreeNode parentNode = (DefaultMutableTreeNode) node;
		NodeModel nodeModel = (NodeModel) ((DefaultMutableTreeNode) node).getUserObject();
		if(nodeModel.getNode() != null){
			Map<String, List<String>> realmMap = getRealmMap();
			List<List> childrenNodes = getDirectChildren(nodeModel.getNode());
			Collections.sort(childrenNodes, new NodeListComparator());
			for(List childList : childrenNodes){
				boolean newlyAdded = addChildNodeToTree((HierarchyNodeSerialized) childList.get(0), parentNode, realmMap, userId);
				anyAdded = anyAdded || newlyAdded;
			}
		}
		return anyAdded;
	}

	/**
	 * This is a helper function for addChildrenNodes.  It will add the child nodes to the parent node and create the NodeModel.
	 * 
	 * @param childNode
	 * @param parentNode
	 * @param realmMap
	 * @param userId
	 * @return
	 */
	private boolean addChildNodeToTree(HierarchyNodeSerialized childNode, DefaultMutableTreeNode parentNode, Map<String, List<String>> realmMap, String userId){
		boolean added = false;
		if(!doesChildExist(childNode.id, parentNode)){
			String realm = "";
			String role = "";
			if(accessNodes.contains(childNode.id)){
				String[] realmRole = getAccessRealmRole(userId, childNode.id);
				realm = realmRole[0];
				role = realmRole[1];
			}
			DefaultMutableTreeNode child = new DelegatedAccessMutableTreeNode();
			child.setUserObject(new NodeModel(childNode.id, childNode,
					accessNodes.contains(childNode.id), realmMap,
					realm, role,
					((NodeModel) parentNode.getUserObject()),
					getRestrictedToolSerializedList(userId,
							childNode.id)));
			parentNode.add(child);
			added = true;
		}
		return added;
	}

	/**
	 * Determines if the child exists in the tree structure.  This is a helper function for addChildNodeToTree to ensure 
	 * the duplicate child nodes aren't added
	 * 
	 * @param childNodeId
	 * @param parentNode
	 * @return
	 */
	private boolean doesChildExist(String childNodeId, DefaultMutableTreeNode parentNode){
		boolean exists = false;

		for(int i = 0; i < parentNode.getChildCount(); i++){
			DefaultMutableTreeNode child = (DefaultMutableTreeNode) parentNode.getChildAt(i);
			if(childNodeId.equals(((NodeModel) child.getUserObject()).getNodeId())){
				exists = true;
				break;
			}
		}

		return exists;
	}

}
