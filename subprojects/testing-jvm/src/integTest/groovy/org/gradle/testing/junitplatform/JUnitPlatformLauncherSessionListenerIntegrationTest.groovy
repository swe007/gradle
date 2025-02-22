/*
 * Copyright 2022 the original author or authors.
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

package org.gradle.testing.junitplatform

import org.gradle.integtests.fixtures.AbstractIntegrationSpec
import spock.lang.Issue

/**
 * Tests JUnitPlatform integrations with {@code LauncherSessionListener}.
 */
class JUnitPlatformLauncherSessionListenerIntegrationTest extends AbstractIntegrationSpec {
    /**
     * @see <a href=https://github.com/JetBrains/intellij-community/commit/d41841670c8a98c0464ef25ef490c79b5bafe8a9">The IntelliJ commit</a>
     * which introduced a {@code LauncherSessionListener} onto the test classpath when using the {@code org.jetbrains.intellij} plugin.
     */
    @Issue("https://github.com/gradle/gradle/issues/22333")
    def "LauncherSessionListeners are automatically loaded from the test classpath when listener does not provide junit platform launcher dependency"() {
        settingsFile << "include 'other'"
        file("other/build.gradle") << """
            plugins {
                id 'java'
            }

            ${mavenCentralRepository()}

            dependencies {
                compileOnly 'org.junit.platform:junit-platform-launcher:1.9.1'
            }
        """
        file("other/src/main/java/com/example/MyLauncherSessionListener.java") << """
            package com.example;
            import org.junit.platform.launcher.LauncherSession;
            import org.junit.platform.launcher.LauncherSessionListener;
            public class MyLauncherSessionListener implements LauncherSessionListener {
                public void launcherSessionOpened(LauncherSession session) {
                    System.out.println("Session opened");
                }
                public void launcherSessionClosed(LauncherSession session) {
                    System.out.println("Session closed");
                }
            }
        """
        file("other/src/main/resources/META-INF/services/org.junit.platform.launcher.LauncherSessionListener") << """
            com.example.MyLauncherSessionListener
        """

        buildFile << """
            plugins {
                id 'java'
            }

            ${mavenCentralRepository()}

            dependencies {
                testImplementation project(':other')
                testRuntimeOnly 'org.junit.jupiter:junit-jupiter-engine:5.9.1'
            }

            test {
                useJUnitPlatform()
                testLogging.showStandardStreams = true
            }
        """
        file("src/test/java/com/example/MyTest.java") << "package com.example; public class MyTest {} "

        when:
        succeeds "test"

        then:
        outputContains("Session opened")
        outputContains("Session closed")

        when:
        succeeds "dependencies", "--configuration", "testRuntimeClasspath"

        then:
        // Sanity check in case future versions for some reason include a launcher
        outputDoesNotContain("junit-platform-launcher")
    }
}
