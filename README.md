# Sharaku

A revival and Java 21 modernization of the **"sharaku2 backbone"** framework
(originally by Tetsunori Matsubara at Sun Microsystems, ~2000–2005), split into
two artifacts that share one lineage and evolve together:

| Module | Artifact | What it is |
|--------|----------|------------|
| [`shazo`](shazo/) | `net.teppan:shazo` | Object-persistence abstraction — one typed `Repository` contract over JDBC, file, shell, and HTTP backends. Usable standalone; ships no JDBC driver. |
| [`shazo-h2`](shazo-h2/) | `net.teppan:shazo-h2` | Optional H2 `DataSource` factory (`H2DataSources`: file / in-memory / server). Keeps H2 out of core shazo's dependencies. |
| [`backbone`](backbone/) | `net.teppan:backbone` | Transactional service + domain-event runtime, built on shazo. Depends on shazo. |
| [`demo`](demo/) | *(not published)* | Runnable sample (a memo/notes service + an orders showcase) exercising shazo and backbone together. |

`backbone` builds on `shazo` (one-way dependency); `shazo` has no dependency on
`backbone` and can be used on its own.

> **Status:** early release (`0.3.2`). API may still change before `1.0.0`.

## Modules

- **[shazo/](shazo/README.md)** — repositories, describers, the fetch pairs
  (`retrieve`/`find`, `catalog`/`gather`), caching, async, HTTP transport.
- **[backbone/](backbone/README.md)** — `ServiceRunner` (service = one
  transaction), transactional-outbox events, and a virtual-thread
  `TimerScheduler`.

## Getting the artifacts

The published modules are resolvable from this single repository via JitPack,
per tag:

```kotlin
repositories {
    maven { url = uri("https://jitpack.io") }
}
dependencies {
    // persistence only (no JDBC driver pulled in)
    implementation("com.github.juanitadevelopment.sharaku:shazo:0.3.2")
    // optional: embedded/file/server H2 DataSource factory (adds H2)
    implementation("com.github.juanitadevelopment.sharaku:shazo-h2:0.3.2")
    // application runtime (pulls in shazo transitively)
    implementation("com.github.juanitadevelopment.sharaku:backbone:0.3.2")
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
