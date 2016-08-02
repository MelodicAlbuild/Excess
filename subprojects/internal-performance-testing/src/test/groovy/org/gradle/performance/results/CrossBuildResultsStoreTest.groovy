/*
 * Copyright 2014 the original author or authors.
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

package org.gradle.performance.results

import org.gradle.performance.ResultSpecification
import org.gradle.test.fixtures.file.TestNameTestDirectoryProvider
import org.gradle.util.SetSystemProperties
import org.junit.Rule

import static org.gradle.performance.measure.DataAmount.kbytes
import static org.gradle.performance.measure.Duration.minutes

class CrossBuildResultsStoreTest extends ResultSpecification {
    @Rule
    TestNameTestDirectoryProvider tmpDir = new TestNameTestDirectoryProvider()
    @Rule
    SetSystemProperties properties = new SetSystemProperties("org.gradle.performance.db.url": "jdbc:h2:" + tmpDir.testDirectory)

    final dbFile = tmpDir.file("results")

    def "persists results"() {
        given:
        println System.getProperty("org.gradle.performance.db.url")
        def results1 = crossBuildResults(testId: "test1", testGroup: "group1")
        def buildResults1 = results1.buildResult(
                new BuildDisplayInfo(
                        "simple",
                        "simple display",
                        ["build"],
                        ["-i"],
                        [],
                        true
                )
        )
        buildResults1 << operation(totalTime: minutes(12),
                configurationTime: minutes(1),
                executionTime: minutes(10),
                totalMemoryUsed: kbytes(12.33),
                totalHeapUsage: kbytes(5612.45),
                maxHeapUsage: kbytes(124.01),
                maxUncollectedHeap: kbytes(45.22),
                maxCommittedHeap: kbytes(200)
        )
        def buildResults2 = results1.buildResult(new BuildDisplayInfo("complex", "complex display", [], [], ["--go-faster"], false))
        buildResults2 << operation()
        buildResults2 << operation()

        and:
        def results2 = crossBuildResults(testId: "test2", testGroup: "group2")
        results2.buildResult(new BuildDisplayInfo("simple", "simple display", ["build"], ["-i"], [], true))

        when:
        def writeStore = new BaseCrossBuildResultsStore(dbFile.name)
        writeStore.report(results1)
        writeStore.report(results2)
        writeStore.close()

        then:
        tmpDir.file("results.h2.db").exists()

        when:
        def readStore = new BaseCrossBuildResultsStore(dbFile.name)
        def tests = readStore.testNames

        then:
        tests == ["test1", "test2"]

        when:
        def history = readStore.getTestResults("test1")

        then:
        history.id == "test1"
        history.displayName == "test1"
        history.scenarioCount == 2
        history.scenarioLabels == ["complex display", "simple display"]
        history.scenarios.size() == 2
        history.scenarios[0].displayName == "complex display"
        history.scenarios[0].testProject == "complex"
        history.scenarios[0].tasks == []
        history.scenarios[0].args == []
        history.scenarios[0].gradleOpts == ["--go-faster"]
        history.scenarios[0].daemon == false
        history.scenarios[1].displayName == "simple display"
        history.scenarios[1].testProject == "simple"
        history.scenarios[1].tasks == ["build"]
        history.scenarios[1].args == ["-i"]
        history.scenarios[1].gradleOpts == []
        history.scenarios[1].daemon

        and:
        def firstSpecification = history.builds[0]
        firstSpecification == new BuildDisplayInfo("complex", "complex display", [], [], ["--go-faster"], false)
        history.results.first().buildResult(firstSpecification).size() == 2
        history.results.first().buildResult("complex display").size() == 2

        and:
        def crossBuildPerformanceResults = history.results.first()
        crossBuildPerformanceResults.testId == "test1"
        crossBuildPerformanceResults.jvm == "java 7"
        crossBuildPerformanceResults.versionUnderTest == "Gradle 1.0"
        crossBuildPerformanceResults.operatingSystem == "windows"
        crossBuildPerformanceResults.testTime == 100
        crossBuildPerformanceResults.vcsBranch == "master"
        crossBuildPerformanceResults.vcsCommits[0] == "abcdef"

        and:
        def secondSpecification = history.builds[1]
        secondSpecification == new BuildDisplayInfo("simple", "simple display", ["build"], ["-i"], [], true)
        def operation = crossBuildPerformanceResults.buildResult(secondSpecification).first
        operation.totalTime == minutes(12)
        operation.configurationTime == minutes(1)
        operation.executionTime == minutes(10)
        operation.totalMemoryUsed == kbytes(12.33)
        operation.totalHeapUsage == kbytes(5612.45)
        operation.maxHeapUsage == kbytes(124.01)
        operation.maxUncollectedHeap == kbytes(45.22)
        operation.maxCommittedHeap == kbytes(200)

        cleanup:
        writeStore?.close()
        readStore?.close()
    }

    def "handles null for details that have not been collected for older test executions"() {
        given:
        def results1 = crossBuildResults(testId: "test1", testGroup: "group1")
        def buildResults1 = results1.buildResult(
                new BuildDisplayInfo(
                        "simple",
                        "simple display",
                        ["build"],
                        ["-i"],
                        null,
                        null
                )
        )
        buildResults1 << operation()

        when:
        def writeStore = new BaseCrossBuildResultsStore(dbFile.name)
        writeStore.report(results1)
        writeStore.close()

        then:
        tmpDir.file("results.h2.db").exists()

        when:
        def readStore = new BaseCrossBuildResultsStore(dbFile.name)
        def history = readStore.getTestResults("test1")

        then:
        history.id == "test1"
        history.displayName == "test1"
        history.scenarioCount == 1
        def firstSpecification = history.builds[0]
        firstSpecification == new BuildDisplayInfo("simple", "simple display", ["build"], ["-i"], null, null)
        history.results.first().buildResult(firstSpecification).size() == 1
        history.results.first().buildResult("simple display").size() == 1

        cleanup:
        writeStore?.close()
        readStore?.close()
    }

    def "scenario settings can change over time"() {
        given:
        def results1 = crossBuildResults(testId: "test1", testGroup: "group1", testTime: 100)
        def buildResults1 = results1.buildResult(
                new BuildDisplayInfo(
                        "project-1",
                        "scenario 1",
                        ["build"],
                        ["-1"],
                        null,
                        null
                )
        )
        buildResults1 << operation()
        def buildResults2 = results1.buildResult(
                new BuildDisplayInfo(
                        "project-1",
                        "scenario 2",
                        ["clean", "build"],
                        ["-1"],
                        null,
                        null
                )
        )
        buildResults2 << operation()

        def results2 = crossBuildResults(testId: "test1", testGroup: "group1", testTime: 200)
        def buildResults3 = results2.buildResult(
                new BuildDisplayInfo(
                        "project-1",
                        "scenario 1",
                        ["build"],
                        ["-2"],
                        ["--new"],
                        true
                )
        )
        buildResults3 << operation()
        def buildResults4 = results2.buildResult(
                new BuildDisplayInfo(
                        "project-1",
                        "scenario 2",
                        ["clean", "build"],
                        ["-1"],
                        ["--new"],
                        true
                )
        )
        buildResults4 << operation()

        def results3 = crossBuildResults(testId: "test1", testGroup: "group1", testTime: 300)
        def buildResults5 = results3.buildResult(
                new BuildDisplayInfo(
                        "project-1-new",
                        "scenario 1",
                        ["assemble"],
                        ["-2", "-3"],
                        ["--new", "--old"],
                        true
                )
        )
        buildResults5 << operation()
        def buildResults6 = results3.buildResult(
                new BuildDisplayInfo(
                        "project-2-new",
                        "scenario 2",
                        ["clean", "assemble"],
                        ["-4"],
                        [],
                        true
                )
        )
        buildResults6 << operation()

        when:
        def writeStore = new BaseCrossBuildResultsStore(dbFile.name)
        writeStore.report(results1)
        writeStore.report(results2)
        writeStore.report(results3)
        writeStore.close()

        then:
        tmpDir.file("results.h2.db").exists()

        when:
        def readStore = new BaseCrossBuildResultsStore(dbFile.name)
        def history = readStore.getTestResults("test1")

        then:
        history.id == "test1"
        history.displayName == "test1"
        history.scenarioCount == 2
        history.scenarioLabels == ["scenario 1", "scenario 2"]
        history.scenarios.size() == 2
        history.builds.size() == 2

        history.executions[0].scenarios.size() == 2
        history.executions[0].scenarios[0].name == "scenario 1"
        history.executions[0].scenarios[0].size() == 1
        history.executions[0].scenarios[1].name == "scenario 2"
        history.executions[0].scenarios[1].size() == 1

        history.executions[1].scenarios.size() == 2
        history.executions[1].scenarios[0].name == "scenario 1"
        history.executions[1].scenarios[0].size() == 1
        history.executions[1].scenarios[1].name == "scenario 2"
        history.executions[1].scenarios[1].size() == 1

        history.executions[2].scenarios.size() == 2
        history.executions[2].scenarios[0].name == "scenario 1"
        history.executions[2].scenarios[0].size() == 1
        history.executions[2].scenarios[1].name == "scenario 2"
        history.executions[2].scenarios[1].size() == 1

        cleanup:
        writeStore?.close()
        readStore?.close()
    }

    def "reports on union of all scenarios"() {
        given:
        def results1 = crossBuildResults(testId: "test1", testGroup: "group1", testTime: 100)
        def buildResults1 = results1.buildResult(
                new BuildDisplayInfo(
                        "project-1",
                        "scenario 1",
                        ["build"],
                        ["-1"],
                        null,
                        null
                )
        )
        buildResults1 << operation()
        def buildResults2 = results1.buildResult(
                new BuildDisplayInfo(
                        "project-1",
                        "scenario 2",
                        ["clean", "build"],
                        ["-1"],
                        null,
                        null
                )
        )
        buildResults2 << operation()

        def results2 = crossBuildResults(testId: "test1", testGroup: "group1", testTime: 200)
        def buildResults3 = results2.buildResult(
                new BuildDisplayInfo(
                        "project-1",
                        "scenario 1",
                        ["build"],
                        ["-2"],
                        ["--new"],
                        true
                )
        )
        buildResults3 << operation()
        def buildResults4 = results2.buildResult(
                new BuildDisplayInfo(
                        "project-1",
                        "scenario 3",
                        ["clean", "build"],
                        ["-1"],
                        ["--new"],
                        true
                )
        )
        buildResults4 << operation()

        def results3 = crossBuildResults(testId: "test1", testGroup: "group1", testTime: 300)
        def buildResults5 = results3.buildResult(
                new BuildDisplayInfo(
                        "project-1-old",
                        "scenario 4",
                        ["assemble"],
                        ["-2", "-3"],
                        ["--new", "--old"],
                        true
                )
        )
        buildResults5 << operation()

        when:
        def writeStore = new BaseCrossBuildResultsStore(dbFile.name)
        writeStore.report(results1)
        writeStore.report(results2)
        writeStore.report(results3)
        writeStore.close()

        then:
        tmpDir.file("results.h2.db").exists()

        when:
        def readStore = new BaseCrossBuildResultsStore(dbFile.name)
        def history = readStore.getTestResults("test1")

        then:
        history.id == "test1"
        history.displayName == "test1"
        history.scenarioCount == 4
        history.scenarioLabels == ["scenario 1", "scenario 2", "scenario 3", "scenario 4"]
        history.scenarios.size() == 4
        history.scenarios[0].displayName == "scenario 1"
        history.scenarios[1].displayName == "scenario 2"
        history.scenarios[2].displayName == "scenario 3"
        history.scenarios[3].displayName == "scenario 4"

        history.builds.size() == 4

        history.executions[0].scenarios.size() == 4
        history.executions[0].scenarios[0].size() == 0
        history.executions[0].scenarios[1].size() == 0
        history.executions[0].scenarios[2].size() == 0
        history.executions[0].scenarios[3].size() == 1

        history.executions[1].scenarios.size() == 4

        history.executions[2].scenarios.size() == 4
        history.executions[2].scenarios[0].size() == 1
        history.executions[2].scenarios[1].size() == 1
        history.executions[2].scenarios[2].size() == 0
        history.executions[2].scenarios[3].size() == 0

        cleanup:
        writeStore?.close()
        readStore?.close()
    }

    def "returns top n results in descending date order"() {
        given:
        def results1 = crossBuildResults(testId: "test1", testTime: 1000)
        results1.buildResult(new BuildDisplayInfo("simple1", "simple 1", ["build"], ["-i"], [], true))

        and:
        def results2 = crossBuildResults(testId: "test1", testTime: 2000)
        results2.buildResult(new BuildDisplayInfo("simple2", "simple 2", ["build"], ["-i"], [], true))

        and:
        def results3 = crossBuildResults(testId: "test1", testTime: 3000)
        results3.buildResult(new BuildDisplayInfo("simple3", "simple 3", ["build"], ["-i"], [], true))

        and:
        def writeStore = new BaseCrossBuildResultsStore(dbFile.name)
        writeStore.report(results2)
        writeStore.report(results3)
        writeStore.report(results1)
        writeStore.close()

        when:
        def readStore = new BaseCrossBuildResultsStore(dbFile.name)
        def history = readStore.getTestResults("test1")

        then:
        history.results*.testTime == [3000, 2000, 1000]

        when:
        history = readStore.getTestResults("test1", 2)

        then:
        history.results*.testTime == [3000, 2000]

        cleanup:
        writeStore?.close()
        readStore?.close()
    }
}
