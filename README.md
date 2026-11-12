# seatwave-cdn

SeatWave's static content behind a CDN stand-in: a header policy that gives every response its `Cache-Control`, a static origin with strong ETags that answers only the CDN tiers, a build step that names every asset after its bytes, an NGINX edge and shield in front of the origin, a model of a shared cache tier for releases and purges at scale, and a load client that measures hit ratio and origin load. This is the project of **Module 17, "CDN"**, of the course System Design Fundamentals; the running case is SeatWave, a ticket-booking service.

## What it shows

- One invariant, in two independent layers: no response containing personal data is ever cacheable at the edge. The origin sends holds, bookings, tokens and every unknown path `private, no-store`; every tier refuses to store or answer from cache anything under `/v1/` or any request with credentials.
- Versioned names (`/static/<name>.<12 hex digits>.<ext>`, cached a year) let a release ship without purges and without a broken page.
- On this laptop, 20,000 first visits sent 60,000 requests to the origin without the CDN stand-in and 3 with it; a cold edge in front of a warm shield sent 0.
- NGINX labels requests that waited on the cache lock `HIT`; after `proxy_cache_lock_timeout` they go upstream as `MISS`. NGINX does not read or send `Age`, so a page passed through two tiers can be served up to the sum of their lifetimes.

## Prerequisites

- JDK 25 (the toolchain asks for 25; run on Eclipse Temurin 25.0.2, the current 25 update being 25.0.5).
- Docker with Compose v2 for the stack (run on Docker Desktop, engine 24.0.6, Compose v2.23.0-desktop.1, whose Linux virtual machine had 12 CPUs and 7.7 GiB). `./gradlew check` needs no Docker.
- `curl` 7.55 or newer (for `-H @file`) and `openssl` for the stack script.

## Images and dependencies

Every version is exact and was checked on 12 November 2026: images on Docker Hub (tag and digest, `arm64` and `amd64` variants), libraries on Maven Central, and OSV listed no vulnerability for result4j 1.2.0, JUnit 6.1.3 or AssertJ 3.27.7.

| Image | Tag | Digest | Used for |
|---|---|---|---|
| `nginx` | `1.30.6` (the stable line, as nginx.org lists it; 1.31.8 is mainline) | `sha256:42c68eab6f7e22611786a9fe1d1c019e275c50185bd54b8f75dc2b9184322703` | the edge and the shield |
| `eclipse-temurin` | `25.0.5_8-jre-noble` (pushed again on 12 November 2026) | `sha256:e3b6f7887a50f20d8f07abf279d8a81a5953fb398a9555bd9edd56b9bd15968c` | the origin and the in-network load client |

| Coordinates | Version | Scope | License | Why the JDK alone is not enough |
|---|---|---|---|---|
| `com.github.sviperll:result4j` | 1.2.0 | main | Apache-2.0 | The JDK has no success-or-typed-error type; a refused build and refused settings are values. |
| `org.junit:junit-bom`, `org.junit.jupiter:junit-jupiter` | 6.1.3 | test | EPL-2.0 | The test engine. |
| `org.assertj:assertj-core` | 3.27.7 | test | Apache-2.0 | Assertions with clear failure messages. |
| `com.diffplug.spotless` (Gradle plugin), `google-java-format` | 8.11.0, 2.0.0 | build | Apache-2.0 | One enforced code style. |
| JaCoCo | 0.8.16 | build | EPL-2.0 | Line coverage, with a floor of 0.80. |

Gradle 9.9.1 through the wrapper; the wrapper jar's and the distribution's SHA-256 match the values services.gradle.org publishes. Everything else is the JDK: the HTTP server and client, SHA-256, virtual threads.

## Build and test (no Docker)

```
./gradlew clean check
```

Formats with Spotless, compiles with `-Xlint:all -Werror`, runs the unit and configuration tests and fails below 80 percent line coverage (the three demo programs are excluded from the count). The configuration tests read `nginx/` and `compose.yaml`, which are inputs of the `test` task. The tests tagged `stack` are left out.

