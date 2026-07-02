plugins {
    // Lets Gradle auto-provision the Java 21 toolchain (e.g. on JitPack/CI,
    // where the running JDK may be older).
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "sharaku"

include(":shazo", ":shazo-h2", ":backbone", ":demo")
