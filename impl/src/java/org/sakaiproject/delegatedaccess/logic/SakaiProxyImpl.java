package org.sakaiproject.delegatedaccess.logic;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import lombok.Getter;
import lombok.Setter;

import org.apache.log4j.Logger;
import org.sakaiproject.authz.api.AuthzGroup;
import org.sakaiproject.authz.api.AuthzGroupService;
import org.sakaiproject.authz.api.SecurityService;
import org.sakaiproject.component.api.ServerConfigurationService;
import org.sakaiproject.delegatedaccess.util.DelegatedAccessConstants;
import org.sakaiproject.entity.api.EntityManager;
import org.sakaiproject.entity.api.Reference;
import org.sakaiproject.event.api.EventTrackingService;
import org.sakaiproject.exception.IdUnusedException;
import org.sakaiproject.javax.PagingPosition;
import org.sakaiproject.site.api.Site;
import org.sakaiproject.site.api.SiteService;
import org.sakaiproject.site.api.SiteService.SelectionType;
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
		log.info("init");
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
		return toolManager.findTools(null, null);
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

	public Site getSiteById(String siteId){
		Site site = null;
		try {
			site = siteService.getSite(siteId);
		} catch (IdUnusedException e) {
			log.error(e);
		}
		return site;
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
	public List<AuthzGroup> getSiteTemplates(){
		return authzGroupService.getAuthzGroups("!site.", new PagingPosition(1, DelegatedAccessConstants.SEARCH_RESULTS_MAX));
	}

	/**
	 * {@inheritDoc}
	 */
	public void refreshCurrentUserAuthz(){
		authzGroupService.refreshUser(getCurrentUserId());
	}
	
	public Set<String> getUserMembershipForCurrentUser(){
		Set<String> returnSet = new HashSet<String>();
		for(Site site: siteService.getSites(SelectionType.ACCESS, null, null, null, null, null)){
			returnSet.add(site.getReference());
		}
		return returnSet;
	}
}
