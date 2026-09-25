package com.peergrab.bench;

/** An explicit opt-in for tools that create data or significant load. */
final class BenchSafety {
    private BenchSafety() {}

    static void requireDisposableStack() {
        String project = System.getenv("PEERGRAB_BENCH_PROJECT");
        if (!"YES".equals(System.getenv("PEERGRAB_BENCH_DISPOSABLE"))
                || project == null || !project.startsWith("peergrab-bench-")
                || System.getenv("PEERGRAB_TEST_DB_HOST") == null
                || System.getenv("PEERGRAB_TEST_DB_PORT") == null) {
            throw new IllegalStateException("Benchmark requires PEERGRAB_BENCH_DISPOSABLE=YES, "
                    + "PEERGRAB_BENCH_PROJECT=peergrab-bench-..., and explicit "
                    + "PEERGRAB_TEST_DB_HOST/PEERGRAB_TEST_DB_PORT for an isolated benchmark stack");
        }
    }
}
