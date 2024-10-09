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
package org.xwiki.contrib.confluence.urlmapping.internal;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.xwiki.contrib.confluence.resolvers.ConfluencePageIdResolver;
import org.xwiki.contrib.confluence.resolvers.ConfluencePageTitleResolver;
import org.xwiki.contrib.confluence.resolvers.ConfluenceResolverException;
import org.xwiki.contrib.confluence.resolvers.ConfluenceSpaceKeyResolver;
import org.xwiki.contrib.urlmapping.URLMappingResult;
import org.xwiki.contrib.urlmapping.suggestions.URLMappingSuggestionUtils;
import org.xwiki.model.EntityType;
import org.xwiki.model.reference.AttachmentReference;
import org.xwiki.model.reference.DocumentReference;
import org.xwiki.model.reference.EntityReference;
import org.xwiki.rendering.block.Block;
import org.xwiki.rendering.block.WordBlock;
import org.xwiki.resource.entity.EntityResourceAction;
import org.xwiki.resource.entity.EntityResourceReference;
import org.xwiki.test.annotation.BeforeComponent;
import org.xwiki.test.annotation.ComponentList;
import org.xwiki.test.junit5.mockito.ComponentTest;
import org.xwiki.test.junit5.mockito.InjectMockComponents;
import org.xwiki.test.junit5.mockito.MockComponent;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ComponentTest
@ComponentList({
    ConfluenceAttachmentURLMapper.class,
    ConfluenceDisplayURLMapper.class,
    ConfluenceViewPageURLMapper.class,
    ConfluenceTinyLinkURLMapper.class
})
class ConfluenceURLMappingTest
{
    private static final String MY_SPACE = "MySpace";
    private static final DocumentReference MY_DOC_REF = new DocumentReference(
        "xwiki",
        List.of("MigrationRoot", MY_SPACE, "MyDoc"),
        "WebHome"
    );
    private static final EntityResourceReference DOC_RR = new EntityResourceReference(
        MY_DOC_REF, EntityResourceAction.VIEW);

    @MockComponent
    private ConfluencePageTitleResolver confluencePageTitleResolver;

    @MockComponent
    private ConfluenceSpaceKeyResolver confluenceSpaceKeyResolver;

    @MockComponent
    private ConfluencePageIdResolver confluencePageIdResolver;

    @MockComponent
    private URLMappingSuggestionUtils suggestionUtils;


    @InjectMockComponents
    ConfluenceURLMappingPrefixHandler handler;

    @BeforeComponent
    void setup() throws ConfluenceResolverException
    {
        when(suggestionUtils.getSuggestionsFromDocumentReference(any())).thenReturn(new WordBlock("mysuggestion"));
        when(confluencePageIdResolver.getDocumentById(42)).thenReturn(MY_DOC_REF);
        when(confluencePageTitleResolver.getDocumentByTitle("MySpace", "My Doc")).thenReturn(MY_DOC_REF);

        // For tiny link tests
        when(confluencePageIdResolver.getDocumentById(139414)).thenReturn(
            new EntityReference("OK.liAC", EntityType.DOCUMENT));
        when(confluencePageIdResolver.getDocumentById(724765494)).thenReturn(
            new EntityReference("OK.NgszKw", EntityType.DOCUMENT));
        when(confluencePageIdResolver.getDocumentById(138313)).thenReturn(
            new EntityReference("OK.SRwC", EntityType.DOCUMENT));
        when(confluencePageIdResolver.getDocumentById(139483)).thenReturn(
            new EntityReference("OK.2yAC", EntityType.DOCUMENT));
        when(confluencePageIdResolver.getDocumentById(956713432)).thenReturn(
            new EntityReference("OK.2EkGOQ", EntityType.DOCUMENT));
        when(confluencePageIdResolver.getDocumentById(139521)).thenReturn(
            new EntityReference("OK.ASEC", EntityType.DOCUMENT));
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "display/MySpace/My+Doc?param=thatwedontcareabout",
        "display/MySpace/My+Doc"
    })
    void convertDisplayURL(String path)
    {
        URLMappingResult converted = handler.convert(path, "get", null);
        assertEquals(DOC_RR, converted.getResourceReference());
        assertEquals("", converted.getURL());
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "pages/viewpage.action?pageId=42&param=thatwedontcareabout",
        "pages/viewpage.action?pageId=42"
    })
    void convertViewPageActionURL(String path)
    {
        URLMappingResult converted = handler.convert(path, "get", null);
        assertEquals(DOC_RR, converted.getResourceReference());
        assertEquals("", converted.getURL());
    }


    @ParameterizedTest
    @ValueSource(strings = {
        "download/attachments/42/hello+world.txt?param=thatwedontcareabout",
        "download/attachments/42/hello+world.txt"
    })
    void convertAttachmentURL(String path)
    {
        URLMappingResult converted = handler.convert(path, "get", null);
        assertEquals(
            new EntityResourceReference(
                new AttachmentReference("hello world.txt", MY_DOC_REF),
                EntityResourceAction.VIEW),
            converted.getResourceReference());
        assertEquals("", converted.getURL());
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "hello/pages/viewpage.action?pageId=42",
        "hello/download/attachments/42/hello+world.txt",
        "hello/display/MySpace/My+Doc?param=thatwedontcareabout",
        "pages/viewpages.action?pageId=42",
        "pages/pages/viewpages.action?pageId=42",
        "download/attachment/42/hello+world.txt",
        "display/display/MySpace/My+Doc?param=thatwedontcareabout",
        "displays/MySpace/My+Doc?param=thatwedontcareabout"
    })
    void dontConvertWrongURL(String path)
    {
        URLMappingResult converted = handler.convert(path, "get", null);
        assertFailedConversion(converted);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "pages/viewpage.action?pageId=1337",
        "download/attachments/43/hello+world.txt"
    })
    void handleNotFoundCorrectURLs(String path)
    {
        URLMappingResult converted = handler.convert(path, "get", null);
        assertFailedConversion(converted);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "display/MyUnknownSpace/My+Doc",
        "display/MySpace/My+Unknown+Doc"
    })
    void notFoundCorrectDisplayURLsHaveSuggestions(String path)
    {
        URLMappingResult converted = handler.convert(path, "get", null);
        assertInstanceOf(Block.class, converted.getSuggestions());
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "x/liAC",
        "x/NgszKw",
        "x/SRwC",
        "x/2yAC",
        "x/2EkGOQ",
        "x/ASEC"
    })
    void handleTinyLinks(String path) throws ConfluenceResolverException
    {
        URLMappingResult converted = handler.convert(path, "get", null);
        assertEquals(
            new EntityResourceReference(
                new EntityReference("OK." + path.substring(2), EntityType.DOCUMENT),
                EntityResourceAction.VIEW),
            converted.getResourceReference());
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "x/OQUAAA",
        "x/=[@{]"
    })
    void tinyLinksBrokenOrDocIdNotFound()
    {
        URLMappingResult converted = handler.convert("/x/abab", "get", null);
        assertFailedConversion(converted);
    }

    private void assertFailedConversion(URLMappingResult conversion)
    {
        assertEquals(404, conversion.getHTTPStatus());
        assertEquals("", conversion.getURL());
        assertNull(conversion.getResourceReference());
        assertNull(conversion.getSuggestions());
    }
}