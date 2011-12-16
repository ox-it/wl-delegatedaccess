package org.sakaiproject.delegatedaccess.logic;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import lombok.Getter;
import lombok.Setter;

import org.apache.log4j.Logger;
import org.sakaiproject.authz.api.AuthzGroup;
import org.sakaiproject.authz.api.AuthzGroupService;
import org.sakaiproject.authz.api.AuthzPermissionException;
import org.sakaiproject.authz.api.GroupNotDefinedException;
import org.sakaiproject.authz.api.Role;
import org.sakaiproject.authz.api.RoleAlreadyDefinedException;
import org.sakaiproject.authz.api.SecurityAdvisor;
import org.sakaiproject.authz.api.SecurityService;
import org.sakaiproject.component.api.ServerConfigurationService;
import org.sakaiproject.delegatedaccess.util.DelegatedAccessConstants;
import org.sakaiproject.entity.api.EntityManager;
import org.sakaiproject.entity.api.Reference;
import org.sakaiproject.event.api.EventTrackingService;
import org.sakaiproject.exception.IdUnusedException;
import org.sakaiproject.exception.PermissionException;
import org.sakaiproject.javax.PagingPosition;
import org.sakaiproject.site.api.Site;
import org.sakaiproject.site.api.SiteService;
import org.sakaiproject.site.api.SiteService.SelectionType;
import org.sakaiproject.site.api.SiteService.SortType;
import org.sakaiproject.tool.api.Session;
import org.sakaiproject.tool.api.SessionManager;
import org.sakaiproject.tool.api.Tool;
import org.sakaiproject.tool.api.ToolManager;
import org.sakaiproject.user.api.User;
import org.sakaiproject.user.api.UserDirectoryService;

/**
 * Implementation of our SakaiProxy API
 * 
 * @author Bryan Holladay (holladay@longsight.com)
 *
 */
public class SakaiProxyImpl implements SakaiProxy {

	private static final Logger log = Logger.getLogger(SakaiProxyImpl.class);
	@Getter @Setter
	private AuthzGroupService authzGroupService;

	@Getter @Setter
	private SessionManager sessionManager;

	@Getter @Setter
	private UserDirectoryService userDirectoryService;

	@Getter @Setter
	private SecurityService securityService;

	@Getter @Setter
	private EventTrackingService eventTrackingService;

	@Getter @Setter
	private ServerConfigurationService serverConfigurationService;

	@Getter @Setter
	private SiteService siteService;

	@Getter @Setter
	private EntityManager entityManager;

	@Getter @Setter
	private ToolManager toolManager;

	/**
	 * init - perform any actions required here for when this bean starts up
	 */
	public void init() {
	}


	/**
	 * {@inheritDoc}
	 */
	public String getCurrentUserId() {
		return sessionManager.getCurrentSessionUserId();
	}


	/**
	 * {@inheritDoc}
	 */
	public boolean isSuperUser() {
		return securityService.isSuperUser();
	}

	/**
	 * {@inheritDoc}
	 */
	public Session getCurrentSession(){
		return sessionManager.getCurrentSession();
	}

	/**
	 * {@inheritDoc}
	 */
	public List<Site> getAllSites(){
		return siteService.getSites(SelectionType.ANY, null, null, null, null, null);
	}


	/**
	 * {@inheritDoc}
	 */
	public void postEvent(String event,String reference,boolean modify) {
		eventTrackingService.post(eventTrackingService.newEvent(event,reference,modify));
	}

	public Set<Tool> getAllTools(){
		Set<Tool> toolSet = new HashSet<Tool>();
		String[] toolsList = null;
		String siteType = serverConfigurationService.getString(DelegatedAccessConstants.PROP_TOOL_LIST_TEMPLATE);
		if(siteType != null && !"".equals(siteType)){
			toolSet = toolManager.findTools(new HashSet<String>(Arrays.asList(siteType)), null);
		}
		if(toolSet.size() == 0){
			toolsList = serverConfigurationService.getStrings(DelegatedAccessConstants.PROP_TOOL_LIST);
			if(toolsList != null && toolsList.length > 0){
				for(String toolId : toolsList){
					Tool tool = toolManager.getTool(toolId);
					if(tool != null){
						toolSet.add(tool);
					}
				}
			}
		}

		if(toolSet.size() == 0){
			return toolManager.findTools(new HashSet<String>(), null);
		}else{
			return toolSet;
		}
	}

