// Root build for the `sharaku` monorepo: the modernized "sharaku2 backbone"
// framework, split into two published artifacts that share one lineage.
//
//   :shazo    — object-persistence abstraction (usable standalone)
//   :backbone — transactional service + event runtime, built on shazo
//
// Common settings (group, version, Java toolchain, lint, test platform) live
// here so the two modules stay in lock-step; module scripts carry only their
// own dependencies and publication metadata.

allprojects {
    group = "net.teppan"
    version = "0.2.1"
}

subprojects {
    apply(plugin = "java-library")
    apply(plugin = "maven-publish")

    repositories {
        mavenCentral()
    }

    configure<JavaPluginExtension> {
        toolchain {
            languageVersion = JavaLanguageVersion.of(21)
        }
    }

    tasks.withType<JavaCompile>().configureEach {
        options.compilerArgs.addAll(listOf("-Xlint:all", "-Xlint:-serial"))
        options.encoding = "UTF-8"
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
    }

    tasks.withType<Javadoc>().configureEach {
        (options as StandardJavadocDocletOptions).apply {
            encoding = "UTF-8"
            charSet = "UTF-8"
            locale = "en"
            addStringOption("Xdoclint:all", "-quiet")
            addBooleanOption("html5", true)
            bottom = "Copyright &#169; 2026 net.teppan. All rights reserved."
        }
    }
}
