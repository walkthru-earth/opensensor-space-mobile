# History host benchmark

This is a synthetic UTC day of 8,640 rows in 96 files. Every input is generated in
memory with fixed values, invalid gates and immutable row identities. Setup checks
that raw and derived charts give the same series. No phone files or device
identifiers are read.

The pre-change aggregator and daily builder come from the frozen golden-test
oracle. The combined before path runs those two passes. The combined after path
runs one DayPass. Raw and derived chart reads both use the current reader, so that
comparison measures the source choice rather than an old reader implementation.

Build the kotlinx-benchmark JMH harness with the bundled JBR.

```sh
JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' ./gradlew --no-daemon :tools:history-benchmark:mainBenchmarkJar
'/Applications/Android Studio.app/Contents/jbr/Contents/Home/bin/java' -jar tools/history-benchmark/build/benchmarks/main/jars/history-benchmark-main-jmh-JMH.jar -wi 3 -i 5 -w 1s -r 1s -f 1 -bm avgt -tu ns -prof gc -rf json -rff /tmp/history-benchmark.json
```

Divide `primaryMetric.score` by 8,640 for ns per row and
`secondaryMetrics["gc.alloc.rate.norm"].score` by 8,640 for allocated bytes per
row. Allocated bytes are not an object count. JVM warmup and heap allocation
measurements do not establish phone latency, energy use or native allocations.
The Kotlin benchmark runtime and plugin are pinned in the version catalog.

Primary tool documentation is [kotlinx-benchmark](https://github.com/Kotlin/kotlinx-benchmark/blob/master/docs/tasks-overview.md)
and [configuration options](https://github.com/Kotlin/kotlinx-benchmark/blob/master/docs/configuration-options.md).
