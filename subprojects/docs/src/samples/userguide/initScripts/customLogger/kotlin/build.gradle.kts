task("compile") {
    doLast {
        println("compiling source")
    }
}
task("testCompile") {
    dependsOn("compile")
    doLast {
        println("compiling test source")
    }
}
task("test") {
    dependsOn("compile", "testCompile")
    doLast {
        println("running unit tests")
    }
}
task("build") {
    dependsOn("test")
}
