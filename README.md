# Sharaku

A revival and Java 21 modernization of the **"sharaku2 backbone"** framework
(originally by Tetsunori Matsubara at Sun Microsystems, ~2000–2005), split into
two artifacts that share one lineage and evolve together:

| Module | Artifact | What it is |
|--------|----------|------------|
| [`shazo`](shazo/) | `net.teppan:shazo` | Object-persistence abstraction — one typed `Repository` contract over JDBC, file, shell, and HTTP backends. Usable standalone. |
| [`backbone`](backbone/) | `net.teppan:backbone` | Transactional service + domain-event runtime, built on shazo. Depends on shazo. |

`backbone` builds on `shazo` (one-way dependency); `shazo` has no dependency on
`backbone` and can be used on its own.

> **Status:** early release (`0.2.0`). API may still change before `1.0.0`.

## Modules

- **[shazo/](shazo/README.md)** — repositories, describers, the fetch pairs
  (`retrieve`/`find`, `catalog`/`gather`), caching, async, HTTP transport.
- **[backbone/](backbone/README.md)** — `ServiceRunner` (service = one
  transaction), transactional-outbox events, and a virtual-thread
  `TimerScheduler`.

## Getting the artifacts

Both modules are published from this single repository via JitPack, resolvable
per tag:

```kotlin
repositories {
    maven { url = uri("https://jitpack.io") }
}
dependencies {
    // persistence only
    implementation("com.github.juanitadevelopment.sharaku:shazo:0.2.0")
    // application runtime (pulls in shazo transitively)
    implementation("com.github.juanitadevelopment.sharaku:backbone:0.2.0")
}
```

## Build

Gradle (Java 21). From the repository root:

```sh
./gradlew build                 # compile + test every module
./gradlew :shazo:test           # test one module
./gradlew :backbone:jar         # build one module's JAR
```

## Requirements

- **Java 21+** (the toolchain is auto-provisioned by the foojay resolver)

## License

Apache License 2.0 — see [LICENSE](LICENSE) and [NOTICE](NOTICE).