```
$ ./gradlew clean check
> Task :check
> Task :jacocoTestReport

BUILD SUCCESSFUL in 4s
8 actionable tasks: 8 executed
Consider enabling configuration cache to speed up this build: https://docs.gradle.org/9.9.1/userguide/configuration_cache_enabling.html
exit status 0
```

```
tests 88, failures 0
line coverage 90.2 percent (624 of 692 lines; the three demo programs excluded)
```

The test task ran 40 times in a row beside four busy loops, and the whole check ran from a path of more than 220 characters and from a folder whose name has a space:

```
start 13:37:20Z, 4 busy loops, load: 5.09 8.93 9.61
test task: 40 runs, 0 failed
stackTest task: 5 runs on one stack, 0 failed
end 13:40:11Z, load: 6.69 7.93 9.06
```

```
== a copy at a path of 370 characters
./gradlew check: exit status 0
== a copy at a path of 210 characters, in a folder named "folder with space+plus"
./gradlew check: exit status 0
```

Every test that guards a rule was mutation-checked: one changed line per mutant, each of which must make the named test fail.

```
unit  caught   PersonalResponsesNeverCachedTest   unknown paths sent public for 60 s
unit  caught   PersonalResponsesNeverCachedTest   personal paths sent public for 60 s
unit  caught   PersonalResponsesNeverCachedTest   a tier answers credentialed requests from cache
unit  caught   EdgeCacheTest                      a tier stores private and no-store responses
unit  caught   EdgeCacheTest                      a tier stores a response that sets a cookie
unit  caught   EdgeCacheTest                      the lock never holds a request back
unit  caught   EdgeCacheTest                      a tier that speaks Age ignores the Age it received
unit  caught   CacheControlTest                   max-age=3600s is read as a lifetime
unit  caught   CacheControlTest                   max-age beats s-maxage at a shared cache
unit  caught   OriginServerTest                   the origin serves requests without the secret
unit  caught   OriginServerTest                   the origin listens on 0.0.0.0 without a secret
unit  caught   OriginServerTest                   only the first origin setting problem is reported
unit  caught   AssetVersionerTest                 a page naming a missing asset is built
unit  caught   EdgeLoadClientTest                 the load client sends the secret to fans through a tier
unit  caught   EdgeLoadClientTest                 no response and no cache print the same text
unit  caught   CdnTierConfigTest                  the API location may store
unit  caught   CdnTierConfigTest                  a tier lengthens lifetimes
unit  caught   CdnTierConfigTest                  credentialed requests answered from cache
unit  caught   CdnTierConfigTest                  a location drops the inherited secret header
unit  caught   CdnTierConfigTest                  the edge published on every address
unit  caught   CdnTierConfigTest                  the shield published outside Module 17's range
unit  caught   CdnTierConfigTest                  the NGINX image pinned by tag only
stack caught   StackTest                          the API location removed from the tiers
stack caught   StackTest                          credentialed requests answered from cache
stack caught   StackTest                          the origin serves requests without the secret
25 of 25 mutants caught
unmutated test: passed
unmutated stack test: passed
```

## The programs

| Task | Program | What it prints |
|---|---|---|
| `./gradlew prepareSampleSite` | (Gradle) | writes the 300,000-byte poster stand-in into `site/static/` and `site/share/` (ignored by git) |
| `./gradlew versionAssets` | `AssetVersioner` | hashed names, the manifest and the pages in `build/dist` |
| `./gradlew origin --args="build/dist 21700"` | `OriginServer` | serves `build/dist` on 127.0.0.1:21700 |
| `./gradlew load --args="<page> <stats> <fans> <at once> [<secret file>]"` | `EdgeLoadClient` | hit ratio, origin requests and bytes, throughput, p50 and p99 |
| `./gradlew edgeRules` | `EdgeRulesDemoCli` | one modeled edge through every rule on a stepped clock |
| `./gradlew release` | `ReleaseDemoCli` | a release under load five ways, on the model of 20 edges |
| `./gradlew originLoad` | `OriginLoadCli` | estimates: origin load from hit ratio, first visits by region, egress cost |

