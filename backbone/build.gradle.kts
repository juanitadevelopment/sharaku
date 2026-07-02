// backbone — transactional service + domain-event runtime, built on shazo.
// Group, version, toolchain, lint and test platform come from the root build.

dependencies {
    // Backbone's public API exposes shazo types (repositories, describers,
    // unit-of-work), so shazo is an `api` dependency. Inside the monorepo it is
    // a project dependency — no JitPack round-trip, always version-aligned.
    api(project(":shazo"))

    implementation("org.slf4j:slf4j-api:2.0.11")

    testImplementation("org.junit.jupiter:junit-jupiter:5.10.1")
    testImplementation("org.assertj:assertj-core:3.25.1")
    testImplementation(project(":shazo-h2"))   // H2DataSources for tests (brings H2 transitively)
    testRuntimeOnly("org.slf4j:slf4j-simple:2.0.11")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.javadoc {
    title = "Backbone ${project.version} API"
    (options as StandardJavadocDocletOptions).apply {
        windowTitle = "Backbone ${project.version} API"
        header = "<b>Backbone ${project.version}</b>"
    }
}

publishing {
    publications {
        create<MavenPublication>("mavenJava") {
            from(components["java"])
            pom {
                name = "Backbone"
                description = "A minimal transactional service and domain-event " +
                    "runtime built on the shazo persistence abstraction."
                licenses {
                    license {
                        name = "The Apache License, Version 2.0"
                        url = "https://www.apache.org/licenses/LICENSE-2.0.txt"
                    }
                }
            }
        }
    }
}