	/**
	 * {@inheritDoc}
	 */
	public String getSkinRepoProperty(){
		return serverConfigurationService.getString("skin.repo");
	}

	/**
	 * {@inheritDoc}
	 */
	public String getToolSkinCSS(String skinRepo){

		String skin = siteService.findTool(sessionManager.getCurrentToolSession().getPlacementId()).getSkin();			

		if(skin == null) {
			skin = serverConfigurationService.getString("skin.default");
		}

		return skinRepo + "/" + skin + "/tool.css";
	}

	/**
	 * {@inheritDoc}
	 */
	public List<User> searchUsers(String search, int first, int last) {
		List<User> returnList = new ArrayList<User>();
		returnList.addAll(userDirectoryService.searchExternalUsers(search, first, last));
		returnList.addAll(userDirectoryService.searchUsers(search, first, last));
		return returnList;
	}

	/**
	 * {@inheritDoc}
	 */
	public Site getSiteByRef(String siteRef){
		Site site = null;
		Reference r = entityManager.newReference(siteRef);
		if(r.getType().equals(SiteService.APPLICATION_ID)){
			try {
				site = siteService.getSite(r.getId());
			} catch (IdUnusedException e) {
				log.error(e);
			}
		}
		return site;
	}

	/**
	 * {@inheritDoc}
	 */
	public void saveSite(Site site){
		try {
			siteService.save(site);
		} catch (IdUnusedException e) {
			log.error(e);
		} catch (PermissionException e) {
			log.error(e);
		}
	}

	/**
	 * {@inheritDoc}
	 */
	public String getRootName(){
		return serverConfigurationService.getString(
				DelegatedAccessConstants.HIERARCHY_ROOT_TITLE_PROPERTY,
				serverConfigurationService.getString("ui.service",
						DelegatedAccessConstants.HIERARCHY_ROOT_TITLE_DEFAULT));
	}

	/**
	 * {@inheritDoc}
	 */
	public String[] getServerConfigurationStrings(String property){
		return serverConfigurationService.getStrings(property);
	}

	/**
	 * {@inheritDoc}
	 */
	public Map<String, List<String>> getSiteTemplates(){
		Map<String, List<String>> returnList = new HashMap<String, List<String>>();
		for(AuthzGroup group : authzGroupService.getAuthzGroups("!site.", new PagingPosition(1, DelegatedAccessConstants.SEARCH_RESULTS_MAX))){
			returnList.put(group.getId(), getFilteredRoles(group, null));
		}
		return returnList;
	}

	public Map<String, List<String>> getShoppingRealmOptions(){
		String[] authzGroups = serverConfigurationService.getStrings("delegatedaccess.realmoptions.shopping");
		if(authzGroups != null && authzGroups.length != 0){
			String[] filterRoles = serverConfigurationService.getStrings("delegatedaccess.roleoptions.shopping");
			return getGroupsById(authzGroups, filterRoles);
		}else{
			return getSiteTemplates();
		}
	}
	
	public Map<String, List<String>> getDelegatedAccessRealmOptions(){
		String[] authzGroups = serverConfigurationService.getStrings("delegatedaccess.realmoptions.delegatedaccess");
		if(authzGroups != null && authzGroups.length != 0){
			String[] filterRoles = serverConfigurationService.getStrings("delegatedaccess.roleoptions.delegatedaccess");
			return getGroupsById(authzGroups, filterRoles);
		}else{
			return getSiteTemplates();
		}
	}
	
	private Map<String, List<String>> getGroupsById(String[] groups, String[] filterRoles){
		Map<String, List<String>> returnList = new HashMap<String, List<String>>();
		for(int i = 0; i < groups.length; i++){
			try {
				AuthzGroup group = authzGroupService.getAuthzGroup(groups[i]);
				if(group != null){
					returnList.put(group.getId(), getFilteredRoles(group, filterRoles));
				}
			} catch (GroupNotDefinedException e) {
				log.error(e);
			}
		}
		return returnList;
	}
	/**
	 * this will remove any role that isn't in the list of roles passed in.
	 * If roles is null or empty, it will not filter any roles
	 * 
	 * @param groups
	 * @param roles
	 * @return
	 */
	private List<String> getFilteredRoles(AuthzGroup group, String[] filterRoles){
		List<String> returnRoles = new ArrayList<String>();
		if(group != null){
			for(Role role : group.getRoles()){
				if(filterRoles != null){
					for(int i = 0; i < filterRoles.length; i++){
						if(role.getId().equals(filterRoles[i])){
							returnRoles.add(role.getId());
							break;
						}
					}
				}else{
					returnRoles.add(role.getId());
				}
			}
		}
		return returnRoles;
	}
	