```
$ ./gradlew -q versionAssets
poster-arena.jpg -> /static/poster-arena.c12fb0010fbd.jpg
seat-map.js -> /static/seat-map.896d2ad8ee81.js
page /events/arena.html
exit status 0
$ cat build/dist/asset-manifest.json
{
  "poster-arena.jpg": "/static/poster-arena.c12fb0010fbd.jpg",
  "seat-map.js": "/static/seat-map.896d2ad8ee81.js"
}
$ grep -o 'src="[^"]*"' build/dist/events/arena.html
src="/static/seat-map.896d2ad8ee81.js"
src="/static/poster-arena.c12fb0010fbd.jpg"
$ find build/dist -type f | sort
build/dist/asset-manifest.json
build/dist/events/arena.html
build/dist/share/poster-arena.jpg
build/dist/static/poster-arena.c12fb0010fbd.jpg
build/dist/static/seat-map.896d2ad8ee81.js
$ ls -l site/static/poster-arena.jpg site/share/poster-arena.jpg | awk '{print $5, $NF}'
300000 site/share/poster-arena.jpg
300000 site/static/poster-arena.jpg
$ java -cp 'build/stack/lib/*' io.plainround.seatwave.cdn.assets.AssetVersioner build/broken-site build/broken-dist
build refused: MissingAsset[page=broken.html, assetName=missing.js]
exit status 2
build/broken-dist written: no
```

```
$ curl -sI http://127.0.0.1:21700/static/seat-map.896d2ad8ee81.js
HTTP/1.1 200 OK
Etag: "896d2ad8ee816483"
Cache-control: public, max-age=31536000, immutable
$ curl -sI http://127.0.0.1:21700/events/arena.html
HTTP/1.1 200 OK
Etag: "e94d7b00486daf27"
Cache-control: public, max-age=0, s-maxage=60, stale-while-revalidate=30
$ curl -sI http://127.0.0.1:21700/share/poster-arena.jpg
HTTP/1.1 200 OK
Etag: "c12fb0010fbd782b"
Cache-control: public, max-age=300, s-maxage=3600
$ curl -sI http://127.0.0.1:21700/v1/events/1/seats
HTTP/1.1 404 Not Found
Cache-control: no-cache
$ curl -sI http://127.0.0.1:21700/v1/holds/h-1
HTTP/1.1 404 Not Found
Cache-control: private, no-store
$ curl -sI http://127.0.0.1:21700/static/seat-map.js
HTTP/1.1 404 Not Found
Cache-control: private, no-store
$ curl -sI -H 'If-None-Match: "e94d7b00486daf27"' http://127.0.0.1:21700/events/arena.html
HTTP/1.1 304 Not Modified
```

```
$ java ... OriginServer build/distt 80 0.0.0.0 no-such-file   (a wrong command line)
settings: dist folder 'build/distt' is not a folder; run ./gradlew versionAssets
settings: port must be in 1024..65535, got 80
settings: secret file 'no-such-file' cannot be read (NoSuchFileException); scripts/stack.sh up writes it
exit status 2
```

## The stack

`scripts/stack.sh` is the only way in and out. It does a dry run unless given `--run`, asks before acting unless also given `--yes`, refuses to do anything when Docker does not answer, writes an audit line to `build/stack/audit.log`, and checks after `down` that no container, network or volume of the project `seatwave-cdn` is left.

```
scripts/stack.sh up --run --yes      # stackFiles, versionAssets, a new secret, origin, shield, edge
scripts/stack.sh test --run --yes    # up, ./gradlew stackTest, then down, on every path
scripts/stack.sh down --run --yes    # docker compose -p seatwave-cdn --profile tools down -v
```

| Port on 127.0.0.1 | What answers |
|---|---|
| 21700 | the origin (`OriginServer`); `403` without the secret header |
| 21701 | the edge (NGINX); fans come here |
| 21702 | the shield (NGINX) |

