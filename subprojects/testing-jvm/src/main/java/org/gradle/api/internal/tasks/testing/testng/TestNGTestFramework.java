/*
 * Copyright 2010 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gradle.api.internal.tasks.testing.testng;

import org.gradle.api.Action;
import org.gradle.api.GradleException;
import org.gradle.api.InvalidUserDataException;
import org.gradle.api.file.FileCollection;
import org.gradle.api.internal.plugins.DslObject;
import org.gradle.api.internal.tasks.testing.TestClassLoaderFactory;
import org.gradle.api.internal.tasks.testing.TestFramework;
import org.gradle.api.internal.tasks.testing.WorkerTestClassProcessorFactory;
import org.gradle.api.internal.tasks.testing.detection.ClassFileExtractionManager;
import org.gradle.api.internal.tasks.testing.filter.DefaultTestFilter;
import org.gradle.api.model.ObjectFactory;
import org.gradle.api.reporting.DirectoryReport;
import org.gradle.api.tasks.testing.Test;
import org.gradle.api.tasks.testing.TestFilter;
import org.gradle.api.tasks.testing.testng.TestNGOptions;
import org.gradle.internal.Factory;
import org.gradle.internal.scan.UsedByScanPlugin;
import org.gradle.process.internal.worker.WorkerProcessBuilder;

import java.io.File;
import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;

@UsedByScanPlugin("test-retry")
public class TestNGTestFramework implements TestFramework {
    private final TestNGOptions options;
    private TestNGDetector detector;
    private final DefaultTestFilter filter;
    private final ObjectFactory objects;
    private final String testTaskPath;
    private final FileCollection testTaskClasspath;
    private final Factory<File> testTaskTemporaryDir;
    private final DirectoryReport htmlReport;
    private transient ClassLoader testClassLoader;

    public TestNGTestFramework(final Test testTask, FileCollection classpath, DefaultTestFilter filter, ObjectFactory objects) {
        this(
            filter,
            objects,
            testTask.getPath(),
            classpath,
            testTask.getTemporaryDirFactory(),
            testTask.getReports().getHtml(),
            objects.newInstance(TestNGOptions.class)
        );
    }

    private TestNGTestFramework(DefaultTestFilter filter, ObjectFactory objects, String testTaskPath, FileCollection testTaskClasspath, Factory<File> testTaskTemporaryDir, DirectoryReport htmlReport, TestNGOptions options) {
        this.filter = filter;
        this.objects = objects;
        this.testTaskPath = testTaskPath;
        this.testTaskClasspath = testTaskClasspath;
        this.testTaskTemporaryDir = testTaskTemporaryDir;
        this.htmlReport = htmlReport;
        this.options = options;
        this.detector = new TestNGDetector(new ClassFileExtractionManager(testTaskTemporaryDir));

        conventionMapOutputDirectory(options, htmlReport);
    }

    private static void conventionMapOutputDirectory(TestNGOptions options, final DirectoryReport html) {
        new DslObject(options).getConventionMapping().map("outputDirectory", new Callable<File>() {
            @Override
            public File call() {
                return html.getOutputLocation().getAsFile().getOrNull();
            }
        });
    }

    @UsedByScanPlugin("test-retry")
    @Override
    public TestFramework copyWithFilters(TestFilter newTestFilters) {
        TestNGOptions copiedOptions = objects.newInstance(TestNGOptions.class);
        copiedOptions.copyFrom(options);

        return new TestNGTestFramework(
            (DefaultTestFilter) newTestFilters,
            objects,
            testTaskPath,
            testTaskClasspath,
            testTaskTemporaryDir,
            htmlReport,
            copiedOptions
        );
    }

    @Override
    public WorkerTestClassProcessorFactory getProcessorFactory() {
        verifyConfigFailurePolicy();
        verifyPreserveOrder();
        verifyGroupByInstances();
        List<File> suiteFiles = options.getSuites(testTaskTemporaryDir.create());
        TestNGSpec spec = toSpec(options, filter);
        return new TestNgTestClassProcessorFactory(this.options.getOutputDirectory(), spec, suiteFiles);
    }

    private static TestNGSpec toSpec(TestNGOptions options, DefaultTestFilter filter) {
        return new TestNGSpec(filter.toSpec(),
            options.getSuiteName(), options.getTestName(), options.getParallel(), options.getThreadCount(),
            options.getUseDefaultListeners(), options.getIncludeGroups(), options.getExcludeGroups(), options.getListeners(),
            options.getConfigFailurePolicy(), options.getPreserveOrder(), options.getGroupByInstances()
        );
    }

    private void verifyConfigFailurePolicy() {
        if (!options.getConfigFailurePolicy().equals(TestNGTestClassProcessor.DEFAULT_CONFIG_FAILURE_POLICY)) {
            String message = String.format("The version of TestNG used does not support setting config failure policy to '%s'.", options.getConfigFailurePolicy());
            try {
                // for TestNG v6.9.12 and higher
                Class<?> failurePolicyEnum = maybeLoadFailurePolicyEnum();
                verifyMethodExists("setConfigFailurePolicy", failurePolicyEnum, message);
            } catch (ClassNotFoundException cnfe) {
                // fallback to legacy String argument
                verifyMethodExists("setConfigFailurePolicy", String.class, message);
            }
        }
    }

    private void verifyPreserveOrder() {
        if (options.getPreserveOrder()) {
            verifyMethodExists("setPreserveOrder", boolean.class, "Preserving the order of tests is not supported by this version of TestNG.");
        }
    }

    private void verifyGroupByInstances() {
        if (options.getGroupByInstances()) {
            verifyMethodExists("setGroupByInstances", boolean.class, "Grouping tests by instances is not supported by this version of TestNG.");
        }
    }

    private void verifyMethodExists(String methodName, Class<?> parameterType, String failureMessage) {
        try {
            createTestNg().getMethod(methodName, parameterType);
        } catch (NoSuchMethodException e) {
            throw new InvalidUserDataException(failureMessage, e);
        }
    }

    private Class<?> createTestNg() {
        maybeInitTestClassLoader();
        try {
            return loadClass("org.testng.TestNG");
        } catch (ClassNotFoundException e) {
            throw new GradleException("Could not load TestNG.", e);
        }
    }

    /**
     * Use reflection to load {@code org.testng.xml.XmlSuite.FailurePolicy}, added in TestNG 6.9.12
     *
     * @return reference to class, if exists.
     * @throws ClassNotFoundException if using older TestNG version.
     */
    protected Class<?> maybeLoadFailurePolicyEnum() throws ClassNotFoundException {
        return loadClass("org.testng.xml.XmlSuite$FailurePolicy");
    }

    private Class<?> loadClass(String clazz) throws ClassNotFoundException {
        maybeInitTestClassLoader();
        return testClassLoader.loadClass(clazz);
    }

    private void maybeInitTestClassLoader() {
        if (testClassLoader == null) {
            TestClassLoaderFactory factory = objects.newInstance(
                TestClassLoaderFactory.class,
                testTaskPath,
                testTaskClasspath
            );
            testClassLoader = factory.create();
        }
    }

    @Override
    public Action<WorkerProcessBuilder> getWorkerConfigurationAction() {
        return workerProcessBuilder -> workerProcessBuilder.sharedPackages("org.testng");
    }

    @Override
    public List<String> getTestWorkerApplicationClasses() {
        return Collections.emptyList();
    }

    @Override
    public List<String> getTestWorkerApplicationModules() {
        return Collections.emptyList();
    }

    @Override
    public boolean getUseDistributionDependencies() {
        // We have no (default) implementation dependencies (see above).
        // The user must add their TestNG dependency to the test's runtimeClasspath themselves
        // or preferably use test suites where the dependencies are automatically managed.
        return false;
    }

    @Override
    public TestNGOptions getOptions() {
        return options;
    }

    @Override
    public TestNGDetector getDetector() {
        return detector;
    }

    @Override
    public void close() throws IOException {
        // Clear expensive state from the test framework to avoid holding on to memory
        // This should probably be a part of the test task and managed there.
        testClassLoader = null;
        detector = null;
    }

}