	/**
	 * {@inheritDoc}
	 */
	public void refreshCurrentUserAuthz(){
		authzGroupService.refreshUser(getCurrentUserId());
	}

	public Set<String> getUserMembershipForCurrentUser(){
		Set<String> returnSet = new HashSet<String>();
		for(Site site: siteService.getSites(SelectionType.ACCESS, null, null, null, SortType.NONE, null)){
			returnSet.add(site.getReference());
		}
		return returnSet;
	}
	
	public List<Site> getSites(SelectionType type, String search, Map<String, String> propsMap){
		return siteService.getSites(type, null, search, propsMap, SortType.NONE, null);
	}
	
	public boolean isShoppingTool(){
		return "sakai.delegatedaccess.shopping".equals(toolManager.getCurrentPlacement().getToolId());
	}
	
	/**
	 * {@inheritDoc}
	 */
	public AuthzGroup getAuthzGroup(String siteId){
		AuthzGroup group = null;
		try {
			group = authzGroupService.getAuthzGroup(siteId);
		} catch (GroupNotDefinedException e) {
			log.error(e);
		}
		return group;
	}

	/**
	 * {@inheritDoc}
	 */
	public void removeRoleFromAuthzGroup(AuthzGroup group, Role role) {
		// Cheating to become admin in order to modify authz groups
		SecurityAdvisor yesMan = new SecurityAdvisor() {
			public SecurityAdvice isAllowed(String userId, String function, String reference) {
				return SecurityAdvice.ALLOWED;
			}
		};
		try {
			group.removeRole(role.getId());
			securityService.pushAdvisor(yesMan);
			authzGroupService.save(group);
		} catch (AuthzPermissionException e) {
			log.error(e);
		} catch (GroupNotDefinedException e) {
			log.error(e);
		} finally {
			securityService.pushAdvisor(yesMan);
		}
	}

	/**
	 * {@inheritDoc}
	 */
	public void copyNewRole(String siteRef, String copyRealm, String copyRole, String newRole){
		// Cheating to become admin in order to modify authz groups
		SecurityAdvisor yesMan = new SecurityAdvisor() {
			public SecurityAdvice isAllowed(String userId, String function, String reference) {
				return SecurityAdvice.ALLOWED;
			}
		};

		try {
			// become admin
			securityService.pushAdvisor(yesMan);

			// Get the source realm and role
			AuthzGroup sourceGroup = authzGroupService.getAuthzGroup(copyRealm);
			Role copyFromRole = sourceGroup.getRole(copyRole);

			// Copy the role to the dest role 
			AuthzGroup destGroup = authzGroupService.getAuthzGroup(siteRef);
			destGroup.removeRole(newRole);
			authzGroupService.save(destGroup);
			destGroup.addRole(newRole, copyFromRole);
			authzGroupService.save(destGroup);

		} catch (RoleAlreadyDefinedException e) {
			log.error(e); // wtf?
		} catch (GroupNotDefinedException e) {
			log.error(e);
		} catch (AuthzPermissionException e) {
			log.error(e);
		} finally {
			securityService.popAdvisor(yesMan);
		}
	}

	public SecurityAdvisor addSiteUpdateSecurityAdvisor(){
		// Cheating to become admin in order to modify authz groups
		SecurityAdvisor yesMan = new SecurityAdvisor() {
			public SecurityAdvice isAllowed(String userId, String function, String reference) {
				if("site.upd".equals(function))
					return SecurityAdvice.ALLOWED;

				return SecurityAdvice.PASS;
			}
		};
		securityService.pushAdvisor(yesMan);
		return yesMan;
	}

	public void popSecurityAdvisor(SecurityAdvisor advisor){
		securityService.popAdvisor(advisor);
	}
	
	public String getTermField(){
		return serverConfigurationService.getString(DelegatedAccessConstants.PROPERTIES_TERMFIELD, "term_eid");
	}
}