The origin's secret is 32 random bytes, written by `scripts/stack.sh up` into `.secrets/` (ignored by git, not under `build/`, deleted by `down`) as a plain file for the origin, an NGINX `proxy_set_header` line for the tiers and a header file for `curl -H @file`, so it never appears in a process listing. The files are readable by the containers' users because a bind mount on a Linux host keeps the host's owner [not run: no Linux host was used]; on Docker Desktop for Mac it worked as shown.

```
$ scripts/stack.sh test --run --yes
plan: test the Compose project seatwave-cdn
  up, then ./gradlew stackTest, then down -v (always)
poster-arena.jpg -> /static/poster-arena.c12fb0010fbd.jpg
seat-map.js -> /static/seat-map.896d2ad8ee81.js
page /events/arena.html
stack is up: origin, shield and edge answered after 0 s
> Task :stackTest
StackTest > aSecretSentByAClientIsReplacedByTheTier() PASSED
StackTest > theOriginsLabPathsAreNotReachableThroughEitherTier() PASSED
StackTest > aRequestStraightToTheOriginWithoutTheSecretIsRefused() PASSED
StackTest > oneFileThroughTheEdgeTwiceIsAMissThenAHitAndReachesTheOriginOnce() PASSED
StackTest > aRequestWithCredentialsIsNeverAnsweredFromTheEdgesCache() PASSED
StackTest > everyPersonalPathBypassesBothTiersAndIsSentPrivate() PASSED
BUILD SUCCESSFUL in 1s
nothing of seatwave-cdn is left
exit status 0
```

## Every measurement and what it printed

On an Apple M2 Max (12 cores, 32 GiB), Docker Desktop's virtual machine with 12 CPUs and 7.7 GiB, one CPU each for the origin, the shield and the edge. Requests from the Mac cross Docker Desktop's port forwarder; the capacity runs at the end run inside the stack's network. Another course project was building at the same time; the load averages are printed beside each run. These are laptop numbers, not a CDN's.

```
date: 2026-11-12T13:23:43Z, load averages at start: 8.27 9.48 8.54
Apple M2 Max
12
32 GiB, macOS 27.2
engine 24.0.6, linux/arm64
Docker VM: 12 CPUs, 8225304576 bytes, kernel 6.4.16-linuxkit
Docker Compose version v2.23.0-desktop.1
openjdk version "25.0.2" 2026-01-20 LTS
curl 8.7.1 (x86_64-apple-darwin26.0) libcurl/8.7.1 (SecureTransport) LibreSSL/3.3.6 zlib/1.2.12 nghttp2/1.69.0
```

```
$ scripts/stack.sh up --run --yes
plan: up the Compose project seatwave-cdn
  ./gradlew -q stackFiles versionAssets; new secret in .secrets/; docker compose -p seatwave-cdn up -d --quiet-pull; wait for 21700, 21701, 21702
poster-arena.jpg -> /static/poster-arena.c12fb0010fbd.jpg
seat-map.js -> /static/seat-map.896d2ad8ee81.js
page /events/arena.html
stack is up: origin, shield and edge answered after 0 s
exit status 0
$ docker compose -p seatwave-cdn ps --format '{{.Service}}  {{.Image}}  {{.Status}}'
edge  nginx:1.30.6@sha256:42c68eab6f7e22611786a9fe1d1c019e275c50185bd54b8f75dc2b9184322703  Up Less than a second
origin  eclipse-temurin:25.0.5_8-jre-noble@sha256:e3b6f7887a50f20d8f07abf279d8a81a5953fb398a9555bd9edd56b9bd15968c  Up Less than a second
shield  nginx:1.30.6@sha256:42c68eab6f7e22611786a9fe1d1c019e275c50185bd54b8f75dc2b9184322703  Up Less than a second
$ docker exec seatwave-cdn-edge-1 nginx -v
nginx version: nginx/1.30.6
$ docker exec seatwave-cdn-edge-1 nginx -t
nginx: the configuration file /etc/nginx/nginx.conf syntax is ok
nginx: configuration file /etc/nginx/nginx.conf test is successful
$ docker exec seatwave-cdn-origin-1 id
uid=10001 gid=10001 groups=10001
$ the edge's processes by user, read from /proc inside the container
   1 nginx nginx: cache loader process 
   1 nginx nginx: cache manager process 
  12 nginx nginx: worker process 
   1 root nginx: master process nginx -g daemon of
```

