/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.sling.jcr.contentloader.it;

import java.io.IOException;
import java.io.InputStream;
import java.util.Map;

import javax.inject.Inject;
import javax.jcr.RepositoryException;
import javax.jcr.Session;

import com.google.common.collect.Multimap;
import org.apache.sling.jcr.api.SlingRepository;
import org.apache.sling.resource.presence.ResourcePresence;
import org.apache.sling.testing.paxexam.SlingOptions;
import org.apache.sling.testing.paxexam.TestSupport;
import org.junit.After;
import org.junit.Before;
import org.ops4j.pax.exam.Option;
import org.ops4j.pax.exam.options.ModifiableCompositeOption;
import org.ops4j.pax.exam.util.Filter;
import org.ops4j.pax.tinybundles.core.TinyBundle;
import org.ops4j.pax.tinybundles.core.TinyBundles;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.Constants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.apache.sling.testing.paxexam.SlingOptions.slingQuickstartOakTar;
import static org.apache.sling.testing.paxexam.SlingOptions.slingResourcePresence;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.ops4j.pax.exam.CoreOptions.composite;
import static org.ops4j.pax.exam.CoreOptions.junitBundles;
import static org.ops4j.pax.exam.CoreOptions.mavenBundle;
import static org.ops4j.pax.exam.CoreOptions.streamBundle;
import static org.ops4j.pax.exam.cm.ConfigurationAdminOptions.factoryConfiguration;
import static org.ops4j.pax.exam.cm.ConfigurationAdminOptions.newConfiguration;
import static org.ops4j.pax.tinybundles.core.TinyBundles.withBnd;

public abstract class ContentloaderTestSupport extends TestSupport {

    @Inject
    protected BundleContext bundleContext;

    @Inject
    protected SlingRepository repository;

    protected Session session;

    protected static final String SLING_INITIAL_CONTENT_HEADER = "Sling-Initial-Content";

    protected static final String BUNDLE_SYMBOLICNAME = "TEST-CONTENT-BUNDLE";

    protected static final String DEFAULT_PATH_IN_BUNDLE = "test-initial-content";

    protected static final String CONTENT_ROOT_PATH = "/test-content/" + BUNDLE_SYMBOLICNAME;

    private final Logger logger = LoggerFactory.getLogger(ContentloaderTestSupport.class);

    ContentloaderTestSupport() {
    }

    @Inject
    @Filter(value = "(path=" + CONTENT_ROOT_PATH + ")")
    private ResourcePresence resourcePresence;

    public ModifiableCompositeOption baseConfiguration() {
        final Option contentloader = mavenBundle().groupId("org.apache.sling").artifactId("org.apache.sling.jcr.contentloader").version(SlingOptions.versionResolver.getVersion("org.apache.sling", "org.apache.sling.jcr.contentloader"));
        return composite(
            super.baseConfiguration(),
            quickstart(),
            // Sling JCR ContentLoader
            testBundle("bundle.filename"),
            factoryConfiguration("org.apache.sling.resource.presence.internal.ResourcePresenter")
                .put("path", CONTENT_ROOT_PATH)
                .asOption(),
            // testing
            newConfiguration("org.apache.sling.jcr.base.internal.LoginAdminWhitelist")
                .put("whitelist.bundles.regexp", "PAXEXAM-PROBE-.*")
                .asOption(),
            slingResourcePresence(),
            junitBundles()
        ).remove(
            contentloader
        );
    }

    protected ModifiableCompositeOption quickstart() {
        final int httpPort = findFreePort();
        final String workingDirectory = workingDirectory();
        return slingQuickstartOakTar(workingDirectory, httpPort);
    }

    @Before
    public void setup() throws Exception {
        session = repository.loginAdministrative(null);
    }

    @After
    public void teardown() {
        session.logout();
    }

    /**
     * Add content to our test bundle
     */
    protected void addContent(final TinyBundle bundle, String pathInBundle, String resourcePath) throws IOException {
        pathInBundle += "/" + resourcePath;
        resourcePath = "/initial-content/" + resourcePath;
        try (final InputStream is = getClass().getResourceAsStream(resourcePath)) {
            assertNotNull("Expecting resource to be found:" + resourcePath, is);
            logger.info("Adding resource to bundle, path={}, resource={}", pathInBundle, resourcePath);
            bundle.add(pathInBundle, is);
        }
    }

    protected Option buildInitialContentBundle(final String header, final Multimap<String, String> content) throws IOException {
        final TinyBundle bundle = TinyBundles.bundle();
        bundle.set(Constants.BUNDLE_SYMBOLICNAME, BUNDLE_SYMBOLICNAME);
        bundle.set(SLING_INITIAL_CONTENT_HEADER, header);
        for (final Map.Entry<String, String> entry : content.entries()) {
            addContent(bundle, entry.getKey(), entry.getValue());
        }
        return streamBundle(
            bundle.build(withBnd())
        ).start();
    }

    protected Bundle findBundle(final String symbolicName) {
        for (final Bundle bundle : bundleContext.getBundles()) {
            if (symbolicName.equals(bundle.getSymbolicName())) {
                return bundle;
            }
        }
        return null;
    }

    protected void assertProperty(final Session session, final String path, final String expected) throws RepositoryException {
        assertTrue("Expecting property " + path, session.itemExists(path));
        final String actual = session.getProperty(path).getString();
        assertEquals("Expecting correct value at " + path, expected, actual);
    }

}
