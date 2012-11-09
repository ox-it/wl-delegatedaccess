package org.sakaiproject.delegatedaccess.jobs;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import lombok.Getter;
import lombok.Setter;

import org.apache.log4j.Logger;
import org.sakaiproject.delegatedaccess.dao.DelegatedAccessDao;
import org.sakaiproject.delegatedaccess.logic.ProjectLogic;
import org.sakaiproject.delegatedaccess.logic.SakaiProxy;
import org.sakaiproject.delegatedaccess.util.DelegatedAccessConstants;
import org.sakaiproject.hierarchy.HierarchyService;
import org.sakaiproject.site.api.Site;
import org.sakaiproject.site.api.SitePage;
import org.sakaiproject.api.app.scheduler.ScheduledInvocationCommand;

public class DelegatedAccessAddToolToMyWorkspacesJob implements ScheduledInvocationCommand{
	
	private static final Logger log = Logger.getLogger(DelegatedAccessAddToolToMyWorkspacesJob.class);
	@Getter @Setter
	private DelegatedAccessDao dao;
	@Getter @Setter
	private ProjectLogic projectLogic;
	@Getter @Setter
	private SakaiProxy sakaiProxy;
	
	
	public void execute(String nodeId){
		log.info("DelegatedAccessAddToolToMyWorkspacesJob started");
		long startTime = System.currentTimeMillis();
		
		List<String> userIds = dao.getDelegatedAccessUsers();
		if(userIds != null){
			//convert userIds to workspace site ids by adding a ~ to the front
			List<String> userWorkspaceIds = new ArrayList<String>();
			for(String userId : userIds){
				if(!DelegatedAccessConstants.SHOPPING_PERIOD_USER.equals(userId)
						&& !DelegatedAccessConstants.SITE_HIERARCHY_USER.equals(userId)){
					userWorkspaceIds.add("~" + userId);
				}
			}
			//find which site's already have the DA tool
			List<String> sitesWithDelegatedAccess = dao.getSitesWithDelegatedAccessTool(userWorkspaceIds.toArray(new String[userWorkspaceIds.size()]));
			//filter out the sites that already ahve the DA tool
			for(String siteId : sitesWithDelegatedAccess){
				userWorkspaceIds.remove(siteId);
			}
			//now go through the leftover sites and add the DA tool:
			//user has access but doesn't have the DA tool, we need to add it
			String currentUserId = sakaiProxy.getCurrentUserId();
			try{
				for(String siteId : userWorkspaceIds){
					//trick the session into thinking you are the user who's workspace this is for.  This way,
					//SiteService will create the workspace if its missing
					sakaiProxy.setSessionUserId(siteId.substring(1));
					
					Site workspace = sakaiProxy.getSiteById(siteId);
					if(workspace != null){
						SitePage page = workspace.addPage();
						page.addTool("sakai.delegatedaccess");
						sakaiProxy.saveSite(workspace);
					}
				}
			}catch (Exception e) {
				log.error(e);
			}finally{
				sakaiProxy.setSessionUserId(currentUserId);
			}
		
		}
		projectLogic.updateAddDAMyworkspaceJobStatus("" + new Date().getTime());
		log.info("DelegatedAccessAddToolToMyWorkspacesJob finished in " + (System.currentTimeMillis() - startTime) + " ms");
	}
}
