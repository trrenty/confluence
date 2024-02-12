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
import org.xwiki.job.event.status.JobProgressManager;
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

    private static final String XWIKI_REDIRECT_CLASS = "XWiki.RedirectClass";

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
    private ConfluenceXMLPackage confluencePackage;

    @Inject
    private Logger logger;

    private final Map<String, Integer> macrosIds = new HashMap<>();

    private ConfluenceIdRangeList objectIdRanges;

    private List<Long> nextIdsForObjectIdRanges;

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

    private int countPages(Map<Long, List<Long>> pages)
    {
        return pages.values().stream().mapToInt(List::size).sum();
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

    private void readInternal(Object filter, ConfluenceFilter proxyFilter) throws FilterException
    {
        pushLevelProgress(2);
        // Prepare package
        try {
            this.confluencePackage.read(this.properties.getSource());
        } catch (Exception e) {
            throw new FilterException("Failed to read package", e);
        }

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

        int pagesCount = willSendPages
            ? (
                (properties.isNonBlogContentEnabled() ? (pages.size() + countPages(pages)) : 0)
                    + (properties.isBlogsEnabled() ? countPages(blogPages) : 0)
            )
            : 0;

        if (this.properties.isUsersEnabled()) {
            Collection<Long> users = this.confluencePackage.getInternalUsers();
            // TODO get users in new format (this.confluencePackage.getAllUsers())
            Collection<Long> groups = this.confluencePackage.getGroups();

            pushLevelProgress(users.size() + groups.size() + pagesCount);

            sendUsersAndGroups(users, groups, proxyFilter);
        } else {
            pushLevelProgress(pagesCount);
        }

        beginSpace(properties.getRootSpace(), proxyFilter);
        try {
            Set<Long> rootSpaces = new LinkedHashSet<>();
            rootSpaces.addAll(pages.keySet());
            rootSpaces.addAll(blogPages.keySet());

            for (Long spaceId : rootSpaces) {
                if (!shouldSendObject(spaceId) || pagesCount == 0) {
                    continue;
                }

                List<Long> regularPageIds = pages.getOrDefault(spaceId, Collections.emptyList());
                List<Long> blogPageIds = blogPages.get(spaceId);
                sendConfluenceRootSpace(spaceId, filter, proxyFilter, regularPageIds, blogPageIds);
            }
        } finally {
            endSpace(properties.getRootSpace(), proxyFilter);
        }

        popLevelProgress();
        // Cleanup

        observationManager.notify(new ConfluenceFilteredEvent(), this, this.confluencePackage);

        closeConfluencePackage();
        popLevelProgress();
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
        List<Long> pages, List<Long> blogPages) throws FilterException
    {
        ConfluenceProperties spaceProperties;
        try {
            spaceProperties = this.confluencePackage.getSpaceProperties(spaceId);
        } catch (ConfigurationException e) {
            throw new FilterException("Failed to get space properties", e);
        }

        String spaceKey = confluenceConverter.toEntityName(ConfluenceXMLPackage.getSpaceKey(spaceProperties));

        FilterEventParameters spaceParameters = new FilterEventParameters();

        if (this.properties.isVerbose()) {
            this.logger.info("Sending Confluence space [{}], id=[{}]", spaceKey, spaceId);
        }

        // > WikiSpace
        proxyFilter.beginWikiSpace(spaceKey, spaceParameters);
        try {
            if (this.properties.isRightsEnabled()) {
                sendSpaceRights(proxyFilter, spaceProperties, spaceKey, spaceId);
            }

            if (this.properties.isContentsEnabled() || this.properties.isRightsEnabled()) {
                sendPages(spaceKey, pages, filter, proxyFilter);
            }

            if (this.properties.isBlogsEnabled() && blogPages != null && !blogPages.isEmpty()) {
                sendBlogs(spaceKey, blogPages, filter, proxyFilter);
            }
        } finally {
            // < WikiSpace
            proxyFilter.endWikiSpace(spaceKey, spaceParameters);
            if (this.properties.isVerbose()) {
                this.logger.info("Finished sending Confluence space [{}], id=[{}]", spaceKey, spaceId);
            }
        }
    }

    private void sendPage(long pageId, String spaceKey, Object filter, ConfluenceFilter proxyFilter)
    {
        this.progress.startStep(this);
        if (this.properties.isIncluded(pageId)) {
            try {
                readPage(pageId, spaceKey, filter, proxyFilter);
            } catch (Exception e) {
                logger.error("Failed to filter the page with id [{}]", createPageIdentifier(pageId, spaceKey), e);
            }
        }
        this.progress.endStep(this);
    }

    private void sendBlogs(String spaceKey, List<Long> blogPages, Object filter, ConfluenceFilter proxyFilter)
        throws FilterException
    {
        // Blog space
        String blogSpaceKey = confluenceConverter.toEntityName(this.properties.getBlogSpaceName());

        // > WikiSpace
        proxyFilter.beginWikiSpace(blogSpaceKey, FilterEventParameters.EMPTY);
        try {
            // Blog Descriptor page
            addBlogDescriptorPage(proxyFilter);

            // Blog post pages
            sendPages(spaceKey, blogPages, filter, proxyFilter);
        } finally {
            // < WikiSpace
            proxyFilter.endWikiSpace(blogSpaceKey, FilterEventParameters.EMPTY);
        }
    }

    private void sendPages(String spaceKey, List<Long> blogPages, Object filter, ConfluenceFilter proxyFilter)
    {
        for (Long pageId : blogPages) {
            sendPage(pageId, spaceKey, filter, proxyFilter);
        }
    }

    private void sendSpaceRights(ConfluenceFilter proxyFilter, ConfluenceProperties spaceProperties, String spaceKey,
        long spaceId) throws FilterException
    {
        Collection<Object> spacePermissions = spaceProperties.getList(ConfluenceXMLPackage.KEY_SPACE_PERMISSIONS);
        if (!spacePermissions.isEmpty()) {
            boolean webPreferencesStarted = false;

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

                    ConfluenceRightData confluenceRight = getConfluenceRightData(spacePermissionProperties);
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
                        if (!webPreferencesStarted) {
                            proxyFilter.beginWikiDocument(WEB_PREFERENCES, new FilterEventParameters());
                            webPreferencesStarted = true;
                        }
                        sendRight(proxyFilter, group, right, users, true);
                    }
                }

            } finally {
                if (webPreferencesStarted) {
                    proxyFilter.endWikiDocument(WEB_PREFERENCES, new FilterEventParameters());
                }
            }
        }
    }

    private ConfluenceRightData getConfluenceRightData(ConfluenceProperties permProperties)
        throws FilterException
    {
        String type = permProperties.getString(ConfluenceXMLPackage.KEY_PERMISSION_TYPE, "");
        String groupStr = permProperties.getString(ConfluenceXMLPackage.KEY_SPACEPERMISSION_GROUP, null);
        if (groupStr == null || groupStr.isEmpty()) {
            groupStr = permProperties.getString(ConfluenceXMLPackage.KEY_CONTENTPERMISSION_GROUP, null);
        }
        String group = (groupStr == null || groupStr.isEmpty())
            ? ""
            : (confluenceConverter.toUserReference(getConfluenceToXWikiGroupName(groupStr)));

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

        return new ConfluenceRightData(type, group, users);
    }

    private static class ConfluenceRightData
    {
        public final String type;
        public final String group;
        public final String users;

        ConfluenceRightData(String type, String group, String users)
        {
            this.type = type;
            this.group = group;
            this.users = users;
        }
    }

    private static void sendRight(ConfluenceFilter proxyFilter, String group, Right right, String users, boolean space)
        throws FilterException
    {
        FilterEventParameters rightParameters = new FilterEventParameters();
        // Page report object
        String rightClassName = space ? XWIKIGLOBALRIGHTS_CLASSNAME : XWIKIRIGHTS_CLASSNAME;
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
                    documentName = this.properties.getSpacePageName();
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
        throws FilterException
    {
        // Switch the wiki if a specific one is forced
        if (this.properties.getUsersWiki() != null) {
            proxyFilter.beginWiki(this.properties.getUsersWiki(), FilterEventParameters.EMPTY);
        }

        sendUsers(users, proxyFilter);
        sendGroups(groups, proxyFilter);

        // Get back to default wiki
        if (this.properties.getUsersWiki() != null) {
            proxyFilter.endWiki(this.properties.getUsersWiki(), FilterEventParameters.EMPTY);
        }
    }

    private void sendGroups(Collection<Long> groupIds, ConfluenceFilter proxyFilter) throws FilterException
    {
        // Group groups by XWiki group name. There can be several Confluence groups mapping to a unique XWiki group.
        Map<String, Collection<ConfluenceProperties>> groupsByXWikiName = getGroupsByXWikiName(groupIds);

        // Loop over the XWiki groups
        for (Map.Entry<String, Collection<ConfluenceProperties>> groupEntry: groupsByXWikiName.entrySet()) {
            String groupName = groupEntry.getKey();
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

            // > Group
            proxyFilter.beginGroupContainer(groupName, groupParameters);

            // We add members of all the Confluence groups mapped to this XWiki group to the XWiki group.
            Collection<String> alreadyAddedMembers = new HashSet<>();
            for (ConfluenceProperties groupProperties : groups) {
                sendUserMembers(proxyFilter, groupProperties, alreadyAddedMembers);
                sendGroupMembers(proxyFilter, groupProperties, alreadyAddedMembers);
            }

            // < Group
            proxyFilter.endGroupContainer(groupName, groupParameters);

            this.progress.endStep(this);
        }
    }

    private void sendGroupMembers(ConfluenceFilter proxyFilter, ConfluenceProperties groupProperties,
        Collection<String> alreadyAddedMembers)
    {
        if (groupProperties.containsKey(ConfluenceXMLPackage.KEY_GROUP_MEMBERGROUPS)) {
            List<Long> groupMembers = this.confluencePackage.getLongList(groupProperties,
                ConfluenceXMLPackage.KEY_GROUP_MEMBERGROUPS);
            for (Long memberInt : groupMembers) {
                FilterEventParameters memberParameters = new FilterEventParameters();

                try {
                    String memberId = getConfluenceToXWikiGroupName(
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
        Collection<String> alreadyAddedMembers)
    {
        if (groupProperties.containsKey(ConfluenceXMLPackage.KEY_GROUP_MEMBERUSERS)) {
            List<Long> groupMembers =
                this.confluencePackage.getLongList(groupProperties, ConfluenceXMLPackage.KEY_GROUP_MEMBERUSERS);
            for (Long memberInt : groupMembers) {
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
        for (long groupId : groups) {
            this.progress.startStep(this);

            ConfluenceProperties groupProperties;
            try {
                groupProperties = this.confluencePackage.getGroupProperties(groupId);
            } catch (ConfigurationException e) {
                throw new FilterException("Failed to get group properties", e);
            }

            String groupName = getConfluenceToXWikiGroupName(
                groupProperties.getString(ConfluenceXMLPackage.KEY_GROUP_NAME, String.valueOf(groupId)));

            if (!groupName.isEmpty()) {
                Collection<ConfluenceProperties> l = groupsByXWikiName.getOrDefault(groupName, new ArrayList<>());
                l.add(groupProperties);
                groupsByXWikiName.put(groupName, l);
            }
        }
        return groupsByXWikiName;
    }

    private void sendUsers(Collection<Long> users, ConfluenceFilter proxyFilter) throws FilterException
    {
        for (Long userId : users) {
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

    private String getConfluenceToXWikiGroupName(String groupName)
    {
        if (!this.properties.isConvertToXWiki() || this.properties.getGroupMapping() == null) {
            return groupName;
        }

        return this.properties.getGroupMapping().getOrDefault(groupName, groupName);
    }

    private void readPage(long pageId, String spaceKey, Object filter, ConfluenceFilter proxyFilter)
        throws FilterException
    {
        if (!shouldSendObject(pageId)) {
            return;
        }

        ConfluenceProperties pageProperties = getPageProperties(pageId);

        if (pageProperties == null) {
            this.logger.error("Can't find page with id [{}]", createPageIdentifier(pageId, spaceKey));
            return;
        }

        String title = pageProperties.getString(ConfluenceXMLPackage.KEY_PAGE_TITLE);

        String documentName;
        if (pageProperties.containsKey(ConfluenceXMLPackage.KEY_PAGE_HOMEPAGE)) {
            documentName = this.properties.getSpacePageName();
            if (documentName == null || documentName.isEmpty()) {
                return;
            }

            if (!documentName.equals(title) && this.properties.isHomeRedirectEnabled()) {
                // Set up a redirect page for the home page, so we don't break links from spaces that are not imported
                // in the same package
                FilterEventParameters redirectDocParameters = new FilterEventParameters();
                redirectDocParameters.put(WikiDocumentFilter.PARAMETER_HIDDEN, true);
                proxyFilter.beginWikiDocument(title, redirectDocParameters);
                FilterEventParameters redirectParameters = new FilterEventParameters();
                redirectParameters.put(WikiObjectFilter.PARAMETER_CLASS_REFERENCE, XWIKI_REDIRECT_CLASS);
                proxyFilter.beginWikiObject(XWIKI_REDIRECT_CLASS, redirectParameters);
                EntityReference location = getReferenceForDocument(spaceKey, this.properties.getSpacePageName());
                proxyFilter.onWikiObjectProperty("location", location, FilterEventParameters.EMPTY);
                proxyFilter.endWikiObject(XWIKI_REDIRECT_CLASS, redirectParameters);
                proxyFilter.endWikiDocument(title, FilterEventParameters.EMPTY);
            }
        } else {
            documentName = title;
        }

        // Skip pages with empty title
        if (StringUtils.isEmpty(documentName)) {
            this.logger.warn("Found a page without a name or title (id={}). Skipping it.",
                createPageIdentifier(pageId, spaceKey));

            return;
        }

        // Skip deleted, archived or draft pages
        String contentStatus = pageProperties.getString(ConfluenceXMLPackage.KEY_PAGE_CONTENT_STATUS);
        if (contentStatus != null
            && (
                contentStatus.equals("deleted")
                || (contentStatus.equals("archived") && !this.properties.isArchivedDocumentsEnabled())
                || contentStatus.equals("draft")
            )
        ) {
            return;
        }

        FilterEventParameters documentParameters = new FilterEventParameters();
        if (this.properties.getDefaultLocale() != null) {
            documentParameters.put(WikiDocumentFilter.PARAMETER_LOCALE, this.properties.getDefaultLocale());
        }

        // Apply the standard entity name validator
        documentName = confluenceConverter.toEntityName(documentName);

        if (this.properties.isVerbose()) {
            this.logger.info("Sending page [{}], Confluence id=[{}]", documentName, pageId);
        }

        // > WikiDocument
        proxyFilter.beginWikiDocument(documentName, documentParameters);

        try {
            if (this.properties.isRightsEnabled()) {
                sendPageRights(pageId, spaceKey, proxyFilter, pageProperties);
            }

            if (this.properties.isContentsEnabled()) {
                sendRevisions(pageId, spaceKey, filter, proxyFilter, pageProperties);
            }
        } finally {
            // < WikiDocument
            proxyFilter.endWikiDocument(documentName, documentParameters);
            if (!macrosIds.isEmpty()) {
                if (properties.isVerbose()) {
                    logger.info(ConfluenceFilter.LOG_MACROS_FOUND, "The following macros [{}] were found on page [{}].",
                        macrosIds, documentName);
                }
                macrosIds.clear();
            }
        }
    }

    private void sendRevisions(long pageId, String spaceKey, Object filter, ConfluenceFilter proxyFilter,
        ConfluenceProperties pageProperties) throws FilterException
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
                    createPageIdentifier(pageId, spaceKey), e);
            }
        }

        if (pageProperties.containsKey(ConfluenceXMLPackage.KEY_PAGE_REVISION)) {
            documentLocaleParameters.put(WikiDocumentFilter.PARAMETER_LASTREVISION,
                pageProperties.getString(ConfluenceXMLPackage.KEY_PAGE_REVISION));
        }

        // > WikiDocumentLocale
        proxyFilter.beginWikiDocumentLocale(locale, documentLocaleParameters);

        try {
            // Revisions
            if (properties.isHistoryEnabled() && pageProperties.containsKey(ConfluenceXMLPackage.KEY_PAGE_REVISIONS)) {
                List<Long> revisions =
                    this.confluencePackage.getLongList(pageProperties, ConfluenceXMLPackage.KEY_PAGE_REVISIONS);
                Collections.sort(revisions);
                for (Long revisionId : revisions) {
                    if (shouldSendObject(revisionId)) {
                        readPageRevision(revisionId, spaceKey, filter, proxyFilter);
                    }
                }
            }

            // Current version
            // Note: no need to check whether the object should be sent. Indeed, this is already checked by an upper
            // function
            readPageRevision(pageId, spaceKey, filter, proxyFilter);
        } finally {
            // < WikiDocumentLocale
            proxyFilter.endWikiDocumentLocale(locale, documentLocaleParameters);
        }
    }

    private void sendPageRights(long pageId, String spaceKey, ConfluenceFilter proxyFilter,
        ConfluenceProperties pageProperties) throws FilterException
    {
        for (Object permissionSetIdObject : pageProperties.getList("contentPermissionSets")) {
            Long permissionSetId = toLong(permissionSetIdObject);
            if (permissionSetId == null) {
                logger.error("Space permission set id is null for space [{}]", spaceKey);
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
                    permissionSetId, createPageIdentifier(pageId, spaceKey), e);
                continue;
            }

            if (permissionSetProperties == null) {
                logger.error("Could not find permission set [{}] for page [{}].",
                    permissionSetId, createPageIdentifier(pageId, spaceKey));
                continue;
            }

            for (Object permissionIdObject : permissionSetProperties.getList("contentPermissions")) {
                Long permissionId = toLong(permissionIdObject);
                if (permissionId == null) {
                    logger.error("Permission id is null for page [{}]", createPageIdentifier(pageId, spaceKey));
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
                        permissionId, createPageIdentifier(pageId, spaceKey), e);
                    continue;
                }

                if (permProperties == null) {
                    logger.error("Could not find permission [{}] for page [{}].",
                        permissionId, createPageIdentifier(pageId, spaceKey));
                    continue;
                }

                ConfluenceRightData confluenceRight = getConfluenceRightData(permProperties);

                ContentPermissionType type;
                try {
                    type = ContentPermissionType.valueOf(confluenceRight.type.toUpperCase());
                } catch (IllegalArgumentException e) {
                    logger.warn("Failed to understand content permission type [{}] for page [{}], permission id [{}].",
                        confluenceRight.type, createPageIdentifier(pageId, spaceKey), permissionId);
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

                if (right != null) {
                    sendRight(proxyFilter, confluenceRight.group, right, confluenceRight.users, false);
                }
            }
        }
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

    private void readPageRevision(Long pageId, String spaceKey, Object filter, ConfluenceFilter proxyFilter)
        throws FilterException
    {
        ConfluenceProperties pageProperties = getPageProperties(pageId);

        if (pageProperties == null) {
            this.logger.warn("Can't find page revision with id [{}]", createPageIdentifier(pageId, spaceKey));
            return;
        }

        readPageRevision(pageId, spaceKey, pageProperties, filter, proxyFilter);
    }

    private void readPageRevision(long pageId, String spaceKey, ConfluenceProperties pageProperties, Object filter,
        ConfluenceFilter proxyFilter) throws FilterException
    {
        String revision = pageProperties.getString(ConfluenceXMLPackage.KEY_PAGE_REVISION);

        FilterEventParameters docRevisionParameters = new FilterEventParameters();

        prepareRevisionMetadata(pageId, spaceKey, pageProperties, docRevisionParameters);

        beginPageRevision(pageId, spaceKey, pageProperties, filter, proxyFilter, revision, docRevisionParameters);

        try {
            readAttachments(pageId, spaceKey, proxyFilter);
            readTags(pageId, spaceKey, pageProperties, proxyFilter);
            readComments(pageId, spaceKey, pageProperties, proxyFilter);
            storeConfluenceDetails(pageId, spaceKey, pageProperties, proxyFilter);
        } finally {
            // < WikiDocumentRevision
            proxyFilter.endWikiDocumentRevision(revision, docRevisionParameters);
        }
    }

    private void beginPageRevision(long pageId, String spaceKey, ConfluenceProperties pageProperties, Object filter,
        ConfluenceFilter proxyFilter, String revision, FilterEventParameters docRevParameters) throws FilterException
    {
        boolean isBlog = pageProperties.containsKey(ConfluenceXMLPackage.KEY_PAGE_BLOGPOST);
        String bodyContent = pageProperties.getString(ConfluenceXMLPackage.KEY_PAGE_BODY, null);
        if (bodyContent != null) {
            // No bodyType means old Confluence syntax
            int bodyType = pageProperties.getInt(ConfluenceXMLPackage.KEY_PAGE_BODY_TYPE, 0);

            if (!isBlog && this.properties.isContentEvents() && filter instanceof Listener) {
                // > WikiDocumentRevision
                proxyFilter.beginWikiDocumentRevision(revision, docRevParameters);

                try {
                    parse(bodyContent, bodyType, this.properties.getMacroContentSyntax(), proxyFilter);
                } catch (Exception e) {
                    this.logger.error("Failed to parse content of page with id [{}]",
                        createPageIdentifier(pageId, spaceKey), e);
                }
                return;
            }

            Syntax bodySyntax = getBodySyntax(pageId, spaceKey, bodyType);

            if (this.properties.isConvertToXWiki()) {
                try {
                    bodyContent = convertToXWiki21(bodyContent, bodyType);
                    bodySyntax = Syntax.XWIKI_2_1;
                } catch (ParseException e) {
                    this.logger.error("Failed to convert content of the page with id [{}]",
                        createPageIdentifier(pageId, spaceKey), e);
                }
            }

            if (!isBlog) {
                docRevParameters.put(WikiDocumentFilter.PARAMETER_CONTENT, bodyContent);
            }

            docRevParameters.put(WikiDocumentFilter.PARAMETER_SYNTAX, bodySyntax);
        }

        // > WikiDocumentRevision
        proxyFilter.beginWikiDocumentRevision(revision, docRevParameters);

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
                    createPageIdentifier(pageId, spaceKey), e);
            }

            addBlogPostObject(pageProperties.getString(ConfluenceXMLPackage.KEY_PAGE_TITLE), bodyContent,
                publishDate, proxyFilter);
        }
    }

    private Syntax getBodySyntax(long pageId, String spaceKey, int bodyType)
    {
        switch (bodyType) {
            case 0:
                return ConfluenceParser.SYNTAX;
            case 2:
                return Syntax.CONFLUENCEXHTML_1_0;
            default:
                this.logger.warn("Unknown body type [{}] for the content of the document with id [{}].", bodyType,
                    createPageIdentifier(pageId, spaceKey));
                return null;
        }
    }

    private void prepareRevisionMetadata(long pageId, String spaceKey, ConfluenceProperties pageProperties,
        FilterEventParameters documentRevisionParameters)
    {
        if (pageProperties.containsKey(ConfluenceXMLPackage.KEY_PAGE_PARENT)) {
            try {
                documentRevisionParameters.put(WikiDocumentFilter.PARAMETER_PARENT,
                    getReferenceFromId(pageProperties, ConfluenceXMLPackage.KEY_PAGE_PARENT));
            } catch (Exception e) {
                this.logger.error("Failed to parse parent for the document with id [{}]",
                    createPageIdentifier(pageId, spaceKey), e);
            }
        }
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
                    createPageIdentifier(pageId, spaceKey), e);
            }
        }
        if (pageProperties.containsKey(ConfluenceXMLPackage.KEY_PAGE_REVISION_COMMENT)) {
            documentRevisionParameters.put(WikiDocumentFilter.PARAMETER_REVISION_COMMENT,
                pageProperties.getString(ConfluenceXMLPackage.KEY_PAGE_REVISION_COMMENT));
        }
        documentRevisionParameters.put(WikiDocumentFilter.PARAMETER_TITLE,
            pageProperties.getString(ConfluenceXMLPackage.KEY_PAGE_TITLE));
    }

    private void readComments(long pageId, String spaceKey, ConfluenceProperties pageProperties,
        ConfluenceFilter proxyFilter) throws FilterException
    {
        Map<Long, ConfluenceProperties> pageComments = new LinkedHashMap<>();
        Map<Long, Integer> commentIndices = new LinkedHashMap<>();
        int commentIndex = 0;
        for (Object commentIdStringObject : pageProperties.getList(ConfluenceXMLPackage.KEY_PAGE_COMMENTS)) {
            Long commentId = Long.parseLong((String) commentIdStringObject);
            if (!shouldSendObject(commentId)) {
                continue;
            }
            ConfluenceProperties commentProperties;
            try {
                commentProperties = this.confluencePackage.getObjectProperties(commentId);
            } catch (ConfigurationException e) {
                logger.error("Failed to get the comment properties [{}] for the page with id [{}]",
                    commentId, createPageIdentifier(pageId, spaceKey), e);
                continue;
            }

            pageComments.put(commentId, commentProperties);
            commentIndices.put(commentId, commentIndex);
            commentIndex++;
        }

        for (Long commentId : pageComments.keySet()) {
            readPageComment(pageId, spaceKey, proxyFilter, commentId, pageComments, commentIndices);
        }
    }

    private void readTags(long pageId, String spaceKey, ConfluenceProperties pageProperties,
        ConfluenceFilter proxyFilter) throws FilterException
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
                    createPageIdentifier(pageId, spaceKey), e);
                continue;
            }

            String tagName = this.confluencePackage.getTagName(tagProperties);
            if (tagName == null) {
                logger.warn("Failed to get the name of tag id [{}] for the page with id [{}].", tagId,
                    createPageIdentifier(pageId, spaceKey));
            } else {
                pageTags.put(tagName, tagProperties);
            }
        }

        if (!pageTags.isEmpty()) {
            readPageTags(proxyFilter, pageTags);
        }
    }

    private void readAttachments(long pageId, String spaceKey, ConfluenceFilter proxyFilter) throws FilterException
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
                    createPageIdentifier(pageId, spaceKey), e);
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
                        createPageIdentifier(pageId, spaceKey), attachmentId, e);
                }
            } else {
                pageAttachments.put(attachmentName, attachmentProperties);
            }
        }

        for (ConfluenceProperties attachmentProperties : pageAttachments.values()) {
            readAttachment(pageId, spaceKey, attachmentProperties, proxyFilter);
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

        ConfluenceProperties pageProperties = this.confluencePackage.getPageProperties(pageId, true);

        Long spaceId = pageProperties.getLong(ConfluenceXMLPackage.KEY_PAGE_SPACE, null);
        String docName = pageProperties.containsKey(ConfluenceXMLPackage.KEY_PAGE_HOMEPAGE)
            ? this.properties.getSpacePageName()
            : confluenceConverter.toEntityName(pageProperties.getString(ConfluenceXMLPackage.KEY_PAGE_TITLE));

        if (StringUtils.isEmpty(docName)) {
            throw new FilterException("Cannot create a reference to the page with id [" + pageId
                + "] because it does not have any title");
        }

        long currentSpaceId = currentProperties.getLong(ConfluenceXMLPackage.KEY_PAGE_SPACE);

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
    private void storeConfluenceDetails(long pageId, String spaceKey, ConfluenceProperties pageProperties,
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
            StringBuilder pageURLBuilder = new StringBuilder();
            if (this.properties.getBaseURLs() != null) {
                pageURLBuilder.append(this.properties.getBaseURLs().get(0).toString());
                pageURLBuilder.append("/wiki/spaces/").append(spaceKey);
                if (!pageProperties.containsKey(ConfluenceXMLPackage.KEY_PAGE_HOMEPAGE)) {
                    String pageName = pageProperties.getString(ConfluenceXMLPackage.KEY_PAGE_TITLE);
                    pageURLBuilder.append("/pages/").append(pageId).append("/").append(pageName);
                }
            }

            proxyFilter.onWikiObjectProperty("id", pageId, FilterEventParameters.EMPTY);
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

        if (this.properties.isConvertToXWiki()) {
            filterProperties.setConverter(createConverter(null));
        }

        BeanInputFilterStreamFactory<ConfluenceXHTMLInputProperties> syntaxFilterFactory =
            ((BeanInputFilterStreamFactory<ConfluenceXHTMLInputProperties>) this.confluenceXHTMLParserFactory);

        return syntaxFilterFactory.createInputFilterStream(filterProperties);
    }

    private void readAttachment(Long pageId, String spaceKey, ConfluenceProperties attachmentProperties,
        ConfluenceFilter proxyFilter) throws FilterException
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
                version, attachmentName, createPageIdentifier(pageId, spaceKey));
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
                    attachmentId, createPageIdentifier(pageId, spaceKey), e);
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
                    attachmentId, createPageIdentifier(pageId, spaceKey), e);
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
                createPageIdentifier(pageId, spaceKey), e);
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

    private void readPageComment(Long pageId, String spaceKey, ConfluenceFilter proxyFilter, Long commentId,
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
            String commentBodyContent = this.confluencePackage.getCommentText(commentId);
            int commentBodyType = this.confluencePackage.getCommentBodyType(commentId);
            String commentText = commentBodyContent;
            if (commentBodyContent != null && this.properties.isConvertToXWiki()) {
                try {
                    commentText = convertToXWiki21(commentBodyContent, commentBodyType);
                } catch (Exception e) {
                    this.logger.error("Failed to convert content of the comment with id [{}] for page [{}]",
                        commentId, createPageIdentifier(pageId, spaceKey), e);
                }
            }

            // creation date
            Date commentDate = null;
            try {
                commentDate = this.confluencePackage.getDate(commentProperties, "creationDate");
            } catch (Exception e) {
                this.logger.error("Failed to parse the creation date of the comment [{}] in page [{}]",
                    commentId, createPageIdentifier(pageId, spaceKey), e);
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
        String documentName = confluenceConverter.toEntityName(this.properties.getSpacePageName());

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
