/*
 * (C) Copyright 2026 Hyland (http://hyland.com/) and others.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * Contributors:
 *     Thibaud Arguillere (with the help of opencode/Copilot - Claude Opus 5.5)
 */
package nuxeo.labs.webdav.directedit;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;

import javax.xml.parsers.DocumentBuilderFactory;

import org.junit.Before;
import org.junit.Test;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * Pins the deployment fragment that mounts the WebDAV endpoint.
 * <p>
 * Web UI is optional: the endpoint and the Automation operations also serve custom UIs. A fragment requiring a bundle
 * that is not deployed is never preprocessed, so requiring {@code org.nuxeo.web.ui} would leave the servlet unmounted
 * on a server without Web UI, with only an ERROR line at startup. The fragment requires the {@code all} marker
 * instead, which still runs it after the Web UI fragment, as the i18n appends need. Fragment ordering itself cannot be
 * exercised by a unit test, hence this structural check.
 *
 * @since 2025.1
 */
public class TestDeploymentFragment {

    protected static final String FRAGMENT_PATH = "OSGI-INF/deployment-fragment.xml";

    protected Element fragment;

    /**
     * Reads the fragment from this module's own output directory: every Nuxeo jar on the test classpath ships a file
     * with the same name, so a class loader lookup could return another bundle's.
     */
    @Before
    public void loadFragment() throws Exception {
        File classesDir = codeSourceOf(WebDavDirectEditServiceImpl.class);
        File file = new File(classesDir, FRAGMENT_PATH);
        assertTrue("could not find " + file, file.isFile());
        fragment = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file).getDocumentElement();
    }

    @Test
    public void shouldRunAfterEveryOtherFragment() {
        assertTrue("the fragment must require the 'all' marker, so the i18n appends run after Web UI's own files",
                getRequires().contains("all"));
    }

    @Test
    public void shouldNotRequireWebUi() {
        assertFalse("requiring org.nuxeo.web.ui unmounts the endpoint on servers without Web UI",
                getRequires().contains("org.nuxeo.web.ui"));
    }

    @Test
    public void shouldMountTheOfficeEditServlet() {
        NodeList patterns = fragment.getElementsByTagName("url-pattern");
        var values = new ArrayList<String>();
        for (int i = 0; i < patterns.getLength(); i++) {
            values.add(patterns.item(i).getTextContent().trim());
        }
        assertTrue("the servlet mapping is gone: " + values, values.contains("/site/officeedit/*"));
    }

    /**
     * Returns the top-level {@code <require>} entries only.
     */
    protected List<String> getRequires() {
        var requires = new ArrayList<String>();
        NodeList children = fragment.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child.getNodeType() == Node.ELEMENT_NODE && "require".equals(child.getNodeName())) {
                requires.add(child.getTextContent().trim());
            }
        }
        return requires;
    }

    protected static File codeSourceOf(Class<?> klass) throws URISyntaxException {
        return new File(klass.getProtectionDomain().getCodeSource().getLocation().toURI());
    }
}
