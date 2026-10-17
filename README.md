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
`/playersound preset <mob>`, `slot <slot> <mob|vanilla>`, `slot <slot> custom <clip>`, `volume|pitch|jitter <slot> <percent>`, `import <name> <path>`, `audio list|remove`, `gui`, `remote enable|disable|set <player> <mob>|clear <player>|list|status|debug <bool>|reset`, `preview <slot>`, `status`, `list`, `reset`, `enable|disable`.

Imported audio must be **mono Ogg Vorbis**, 8-48 kHz, up to 5 s and 256 KiB. Convert with e.g. `ffmpeg -i in.wav -ac 1 -ar 22050 -c:a libvorbis out.ogg`. Config: `config/moarplayersounds.json`.

See ARCHITECTURE.md for design, limitations and the test plan.

Status: Phase 1 tested in-game. Phase 2 (per-slot volume/pitch/jitter, imported .ogg) written, awaiting first build. See ARCHITECTURE.md.


## GUI
Open it from Mod Menu (Mods list -> MoarPlayerSounds -> config button) or with `/playersound gui`. Per slot: source picker, volume / pitch / randomness sliders, preview. `Clips...` manages imported audio; drag a mono `.ogg` onto the game window while it is open to import.