One file through the edge, twice, and the origin's counter:

```
$ curl -s -H @.secrets/origin-secret.header http://127.0.0.1:21700/_origin/stats
requests=0 bodyBytes=0 refused=0
$ curl -sI http://127.0.0.1:21701/static/seat-map.896d2ad8ee81.js   (first)
HTTP/1.1 200 OK
Etag: "896d2ad8ee816483"
Cache-control: public, max-age=31536000, immutable
X-Shield-Cache-Status: MISS
X-Cache-Status: MISS
$ curl -sI http://127.0.0.1:21701/static/seat-map.896d2ad8ee81.js   (second)
HTTP/1.1 200 OK
Etag: "896d2ad8ee816483"
Cache-control: public, max-age=31536000, immutable
X-Shield-Cache-Status: MISS
X-Cache-Status: HIT
$ curl -s -H @.secrets/origin-secret.header http://127.0.0.1:21700/_origin/stats
requests=1 bodyBytes=502 refused=0
```

The personal paths, twice each:

```
$ curl -s -H @.secrets/origin-secret.header http://127.0.0.1:21700/_origin/stats
requests=1 bodyBytes=502 refused=0
$ curl -sI http://127.0.0.1:21701/v1/holds/h-1   (fan 1)
HTTP/1.1 404 Not Found
Cache-control: private, no-store
X-Shield-Cache-Status: BYPASS
X-Cache-Status: BYPASS
$ curl -sI http://127.0.0.1:21701/v1/holds/h-1   (fan 2)
HTTP/1.1 404 Not Found
Cache-control: private, no-store
X-Shield-Cache-Status: BYPASS
X-Cache-Status: BYPASS
$ curl -sI http://127.0.0.1:21701/v1/bookings   (fan 1)
HTTP/1.1 404 Not Found
Cache-control: private, no-store
X-Shield-Cache-Status: BYPASS
X-Cache-Status: BYPASS
$ curl -sI http://127.0.0.1:21701/v1/bookings   (fan 2)
HTTP/1.1 404 Not Found
Cache-control: private, no-store
X-Shield-Cache-Status: BYPASS
X-Cache-Status: BYPASS
$ curl -sI http://127.0.0.1:21701/v1/tokens   (fan 1)
HTTP/1.1 404 Not Found
Cache-control: private, no-store
X-Shield-Cache-Status: BYPASS
X-Cache-Status: BYPASS
$ curl -sI http://127.0.0.1:21701/v1/tokens   (fan 2)
HTTP/1.1 404 Not Found
Cache-control: private, no-store
X-Shield-Cache-Status: BYPASS
X-Cache-Status: BYPASS
$ curl -s -H @.secrets/origin-secret.header http://127.0.0.1:21700/_origin/stats
requests=7 bodyBytes=562 refused=0
```

The origin's refusal:

```
$ curl -si http://127.0.0.1:21700/events/arena.html | head -1; ... body
HTTP/1.1 403 Forbidden
Cache-control: private, no-store
forbidden: requests must come through the CDN tiers
$ curl -s http://127.0.0.1:21700/_origin/stats
HTTP 403
$ curl -s -o /dev/null -w '%{http_code}' -H 'X-SeatWave-Origin-Secret: <64 zeros>' http://127.0.0.1:21701/events/arena.html?forged=1   (through the edge)
HTTP 200
$ curl -s -H @.secrets/origin-secret.header http://127.0.0.1:21700/_origin/stats
requests=8 bodyBytes=1120 refused=3
```

BEFORE: 20,000 first visits straight at the origin (with the secret, so the origin serves them as it would without a CDN):

