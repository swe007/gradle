/*
 * Copyright 2017 the original author or authors.
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

package org.gradle.integtests.resolve.maven

import org.gradle.integtests.fixtures.AbstractDependencyResolutionTest
import org.gradle.integtests.fixtures.ToBeFixedForConfigurationCache
import org.gradle.integtests.fixtures.resolve.ResolveTestFixture

import static org.gradle.api.internal.artifacts.ivyservice.ivyresolve.parser.GradleModuleMetadataParser.FORMAT_VERSION

class MavenLocalDependencyWithGradleMetadataResolutionIntegrationTest extends AbstractDependencyResolutionTest {
    def resolve = new ResolveTestFixture(buildFile, "compile")

    def setup() {
        resolve.prepare()
        settingsFile << "rootProject.name = 'test'"
    }

    def "uses the module metadata when configured as source and pom is not present"() {
        mavenRepo.module("test", "a", "1.2").withNoPom().withModuleMetadata().publish()

        given:
        buildFile << """
repositories {
    maven {
        url = '${mavenRepo.uri}'
        metadataSources { gradleMetadata() }
    }
}
configurations { compile }
dependencies {
    compile 'test:a:1.2'
}
"""

        when:
        run("checkDeps")

        then:
        resolve.expectGraph {
            root(":", ":test:") {
                module("test:a:1.2:runtime")
            }
        }

        when:
        run("checkDeps")

        then:
        resolve.expectGraph {
            root(":", ":test:") {
                module("test:a:1.2:runtime")
            }
        }
    }

    def "uses dependencies and files from selected variant"() {
        def c = mavenRepo.module("test", "c", "2.2").publish()
        def b = mavenRepo.module("test", "b", "2.0").publish()
        def a = mavenRepo.module("test", "a", "1.2")
            .dependsOn("test", "ignore-me", "0.1")
            .withModuleMetadata()
        a.artifact(classifier: 'debug')
        a.artifact(classifier: 'release')
        a.publish()
        a.moduleMetadata.file.text = """
{
    "formatVersion": "${FORMAT_VERSION}",
    "variants": [
        {
            "name": "debug",
            "attributes": {
                "buildType": "debug"
            },
            "files": [ { "name": "a-1.2-debug.jar", "url": "a-1.2-debug.jar" } ],
            "dependencies": [ { "group": "test", "module": "b", "version": { "prefers": "2.0", "rejects": [] } } ]
        },
        {
            "name": "release",
            "attributes": {
                "buildType": "release"
            },
            "files": [ { "name": "a-1.2-release.jar", "url": "a-1.2-release.jar" } ],
            "dependencies": [ { "group": "test", "module": "c", "version": { "prefers": "2.2" } } ]
        }
    ]
}
"""

        given:
        buildFile << """
repositories {
    maven {
        url = '${mavenRepo.uri}'
    }
}
def attr = Attribute.of("buildType", String)
configurations {
    debug { attributes.attribute(attr, "debug") }
    release { attributes.attribute(attr, "release") }
}
dependencies {
    debug 'test:a:1.2'
    release 'test:a:1.2'
}
task checkDebug {
    doLast { assert configurations.debug.files*.name == ['a-1.2-debug.jar', 'b-2.0.jar'] }
}
task checkRelease {
    doLast { assert configurations.release.files*.name == ['a-1.2-release.jar', 'c-2.2.jar'] }
}
"""

        expect:
        succeeds("checkDebug")

        and:
        succeeds("checkRelease")
    }

    def "variant can define zero files or multiple files"() {
        def b = mavenRepo.module("test", "b", "2.0").publish()
        def a = mavenRepo.module("test", "a", "1.2")
            .withModuleMetadata()
        a.artifact(classifier: 'api')
        a.artifact(classifier: 'runtime')
        a.publish()
        a.moduleMetadata.file.text = """
{
    "formatVersion": "${FORMAT_VERSION}",
    "variants": [
        {
            "name": "debug",
            "attributes": {
                "buildType": "debug"
            },
            "files": [
                { "name": "a-1.2-api.jar", "url": "a-1.2-api.jar" },
                { "name": "a-1.2-runtime.jar", "url": "a-1.2-runtime.jar" }
            ],
            "dependencies": [ { "group": "test", "module": "b", "version": { "prefers": "2.0" } } ]
        },
        {
            "name": "release",
            "attributes": {
                "buildType": "release"
            },
            "dependencies": [ { "group": "test", "module": "b", "version": { "prefers": "2.0" } } ]
        }
    ]
}
"""

        given:
        buildFile << """
repositories {
    maven {
        url = '${mavenRepo.uri}'
    }
}
def attr = Attribute.of("buildType", String)
configurations {
    debug { attributes.attribute(attr, "debug") }
    release { attributes.attribute(attr, "release") }
}
dependencies {
    debug 'test:a:1.2'
    release 'test:a:1.2'
}
task checkDebug {
    doLast { assert configurations.debug.files*.name == ['a-1.2-api.jar', 'a-1.2-runtime.jar', 'b-2.0.jar'] }
}
task checkRelease {
    doLast { assert configurations.release.files*.name == ['b-2.0.jar'] }
}
"""

        expect:
        succeeds("checkDebug")

        and:
        succeeds("checkRelease")
    }

    @ToBeFixedForConfigurationCache
    def "variant can define files whose names are different to their maven contention location"() {
        def a = mavenRepo.module("test", "a", "1.2")
            .withModuleMetadata()
        a.artifact(type: 'zip')
        a.artifact(classifier: 'extra')
        a.publish()
        a.moduleMetadata.file.text = """
{
    "formatVersion": "${FORMAT_VERSION}",
    "variants": [
        {
            "name": "lot-o-files",
            "files": [
                { "name": "a_main.jar", "url": "a-1.2.jar" },
                { "name": "a_extra.jar", "url": "a-1.2-extra.jar" },
                { "name": "a.zip", "url": "a-1.2.zip" }
            ]
        }
    ]
}
"""

        given:
        buildFile << """
repositories {
    maven {
        url = '${mavenRepo.uri}'
    }
}
configurations {
    debug
}
dependencies {
    debug 'test:a:1.2'
}
task checkDebug {
    doLast { assert configurations.debug.files*.name == ['a_main.jar', 'a_extra.jar', 'a.zip'] }
}
"""

        expect:
        succeeds("checkDebug")

        and:
        succeeds("checkDebug")
    }

    @ToBeFixedForConfigurationCache
    def "variant can define files whose names and locations do not match maven convention"() {
        def a = mavenRepo.module("test", "a", "1.2")
            .withModuleMetadata()
        a.getArtifact("file1.jar").file << "file 1"
        a.getArtifact("file2.jar").file << "file 2"
        a.getArtifact("../sibling/file3.jar").file << "file 3"
        a.getArtifact("child/file4.jar").file << "file 4"
        a.publish()
        a.moduleMetadata.file.text = """
{
    "formatVersion": "${FORMAT_VERSION}",
    "variants": [
        {
            "name": "lot-o-files",
            "files": [
                { "name": "file1.jar", "url": "file1.jar" },
                { "name": "a-1.2.jar", "url": "file2.jar" },
                { "name": "a-3.jar", "url": "../sibling/file3.jar" },
                { "name": "file4.jar", "url": "child/file4.jar" }
            ]
        }
    ]
}
"""

        given:
        buildFile << """
repositories {
    maven {
        url = '${mavenRepo.uri}'
    }
}
configurations {
    debug
}
dependencies {
    debug 'test:a:1.2'
}
task checkDebug {
    doLast { assert configurations.debug.files*.name == ['file1.jar', 'a-1.2.jar', 'a-3.jar', 'file4.jar'] }
}
"""

        expect:
        succeeds("checkDebug")

        and:
        succeeds("checkDebug")
    }

    def "module with module metadata can depend on another module with module metadata"() {
        def c = mavenRepo.module("test", "c", "preview")
            .withModuleMetadata()
        c.artifact(classifier: 'debug')
        c.publish()
        c.moduleMetadata.file.text = """
{
    "formatVersion": "${FORMAT_VERSION}",
    "variants": [
        {
            "name": "debug",
            "attributes": {
                "buildType": "debug"
            },
            "files": [ { "name": "c-preview-debug.jar", "url": "c-preview-debug.jar" } ]
        },
        {
            "name": "release",
            "attributes": {
                "buildType": "release"
            }
        }
    ]
}
"""

        def b = mavenRepo.module("test", "b", "2.0")
            .dependsOn(c)
            .withModuleMetadata()
        b.publish()

        def a = mavenRepo.module("test", "a", "1.2")
            .withModuleMetadata()
        a.artifact(classifier: 'debug')
        a.publish()
        a.moduleMetadata.file.text = """
{
    "formatVersion": "${FORMAT_VERSION}",
    "variants": [
        {
            "name": "debug",
            "attributes": {
                "buildType": "debug"
            },
            "files": [ { "name": "a-1.2-debug.jar", "url": "a-1.2-debug.jar" } ],
            "dependencies": [ { "group": "test", "module": "b", "version": { "prefers": "2.0" } } ]
        },
        {
            "name": "release",
            "attributes": {
                "buildType": "release"
            },
            "dependencies": [ { "group": "test", "module": "c", "version": { "prefers": "preview" } } ]
        }
    ]
}
"""

        given:
        buildFile << """
repositories {
    maven {
        url = '${mavenRepo.uri}'
    }
}
def attr = Attribute.of("buildType", String)
configurations {
    debug { attributes.attribute(attr, "debug") }
    release { attributes.attribute(attr, "release") }
}
dependencies {
    debug 'test:a:1.2'
    release 'test:a:1.2'
}
task checkDebug {
    doLast { assert configurations.debug.files*.name == ['a-1.2-debug.jar', 'b-2.0.jar', 'c-preview-debug.jar'] }
}
task checkRelease {
    doLast { assert configurations.release.files*.name == [] }
}
"""

        expect:
        succeeds("checkDebug")

        and:
        succeeds("checkRelease")
    }
}
