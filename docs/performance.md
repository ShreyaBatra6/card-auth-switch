# Performance notes

All numbers are from one laptop: Intel Core i5-1135G7 (4 cores / 8 threads, 2.4 GHz base), 8 GB RAM,
Windows 11, Temurin JDK 21. **The load generator runs on the same machine**, so the two compete for CPU and the
results are a conservative lower bound. `scripts/benchmark.sh` reproduces the final results.

## How latency is measured

`loadgen run` is an **open-model** generator: request *n* is scheduled at `start + n / tps`, whether or not
earlier requests have been answered, which is how thousands of independent terminals behave. Two latencies are
recorded per request in HdrHistogram:

- **Latency** is measured from the *scheduled* send time. If the generator or the switch stalls, every request
  that should have been sent during the stall is charged for the wait. This is the headline number.
- **Service time** is measured from the *actual* send time. It hides stalls (coordinated omission) and is shown
  only for comparison.

A closed-loop tool (send, wait, send again) would back off exactly when the system is slow and under-report the
tail. The gap between the two columns below is that effect.

## Final results

Recorded with `scripts/benchmark.sh` on AC power (G1, `-XX:MaxGCPauseMillis=10`, 1 GB heap, 16 connections).
Raw tables: [`results/capacity.md`](results/capacity.md), [`results/realistic.md`](results/realistic.md),
[`results/resilience.md`](results/resilience.md), and the per-second [`results/resilience.csv`](results/resilience.csv).

**Switch alone (issuers answer instantly)**: latency vs. service time shows where coordinated omission would hide
the tail.

| TPS | Latency p50 / p99 / p99.9 | Service time p50 / p99 / p99.9 |
|---:|---|---|
| 2,000 | 0.21 / 0.32 / 5.10 ms | 0.20 / 0.31 / 0.72 ms |
| 5,000 | 0.15 / 8.74 / 24.7 ms | 0.15 / 0.45 / 12.9 ms |
| 10,000 | 0.12 / 31.0 / 53.6 ms | 0.12 / 0.56 / 13.5 ms |
| 15,000 | 0.17 / 722 / 773 ms | 0.15 / 10.2 / 116 ms |

At 10,000 TPS the switch answers 99% of requests within 0.56 ms *of receiving them*, but some requests left the
generator late (GC pauses, and a CPU shared with the generator). A closed-loop tool would have reported 0.56 ms.

**End to end (simulated issuer latency)**: 2,000 TPS p50 4.16 / p99 25.3 ms; 5,000 TPS p50 4.25 / p99 25.7 ms.
A 1,000 TPS stage in the same run was discarded: the machine had run out of RAM (0.2 GB free, ~7,800 pages/s of
paging from other applications) and froze for seconds at a time. Benchmarks need free memory as much as free CPU.

**Issuer outage**: recorded before the precise latency simulator below existed, so its absolute latencies include
Windows timer rounding of the simulated issuer delays. The failover behavior is what it measures: 0 errors out of
180,000, failover within ~2 s, recovery within 3 s.

## Investigation log

### 1. Windows timer granularity (simulated latency was 3x too high)

The first 2,000 TPS run showed p50 = 12.4 ms, although the simulated issuers have medians of 2, 4 and 6 ms.
A probe scheduling 2 ms tasks on a `ScheduledThreadPoolExecutor` showed the cause:

| | `schedule(2 ms)` actual delay p50 | p90 | max |
|---|---:|---:|---:|
| default | 15.58 ms | 16.09 ms | 16.91 ms |
| with 1 ms timer resolution requested | 2.66 ms | 3.64 ms | 4.97 ms |

Windows rounds timed waits up to its 15.6 ms timer tick. The usual JVM workaround is a daemon thread in
`Thread.sleep(Long.MAX_VALUE)`, because HotSpot requests 1 ms resolution while such a sleep is in progress. It
looked like it worked (p50 fell to 4.7 ms in one run), but later runs were back at ~12.5 ms. A probe that measured
over time showed why:

