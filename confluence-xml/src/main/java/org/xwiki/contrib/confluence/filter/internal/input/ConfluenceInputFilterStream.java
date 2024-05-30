/*
 * See the NOTICE file distributed with this work for additional
 * information regarding copyright ownership.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */
package org.xwiki.contrib.confluence.filter.internal.input;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Provider;

import org.apache.commons.configuration2.ex.ConfigurationException;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.xwiki.component.annotation.Component;
import org.xwiki.component.annotation.InstantiationStrategy;
import org.xwiki.component.descriptor.ComponentInstantiationStrategy;
import org.xwiki.contrib.confluence.filter.PageIdentifier;
import org.xwiki.contrib.confluence.filter.event.ConfluenceFilteredEvent;
import org.xwiki.contrib.confluence.filter.event.ConfluenceFilteringEvent;
import org.xwiki.contrib.confluence.filter.input.ConfluenceInputContext;
import org.xwiki.contrib.confluence.filter.input.ConfluenceInputProperties;
import org.xwiki.contrib.confluence.filter.input.ConfluenceProperties;
import org.xwiki.contrib.confluence.filter.input.ConfluenceXMLPackage;
import org.xwiki.contrib.confluence.filter.input.ContentPermissionType;
import org.xwiki.contrib.confluence.filter.input.SpacePermissionType;
import org.xwiki.contrib.confluence.filter.internal.ConfluenceFilter;
import org.xwiki.contrib.confluence.filter.internal.idrange.ConfluenceIdRangeList;
import org.xwiki.contrib.confluence.parser.confluence.internal.ConfluenceParser;
import org.xwiki.contrib.confluence.parser.xhtml.ConfluenceXHTMLInputProperties;
import org.xwiki.contrib.confluence.parser.xhtml.internal.ConfluenceXHTMLParser;
import org.xwiki.contrib.confluence.parser.xhtml.internal.InternalConfluenceXHTMLInputProperties;
import org.xwiki.filter.FilterEventParameters;
import org.xwiki.filter.FilterException;
import org.xwiki.filter.event.model.WikiAttachmentFilter;
import org.xwiki.filter.event.model.WikiDocumentFilter;
import org.xwiki.filter.event.model.WikiObjectFilter;
import org.xwiki.filter.event.user.GroupFilter;
import org.xwiki.filter.event.user.UserFilter;
import org.xwiki.filter.input.AbstractBeanInputFilterStream;
import org.xwiki.filter.input.BeanInputFilterStream;
import org.xwiki.filter.input.BeanInputFilterStreamFactory;
import org.xwiki.filter.input.InputFilterStreamFactory;
import org.xwiki.filter.input.StringInputSource;
import org.xwiki.job.Job;
import org.xwiki.job.JobContext;
import org.xwiki.job.event.status.CancelableJobStatus;
import org.xwiki.job.event.status.JobProgressManager;
import org.xwiki.job.event.status.JobStatus;
import org.xwiki.model.EntityType;
import org.xwiki.model.reference.EntityReference;
import org.xwiki.model.reference.SpaceReference;
import org.xwiki.observation.ObservationManager;
import org.xwiki.rendering.listener.Listener;
import org.xwiki.rendering.parser.ParseException;
import org.xwiki.rendering.parser.StreamParser;
import org.xwiki.rendering.renderer.PrintRenderer;
import org.xwiki.rendering.renderer.PrintRendererFactory;
import org.xwiki.rendering.renderer.printer.DefaultWikiPrinter;
import org.xwiki.rendering.syntax.Syntax;
import org.xwiki.security.authorization.Right;

/**
 * @version $Id$
 * @since 9.0
 */
