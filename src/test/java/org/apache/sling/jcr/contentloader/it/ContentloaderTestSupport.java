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

import static org.apache.sling.testing.paxexam.SlingOptions.slingQuickstartOakTar;
import static org.apache.sling.testing.paxexam.SlingOptions.slingResourcePresence;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.fail;
import static org.ops4j.pax.exam.CoreOptions.junitBundles;
import static org.ops4j.pax.exam.CoreOptions.mavenBundle;
import static org.ops4j.pax.exam.cm.ConfigurationAdminOptions.newConfiguration;

import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import javax.inject.Inject;
import javax.jcr.Session;

import org.apache.sling.jcr.api.SlingRepository;
import org.apache.sling.testing.paxexam.SlingOptions;
import org.apache.sling.testing.paxexam.TestSupport;
import org.junit.After;
import org.junit.Before;
import org.ops4j.pax.exam.Configuration;
import org.ops4j.pax.exam.Option;
import org.ops4j.pax.exam.options.CompositeOption;
import org.ops4j.pax.exam.options.DefaultCompositeOption;
import org.ops4j.pax.tinybundles.core.TinyBundle;
import org.ops4j.pax.tinybundles.core.TinyBundles;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.Constants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public abstract class ContentloaderTestSupport extends TestSupport {

    @Inject
    protected BundleContext bundleContext;

    @Inject
    protected SlingRepository repository;

    protected Session session;

    protected String bundleSymbolicName;

    protected String contentRootPath;

    protected static final String SLING_INITIAL_CONTENT_HEADER = "Sling-Initial-Content";

    protected static final String DEFAULT_PATH_IN_BUNDLE = "test-initial-content";

    private final Logger logger = LoggerFactory.getLogger(ContentloaderTestSupport.class);

    ContentloaderTestSupport() {
    }

    @Before
    public void setup() throws Exception {
        bundleSymbolicName = "TEST-" + UUID.randomUUID();
        contentRootPath = "/test-content/" + bundleSymbolicName;
        session = repository.loginAdministrative(null);

        assertFalse("Expecting no content before test", session.itemExists(contentRootPath));

        // Create, install and start a bundle that has initial content
        try (InputStream is = getTestBundleStream()) {
            final Bundle bundle = bundleContext.installBundle(bundleSymbolicName, is);
            bundle.start();
        }
        
		// stabilize the downstream assertions by waiting a moment for the background content loading 
        // to be processed.  Retry the checking a few times (if necessary) since the timing is tricky.
        String contentLoadedPath = String.format("/var/sling/bundle-content/%s", bundleSymbolicName);
        long timeoutSeconds = 30;
        long timeout = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(timeoutSeconds);
        boolean retry = true;
        do {
        	if (session.itemExists(contentLoadedPath)) {
        		//stop looping
        		retry = false;
        	} else {
        		if (System.currentTimeMillis() > timeout) {
			        fail("RetryLoop failed, condition is false after " + timeoutSeconds + " seconds: " 
			                + "A content loaded node expected at " + contentLoadedPath);
        		} else {
                    logger.warn("Bundle content not loaded yet, retrying after a short delay, path={}", contentLoadedPath);
                    Thread.sleep(200);
                    session.refresh(false);
        		}        		
        	}
        } while (retry);        
    }

    @After
    public void teardown() {
        session.logout();
    }

    @Configuration
    public Option[] configuration() {
    	//workaround to get the required jcr.base bundle into the runtime
    	SlingOptions.versionResolver.setVersionFromProject("org.apache.sling", "org.apache.sling.jcr.base");
    	
        CompositeOption quickstart = (CompositeOption) quickstart();
        final Option[] options = Arrays.stream(quickstart.getOptions()).filter(e -> !Objects.deepEquals(e,
            mavenBundle().groupId("org.apache.sling").artifactId("org.apache.sling.jcr.contentloader").version(SlingOptions.versionResolver.getVersion("org.apache.sling", "org.apache.sling.jcr.contentloader"))
        )).toArray(Option[]::new);
        quickstart = new DefaultCompositeOption(options);
        return new Option[]{
            super.baseConfiguration(),
            quickstart,
            // Sling JCR ContentLoader
            testBundle("bundle.filename"),
            // testing
            newConfiguration("org.apache.sling.jcr.base.internal.LoginAdminWhitelist")
                .put("whitelist.bundles.regexp", "PAXEXAM-PROBE-.*")
                .asOption(),
            slingResourcePresence(),
            junitBundles()
        };
    }

    protected Option quickstart() {
        final int httpPort = findFreePort();
        final String workingDirectory = workingDirectory();
        return slingQuickstartOakTar(workingDirectory, httpPort);
    }


    private InputStream getTestBundleStream() throws Exception {
        final TinyBundle bundle = TinyBundles.bundle().set(Constants.BUNDLE_SYMBOLICNAME, bundleSymbolicName);
        return setupTestBundle(bundle).build(TinyBundles.withBnd());
    }

    abstract protected TinyBundle setupTestBundle(TinyBundle b) throws Exception;

    /**
     * Add content to our test bundle
     */
    protected void addContent(TinyBundle b, String pathInBundle, String resourcePath) throws IOException {
        pathInBundle += "/" + resourcePath;
        resourcePath = "/initial-content/" + resourcePath;
        try (final InputStream is = getClass().getResourceAsStream(resourcePath)) {
            assertNotNull("Expecting resource to be found:" + resourcePath, is);
            logger.info("Adding resource to bundle, path={}, resource={}", pathInBundle, resourcePath);
            b.add(pathInBundle, is);
        }
    }

    protected Bundle findBundle(final String symbolicName) {
        for (final Bundle bundle : bundleContext.getBundles()) {
            if (symbolicName.equals(bundle.getSymbolicName())) {
                return bundle;
            }
        }
        return null;
    }

}
