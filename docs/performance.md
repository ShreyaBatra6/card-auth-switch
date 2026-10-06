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

Recorded with `scripts/benchmark.sh` (G1, `-XX:MaxGCPauseMillis=10`, 1 GB heap, 16 connections). The laptop was
**on battery** (29%, discharging), which lowers CPU clocks and lets Windows ignore the 1 ms timer request below.
Expect better numbers plugged in. Raw tables: [`results/capacity.md`](results/capacity.md),
[`results/realistic.md`](results/realistic.md), [`results/resilience.md`](results/resilience.md), and the per-second
[`results/resilience.csv`](results/resilience.csv).

**Switch alone (issuers answer instantly)**: latency vs. service time shows where coordinated omission would hide
the tail.

| TPS | Latency p50 / p99 / p99.9 | Service time p50 / p99 / p99.9 |
|---:|---|---|
| 2,000 | 0.29 / 1.23 / 12.2 ms | 0.28 / 0.50 / 5.95 ms |
| 5,000 | 0.26 / 9.61 / 18.7 ms | 0.25 / 0.54 / 17.9 ms |
| 10,000 | 0.26 / 57.6 / 121 ms | 0.24 / 4.67 / 20.7 ms |
| 15,000 | 24.4 / 467 / 473 ms | 0.23 / 20.8 / 120 ms |

At 10,000 TPS the switch still answers 99% of requests within 4.7 ms *of receiving them*, but the requests were
sometimes sent late because the generator shared a saturated CPU. A closed-loop tool would have reported the
4.7 ms. An earlier tuning run at 10,000 TPS (after a 5,000 TPS warm-up rather than the full 2k/5k ramp) measured
p99 13.8 ms, so 10k is right at this machine's knee.



## Investigation log

### 1. Windows timer granularity (simulated latency was 3x too high)

The first 2,000 TPS run showed p50 = 12.4 ms, although the simulated issuers have medians of 2, 4 and 6 ms.
A probe scheduling 2 ms tasks on a `ScheduledThreadPoolExecutor` showed the cause:

| | `schedule(2 ms)` actual delay p50 | p90 | max |
|---|---:|---:|---:|
| default | 15.58 ms | 16.09 ms | 16.91 ms |
| with 1 ms timer resolution | 2.66 ms | 3.64 ms | 4.97 ms |

Windows rounds timed waits up to its 15.6 ms timer tick. HotSpot asks for 1 ms resolution while any
`Thread.sleep` is in progress, so `WindowsTimerResolution` keeps a daemon thread asleep. End-to-end p50 at
2,000 TPS dropped from **12.4 ms to 4.7 ms**, which matches the simulated issuers. This doesn't apply on Linux.

Caveat: in the final benchmark the laptop was on battery and the long-running switch process got the 15.6 ms
granularity again (p50 ≈ 12 ms in the end-to-end table), while a short foreground probe still got 1 ms.
Windows 11 power throttling can ignore timer-resolution requests from background processes. Only the
*simulated* issuer delays are affected; the switch-alone numbers don't use timers on the request path.

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
| **G1, `-XX:MaxGCPauseMillis=10`** (final benchmark) | 2k → 5k → 10k → 15k | **9.6 ms** | 57.6 ms | 124.5 ms |

The like-for-like comparison is the first row against the last: **p99 at 5,000 TPS went from 21.9 ms to
9.6 ms**. At 10,000 TPS the result depends on how much state earlier stages left in the caches, and on noise
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
