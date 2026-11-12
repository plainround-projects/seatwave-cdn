# Changelog

## 1.0.0 (12 November 2026)

First release, built from Module 17's code seed.

- Gradle build: wrapper 9.9.1 (checksums compared with services.gradle.org), version catalog, lockfiles, `-Xlint:all -Werror`, Spotless with google-java-format 2.0.0, JaCoCo line floor 0.80, one task per program, and `prepareSampleSite`, which writes the 300,000-byte poster stand-in that the seed's session script used to write.
- The origin answers only requests that carry a shared secret header, generated per stack into the ignored `.secrets/` folder and added by the tiers; it refuses to listen on `0.0.0.0` without one. `POST /_origin/deploy` reads the dist folder again, for the release lab.
- `OriginServer` and `EdgeLoadClient` report every settings problem together, with exit status 2, instead of a stack trace; ports and counts with leading zeros are read as decimal.
- `EdgeLoadClient` counts a response as failed unless it is a whole `200` (the promised length arrived), prints throughput, prints the hit ratio to three decimals, and prints different texts for "no cache in front" and "nothing received"; it sends the secret only to the origin.
- `AssetVersioner` refuses a site folder that does not exist instead of writing an empty manifest.
- The tier template: the secret `include`, `$msec` and `$request_uri` in the log, `/_origin/` closed at both tiers, a 900 MB cache on the 1 GB `tmpfs`.
- `compose.yaml`: read-only root file systems, dropped capabilities, the origin as user 10001, memory limits, the JRE image's new digest of 12 November 2026, and a `load` service (profile `tools`) to measure capacity inside the stack's network.
- Comments in US English (the seed had "labelled", "recognise" and "modelled").
- Tests: 88 unit and configuration tests, 6 stack tests, `scripts/stack.sh`.
