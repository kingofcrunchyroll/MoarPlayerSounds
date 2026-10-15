# MoarPlayerSounds
Tired of the usual sounds? Wanna sound like your favourite mob? Don't worry, this mod has you covered ;)

## Build
Requires JDK 21.
```
gradle wrapper --gradle-version 8.12   # once, if you have no gradlew
./gradlew build                         # jar in build/libs/
./gradlew runClient                     # dev client
```
Version pins are in `gradle.properties` / `build.gradle` (Loom 1.10-SNAPSHOT, Loader 0.16.14, Fabric API 0.116.17+1.21.1, Yarn 1.21.1+build.3); confirm them at fabricmc.net/develop if resolution fails.

## Install
Fabric Loader + Fabric API for 1.21.1, then drop `moarplayersounds-0.1.0.jar` in `.minecraft/mods`. Not needed on servers.

## Use
`/playersound preset <mob>`, `slot <slot> <mob|vanilla>`, `preview <slot>`, `status`, `list`, `reset`, `enable|disable`. Config: `config/moarplayersounds.json`.

See ARCHITECTURE.md for design, limitations and the test plan.

Status: Phase 1 written but not yet compiled or run. See ARCHITECTURE.md.