| Time since JVM start | 0 s | 3 s | 6 s | … | 21 s |
|---|---:|---:|---:|---|---:|
| `schedule(2 ms)` p50 with the workaround | 2.96 ms | 15.74 ms | 15.49 ms | … | 15.84 ms |

**Windows 11 honored the 1 ms request for only about 3 seconds**, whether the process ran in the foreground or
background, on battery or AC. (The one good run was probably helped by another application holding the
system timer at high resolution at the time.)

The fix was to stop depending on OS timers where precision matters. `PreciseDelayScheduler` runs the simulated
issuer delays on a dedicated thread that parks until the next deadline is 20 ms away (more than one OS tick), then
spins for the rest. A unit test asserts a median lateness under 1 ms for 2 ms delays. End-to-end p50 at 2,000 TPS
is now 4.2 ms, which matches the configured issuer medians. The cost is up to one core while responses are pending;
acceptable for a simulator of remote hosts. The switch's own 100 ms issuer timeout still uses an ordinary
scheduler, so on Windows it fires after 100–116 ms; on Linux timers are far finer.

### 2. GC pauses were the whole tail

With issuers answering instantly, the switch's service time was p50 0.2 ms, p99 < 0.5 ms, yet p99.9 sat at
30–50 ms. A GC log showed G1 young pauses of **34–58 ms** (p50 34 ms) every few seconds. The switch keeps state
per transaction: duplicate-detection keys (2 min), velocity windows (60 s), and the mock issuers' holds. Those
objects survive young collections and have to be copied.

| Collector (switch JVM) | Stages in the run | 5,000 TPS p99 | 10,000 TPS p99 | 10,000 TPS max |
|---|---|---:|---:|---:|
| G1, default 200 ms pause goal | 2k → 5k → 10k → … | 21.9 ms | 68.7 ms | 115.5 ms |
| Generational ZGC (`-XX:+UseZGC -XX:+ZGenerational`) | 5k → 10k | 360.7 ms | 1,045.5 ms | 1,229.8 ms |
| G1, `-XX:MaxGCPauseMillis=10` | 5k → 10k | 8.7 ms | 13.8 ms | 26.0 ms |
| **G1, `-XX:MaxGCPauseMillis=10`** (same 2k → 5k → 10k → 15k ramp) | 2k → 5k → 10k → 15k | **9.6 ms** | 57.6 ms | 124.5 ms |

These four runs were all on battery. The like-for-like comparison is the first row against the last:
**p99 at 5,000 TPS went from 21.9 ms to 9.6 ms** (8.7 ms on AC power in the final results). At 10,000 TPS the result depends on how much state earlier stages left in the caches, and on noise
at this machine's saturation point.

- **ZGC made it much worse here.** Its pauses are tiny, but its concurrent marking and relocation threads
  compete for the same 4 cores as the Netty event loops *and* the load generator, so the application threads
  are starved. On a dedicated many-core server, ZGC would be the first thing to try again.
- **A 10 ms pause goal** makes G1 use a much smaller young generation: pauses every ~0.65 s instead of every
  few seconds, each about 15 ms (p90 19 ms). Phase logging (`-Xlog:gc+phases=debug`) splits a 24 ms pause into
  ~10 ms *Scan Heap Roots* (old-generation cache tables pointing at new entries) and ~13 ms *Object Copy*
  (the new entries themselves).

### 3. Where the remaining tail comes from, and what would fix it

Both GC phases scale with *long-lived objects created per transaction*. The next steps, in order of payoff:

1. **Allocation-free duplicate detection**: two rotating open-addressing `long[]` tables of 64-bit fingerprints
   instead of a `Caffeine<String, Boolean>`. That removes roughly 200 bytes of long-lived garbage per
   transaction.
2. **Run the issuer simulators out of process**: their holds and accounts are a large share of the switch JVM's
   live set, and a real switch wouldn't carry them.
3. **Separate machines for load generator and switch**: removes the CPU contention that also slows GC workers
   (8 GC worker threads share 4 physical cores with everything else).
