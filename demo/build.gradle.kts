// demo — runnable sample app (a memo/notes service + an orders showcase) that
// exercises shazo and backbone together. Not published; it exists to keep a
// real, end-to-end usage example compiling and tested alongside the libraries.
//
// Group, version, toolchain, lint and test platform come from the root build;
// this module adds the `application` plugin and the two project dependencies.

plugins {
    application
}

dependencies {
    implementation(project(":shazo"))
    implementation(project(":shazo-h2"))   // MemoApp/OrderShowcase use H2DataSources
    implementation(project(":backbone"))

    testImplementation("org.junit.jupiter:junit-jupiter:5.10.1")
    testImplementation("org.assertj:assertj-core:3.25.1")
    testRuntimeOnly("org.slf4j:slf4j-simple:2.0.11")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

application {
    mainClass = "net.teppan.demo.memo.MemoApp"
}

tasks.named<JavaExec>("run") {
    args("--storage=jdbc", "--port=8080", "--db-path=./memo-db")
}

// Sample module — nothing to publish to Maven, and no API-doc consumers
// (the strict Xdoclint:all convention from the root build stays for the
// published :shazo and :backbone modules only).
tasks.withType<AbstractPublishToMaven>().configureEach { enabled = false }
tasks.javadoc { enabled = false }