```
load at start: 9.52 9.72 8.65
fans 20,000, requests 60,000 in 36.81 s, failed 0
x-cache-status {none=60000}
edge hit ratio none (no cache in front)
origin requests 60,000 (3.0000 per fan), origin body bytes 6,021,200,000
throughput 1,630 responses/s, 1.31 Gbps of bodies received
latency p50 10.249 ms, p99 208.143 ms
load at end: 12.35 10.36 8.92
```

AFTER: the same visits through the edge, after both tiers were emptied by a restart:

```
load at start: 12.35 10.36 8.92
fans 20,000, requests 60,000 in 34.99 s, failed 0
x-cache-status {HIT=59997, MISS=3}
edge hit ratio 99.995%
origin requests 3 (0.0002 per fan), origin body bytes 301,060
throughput 1,715 responses/s, 1.38 Gbps of bodies received
latency p50 10.450 ms, p99 180.122 ms
load at end: 16.06 11.49 9.39
```

A cold edge in front of a warm shield:

```
$ docker compose -p seatwave-cdn restart edge
edge answering again after 0 s; load: 16.06 11.49 9.39
fans 20,000, requests 60,000 in 35.08 s, failed 0
x-cache-status {HIT=59997, MISS=3}
edge hit ratio 99.995%
origin requests 0 (0.0000 per fan), origin body bytes 0
throughput 1,710 responses/s, 1.37 Gbps of bodies received
latency p50 10.592 ms, p99 181.053 ms
```

How NGINX labels requests that waited on the cache lock (the origin paused while ten fans ask for one cold key):

```
== origin paused for 1 s; 10 fans ask for /static/poster-arena.c12fb0010fbd.jpg?probe=lock-probe-1-1794489943 at once
   7 X-Cache-Status: HIT X-Shield-Cache-Status: MISS  total 1.5 s
   2 X-Cache-Status: HIT X-Shield-Cache-Status: MISS  total 1.6 s
   1 X-Cache-Status: MISS X-Shield-Cache-Status: MISS  total 1.1 s
origin requests for the 10 fans: 1
== origin paused for 7 s; 10 fans ask for /static/poster-arena.c12fb0010fbd.jpg?probe=lock-probe-7-1794489944 at once
   8 X-Cache-Status: MISS X-Shield-Cache-Status: HIT  total 7.6 s
   2 X-Cache-Status: MISS X-Shield-Cache-Status: MISS  total 7.1 s
origin requests for the 10 fans: 2
== the edge's log lines for the 1 s probe (arrival = msec - rt)
msec=1794489944.459 cache=MISS rt=1.118
msec=1794489944.854 cache=HIT rt=1.513
msec=1794489944.854 cache=HIT rt=1.513
msec=1794489944.854 cache=HIT rt=1.512
msec=1794489944.854 cache=HIT rt=1.512
msec=1794489944.854 cache=HIT rt=1.512
msec=1794489944.854 cache=HIT rt=1.510
msec=1794489944.854 cache=HIT rt=1.509
msec=1794489944.862 cache=HIT rt=1.520
msec=1794489944.862 cache=HIT rt=1.520
```

A fixed-name file changed in place, and the purge that makes the edge serve it:

```
edge's ETag of the poster as fans have it: "c12fb0010fbd782b"
$ java ... AssetVersioner build/changed-site build/dist   (the /share/ poster changed in place)
page /events/arena.html
$ curl -s -X POST -H @.secrets/origin-secret.header http://127.0.0.1:21700/_origin/deploy
deployed 5 files
origin's ETag now: "e6bf257e65dbf33a"
edge's answer after the deploy, before any purge: Etag "c12fb0010fbd782b"  X-Cache-Status HIT  
$ docker compose -p seatwave-cdn restart shield edge; then poll the edge every 0.1 s
edge's ETag after the purge: "e6bf257e65dbf33a", first answered 495 ms after the restart began
```

A versioned release during load:

