// shazo — object-persistence abstraction. Standalone-usable; no backbone dep.
// Group, version, toolchain, lint and test platform come from the root build.

dependencies {
    implementation("org.slf4j:slf4j-api:2.0.11")
    compileOnly("jakarta.servlet:jakarta.servlet-api:6.0.0")

    implementation("com.h2database:h2:2.2.224")

    testImplementation("org.junit.jupiter:junit-jupiter:5.10.1")
    testImplementation("org.assertj:assertj-core:3.25.1")
    testImplementation("com.zaxxer:HikariCP:5.1.0")
    testRuntimeOnly("org.slf4j:slf4j-simple:2.0.11")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.javadoc {
    title = "Shazo ${project.version} API"
    (options as StandardJavadocDocletOptions).apply {
        windowTitle = "Shazo ${project.version} API"
        header = "<b>Shazo ${project.version}</b>"
    }
}

publishing {
    publications {
        create<MavenPublication>("mavenJava") {
            from(components["java"])
            pom {
                name = "Shazo"
                description = "Object-persistence abstraction with JDBC, file, " +
                    "shell, and HTTP backends behind a single typed repository contract."
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
