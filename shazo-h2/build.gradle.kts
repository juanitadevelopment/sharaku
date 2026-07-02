// shazo-h2 — optional H2 convenience for shazo. Keeps the H2 driver out of core
// shazo's dependency graph: only apps that want the embedded/file/server H2
// DataSource factory (H2DataSources) pull H2 onto their classpath.
//
// Group, version, toolchain, lint and test platform come from the root build.

dependencies {
    // shazo types (DataSource-returning factory) are part of this module's
    // surface, and H2 is required to use it — both are `api`.
    api(project(":shazo"))
    api("com.h2database:h2:2.2.224")

    testImplementation("org.junit.jupiter:junit-jupiter:5.10.1")
    testImplementation("org.assertj:assertj-core:3.25.1")
    testRuntimeOnly("org.slf4j:slf4j-simple:2.0.11")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.javadoc {
    title = "Shazo-H2 ${project.version} API"
    (options as StandardJavadocDocletOptions).apply {
        windowTitle = "Shazo-H2 ${project.version} API"
        header = "<b>Shazo-H2 ${project.version}</b>"
    }
}

publishing {
    publications {
        create<MavenPublication>("mavenJava") {
            from(components["java"])
            pom {
                name = "Shazo-H2"
                description = "Optional H2 DataSource factory for shazo — " +
                    "embedded, file, or server H2, kept out of shazo's core dependencies."
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