```
$ java ... AssetVersioner build/release-site build/dist; POST /_origin/deploy   (release 2)
seat-map.js -> /static/seat-map.2b738e461e7d.js
deployed 5 files
old page names /static/seat-map.896d2ad8ee81.js; new page names /static/seat-map.2b738e461e7d.js
the edge first served the new page 119575 ms after the deploy
the new script through the edge: HTTP 200; the old one: HTTP 200
== the load during the release (16 fans at once, through the edge)
fans 100,000, requests 300,000 in 167.91 s, failed 0
x-cache-status {HIT=299995, MISS=3, STALE=2}
edge hit ratio 99.999%
origin requests 5 (0.0001 per fan), origin body bytes 301,620
throughput 1,787 responses/s, 1.43 Gbps of bodies received
latency p50 3.152 ms, p99 60.821 ms
origin requests from the load's start to now: 6
```

Capacity, from inside the stack's network:

```
load at start: 15.18 13.35 10.66
$ docker compose -p seatwave-cdn run --rm load http://origin:21700/events/arena.html http://origin:21700/_origin/stats 20000 64 /etc/seatwave-secret/origin-secret
fans 20,000, requests 60,000 in 9.45 s, failed 0
x-cache-status {none=60000}
edge hit ratio none (no cache in front)
origin requests 60,000 (3.0000 per fan), origin body bytes 6,022,360,000
throughput 6,352 responses/s, 5.10 Gbps of bodies received
latency p50 5.881 ms, p99 39.508 ms
$ docker compose -p seatwave-cdn run --rm load http://edge:8080/events/arena.html http://origin:21700/_origin/stats 20000 64 /etc/seatwave-secret/origin-secret
fans 20,000, requests 60,000 in 9.77 s, failed 0
x-cache-status {HIT=59997, MISS=3}
edge hit ratio 99.995%
origin requests 3 (0.0002 per fan), origin body bytes 301,118
throughput 6,138 responses/s, 4.93 Gbps of bodies received
latency p50 4.649 ms, p99 38.021 ms
load at end: 14.56 13.33 10.72
```

```
$ scripts/stack.sh down --run --yes
plan: down the Compose project seatwave-cdn
  docker compose -p seatwave-cdn --profile tools down -v --remove-orphans; remove .secrets/; check nothing of seatwave-cdn is left
nothing of seatwave-cdn is left
exit status 0
$ docker ps -a --filter label=com.docker.compose.project=seatwave-cdn -q | wc -l
0
$ docker network ls --filter label=com.docker.compose.project=seatwave-cdn -q | wc -l
0
$ docker volume ls --filter label=com.docker.compose.project=seatwave-cdn -q | wc -l
0
load at end: 13.72 13.17 10.68, 2026-11-12T13:29:07Z
```

## Project structure

```
build.gradle.kts, settings.gradle.kts, gradle/      the build, the version catalog, the wrapper
compose.yaml                                       origin, shield, edge; the load client (profile tools)
nginx/templates/cdn-tier.conf.template             one tier's configuration, used by edge and shield
scripts/stack.sh                                   up, test, down, status
site/                                              the sample site (the poster is written by prepareSampleSite)
src/main/java/io/plainround/seatwave/cdn/          http, origin, assets, edge, demo, load
src/test/java/io/plainround/seatwave/cdn/          unit and configuration tests; stack/StackTest (tag "stack")
```

## What is simplified

- One edge and one shield on one laptop: no distance, so the round trips by region, and every time derived from them, are estimates (`OriginLoadCli`). Measuring them needs pollers in several regions against a real CDN, which this project does not use.
- The purge is a restart of a tier, because open-source NGINX has no purge command; a CDN purges by path or tag through its API.
- The origin is a teaching origin; Module 29 replaces it with object storage.
- The hit ratios here are for three very popular files; a real site's overall hit ratio is pulled down by its long tail.

## No CI on purpose

There is no `.github/` folder and no workflow: every check above was run on the machine named, and the commands are here to run again.

## License

MIT, see `LICENSE`.