@Component
@Named(ConfluenceInputFilterStreamFactory.ROLEHINT)
@InstantiationStrategy(ComponentInstantiationStrategy.PER_LOOKUP)
public class ConfluenceInputFilterStream
    extends AbstractBeanInputFilterStream<ConfluenceInputProperties, ConfluenceFilter>
{
    private static final String CONFLUENCEPAGE_CLASSNAME = "Confluence.Code.ConfluencePageClass";

    private static final String TAGS_CLASSNAME = "XWiki.TagClass";

    private static final String COMMENTS_CLASSNAME = "XWiki.XWikiComments";

    private static final String BLOG_CLASSNAME = "Blog.BlogClass";

    private static final String BLOG_POST_CLASSNAME = "Blog.BlogPostClass";

    private static final String XWIKIRIGHTS_CLASSNAME = "XWiki.XWikiRights";

    private static final String XWIKIGLOBALRIGHTS_CLASSNAME = "XWiki.XWikiGlobalRights";

    private static final String WEB_PREFERENCES = "WebPreferences";

    private static final String FAILED_TO_GET_USER_PROPERTIES = "Failed to get user properties";

    private static final String WEB_HOME = "WebHome";

    private static final String XWIKI_PREFERENCES_CLASS = "XWiki.XWikiPreferences";

    private static final String FAILED_TO_READ_PACKAGE = "Failed to read package";

    @Inject
    @Named(ConfluenceParser.SYNTAX_STRING)
    private StreamParser confluenceWIKIParser;

    @Inject
    @Named(ConfluenceXHTMLParser.SYNTAX_STRING)
    private InputFilterStreamFactory confluenceXHTMLParserFactory;

    @Inject
    private Provider<ConfluenceConverterListener> converterProvider;

    @Inject
    private JobProgressManager progress;

    @Inject
    @Named("xwiki/2.1")
    private PrintRendererFactory xwiki21Factory;

    @Inject
    private ObservationManager observationManager;

    @Inject
    private ConfluenceInputContext context;

    @Inject
    private ConfluenceConverter confluenceConverter;

    @Inject
    private ConfluenceXMLMacroSupport macroSupport;

    @Inject
    private ConfluenceXMLPackage confluencePackage;

    @Inject
    private Logger logger;

    @Inject
    private JobContext jobContext;

    private final Map<String, Integer> macrosIds = new HashMap<>();

    private ConfluenceIdRangeList objectIdRanges;

    private List<Long> nextIdsForObjectIdRanges;

    private int remainingPages = -1;

    private CancelableJobStatus jobStatus;

    private static class MaxPageCountReachedException extends ConfluenceInterruptedException
    {
        private static final long serialVersionUID = 1L;
    }

    @Override
    public void close() throws IOException
    {
        this.properties.getSource().close();
    }

    @Override
    protected void read(Object filter, ConfluenceFilter proxyFilter) throws FilterException
    {
        if (this.context instanceof DefaultConfluenceInputContext) {
            ((DefaultConfluenceInputContext) this.context).set(this.confluencePackage, this.properties);
        }

        try {
            readInternal(filter, proxyFilter);
        } finally {
            if (this.context instanceof DefaultConfluenceInputContext) {
                ((DefaultConfluenceInputContext) this.context).remove();
            }
        }
    }

    /**
     *  @return if the object should be sent and not ignored, given the object id ranges provided in the properties.
     *  It is very important that each object is only checked once.
     */
    private boolean shouldSendObject(Long id) throws FilterException
    {
        if (id == null || this.objectIdRanges == null) {
            // by default, we ignore no objects.
            return true;
        }

        if (this.nextIdsForObjectIdRanges == null || this.nextIdsForObjectIdRanges.isEmpty()) {
            if (this.objectIdRanges.pushId(id)) {
                this.nextIdsForObjectIdRanges = null;
                return true;
            }

            if (this.nextIdsForObjectIdRanges  == null) {
                // we only prepare for the next range id if it has not been already asked.
                prepareNextObjectRangeId();
            }
            return false;
        }

        if (id.equals(this.nextIdsForObjectIdRanges.get(0))) {
            this.nextIdsForObjectIdRanges.remove(0);
            return true;
        }

        return false;
    }

    private int countPages(Map<Long, List<Long>> pagesBySpace, Collection<Long> disabledSpaces)
    {
        int n = 0;
        for (Map.Entry<Long, List<Long>> pagesEntry : pagesBySpace.entrySet()) {
            if (!disabledSpaces.contains(pagesEntry.getKey())) {
                n += pagesEntry.getValue().size();
            }
        }
        return n;
    }

    private void pushLevelProgress(int steps)
    {
        try {
            this.progress.pushLevelProgress(steps, this);
        } catch (Exception e) {
            logger.error("Could not push level progress", e);
        }
    }

    private void popLevelProgress()
    {
        try {
            this.progress.popLevelProgress(this);
        } catch (Exception e) {
            logger.error("Could not pop level progress", e);
        }
    }

    private void beginSpace(EntityReference space, ConfluenceFilter proxyFilter) throws FilterException
    {
        if (space == null || !EntityType.SPACE.equals(space.getType())) {
            return;
        }

        beginSpace(space.getParent(), proxyFilter);
        proxyFilter.beginWikiSpace(space.getName(), FilterEventParameters.EMPTY);
    }

    private void endSpace(EntityReference space, ConfluenceFilter proxyFilter) throws FilterException
    {
        if (space == null || !EntityType.SPACE.equals(space.getType())) {
            return;
        }

        proxyFilter.endWikiSpace(space.getName(), FilterEventParameters.EMPTY);
        endSpace(space.getParent(), proxyFilter);
    }

    private void getJobStatus()
    {
        Job job = this.jobContext.getCurrentJob();
        if (job != null) {
            JobStatus status = job.getStatus();
            if (status instanceof CancelableJobStatus) {
                this.jobStatus = (CancelableJobStatus) status;
            }
        }
    }

    private void readInternal(Object filter, ConfluenceFilter proxyFilter) throws FilterException
    {
        // Prepare package
        boolean restored = false;
        String wd = this.properties.getWorkingDirectory();
        if (StringUtils.isNotEmpty(wd)) {
            restored = this.confluencePackage.restoreState(wd);
        }

        try {
            pushLevelProgress(restored ? 1 : 2);
            if (!restored) {
                this.confluencePackage.read(this.properties.getSource(), wd);
            }
        } catch (Exception e) {
            if (e.getCause() instanceof ConfluenceCanceledException) {
                this.logger.warn("The job was canceled", e);
                closeConfluencePackage();
            } else {
                this.logger.error(FAILED_TO_READ_PACKAGE, e);
                closeConfluencePackage();
                throw new FilterException(FAILED_TO_READ_PACKAGE, e);
            }
            return;
        }

        getJobStatus();

        maybeRemoveArchivedSpaces();

        ConfluenceFilteringEvent filteringEvent = new ConfluenceFilteringEvent();
        this.observationManager.notify(filteringEvent, this, this.confluencePackage);
        if (filteringEvent.isCanceled()) {
            closeConfluencePackage();
            return;
        }

        this.objectIdRanges = this.properties.getObjectIdRanges();
        if (this.objectIdRanges != null) {
            prepareNextObjectRangeId();
        }

        Map<Long, List<Long>> pages = this.confluencePackage.getPages();
        Map<Long, List<Long>> blogPages = this.confluencePackage.getBlogPages();

        // Only count pages if we are going to send them
        boolean willSendPages = this.properties.isContentsEnabled() || this.properties.isRightsEnabled();

        Collection<Long> disabledSpaces = filteringEvent.getDisabledSpaces();
        int pagesCount = willSendPages
            ? (
                (properties.isNonBlogContentEnabled() ? countPages(pages, disabledSpaces) : 0)
                    + (properties.isBlogsEnabled() ? countPages(blogPages, disabledSpaces) : 0)
            )
            : 0;

        this.remainingPages = this.properties.getMaxPageCount();
        if (this.remainingPages != -1) {
            pagesCount = Integer.min(this.remainingPages, pagesCount);
        }

        int progressCount = pagesCount;

        Collection<Long> users = null;
        if (this.properties.isUsersEnabled()) {
            users = this.confluencePackage.getInternalUsers();
            progressCount += users.size();
        }

        Collection<Long> groups = null;
        if (this.properties.isGroupsEnabled()) {
            groups = this.confluencePackage.getGroups();
            progressCount += groups.size();
        }

        pushLevelProgress(progressCount);
        try {
            sendUsersAndGroups(users, groups, proxyFilter);
            if (this.properties.isContentsEnabled() || this.properties.isRightsEnabled()) {
                sendSpaces(filter, proxyFilter, pages, blogPages, disabledSpaces);
            }
        } catch (MaxPageCountReachedException e) {
            logger.info("The maximum of pages to read has been reached.");
        } catch (ConfluenceInterruptedException e) {
            logger.warn("The job was canceled.");
        } finally {
            popLevelProgress();
            observationManager.notify(new ConfluenceFilteredEvent(), this, this.confluencePackage);
            closeConfluencePackage();
            popLevelProgress();
        }
    }

    private void sendSpaces(Object filter, ConfluenceFilter proxyFilter, Map<Long, List<Long>> pages,
        Map<Long, List<Long>> blogPages, Collection<Long> disabledSpaces)
        throws FilterException, ConfluenceInterruptedException
    {
        beginSpace(properties.getRootSpace(), proxyFilter);
        try {
            Set<Long> rootSpaces = new LinkedHashSet<>();
            rootSpaces.addAll(pages.keySet());
            rootSpaces.addAll(blogPages.keySet());
            rootSpaces.removeAll(disabledSpaces);

            for (Long spaceId : rootSpaces) {
                if (spaceId == null) {
                    this.logger.error("A null space has been found. This likely means that there is a bug. Skipping.");
                    continue;
                }
                if (!shouldSendObject(spaceId)) {
                    continue;
                }

                List<Long> regularPageIds = pages.getOrDefault(spaceId, Collections.emptyList());
                List<Long> blogPageIds = blogPages.getOrDefault(spaceId, Collections.emptyList());
                if (!regularPageIds.isEmpty() || !blogPageIds.isEmpty()) {
                    sendConfluenceRootSpace(spaceId, filter, proxyFilter, blogPageIds);
                }
            }
        } finally {
            endSpace(properties.getRootSpace(), proxyFilter);
        }
    }

    private void prepareNextObjectRangeId() throws FilterException
    {
        Long nextIdForObjectIdRanges = this.objectIdRanges.getNextId();
        if (nextIdForObjectIdRanges != null) {
            try {
                this.nextIdsForObjectIdRanges = this.confluencePackage.getAncestors(nextIdForObjectIdRanges);
            } catch (ConfigurationException e) {
                throw new FilterException(e);
            }
        }
    }

    private void maybeRemoveArchivedSpaces() throws FilterException
    {
        // Yes, this is a bit hacky, I know. It would be better to not even create objects related to spaces that should
        // not be there. This is harder to do. If you find a cleaner way, don't hesitate do change this.
        if (!properties.isArchivedSpacesEnabled()) {
            try {
                for (Iterator<Long> it = confluencePackage.getPages().keySet().iterator(); it.hasNext();) {
                    Long spaceId = it.next();
                    if (spaceId != null && confluencePackage.isSpaceArchived(spaceId)) {
                        confluencePackage.getBlogPages().remove(spaceId);
                        confluencePackage.getSpacesByKey().remove(confluencePackage.getSpaceKey(spaceId));
                        it.remove();
                    }
                }
            } catch (ConfigurationException e) {
                throw new FilterException("Failed to determine if the space is archived", e);
            }
        }
    }

    private void sendConfluenceRootSpace(Long spaceId, Object filter, ConfluenceFilter proxyFilter,
        List<Long> blogPages) throws FilterException, ConfluenceInterruptedException
    {
        ConfluenceProperties spaceProperties;
        try {
            spaceProperties = this.confluencePackage.getSpaceProperties(spaceId);
        } catch (ConfigurationException e) {
            throw new FilterException("Failed to get space properties", e);
        }

        if (spaceProperties == null) {
            this.logger.error("Could not get the properties of space id=[{}]. Skipping.", spaceId);
            return;
        }
        String spaceKey = confluenceConverter.toEntityName(ConfluenceXMLPackage.getSpaceKey(spaceProperties));
        if (StringUtils.isEmpty(spaceKey)) {
            this.logger.error("Could not determine the key of space id=[{}]. Skipping.", spaceId);
            return;
        }
        ((DefaultConfluenceInputContext) this.context).setCurrentSpace(spaceKey);

        FilterEventParameters spaceParameters = new FilterEventParameters();

        if (this.properties.isVerbose()) {
            this.logger.info("Sending Confluence space [{}], id=[{}]", spaceKey, spaceId);
        }

        // > WikiSpace
        proxyFilter.beginWikiSpace(spaceKey, spaceParameters);
        try {
            Collection<ConfluenceRight> inheritedRights = null;
            ConfluenceProperties homePageProperties = null;

            try {
                if (this.properties.isContentsEnabled() || this.properties.isRightsEnabled()) {
                    Long homePageId = confluencePackage.getHomePage(spaceId);
                    if (homePageId != null) {
                        inheritedRights = sendPage(homePageId, spaceKey, false, filter, proxyFilter);
                        homePageProperties = getPageProperties(homePageId);
                    }

                    sendPages(spaceKey, false, confluencePackage.getOrphans(spaceId), filter, proxyFilter);
                    sendBlogs(spaceKey, blogPages, filter, proxyFilter);
                }
            } catch (ConfluenceInterruptedException e) {
                // Even if we reached the maximum page count, we want to send the space rights.
                if (this.properties.isRightsEnabled()) {
                    sendSpaceRights(proxyFilter, spaceProperties, spaceKey, spaceId, inheritedRights,
                        homePageProperties);
                }
                throw e;
            }
            if (this.properties.isRightsEnabled()) {
                sendSpaceRights(proxyFilter, spaceProperties, spaceKey, spaceId, inheritedRights, homePageProperties);
            }
        } finally {
            // < WikiSpace
            proxyFilter.endWikiSpace(spaceKey, spaceParameters);
            if (this.properties.isVerbose()) {
                this.logger.info("Finished sending Confluence space [{}], id=[{}]", spaceKey, spaceId);
            }
        }
    }

    private void checkCanceled() throws ConfluenceCanceledException
    {
        if (jobStatus != null && jobStatus.isCanceled()) {
            throw new ConfluenceCanceledException();
        }
    }

    private Collection<ConfluenceRight> sendPage(long pageId, String spaceKey, boolean blog, Object filter,
        ConfluenceFilter proxyFilter) throws ConfluenceInterruptedException
    {
        if (this.remainingPages == 0) {
            throw new MaxPageCountReachedException();
        }

        checkCanceled();

        Collection<ConfluenceRight> inheritedRights = null;

        if (this.properties.isIncluded(pageId)) {
            ((DefaultConfluenceInputContext) this.context).setCurrentPage(pageId);
            try {
                inheritedRights = readPage(pageId, spaceKey, blog, filter, proxyFilter);
            } catch (MaxPageCountReachedException e) {
                // ignore
            } catch (ConfluenceCanceledException e) {
                throw e;
            } catch (Exception e) {
                logger.error("Failed to filter the page with id [{}]", createPageIdentifier(pageId, spaceKey), e);
            }
        }

        return inheritedRights;
    }

    private void sendBlogs(String spaceKey, List<Long> blogPages, Object filter, ConfluenceFilter proxyFilter)
        throws FilterException, ConfluenceInterruptedException
    {
        if (!this.properties.isBlogsEnabled() || blogPages == null || blogPages.isEmpty()) {
            return;
        }

        // Blog space
        String blogSpaceKey = confluenceConverter.toEntityName(this.properties.getBlogSpaceName());

        // > WikiSpace
        proxyFilter.beginWikiSpace(blogSpaceKey, FilterEventParameters.EMPTY);
        try {
            // Blog Descriptor page
            addBlogDescriptorPage(proxyFilter);

            // Blog post pages
            sendPages(spaceKey, true, blogPages, filter, proxyFilter);
        } finally {
            // < WikiSpace
            proxyFilter.endWikiSpace(blogSpaceKey, FilterEventParameters.EMPTY);
        }
    }

    private void sendPages(String spaceKey, boolean blog, List<Long> pages, Object filter, ConfluenceFilter proxyFilter)
        throws ConfluenceInterruptedException
    {
        for (Long pageId : pages) {
            sendPage(pageId, spaceKey, blog, filter, proxyFilter);
        }
    }

    private void sendSpaceRights(ConfluenceFilter proxyFilter, ConfluenceProperties spaceProperties, String spaceKey,
        long spaceId, Collection<ConfluenceRight> inheritedRights, ConfluenceProperties homePageProperties)
        throws FilterException
    {
        Collection<Object> spacePermissions = spaceProperties.getList(ConfluenceXMLPackage.KEY_SPACE_PERMISSIONS);
        if (spacePermissions.isEmpty()) {
            return;
        }

        FilterEventParameters webPreferencesParameters = null;

        try {
            // This lets us avoid duplicate XWiki right objects. For instance, REMOVEPAGE and REMOVEBLOG are both
            // mapped to DELETE, and EDITPAGE and EDITBLOG are both mapped to EDIT. In each of these cases,
            // if both rights are set, we need to deduplicate.
            Set<String> addedRights = new HashSet<>();

            for (Object spacePermissionObject : spacePermissions) {
                Long spacePermissionId = toLong(spacePermissionObject);
                if (spacePermissionId == null) {
                    logger.warn("Space permission id is null for the space [{}]", spaceKey);
                    continue;
                }

                if (!shouldSendObject(spacePermissionId)) {
                    continue;
                }

                ConfluenceProperties spacePermissionProperties;
                try {
                    spacePermissionProperties = this.confluencePackage.getSpacePermissionProperties(spaceId,
                        spacePermissionId);
                } catch (ConfigurationException e) {
                    logger.error("Failed to get space permission properties [{}] for the space [{}]",
                        spacePermissionId, spaceKey, e);
                    continue;
                }

                ConfluenceRight confluenceRight = getConfluenceRightData(spacePermissionProperties);
                if (confluenceRight == null) {
                    continue;
                }

                SpacePermissionType type;
                try {
                    type = SpacePermissionType.valueOf(confluenceRight.type);
                } catch (IllegalArgumentException e) {
                    logger.warn("Failed to understand space permission type [{}] for the space [{}], "
                            + "permission id [{}].", confluenceRight.type, spaceKey, spacePermissionId);
                    continue;
                }

                Right right = null;
                switch (type) {
                    case EXPORTSPACE:
                    case EXPORTPAGE:
                    case REMOVEMAIL:
                    case REMOVEOWNCONTENT:
                    case CREATEATTACHMENT:
                    case REMOVEATTACHMENT:
                    case REMOVECOMMENT:
                    case PROFILEATTACHMENTS:
                    case UPDATEUSERSTATUS:
                    case ARCHIVEPAGE:
                    case USECONFLUENCE:
                        // These rights are irrelevant in XWiki or can't be represented as-is.
                        // EDITBLOG and REMOVEBLOG can be implemented when migrating blogs is supported.
                        continue;
                    case ADMINISTRATECONFLUENCE:
                    case SYSTEMADMINISTRATOR:
                    case SETPAGEPERMISSIONS:
                    case SETSPACEPERMISSIONS:
                        right = Right.ADMIN;
                        break;
                    case VIEWSPACE:
                        right = Right.VIEW;
                        break;
                    case EDITSPACE:
                    case EDITBLOG:
                        right = Right.EDIT;
                        break;
                    case CREATESPACE:
                    case PERSONALSPACE:
                        break;
                    case REMOVEBLOG:
                    case REMOVEPAGE:
                        right = Right.DELETE;
                        break;
                    case COMMENT:
                        right = Right.COMMENT;
                        break;
                    default:
                        // nothing
                }

                if (right == null) {
                    this.logger.warn("Unknown space permission right type [{}].", right);
                    continue;
                }


                String group = confluenceRight.group;
                if (right != null && group != null && !group.isEmpty()) {
                    String groupRightString = "g:" + group + ":" + right;
                    if (addedRights.contains(groupRightString)) {
                        group = "";
                    } else {
                        addedRights.add(groupRightString);
                    }
                } else {
                    group = "";
                }

                String users = confluenceRight.users;
                if (right != null && users != null && !users.isEmpty()) {
                    String userRightString = "u:" + users + ":" + right;
                    if (addedRights.contains(userRightString)) {
                        users = "";
                    } else {
                        addedRights.add(userRightString);
                    }
                } else {
                    users = "";
                }

                if (right != null && !(users.isEmpty() && group.isEmpty())) {
                    if (webPreferencesParameters == null) {
                        webPreferencesParameters = beginWebPreferences(proxyFilter);
                    }
                    if (webPreferencesParameters != null) {
                        sendRight(proxyFilter, group, right, users, true);
                    }
                }
            }

            if (inheritedRights != null) {
                for (ConfluenceRight confluenceRight : inheritedRights) {
                    sendInheritedPageRight(homePageProperties, proxyFilter, confluenceRight);
                }
            }
        } finally {
            if (webPreferencesParameters != null) {
                proxyFilter.endWikiDocument(WEB_PREFERENCES, webPreferencesParameters);
            }
        }
    }

    private FilterEventParameters beginWebPreferences(ConfluenceFilter proxyFilter) throws FilterException
    {
        FilterEventParameters webPreferencesParameters = new FilterEventParameters();
        webPreferencesParameters.put(WikiDocumentFilter.PARAMETER_HIDDEN, true);
        proxyFilter.beginWikiDocument(WEB_PREFERENCES, webPreferencesParameters);
        try {
            FilterEventParameters prefParameters = new FilterEventParameters();
            prefParameters.put(WikiObjectFilter.PARAMETER_CLASS_REFERENCE, XWIKI_PREFERENCES_CLASS);
            proxyFilter.beginWikiObject(XWIKI_PREFERENCES_CLASS, prefParameters);
            proxyFilter.endWikiObject(XWIKI_PREFERENCES_CLASS, prefParameters);
            return webPreferencesParameters;
        } catch (FilterException e) {
            proxyFilter.endWikiDocument(WEB_PREFERENCES, webPreferencesParameters);
            throw e;
        }
    }

    private ConfluenceRight getConfluenceRightData(ConfluenceProperties permProperties)
        throws FilterException
    {
        String type = permProperties.getString(ConfluenceXMLPackage.KEY_PERMISSION_TYPE, "");
        String groupStr = permProperties.getString(ConfluenceXMLPackage.KEY_SPACEPERMISSION_GROUP, null);
        if (groupStr == null || groupStr.isEmpty()) {
            groupStr = permProperties.getString(ConfluenceXMLPackage.KEY_CONTENTPERMISSION_GROUP, null);
        }
        String group = (groupStr == null || groupStr.isEmpty())
            ? ""
            : (confluenceConverter.toGroupReference(groupStr));

        String users = "";

        String allUsersSubject = permProperties.getString(ConfluenceXMLPackage.KEY_PERMISSION_ALLUSERSSUBJECT, null);
        if ("anonymous-users".equals(allUsersSubject)) {
            users = "XWiki.XWikiGuest";
        }

        String userName = permProperties.getString(ConfluenceXMLPackage.KEY_SPACEPERMISSION_USERNAME, null);
        if (userName == null || userName.isEmpty()) {
            String userSubjectStr = permProperties.getString(ConfluenceXMLPackage.KEY_PERMISSION_USERSUBJECT, null);
            if (userSubjectStr != null && !userSubjectStr.isEmpty()) {
                ConfluenceProperties userProperties;
                try {
                    userProperties = confluencePackage.getUserImplProperties(userSubjectStr);
                    if (userProperties != null) {
                        userName = userProperties.getString(ConfluenceXMLPackage.KEY_USER_NAME, userSubjectStr);
                    }
                } catch (ConfigurationException e) {
                    throw new FilterException(FAILED_TO_GET_USER_PROPERTIES, e);
                }
            }
        }

        if (userName != null && !userName.isEmpty()) {
            users = (users.isEmpty() ? "" : users + ",") + confluenceConverter.toUserReference(userName);
        }

        return new ConfluenceRight(type, group, users);
    }

    private static class ConfluenceRight
    {
        public final String type;
        public final String group;
        public final String users;

        ConfluenceRight(String type, String group, String users)
        {
            this.type = type;
            this.group = group;
            this.users = users;
        }
    }

    private void sendInheritedPageRight(ConfluenceProperties pageProperties, ConfluenceFilter proxyFilter,
        ConfluenceRight confluenceRight) throws FilterException
    {
        if (confluenceRight.users.isEmpty() && confluenceRight.group.isEmpty()) {
            return;
        }

        ContentPermissionType type = getContentPermissionType(pageProperties, confluenceRight, null);
        if (type == null) {
            return;
        }
        Right right;
        switch (type) {
            case VIEW:
                right = Right.VIEW;
                break;
            case EDIT:
                right = Right.EDIT;
                break;
            default:
                return;
        }
        sendRight(proxyFilter, confluenceRight.group, right, confluenceRight.users, true);
    }

    private static void sendRight(ConfluenceFilter proxyFilter, String group, Right right, String users, boolean global)
        throws FilterException
    {
        FilterEventParameters rightParameters = new FilterEventParameters();
        // Page report object
        String rightClassName = global ? XWIKIGLOBALRIGHTS_CLASSNAME : XWIKIRIGHTS_CLASSNAME;
        rightParameters.put(WikiObjectFilter.PARAMETER_CLASS_REFERENCE, rightClassName);
        proxyFilter.beginWikiObject(rightClassName, rightParameters);
        try {
            proxyFilter.onWikiObjectProperty("allow", "1", FilterEventParameters.EMPTY);
            proxyFilter.onWikiObjectProperty("groups", group, FilterEventParameters.EMPTY);
            proxyFilter.onWikiObjectProperty("levels", right.getName(), FilterEventParameters.EMPTY);
            proxyFilter.onWikiObjectProperty("users", users, FilterEventParameters.EMPTY);
        } finally {
            proxyFilter.endWikiObject(rightClassName, rightParameters);
        }
    }

    private PageIdentifier createPageIdentifier(Long pageId, String spaceKey)
    {
        PageIdentifier page = new PageIdentifier(pageId);
        page.setSpaceTitle(spaceKey);
        try {
            ConfluenceProperties pageProperties = getPageProperties(pageId);
            if (pageProperties != null) {
                String documentName;
                if (pageProperties.containsKey(ConfluenceXMLPackage.KEY_PAGE_HOMEPAGE)) {
                    documentName = WEB_HOME;
                } else {
                    documentName = pageProperties.getString(ConfluenceXMLPackage.KEY_PAGE_TITLE);
                }
                page.setPageTitle(documentName);
                page.setPageRevision(pageProperties.getString(ConfluenceXMLPackage.KEY_PAGE_REVISION));
                if (pageProperties.containsKey(ConfluenceXMLPackage.KEY_PAGE_PARENT)) {
                    Long parentId = pageProperties.getLong(ConfluenceXMLPackage.KEY_PAGE_PARENT);
                    ConfluenceProperties parentPageProperties = getPageProperties(parentId);
                    if (parentPageProperties != null) {
                        page.setParentTitle(parentPageProperties.getString(ConfluenceXMLPackage.KEY_PAGE_TITLE));
                    }
                }
            }
        } catch (FilterException ignored) {
            // ignore
        }
        return page;
    }

    private PageIdentifier createPageIdentifier(ConfluenceProperties pageProperties)
    {
        Long pageId = pageProperties.getLong("id");
        Long spaceId = pageProperties.getLong(ConfluenceXMLPackage.KEY_PAGE_SPACE, null);
        if (spaceId == null) {
            return null;
        }
        String spaceKey;
        try {
            spaceKey = confluencePackage.getSpaceKey(spaceId);
        } catch (ConfigurationException e) {
            this.logger.error("Configuration error while creating page identifier for page [{}]", pageId, e);
            spaceKey = null;
        }
        return createPageIdentifier(pageId, spaceKey);
    }

    private void closeConfluencePackage() throws FilterException
    {
        if ("NO".equals(this.properties.getCleanup())) {
            return;
        }

        try {
            this.confluencePackage.close("ASYNC".equals(this.properties.getCleanup()));
        } catch (IOException e) {
            throw new FilterException("Failed to close package", e);
        }
    }

    private void sendUsersAndGroups(Collection<Long> users, Collection<Long> groups, ConfluenceFilter proxyFilter)
        throws FilterException, ConfluenceCanceledException
    {
        if ((users == null || users.isEmpty()) && (groups == null || groups.isEmpty())) {
            return;
        }

        // Switch the wiki if a specific one is forced
        if (this.properties.getUsersWiki() != null) {
            proxyFilter.beginWiki(this.properties.getUsersWiki(), FilterEventParameters.EMPTY);
        }

        if (users != null) {
            sendUsers(users, proxyFilter);
        }

        if (groups != null) {
            sendGroups(groups, proxyFilter);
        }

        // Get back to default wiki
        if (this.properties.getUsersWiki() != null) {
            proxyFilter.endWiki(this.properties.getUsersWiki(), FilterEventParameters.EMPTY);
        }
    }

    private void sendGroups(Collection<Long> groupIds, ConfluenceFilter proxyFilter)
        throws FilterException, ConfluenceCanceledException
    {
        // Group groups by XWiki group name. There can be several Confluence groups mapping to a unique XWiki group.
        Map<String, Collection<ConfluenceProperties>> groupsByXWikiName = getGroupsByXWikiName(groupIds);

        // Loop over the XWiki groups
        for (Map.Entry<String, Collection<ConfluenceProperties>> groupEntry: groupsByXWikiName.entrySet()) {
            checkCanceled();
            String groupName = groupEntry.getKey();
            if ("XWikiAllGroup".equals(groupName)) {
                continue;
            }

            this.progress.startStep(this);

            FilterEventParameters groupParameters = new FilterEventParameters();

            // We arbitrarily take the creation and revision date of the first Confluence group mapped to this
            // XWiki group.
            Collection<ConfluenceProperties> groups = groupEntry.getValue();
            try {
                ConfluenceProperties firstGroupProperties = groups.iterator().next();
                groupParameters.put(GroupFilter.PARAMETER_REVISION_DATE,
                    this.confluencePackage.getDate(firstGroupProperties, ConfluenceXMLPackage.KEY_GROUP_REVISION_DATE));
                groupParameters.put(GroupFilter.PARAMETER_CREATION_DATE,
                    this.confluencePackage.getDate(firstGroupProperties, ConfluenceXMLPackage.KEY_GROUP_CREATION_DATE));
            } catch (Exception e) {
                if (this.properties.isVerbose()) {
                    this.logger.error("Failed to parse the group date", e);
                }
            }

            if (properties.isVerbose()) {
                logger.info("Sending group [{}]", groupName);
            }

            proxyFilter.beginGroupContainer(groupName, groupParameters);
            try {
                // We add members of all the Confluence groups mapped to this XWiki group to the XWiki group.
                Collection<String> alreadyAddedMembers = new HashSet<>();
                for (ConfluenceProperties groupProperties : groups) {
                    sendUserMembers(proxyFilter, groupProperties, alreadyAddedMembers);
                    sendGroupMembers(proxyFilter, groupProperties, alreadyAddedMembers);
                }
            } finally {
                proxyFilter.endGroupContainer(groupName, groupParameters);
            }

            this.progress.endStep(this);
        }
    }

    private void sendGroupMembers(ConfluenceFilter proxyFilter, ConfluenceProperties groupProperties,
        Collection<String> alreadyAddedMembers) throws ConfluenceCanceledException
    {
        if (groupProperties.containsKey(ConfluenceXMLPackage.KEY_GROUP_MEMBERGROUPS)) {
            List<Long> groupMembers = this.confluencePackage.getLongList(groupProperties,
                ConfluenceXMLPackage.KEY_GROUP_MEMBERGROUPS);
            for (Long memberInt : groupMembers) {
                checkCanceled();
                FilterEventParameters memberParameters = new FilterEventParameters();

                try {
                    String memberId = confluenceConverter.toGroupReference(
                        this.confluencePackage.getGroupProperties(memberInt)
                            .getString(ConfluenceXMLPackage.KEY_GROUP_NAME, String.valueOf(memberInt)));

                    if (!alreadyAddedMembers.contains(memberId)) {
                        proxyFilter.onGroupMemberGroup(memberId, memberParameters);
                        alreadyAddedMembers.add(memberId);
                    }
                } catch (Exception e) {
                    this.logger.error("Failed to get group properties", e);
                }
            }
        }
    }

    private void sendUserMembers(ConfluenceFilter proxyFilter, ConfluenceProperties groupProperties,
        Collection<String> alreadyAddedMembers) throws ConfluenceCanceledException
    {
        if (groupProperties.containsKey(ConfluenceXMLPackage.KEY_GROUP_MEMBERUSERS)) {
            List<Long> groupMembers =
                this.confluencePackage.getLongList(groupProperties, ConfluenceXMLPackage.KEY_GROUP_MEMBERUSERS);
            for (Long memberInt : groupMembers) {
                checkCanceled();
                FilterEventParameters memberParameters = new FilterEventParameters();

                try {
                    String memberId = confluenceConverter.toUserReferenceName(
                        this.confluencePackage.getInternalUserProperties(memberInt)
                            .getString(ConfluenceXMLPackage.KEY_USER_NAME, String.valueOf(memberInt)));

                    if (!alreadyAddedMembers.contains(memberId)) {
                        proxyFilter.onGroupMemberGroup(memberId, memberParameters);
                        alreadyAddedMembers.add(memberId);
                    }
                } catch (Exception e) {
                    this.logger.error(FAILED_TO_GET_USER_PROPERTIES, e);
                }
            }
        }
    }

    private Map<String, Collection<ConfluenceProperties>> getGroupsByXWikiName(Collection<Long> groups)
        throws FilterException
    {
        Map<String, Collection<ConfluenceProperties>> groupsByXWikiName = new HashMap<>();
        int i = 0;
        for (long groupId : groups) {
            this.progress.startStep(this);
            if (properties.isVerbose()) {
                logger.info("Reading group [{}] ({}/{})", groupId, ++i, groups.size());
            }
            ConfluenceProperties groupProperties;
            try {
                groupProperties = this.confluencePackage.getGroupProperties(groupId);
            } catch (ConfigurationException e) {
                throw new FilterException("Failed to get group properties", e);
            }

            String groupName = confluenceConverter.toGroupReferenceName(
                groupProperties.getString(ConfluenceXMLPackage.KEY_GROUP_NAME, String.valueOf(groupId)));

            if (!groupName.isEmpty()) {
                Collection<ConfluenceProperties> l = groupsByXWikiName.getOrDefault(groupName, new ArrayList<>());
                l.add(groupProperties);
                groupsByXWikiName.put(groupName, l);
            }
        }
        return groupsByXWikiName;
    }

    private void sendUsers(Collection<Long> users, ConfluenceFilter proxyFilter)
        throws FilterException, ConfluenceCanceledException
    {
        for (Long userId : users) {
            checkCanceled();
            this.progress.startStep(this);

            if (shouldSendObject(userId)) {
                sendUser(proxyFilter, userId);
            }

            this.progress.endStep(this);
        }
    }

    private void sendUser(ConfluenceFilter proxyFilter, Long userId) throws FilterException
    {
        ConfluenceProperties userProperties;
        try {
            userProperties = this.confluencePackage.getInternalUserProperties(userId);
        } catch (ConfigurationException e) {
            throw new FilterException(FAILED_TO_GET_USER_PROPERTIES, e);
        }

        String userName = confluenceConverter.toUserReferenceName(
            userProperties.getString(ConfluenceXMLPackage.KEY_USER_NAME, String.valueOf(userId)));

        if (this.properties.isVerbose()) {
            this.logger.info("Sending user [{}] (id = [{}])", userName, userId);
        }

        FilterEventParameters userParameters = new FilterEventParameters();

        userParameters.put(UserFilter.PARAMETER_FIRSTNAME,
            userProperties.getString(ConfluenceXMLPackage.KEY_USER_FIRSTNAME, "").trim());
        userParameters.put(UserFilter.PARAMETER_LASTNAME,
            userProperties.getString(ConfluenceXMLPackage.KEY_USER_LASTNAME, "").trim());
        userParameters.put(UserFilter.PARAMETER_EMAIL,
            userProperties.getString(ConfluenceXMLPackage.KEY_USER_EMAIL, "").trim());
        userParameters.put(UserFilter.PARAMETER_ACTIVE,
            userProperties.getBoolean(ConfluenceXMLPackage.KEY_USER_ACTIVE, true));

        try {
            userParameters.put(UserFilter.PARAMETER_REVISION_DATE,
                this.confluencePackage.getDate(userProperties, ConfluenceXMLPackage.KEY_USER_REVISION_DATE));
            userParameters.put(UserFilter.PARAMETER_CREATION_DATE,
                this.confluencePackage.getDate(userProperties, ConfluenceXMLPackage.KEY_USER_CREATION_DATE));
        } catch (Exception e) {
            if (this.properties.isVerbose()) {
                this.logger.error("Failed to parse the user date", e);
            }
        }

        // TODO: no idea how to import/convert the password, probably salted with the Confluence instance id

        // > User
        proxyFilter.beginUser(userName, userParameters);

        // < User
        proxyFilter.endUser(userName, userParameters);
    }

    private String getSpaceTitle(Long spaceId)
    {
        try {
            ConfluenceProperties spaceProperties = confluencePackage.getSpaceProperties(spaceId);
            if (spaceProperties != null) {
                return spaceProperties.getString(ConfluenceXMLPackage.KEY_SPACE_NAME, null);
            }
        } catch (ConfigurationException e) {
            this.logger.warn("Could not get the title of space id=[{}]", spaceId, e);
        }

        return null;
    }

    private Collection<ConfluenceRight> readPage(long pageId, String spaceKey, boolean blog, Object filter,
        ConfluenceFilter proxyFilter) throws FilterException, ConfluenceInterruptedException
    {
        if (!shouldSendObject(pageId)) {
            emptyStep();
            return null;
        }

        Collection<ConfluenceRight> homePageInheritedRights = null;

        ConfluenceProperties pageProperties = getPageProperties(pageId);

        if (pageProperties == null) {
            this.logger.error("Can't find page with id [{}]", createPageIdentifier(pageId, spaceKey));
            emptyStep();
            return null;
        }

        String title = pageProperties.getString(ConfluenceXMLPackage.KEY_PAGE_TITLE);

        boolean isHomePage = pageProperties.containsKey(ConfluenceXMLPackage.KEY_PAGE_HOMEPAGE);
        String documentName = isHomePage ? WEB_HOME : title;

        // Skip pages with empty title
        if (StringUtils.isEmpty(documentName)) {
            this.logger.warn("Found a page without a name or title (id={}). Skipping it.",
                createPageIdentifier(pageId, spaceKey));

            emptyStep();
            return null;
        }

        // Skip archived pages
        String status = pageProperties.getString(ConfluenceXMLPackage.KEY_PAGE_CONTENT_STATUS);
        if ("archived".equals(status) && !this.properties.isArchivedDocumentsEnabled()) {
            emptyStep();
            return null;
        }

        FilterEventParameters documentParameters = new FilterEventParameters();
        if (this.properties.getDefaultLocale() != null) {
            documentParameters.put(WikiDocumentFilter.PARAMETER_LOCALE, this.properties.getDefaultLocale());
        }

        if (this.properties.isVerbose()) {
            this.logger.info("Sending page [{}], Confluence id=[{}]", createPageIdentifier(pageId, spaceKey), pageId);
        }

        String spaceName = confluenceConverter.toEntityName(title);

        boolean nested = !blog;
        if (nested) {
            if (!isHomePage) {
                proxyFilter.beginWikiSpace(spaceName, FilterEventParameters.EMPTY);
            }
            documentName = WEB_HOME;
        } else {
            // Apply the standard entity name validator
            documentName = confluenceConverter.toEntityName(documentName);
        }

        try {
            Collection<ConfluenceRight> inheritedRights = sendTerminalDoc(blog, filter, proxyFilter, documentName,
                documentParameters, pageProperties, spaceKey, isHomePage);

            if (isHomePage) {
                // We only send inherited rights of the home page so they are added to the space's WebPreference page
                homePageInheritedRights = inheritedRights;
            }

            if (nested) {
                sendPages(spaceKey, blog, confluencePackage.getPageChildren(pageId), filter, proxyFilter);
            }
        } finally {
            if (nested && !isHomePage) {
                proxyFilter.endWikiSpace(spaceName, FilterEventParameters.EMPTY);
            }
        }
        return homePageInheritedRights;
    }

    private void emptyStep()
    {
        this.progress.startStep(this);
        this.progress.endStep(this);
    }

    private Collection<ConfluenceRight> sendTerminalDoc(boolean blog, Object filter, ConfluenceFilter proxyFilter,
        String documentName, FilterEventParameters documentParameters, ConfluenceProperties pageProperties,
        String spaceKey, boolean isHomePage) throws FilterException, ConfluenceCanceledException
    {
        this.progress.startStep(this);
        // > WikiDocument
        proxyFilter.beginWikiDocument(documentName, documentParameters);

        Collection<ConfluenceRight> inheritedRights = null;
        try {
            if (this.properties.isContentsEnabled() || this.properties.isRightsEnabled()) {
                inheritedRights = sendRevisions(blog, filter, proxyFilter, pageProperties, spaceKey);
            }
        } finally {
            // < WikiDocument
            proxyFilter.endWikiDocument(documentName, documentParameters);

            if (!isHomePage && inheritedRights != null && !inheritedRights.isEmpty()) {
                // inherited rights from the home page are put in the space WebPreferences page
                FilterEventParameters webPreferencesParameters = beginWebPreferences(proxyFilter);
                try {
                    for (ConfluenceRight right : inheritedRights) {
                        sendInheritedPageRight(pageProperties, proxyFilter, right);
                    }
                } finally {
                    proxyFilter.endWikiDocument(WEB_PREFERENCES, webPreferencesParameters);
                }
            }

            if (!macrosIds.isEmpty()) {
                if (properties.isVerbose()) {
                    logger.info(ConfluenceFilter.LOG_MACROS_FOUND, "The following macros [{}] were found on page [{}].",
                        macrosIds, createPageIdentifier(pageProperties));
                }
                macrosIds.clear();
            }
            if (this.remainingPages > 0) {
                this.remainingPages--;
            }
            this.progress.endStep(this);
        }

        return isHomePage ? inheritedRights : null;
    }

    private Collection<ConfluenceRight> sendRevisions(boolean blog, Object filter, ConfluenceFilter proxyFilter,
        ConfluenceProperties pageProperties, String spaceKey) throws FilterException, ConfluenceCanceledException
    {
        Locale locale = Locale.ROOT;

        FilterEventParameters documentLocaleParameters = new FilterEventParameters();
        if (pageProperties.containsKey(ConfluenceXMLPackage.KEY_PAGE_CREATION_AUTHOR)) {
            documentLocaleParameters.put(WikiDocumentFilter.PARAMETER_CREATION_AUTHOR,
                confluenceConverter.toUserReference(
                    pageProperties.getString(ConfluenceXMLPackage.KEY_PAGE_CREATION_AUTHOR)));
        } else if (pageProperties.containsKey(ConfluenceXMLPackage.KEY_PAGE_CREATION_AUTHOR_KEY)) {
            String authorKey = pageProperties.getString(ConfluenceXMLPackage.KEY_PAGE_CREATION_AUTHOR_KEY);
            String authorName = confluenceConverter.toUserReference(
                confluencePackage.resolveUserName(authorKey, authorKey));
            documentLocaleParameters.put(WikiDocumentFilter.PARAMETER_CREATION_AUTHOR, authorName);
        }

        if (pageProperties.containsKey(ConfluenceXMLPackage.KEY_PAGE_CREATION_DATE)) {
            try {
                documentLocaleParameters.put(WikiDocumentFilter.PARAMETER_CREATION_DATE,
                    this.confluencePackage.getDate(pageProperties, ConfluenceXMLPackage.KEY_PAGE_CREATION_DATE));
            } catch (Exception e) {
                this.logger.error("Failed to parse creation date of the document with id [{}]",
                    createPageIdentifier(pageProperties), e);
            }
        }

        if (pageProperties.containsKey(ConfluenceXMLPackage.KEY_PAGE_REVISION)) {
            documentLocaleParameters.put(WikiDocumentFilter.PARAMETER_LASTREVISION,
                pageProperties.getString(ConfluenceXMLPackage.KEY_PAGE_REVISION));
        }

        // > WikiDocumentLocale
        proxyFilter.beginWikiDocumentLocale(locale, documentLocaleParameters);

        Collection<ConfluenceRight> inheritedRights = null;
        try {
            // Revisions
            if (properties.isHistoryEnabled() && pageProperties.containsKey(ConfluenceXMLPackage.KEY_PAGE_REVISIONS)) {
                List<Long> revisions =
                    this.confluencePackage.getLongList(pageProperties, ConfluenceXMLPackage.KEY_PAGE_REVISIONS);
                Collections.sort(revisions);
                for (Long revisionId : revisions) {
                    if (shouldSendObject(revisionId)) {
                        ConfluenceProperties revisionProperties = getPageProperties(revisionId);
                        if (revisionProperties == null) {
                            this.logger.warn("Can't find page revision with id [{}]", revisionId);
                            continue;
                        }

                        try {
                            readPageRevision(revisionProperties, blog, filter, proxyFilter, spaceKey);
                        } catch (Exception e) {
                            logger.error("Failed to filter the page revision with id [{}]",
                                createPageIdentifier(revisionId, spaceKey), e);
                        }
                        checkCanceled();
                    }
                }
            }

            // Current version
            // Note: no need to check whether the object should be sent. Indeed, this is already checked by an upper
            // function
            inheritedRights = readPageRevision(pageProperties, blog, filter, proxyFilter, spaceKey);
        } finally {
            // < WikiDocumentLocale
            proxyFilter.endWikiDocumentLocale(locale, documentLocaleParameters);
        }

        return inheritedRights;
    }

    private Collection<ConfluenceRight> sendPageRights(ConfluenceFilter proxyFilter,
        ConfluenceProperties pageProperties) throws FilterException
    {
        Collection<ConfluenceRight> inheritedRights = new ArrayList<>();
        for (Object permissionSetIdObject : ConfluenceXMLPackage.getContentPermissionSets(pageProperties)) {
            Long permissionSetId = toLong(permissionSetIdObject);
            if (permissionSetId == null) {
                logger.error("Space permission set id is null for page [{}]", createPageIdentifier(pageProperties));
                continue;
            }

            if (!shouldSendObject(permissionSetId)) {
                continue;
            }

            ConfluenceProperties permissionSetProperties = null;
            try {
                permissionSetProperties = confluencePackage.getContentPermissionSetProperties(permissionSetId);
            } catch (ConfigurationException e) {
                logger.error("Could not get permission set [{}] for page [{}]",
                    permissionSetId, createPageIdentifier(pageProperties), e);
                continue;
            }

            if (permissionSetProperties == null) {
                logger.error("Could not find permission set [{}] for page [{}].",
                    permissionSetId, createPageIdentifier(pageProperties));
                continue;
            }

            for (Object permissionIdObject : ConfluenceXMLPackage.getContentPermissions(permissionSetProperties)) {
                Long permissionId = toLong(permissionIdObject);
                if (permissionId == null) {
                    logger.error("Permission id is null for page [{}]", createPageIdentifier(pageProperties));
                    continue;
                }

                if (!shouldSendObject(permissionId)) {
                    continue;
                }

                ConfluenceProperties permProperties = null;
                try {
                    permProperties = confluencePackage.getContentPermissionProperties(permissionSetId, permissionId);
                } catch (ConfigurationException e) {
                    logger.error("Could not get permission [{}] for page [{}]",
                        permissionId, createPageIdentifier(pageProperties), e);
                    continue;
                }

                if (permProperties == null) {
                    logger.error("Could not find permission [{}] for page [{}].",
                        permissionId, createPageIdentifier(pageProperties));
                    continue;
                }

                ConfluenceRight confluenceRight = getConfluenceRightData(permProperties);

                ContentPermissionType type = getContentPermissionType(pageProperties, confluenceRight, permissionId);
                if (type == null) {
                    continue;
                }

                Right right = null;
                switch (type) {
                    case VIEW:
                        right = Right.VIEW;
                        break;
                    case EDIT:
                        right = Right.EDIT;
                        break;
                    case SHARE:
                        // Sharing is not represented in XWiki rights
                        continue;
                    default:
                        this.logger.warn("Unknown content permission right type [{}].", right);
                        continue;
                }

                if (right != null && !(confluenceRight.users.isEmpty() && confluenceRight.group.isEmpty())) {
                    if (Right.VIEW.equals(right)) {
                        inheritedRights.add(confluenceRight);
                    } else {
                        sendRight(proxyFilter, confluenceRight.group, right, confluenceRight.users, false);
                    }
                }
            }
        }
        return inheritedRights;
    }

    private ContentPermissionType getContentPermissionType(ConfluenceProperties pageProperties,
        ConfluenceRight confluenceRight, Long permissionId)
    {
        ContentPermissionType type;
        try {
            type = ContentPermissionType.valueOf(confluenceRight.type.toUpperCase());
        } catch (IllegalArgumentException e) {
            logger.warn("Failed to understand content permission type [{}] for page [{}], permission id [{}].",
                confluenceRight.type, createPageIdentifier(pageProperties), permissionId == null);
            return null;
        }
        return type;
    }

    private static Long toLong(Object permissionSetIdObject)
    {
        return permissionSetIdObject instanceof Long
            ? (Long) permissionSetIdObject
            : Long.parseLong((String) permissionSetIdObject);
    }

    private ConfluenceProperties getPageProperties(Long pageId) throws FilterException
    {
        try {
            return this.confluencePackage.getPageProperties(pageId, false);
        } catch (ConfigurationException e) {
            throw new FilterException("Failed to get page properties", e);
        }
    }

    private Collection<ConfluenceRight> readPageRevision(ConfluenceProperties pageProperties, boolean blog,
        Object filter, ConfluenceFilter proxyFilter, String spaceKey) throws FilterException
    {
        // beware. Here, pageProperties might not have a space key. You need to use the one passed in parameters
        // FIXME we could ensure it though with some work

        Long pageId = pageProperties.getLong("id", null);
        if (pageId == null) {
            throw new FilterException("Found a null revision id in space [" + spaceKey + "], this should not happen.");
        }

        // pageId is used as a fallback, an empty revision would prevent the revision from going through.
        String revision = pageProperties.getString(ConfluenceXMLPackage.KEY_PAGE_REVISION, pageId.toString());

        FilterEventParameters docRevisionParameters = new FilterEventParameters();

        prepareRevisionMetadata(pageProperties, docRevisionParameters);

        beginPageRevision(blog, pageProperties, filter, proxyFilter, revision, docRevisionParameters);

        Collection<ConfluenceRight> inheritedRights = null;

        if (this.properties.isRightsEnabled()) {
            inheritedRights = sendPageRights(proxyFilter, pageProperties);
        }

        try {
            readAttachments(pageId, pageProperties, proxyFilter);
            readTags(pageProperties, proxyFilter);
            readComments(pageProperties, proxyFilter);
            storeConfluenceDetails(spaceKey, pageId, pageProperties, proxyFilter);
        } finally {
            // < WikiDocumentRevision
            proxyFilter.endWikiDocumentRevision(revision, docRevisionParameters);
        }
        return inheritedRights;
    }

    private void beginPageRevision(boolean isBlog, ConfluenceProperties pageProperties,
        Object filter, ConfluenceFilter proxyFilter, String revision, FilterEventParameters docRevisionParameters)
        throws FilterException
    {
        String bodyContent = pageProperties.getString(ConfluenceXMLPackage.KEY_PAGE_BODY, null);
        if (bodyContent != null && this.properties.isContentsEnabled()) {
            // No bodyType means old Confluence syntax
            int bodyType = pageProperties.getInt(ConfluenceXMLPackage.KEY_PAGE_BODY_TYPE, 0);

            if (!isBlog && this.properties.isContentEvents() && filter instanceof Listener) {
                // > WikiDocumentRevision
                proxyFilter.beginWikiDocumentRevision(revision, docRevisionParameters);

                try {
                    parse(bodyContent, bodyType, this.properties.getMacroContentSyntax(), proxyFilter);
                } catch (Exception e) {
                    this.logger.error("Failed to parse content of page with id [{}]",
                        createPageIdentifier(pageProperties), e);
                }
                return;
            }

            Syntax bodySyntax = getBodySyntax(pageProperties, bodyType);

            if (this.properties.isConvertToXWiki()) {
                try {
                    bodyContent = convertToXWiki21(bodyContent, bodyType);
                    bodySyntax = Syntax.XWIKI_2_1;
                } catch (ParseException e) {
                    this.logger.error("Failed to convert content of the page with id [{}]",
                        createPageIdentifier(pageProperties), e);
                }
            }

            if (!isBlog) {
                docRevisionParameters.put(WikiDocumentFilter.PARAMETER_CONTENT, bodyContent);
            }

            docRevisionParameters.put(WikiDocumentFilter.PARAMETER_SYNTAX, bodySyntax);
        }

        // > WikiDocumentRevision
        proxyFilter.beginWikiDocumentRevision(revision, docRevisionParameters);

        // Generate page content when the page is a regular page or the value of the "content" property of the
        // "Blog.BlogPostClass" object if the page is a blog post.
        if (isBlog) {
            // Add the Blog post object
            Date publishDate = null;
            try {
                publishDate =
                    this.confluencePackage.getDate(pageProperties, ConfluenceXMLPackage.KEY_PAGE_REVISION_DATE);
            } catch (Exception e) {
                this.logger.error(
                    "Failed to parse the publish date of the blog post document with id [{}]",
                    createPageIdentifier(pageProperties), e);
            }

            addBlogPostObject(pageProperties.getString(ConfluenceXMLPackage.KEY_PAGE_TITLE), bodyContent,
                publishDate, proxyFilter);
        }
    }

    private Syntax getBodySyntax(ConfluenceProperties pageProperties, int bodyType)
    {
        switch (bodyType) {
            case 0:
                return ConfluenceParser.SYNTAX;
            case 2:
                return Syntax.CONFLUENCEXHTML_1_0;
            default:
                this.logger.warn("Unknown body type [{}] for the content of the document with id [{}].", bodyType,
                    createPageIdentifier(pageProperties));
                return null;
        }
    }

    private void prepareRevisionMetadata(ConfluenceProperties pageProperties,
        FilterEventParameters documentRevisionParameters)
    {
        if (pageProperties.containsKey(ConfluenceXMLPackage.KEY_PAGE_REVISION_AUTHOR)) {
            documentRevisionParameters.put(WikiDocumentFilter.PARAMETER_REVISION_EFFECTIVEMETADATA_AUTHOR,
                confluenceConverter.toUserReference(
                    pageProperties.getString(ConfluenceXMLPackage.KEY_PAGE_REVISION_AUTHOR)));
        } else if (pageProperties.containsKey(ConfluenceXMLPackage.KEY_PAGE_REVISION_AUTHOR_KEY)) {
            String authorKey = pageProperties.getString(ConfluenceXMLPackage.KEY_PAGE_REVISION_AUTHOR_KEY);
            String authorName = confluenceConverter.toUserReference(
                confluencePackage.resolveUserName(authorKey, authorKey));
            documentRevisionParameters.put(WikiDocumentFilter.PARAMETER_REVISION_EFFECTIVEMETADATA_AUTHOR, authorName);
        }
        if (pageProperties.containsKey(ConfluenceXMLPackage.KEY_PAGE_REVISION_DATE)) {
            try {
                documentRevisionParameters.put(WikiDocumentFilter.PARAMETER_REVISION_DATE,
                    this.confluencePackage.getDate(pageProperties, ConfluenceXMLPackage.KEY_PAGE_REVISION_DATE));
            } catch (Exception e) {
                this.logger.error("Failed to parse the revision date of the document with id [{}]",
                    createPageIdentifier(pageProperties), e);
            }
        }
        if (pageProperties.containsKey(ConfluenceXMLPackage.KEY_PAGE_REVISION_COMMENT)) {
            documentRevisionParameters.put(WikiDocumentFilter.PARAMETER_REVISION_COMMENT,
                pageProperties.getString(ConfluenceXMLPackage.KEY_PAGE_REVISION_COMMENT));
        }

        String title = (!this.properties.isSpaceTitleFromHomePage()
            && pageProperties.containsKey(ConfluenceXMLPackage.KEY_PAGE_HOMEPAGE))
                ? getSpaceTitle(pageProperties.getLong(ConfluenceXMLPackage.KEY_PAGE_SPACE, null))
                : pageProperties.getString(ConfluenceXMLPackage.KEY_PAGE_TITLE, null);

        if (title != null) {
            documentRevisionParameters.put(WikiDocumentFilter.PARAMETER_TITLE, title);
        }
    }

    private void readComments(ConfluenceProperties pageProperties, ConfluenceFilter proxyFilter) throws FilterException
    {
        Map<Long, ConfluenceProperties> pageComments = new LinkedHashMap<>();
        Map<Long, Integer> commentIndices = new LinkedHashMap<>();
        int commentIndex = 0;
        for (Long commentId : confluencePackage.getPageComments(pageProperties)) {
            if (!shouldSendObject(commentId)) {
                continue;
            }
            ConfluenceProperties commentProperties;
            try {
                commentProperties = this.confluencePackage.getObjectProperties(commentId);
            } catch (ConfigurationException e) {
                logger.error("Failed to get the comment properties [{}] for the page with id [{}]",
                    commentId, createPageIdentifier(pageProperties), e);
                continue;
            }

            pageComments.put(commentId, commentProperties);
            commentIndices.put(commentId, commentIndex);
            commentIndex++;
        }

        for (Long commentId : pageComments.keySet()) {
            readPageComment(pageProperties, proxyFilter, commentId, pageComments, commentIndices);
        }
    }

    private void readTags(ConfluenceProperties pageProperties, ConfluenceFilter proxyFilter) throws FilterException
    {
        if (!this.properties.isTagsEnabled()) {
            return;
        }

        Map<String, ConfluenceProperties> pageTags = new LinkedHashMap<>();
        for (Object tagIdStringObject : pageProperties.getList(ConfluenceXMLPackage.KEY_PAGE_LABELLINGS)) {
            Long tagId = Long.parseLong((String) tagIdStringObject);
            if (!shouldSendObject(tagId)) {
                continue;
            }
            ConfluenceProperties tagProperties;
            try {
                tagProperties = this.confluencePackage.getObjectProperties(tagId);
            } catch (ConfigurationException e) {
                logger.error("Failed to get tag properties [{}] for the page with id [{}].", tagId,
                    createPageIdentifier(pageProperties), e);
                continue;
            }

            String tagName = this.confluencePackage.getTagName(tagProperties);
            if (tagName == null) {
                logger.warn("Failed to get the name of tag id [{}] for the page with id [{}].", tagId,
                    createPageIdentifier(pageProperties));
            } else {
                pageTags.put(tagName, tagProperties);
            }
        }

        if (!pageTags.isEmpty()) {
            readPageTags(proxyFilter, pageTags);
        }
    }

    private void readAttachments(Long pageId, ConfluenceProperties pageProperties, ConfluenceFilter proxyFilter)
        throws FilterException
    {
        if (!this.properties.isAttachmentsEnabled()) {
            return;
        }

        Map<String, ConfluenceProperties> pageAttachments = new LinkedHashMap<>();
        for (Long attachmentId : this.confluencePackage.getAttachments(pageId)) {
            if (!shouldSendObject(attachmentId)) {
                continue;
            }

            ConfluenceProperties attachmentProperties;
            try {
                attachmentProperties = this.confluencePackage.getAttachmentProperties(pageId, attachmentId);
            } catch (ConfigurationException e) {
                logger.error(
                    "Failed to get the properties of the attachments from the document identified by [{}]",
                    createPageIdentifier(pageProperties), e);
                continue;
            }

            String attachmentName = this.confluencePackage.getAttachmentName(attachmentProperties);

            ConfluenceProperties currentAttachmentProperties = pageAttachments.get(attachmentName);
            if (currentAttachmentProperties != null) {
                try {
                    Date date = this.confluencePackage.getDate(attachmentProperties,
                        ConfluenceXMLPackage.KEY_ATTACHMENT_REVISION_DATE);
                    Date currentDate = this.confluencePackage.getDate(currentAttachmentProperties,
                        ConfluenceXMLPackage.KEY_ATTACHMENT_REVISION_DATE);

                    if (date.after(currentDate)) {
                        pageAttachments.put(attachmentName, attachmentProperties);
                    }
                } catch (Exception e) {
                    this.logger.error(
                        "Failed to parse the date of attachment [{}] from the page with id [{}], skipping it",
                        pageProperties, attachmentId, e);
                }
            } else {
                pageAttachments.put(attachmentName, attachmentProperties);
            }
        }

        for (ConfluenceProperties attachmentProperties : pageAttachments.values()) {
            readAttachment(pageId, pageProperties, attachmentProperties, proxyFilter);
        }
    }

    /**
     * @param currentProperties the properties where to find the page identifier
     * @param key the key to find the page identifier
     * @return the reference of the page
     * @throws ConfigurationException when failing to get page properties
     * @throws FilterException when failing to create the reference
     */
    public EntityReference getReferenceFromId(ConfluenceProperties currentProperties, String key)
        throws ConfigurationException, FilterException
    {
        Long pageId = currentProperties.getLong(key, null);
        if (pageId == null) {
            return null;
        }

        ConfluenceProperties pageProperties = this.confluencePackage.getPageProperties(pageId, false);
        if (pageProperties == null) {
            return null;
        }

        Long spaceId = pageProperties.getLong(ConfluenceXMLPackage.KEY_PAGE_SPACE, null);
        String docName = pageProperties.containsKey(ConfluenceXMLPackage.KEY_PAGE_HOMEPAGE)
            ? WEB_HOME
            : confluenceConverter.toEntityName(pageProperties.getString(ConfluenceXMLPackage.KEY_PAGE_TITLE));

        if (StringUtils.isEmpty(docName)) {
            throw new FilterException("Cannot create a reference to the page with id [" + pageId
                + "] because it does not have any title");
        }

        long currentSpaceId = currentProperties.getLong(ConfluenceXMLPackage.KEY_PAGE_SPACE, null);

        EntityReference spaceReference = null;
        if (spaceId != null && !spaceId.equals(currentSpaceId)) {
            String spaceName = this.confluencePackage.getSpaceKey(spaceId);
            if (spaceName != null) {
                spaceReference = new EntityReference(confluenceConverter.toEntityName(spaceName), EntityType.SPACE);
            }
        }

        return new EntityReference(docName, EntityType.DOCUMENT, spaceReference);
    }

    private EntityReference getReferenceForDocument(String spaceKey, String docName)
    {
        // FIXME is this somewhat a duplicate of the end of getReferenceFromId?
        SpaceReference rootSpace = this.properties.getRootSpace();
        String convertedSpace = confluenceConverter.toEntityName(spaceKey);
        EntityReference spaceReference = rootSpace == null
            ? new EntityReference(convertedSpace, EntityType.SPACE)
            : new SpaceReference(convertedSpace, rootSpace);

        return new EntityReference(docName, EntityType.DOCUMENT, spaceReference);
    }

    /**
     * @since 9.13
     */
    private void storeConfluenceDetails(String spaceKey, Long pageId, ConfluenceProperties pageProperties,
        ConfluenceFilter proxyFilter) throws FilterException
    {
        if (!this.properties.isStoreConfluenceDetailsEnabled()) {
            return;
        }

        FilterEventParameters pageReportParameters = new FilterEventParameters();

        // Page report object
        pageReportParameters.put(WikiObjectFilter.PARAMETER_CLASS_REFERENCE, CONFLUENCEPAGE_CLASSNAME);
        proxyFilter.beginWikiObject(CONFLUENCEPAGE_CLASSNAME, pageReportParameters);
        try {
            proxyFilter.onWikiObjectProperty("id", pageId, FilterEventParameters.EMPTY);
            StringBuilder pageURLBuilder = new StringBuilder();
            if (this.properties.getBaseURLs() != null) {
                pageURLBuilder.append(this.properties.getBaseURLs().get(0).toString());
                pageURLBuilder.append("/wiki/spaces/").append(spaceKey);
                if (!pageProperties.containsKey(ConfluenceXMLPackage.KEY_PAGE_HOMEPAGE)) {
                    String pageName = pageProperties.getString(ConfluenceXMLPackage.KEY_PAGE_TITLE);
                    pageURLBuilder.append("/pages/").append(pageId).append("/").append(pageName);
                }
            }
            proxyFilter.onWikiObjectProperty("url", pageURLBuilder.toString(), FilterEventParameters.EMPTY);
            proxyFilter.onWikiObjectProperty("space", spaceKey, FilterEventParameters.EMPTY);
        } finally {
            proxyFilter.endWikiObject(CONFLUENCEPAGE_CLASSNAME, pageReportParameters);
        }
    }

    private String convertToXWiki21(String bodyContent, int bodyType) throws FilterException, ParseException
    {
        DefaultWikiPrinter printer = new DefaultWikiPrinter();
        PrintRenderer renderer = this.xwiki21Factory.createRenderer(printer);

        parse(bodyContent, bodyType, Syntax.XWIKI_2_1, renderer);

        return printer.toString();
    }

    private ConfluenceConverterListener createConverter(Listener listener)
    {
        ConfluenceConverterListener converterListener = this.converterProvider.get();
        converterListener.setWrappedListener(listener);
        converterListener.setMacroIds(macrosIds);

        return converterListener;
    }

    private Listener wrap(Listener listener)
    {
        if (this.properties.isConvertToXWiki()) {
            return createConverter(listener);
        }

        return listener;
    }

    private void parse(String bodyContent, int bodyType, Syntax macroContentSyntax, Listener listener)
        throws FilterException, ParseException
    {
        switch (bodyType) {
            case 0:
                this.confluenceWIKIParser.parse(new StringReader(bodyContent), wrap(listener));
                break;
            case 2:
                createSyntaxFilter(bodyContent, macroContentSyntax).read(listener);
                break;
            default:
                break;
        }
    }

    private BeanInputFilterStream<ConfluenceXHTMLInputProperties> createSyntaxFilter(String bodyContent,
        Syntax macroContentSyntax) throws FilterException
    {
        InternalConfluenceXHTMLInputProperties filterProperties = new InternalConfluenceXHTMLInputProperties();
        filterProperties.setSource(new StringInputSource(bodyContent));
        filterProperties.setMacroContentSyntax(macroContentSyntax);
        filterProperties.setReferenceConverter(confluenceConverter);
        filterProperties.setMacroSupport(macroSupport);

        if (this.properties.isConvertToXWiki()) {
            filterProperties.setConverter(createConverter(null));
        }

        BeanInputFilterStreamFactory<ConfluenceXHTMLInputProperties> syntaxFilterFactory =
            ((BeanInputFilterStreamFactory<ConfluenceXHTMLInputProperties>) this.confluenceXHTMLParserFactory);

        return syntaxFilterFactory.createInputFilterStream(filterProperties);
    }

    private void readAttachment(Long pageId, ConfluenceProperties pageProperties,
        ConfluenceProperties attachmentProperties, ConfluenceFilter proxyFilter) throws FilterException
    {
        String contentStatus = attachmentProperties.getString(ConfluenceXMLPackage.KEY_ATTACHMENT_CONTENTSTATUS, null);
        if (StringUtils.equals(contentStatus, "deleted")) {
            // The actual deleted attachment is not in the exported package, so we can't really do anything with it
            return;
        }

        Long attachmentId = attachmentProperties.getLong("id");
        // no need to check shouldSendObject(attachmentId), already done by the caller.

        String attachmentName = this.confluencePackage.getAttachmentName(attachmentProperties);

        long attachmentSize;
        String mediaType = null;
        if (attachmentProperties.containsKey(ConfluenceXMLPackage.KEY_ATTACHMENT_CONTENTPROPERTIES)) {
            ConfluenceProperties attachmentContentProperties =
                getContentProperties(attachmentProperties, ConfluenceXMLPackage.KEY_ATTACHMENT_CONTENTPROPERTIES);

            attachmentSize =
                attachmentContentProperties.getLong(ConfluenceXMLPackage.KEY_ATTACHMENT_CONTENT_FILESIZE, -1);
            if (attachmentProperties.containsKey(ConfluenceXMLPackage.KEY_ATTACHMENT_CONTENTTYPE)) {
                mediaType =
                    attachmentContentProperties.getString(ConfluenceXMLPackage.KEY_ATTACHMENT_CONTENT_MEDIA_TYPE);
            }
        } else {
            attachmentSize = attachmentProperties.getLong(ConfluenceXMLPackage.KEY_ATTACHMENT_CONTENT_SIZE, -1);
            if (attachmentProperties.containsKey(ConfluenceXMLPackage.KEY_ATTACHMENT_CONTENTTYPE)) {
                mediaType = attachmentProperties.getString(ConfluenceXMLPackage.KEY_ATTACHMENT_CONTENTTYPE);
            }
        }

        Long version = this.confluencePackage.getAttachementVersion(attachmentProperties);

        Long originalRevisionId =
            this.confluencePackage.getAttachmentOriginalVersionId(attachmentProperties, attachmentId);
        File contentFile;
        try {
            contentFile = this.confluencePackage.getAttachmentFile(pageId, originalRevisionId, version);
        } catch (FileNotFoundException e) {
            this.logger.warn("Failed to find file corresponding to version [{}] attachment [{}] in page [{}]",
                version, attachmentName, createPageIdentifier(pageProperties));
            return;
        }

        FilterEventParameters attachmentParameters = new FilterEventParameters();
        if (mediaType != null) {
            attachmentParameters.put(WikiAttachmentFilter.PARAMETER_CONTENT_TYPE, mediaType);
        }
        if (attachmentProperties.containsKey(ConfluenceXMLPackage.KEY_ATTACHMENT_CREATION_AUTHOR)) {
            attachmentParameters.put(WikiAttachmentFilter.PARAMETER_CREATION_AUTHOR,
                attachmentProperties.getString(ConfluenceXMLPackage.KEY_ATTACHMENT_CREATION_AUTHOR));
        }
        if (attachmentProperties.containsKey(ConfluenceXMLPackage.KEY_ATTACHMENT_CREATION_DATE)) {
            try {
                attachmentParameters.put(WikiAttachmentFilter.PARAMETER_CREATION_DATE, this.confluencePackage
                    .getDate(attachmentProperties, ConfluenceXMLPackage.KEY_ATTACHMENT_CREATION_DATE));
            } catch (Exception e) {
                this.logger.error("Failed to parse the creation date of the attachment [{}] in page [{}]",
                    attachmentId, createPageIdentifier(pageProperties), e);
            }
        }

        attachmentParameters.put(WikiAttachmentFilter.PARAMETER_REVISION, String.valueOf(version));
        if (attachmentProperties.containsKey(ConfluenceXMLPackage.KEY_ATTACHMENT_REVISION_AUTHOR)) {
            attachmentParameters.put(WikiAttachmentFilter.PARAMETER_REVISION_AUTHOR,
                attachmentProperties.getString(ConfluenceXMLPackage.KEY_ATTACHMENT_REVISION_AUTHOR));
        }
        if (attachmentProperties.containsKey(ConfluenceXMLPackage.KEY_ATTACHMENT_REVISION_DATE)) {
            try {
                attachmentParameters.put(WikiAttachmentFilter.PARAMETER_REVISION_DATE, this.confluencePackage
                    .getDate(attachmentProperties, ConfluenceXMLPackage.KEY_ATTACHMENT_REVISION_DATE));
            } catch (Exception e) {
                this.logger.error("Failed to parse the revision date of the attachment [{}] in page [{}]",
                    attachmentId, createPageIdentifier(pageProperties), e);
            }
        }
        if (attachmentProperties.containsKey(ConfluenceXMLPackage.KEY_ATTACHMENT_REVISION_COMMENT)) {
            attachmentParameters.put(WikiAttachmentFilter.PARAMETER_REVISION_COMMENT,
                attachmentProperties.getString(ConfluenceXMLPackage.KEY_ATTACHMENT_REVISION_COMMENT));
        }

        // WikiAttachment

        try (FileInputStream fis = new FileInputStream(contentFile)) {
            proxyFilter.onWikiAttachment(attachmentName, fis,
                attachmentSize != -1 ? attachmentSize : contentFile.length(), attachmentParameters);
        } catch (Exception e) {
            this.logger.error("Failed to read attachment [{}] for the page [{}].", attachmentId,
                createPageIdentifier(pageProperties), e);
        }
    }

    private void readPageTags(ConfluenceFilter proxyFilter, Map<String, ConfluenceProperties> pageTags)
        throws FilterException
    {
        FilterEventParameters pageTagsParameters = new FilterEventParameters();

        // Tag object
        pageTagsParameters.put(WikiObjectFilter.PARAMETER_CLASS_REFERENCE, TAGS_CLASSNAME);
        proxyFilter.beginWikiObject(TAGS_CLASSNAME, pageTagsParameters);
        try {
            // get page tags separated by | as string
            StringBuilder tagBuilder = new StringBuilder();
            String prefix = "";
            for (String tag : pageTags.keySet()) {
                tagBuilder.append(prefix);
                tagBuilder.append(tag);
                prefix = "|";
            }

            // <tags> object property
            proxyFilter.onWikiObjectProperty("tags", tagBuilder.toString(), FilterEventParameters.EMPTY);
        } finally {
            proxyFilter.endWikiObject(TAGS_CLASSNAME, pageTagsParameters);
        }
    }

    private void readPageComment(ConfluenceProperties pageProperties, ConfluenceFilter proxyFilter, Long commentId,
        Map<Long, ConfluenceProperties> pageComments, Map<Long, Integer> commentIndices) throws FilterException
    {
        FilterEventParameters commentParameters = new FilterEventParameters();

        // Comment object
        commentParameters.put(WikiObjectFilter.PARAMETER_CLASS_REFERENCE, COMMENTS_CLASSNAME);
        proxyFilter.beginWikiObject(COMMENTS_CLASSNAME, commentParameters);

        try {
            // object properties
            ConfluenceProperties commentProperties = pageComments.get(commentId);

            // creator
            String commentCreator;
            if (commentProperties.containsKey("creatorName")) {
                // old creator reference by name
                commentCreator = commentProperties.getString("creatorName");
            } else {
                // new creator reference by key
                commentCreator = commentProperties.getString("creator");
                commentCreator = confluencePackage.resolveUserName(commentCreator, commentCreator);
            }
            String commentCreatorReference = confluenceConverter.toUserReference(commentCreator);

            // content
            String commentBodyContent = this.confluencePackage.getCommentText(commentProperties);
            int commentBodyType = this.confluencePackage.getCommentBodyType(commentProperties);
            String commentText = commentBodyContent;
            if (commentBodyContent != null && this.properties.isConvertToXWiki()) {
                try {
                    commentText = convertToXWiki21(commentBodyContent, commentBodyType);
                } catch (Exception e) {
                    this.logger.error("Failed to convert content of the comment with id [{}] for page [{}]",
                        commentId, createPageIdentifier(pageProperties), e);
                }
            }

            // creation date
            Date commentDate = null;
            try {
                commentDate = this.confluencePackage.getDate(commentProperties, "creationDate");
            } catch (Exception e) {
                this.logger.error("Failed to parse the creation date of the comment [{}] in page [{}]",
                    commentId, createPageIdentifier(pageProperties), e);
            }

            // parent (replyto)
            Integer parentIndex = null;
            if (commentProperties.containsKey("parent")) {
                Long parentId = commentProperties.getLong("parent");
                parentIndex = commentIndices.get(parentId);
            }

            proxyFilter.onWikiObjectProperty("author", commentCreatorReference, FilterEventParameters.EMPTY);
            proxyFilter.onWikiObjectProperty("comment", commentText, FilterEventParameters.EMPTY);
            proxyFilter.onWikiObjectProperty("date", commentDate, FilterEventParameters.EMPTY);
            proxyFilter.onWikiObjectProperty("replyto", parentIndex, FilterEventParameters.EMPTY);
        } finally {
            proxyFilter.endWikiObject(COMMENTS_CLASSNAME, commentParameters);
        }
    }

    private void addBlogDescriptorPage(ConfluenceFilter proxyFilter) throws FilterException
    {
        // Apply the standard entity name validator
        String documentName = confluenceConverter.toEntityName(WEB_HOME);

        // > WikiDocument
        proxyFilter.beginWikiDocument(documentName, FilterEventParameters.EMPTY);
        try {
            FilterEventParameters blogParameters = new FilterEventParameters();

            // Blog Object
            blogParameters.put(WikiObjectFilter.PARAMETER_CLASS_REFERENCE, BLOG_CLASSNAME);

            String blogSpaceName = this.properties.getBlogSpaceName();
            proxyFilter.beginWikiObject(BLOG_CLASSNAME, blogParameters);
            try {
                // Object properties
                proxyFilter.onWikiObjectProperty("title", blogSpaceName, FilterEventParameters.EMPTY);
                proxyFilter.onWikiObjectProperty("postsLayout", "image", FilterEventParameters.EMPTY);
                proxyFilter.onWikiObjectProperty("displayType", "paginated", FilterEventParameters.EMPTY);
            } finally {
                proxyFilter.endWikiObject(BLOG_CLASSNAME, blogParameters);
            }
        } finally {
            // < WikiDocument
            proxyFilter.endWikiDocument(documentName, FilterEventParameters.EMPTY);
        }
    }

    private void addBlogPostObject(String title, String content, Date publishDate, ConfluenceFilter proxyFilter)
        throws FilterException
    {
        FilterEventParameters blogPostParameters = new FilterEventParameters();

        // Blog Post Object
        blogPostParameters.put(WikiObjectFilter.PARAMETER_CLASS_REFERENCE, BLOG_POST_CLASSNAME);

        proxyFilter.beginWikiObject(BLOG_POST_CLASSNAME, blogPostParameters);
        try {
            // Object properties
            proxyFilter.onWikiObjectProperty("title", title, FilterEventParameters.EMPTY);
            proxyFilter.onWikiObjectProperty("content", content, FilterEventParameters.EMPTY);
            proxyFilter.onWikiObjectProperty("publishDate", publishDate, FilterEventParameters.EMPTY);

            // The blog post 'published' property is always set to true because unpublished blog posts are draft pages
            // and draft pages are skipped during the import.
            proxyFilter.onWikiObjectProperty("published", 1, FilterEventParameters.EMPTY);
            proxyFilter.onWikiObjectProperty("hidden", 0, FilterEventParameters.EMPTY);
        } finally {
            proxyFilter.endWikiObject(BLOG_POST_CLASSNAME, blogPostParameters);
        }
    }

    private ConfluenceProperties getContentProperties(ConfluenceProperties properties, String key)
        throws FilterException
    {
        try {
            return this.confluencePackage.getContentProperties(properties, key);
        } catch (Exception e) {
            throw new FilterException("Failed to parse content properties", e);
        }
    }
}
