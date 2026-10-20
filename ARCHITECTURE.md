# MoarPlayerSounds — feasibility & architecture

Status legend: **[verified]** checked against Yarn 1.21.1 mappings or a live source; **[to verify]** expected from vanilla behaviour, must be confirmed in the dev environment (`genSources`) before the phase that depends on it.

## 1. What vanilla actually does

**Real player sound events [to verify against sounds.json]:** `entity.player.hurt`, `hurt_drown`, `hurt_on_fire`, `hurt_freeze`, `hurt_sweet_berry_bush`, `death`, `small_fall`, `big_fall`, `splash`, `splash.high_speed`, `swim`, `burp`, `levelup`, `attack.*`, plus block step/fall sounds and eat/drink sounds. Yarn does not name `SoundEvents` fields in 1.21.1, so the mod never hardcodes them: it reads ids from the live registry.

**Not real player sounds:** ambient/idle and jump. Players have neither. PlayerSound synthesises them (idle timer; ground->air transition with upward velocity). They are labelled `existsInVanilla = false` in `SoundSlot`.

**Verified hook points (Yarn 1.21.1):**
- `LivingEntity#getHurtSound(DamageSource)`, `#getDeathSound()`, `#getFallSound(int)`, `#getFallSounds()`; `Entity#getSwimSound()`, `#getSplashSound()`, `#getHighSpeedSplashSound()`, `#playStepSound(BlockPos,BlockState)`, `#playSound(SoundEvent,float,float)`.
- Client choke point: `ClientWorld#playSound(double,double,double,SoundEvent,SoundCategory,float,float,boolean,long)`.
- Packet handlers: `ClientPlayPacketListener#onPlaySound(PlaySoundS2CPacket)` (position + sound + seed, **no entity**) and `#onPlaySoundFromEntity(...)` (entity-attributed).
- `World#playSound(PlayerEntity except, ...)` — the "except" player is how the server avoids echoing a player's own sounds to them.

**[to verify, drives Phase 3]:** the local player's hurt/death/fall/swim sounds are generated client-side (server excludes the owner), so a getter hook on the local `PlayerEntity` is enough and cannot double. Remote players' sounds reach us as server sound packets at the player's position, not as getter calls on our client, so entity-getter mixins do *not* reach them.

## 2. Local replacement (Phase 1, implemented)

Replace at the **selection** point, not cancel-and-replay: the mixin swaps the `SoundEvent` a getter returns. Position, attenuation, category, volume sliders and the vanilla seed all stay vanilla's. Hooks only select; they never play, so no event loops. Any exception returns the vanilla event. Mob presets come from `entity.<mob>.<event>` ids in the registry, random pick among variants, fallback chain (hurt->ambient, death->hurt, swim<->splash); if nothing fits the vanilla player sound is kept — never silence.

## 3. Remote players: profile sync, not event relay (deliberate design change)

The brief asks for sound-event synchronisation. Relaying events is the wrong tool here:
- Listeners already receive a vanilla stimulus for every real event (the sound packet), so they can decide *locally* which player made it.
- Event relay adds latency, duplicate/echo handling, spoofing and spam surface, and needs a realtime channel.

So the design syncs only a small, cacheable **profile** per player UUID, and listeners derive events locally:
1. Intercept at `ClientWorld#playSound` / `onPlaySoundFromEntity`.
2. If the sound id is in the whitelisted player set **and** exactly one remote player with a known profile is within ~1.5 blocks of the packet position (server plays these at the entity's position) -> swap to the mapped sound, same position/volume/pitch.
3. Ambiguous (two players overlapping), unknown profile, fetch failed, or no mapping -> play vanilla untouched. This is the spec's "suppress vanilla only when reliable".
4. JUMP and AMBIENT for remote players are synthesised locally from the observed player's movement/timer, same as for the local player.
5. Block step sounds are indistinguishable from other entities' steps; best-effort, off by default.

Limitation: a profile only affects sounds the listener actually receives. Sounds outside the server's tracking/hearing range are not heard in vanilla either.

## 4. Networking: what is and isn't possible

- **Through the Minecraft server: no.** A vanilla server drops unknown client->server payloads and never relays them. Chat/sign/skin smuggling is fragile and abusive. Rejected.
- **True P2P: not realistically.** Peers need discovery (a rendezvous server) and NAT traversal (STUN/TURN or WebRTC); many networks block it, and it exposes every listener's IP address to every other player. Rejected as the primary path.
- **What Cosmetica-style mods do** [verified from its Modrinth page / Essential Patcher's docs]: client-only mod + a central service keyed by Minecraft UUID, with a websocket for near-instant updates. Essential Patcher proves ownership via Mojang's `hasJoined` and issues a short-lived JWT holding only the UUID.

**Chosen architecture — an open-source, self-hostable "hub":** a small HTTP service that is only a *mailbox*, not a relay. Clients upload their own profile and custom audio; others download by UUID. The hub URL is configurable (any number of hubs), and the mod works fully offline without one (local features + vanilla for others).

Hub API (protocol v1):
- `POST /v1/auth/challenge` -> random `serverId`. Client calls Mojang `joinServer` with it. `POST /v1/auth/verify {username, serverId}` -> hub calls `hasJoined` -> JWT(uuid, 1h). Access tokens never leave the client.
- `PUT /v1/profile` (JWT, <= 8 KiB JSON). `GET /v1/profiles?uuids=...` (<= 32, ETag/304).
- `PUT /v1/blob/<sha256>` (JWT, <= 256 KiB, <= 8 blobs and 2 MiB per user). `GET /v1/blob/<sha256>`.
- Optional `GET /v1/live` websocket for push; polling with ETag (>= 60 s) is the baseline.

Profile wire format: `{protocol:1, slots:{hurt:{src:"mob:enderman"|"blob:<sha256>", vol:0..1, pitch:0..0.5}, ...}}`.

Client-side trust rules (never trust hub or peers):
- `src` must match `mob:[a-z0-9_]{1,40}` **and** exist in the local `MobSoundIndex`, or `blob:[0-9a-f]{64}`; anything else -> vanilla. No paths, ever.
- Blobs are re-validated locally: Ogg/Vorbis header parse, **mono** (stereo does not attenuate positionally in Minecraft), 8–48 kHz, <= 5 s, <= 256 KiB, SHA-256 must equal the id. Stored as `<sha256>.ogg` in a cache dir; the filename is derived from the hash only.
- Per-slot rate limits and cooldowns on the listener; fetch queue with timeouts, exponential backoff, negative caching; protocol-version mismatch -> treat that player as vanilla.

Privacy: only the hub sees IPs; no player-to-player exposure; deletion by UUID; profile contains no gameplay data.

**Biggest technical risk (Phase 2/5):** playing imported audio. Vanilla only plays sounds registered through the resource system, so custom `.ogg` needs a runtime sound entry plus a stream source (a mixin on the sound loader/resource lookup or an injected in-memory resource pack). Needs a spike in the dev environment before committing.

## 5. Phases
1. **Local replacement + mob presets** — done, tested in-game.
2. **Per-slot volume/pitch/jitter, imported `.ogg` + validator** — written, awaiting first build/test (see Phase 2 below).
3. **Remote attribution (section 3), with hand-assigned profiles for testing** — written, off by default, awaiting a two-player test (see section 8).
4. Hub client + reference hub; profile sync.
5. Blob transfer, cache, failure recovery.
6. Config screen, polish, test plan execution.

## 6. Phase 1 test plan
1. `./gradlew runClient`, new creative world. `/playersound preset enderman`; take damage -> enderman hurt; `/kill` -> death sound.
2. `/playersound slot hurt zombie`; `/playersound slot death skeleton`; confirm independent mixing.
3. Jump repeatedly: JUMP only plays for slots mapped to a mob with a `jump` sound (e.g. slime); no sound otherwise. Idle ~5–20 s: AMBIENT plays.
4. Swim and enter water from height: SWIM/SPLASH replaced; fall >3 blocks: FALL only if the mob has a fall sound, else vanilla.
5. `/playersound disable` -> all vanilla. Delete config -> defaults. Corrupt config JSON -> game starts, warning logged.
6. Singleplayer and a vanilla server: confirm no doubled sound; other entities' sounds unchanged.

## 7. Phase 2 design (per-slot tuning + imported audio)

**Volume / pitch / jitter.** The getter hooks can only choose a `SoundEvent`; volume and pitch are decided at the play call. So the hook leaves a short-lived token (`PendingSounds`: sound id, 250 ms TTL, must be within 3 blocks of the local player) and a narrow `ClientWorld#playSound(DDDSoundEvent..J)` injection applies the slot's settings to *that* sound only, then re-issues it. The re-issued call finds no token, so no loop; a mob's identical sound elsewhere is never touched. Note vanilla clamps final gain to 1.0, so volume above 100% only extends audible range.

**Imported audio.** A clip gets the runtime id `moarplayersounds:custom/<sha256>` and plays through the *normal* vanilla pipeline (positioning, attenuation, category, sliders):
1. `AudioLibrary` stores validated files as `config/moarplayersounds/audio/<sha256>.ogg` plus `library.json` (name -> hash). Limits: 256 KiB/file, 5 s, mono Ogg Vorbis 8–48 kHz, 32 clips, 4 MiB total.
2. `OggValidator` (pure Java, no decoding) checks the capture pattern, page structure and CRC of every page, one logical stream, Vorbis identification header, mono, rate, duration from the final granule position. Tested against real files: valid mono accepted; stereo, Opus, truncated, bit-flipped, oversized, too long, chained and garbage rejected.
3. `CustomSounds.eventFor(hash)` creates the `SoundEvent` and (re-)inserts a `WeightedSoundSet` into `SoundManager.sounds` via an accessor mixin, on every use, because a resource reload (F3+T) rebuilds that map.
4. `SoundLoaderMixin` intercepts `SoundLoader#loadStatic(Identifier)` for our namespace only, decoding the library file with the game's own `OggAudioStream` (iteration and size capped), cached per loader and cleared on `close()`.
5. Any failure anywhere returns `null` -> the slot plays vanilla and one warning is logged.

Filenames are only ever derived from a 64-hex hash, so no path can reach the file API from config or (later) from the network.

**Unverified until your build/run:** `Sound`/`WeightedSoundSet` constructor use, `RegistrationType.getByName("file")`, `AudioStream.read(int)` end-of-stream behaviour, and that `SoundManager#sounds` is a mutable map. If a custom clip is silent, the log will say which step failed.

### Phase 2 test plan
1. Build; start; confirm log line `Phase 2 ... initialised`. Existing `moarplayersounds.json` from Phase 1 must still load (plain-string slots are migrated).
2. `/playersound volume hurt 30`, take damage: quieter. `pitch hurt 70`: deeper. `jitter hurt 30`: varied. `status` shows them. A *zombie* hurt next to you stays unchanged.
3. Import `test-assets/tone_440hz_mono.ogg`: `/playersound import beep C:\path\to\tone_440hz_mono.ogg`, then `/playersound slot hurt custom beep`, `preview hurt`, take damage.
4. Reject checks: import a stereo file, a >5 s file, a renamed `.mp3`, a huge file, a missing path -> each gives a clear error, no crash.
5. F3+T (reload resources), take damage again: custom clip still plays.
6. `/playersound audio remove beep`: slot falls back to vanilla without errors. Restart the game: clips and slot assignments persist.
7. Walk away from a custom-sound source: it is positional and attenuates; stand underwater/in a cave to confirm normal mixing.

## 8. Phase 3 (remote players) — written, off by default

Scope: make *you* hear another player with the sound profile **you assigned to them**. There is no network sync yet; Phase 4 fills the same `RemoteProfiles` table from synced profiles, so the detection code written here is what Phase 4 uses.

- `RemoteProfiles` — UUID -> slot settings, stored in `config/moarplayersounds/remote.json`; a master switch (`/playersound remote enable|disable`), default OFF.
- `RemoteSounds` — takes a sound that is about to play and decides if another player made it. Only ids in a fixed whitelist of vanilla player sounds are considered (`entity.player.hurt*`, `death`, `small_fall`, `big_fall`, `swim`, `splash`, `splash.high_speed`); everything else returns immediately.
  - *Position path* (`ClientWorld#playSound`, 9-arg): the nearest player within 3 blocks horizontally / 4 vertically of the sound, accepted only if the next-nearest is at least 1 block farther away; otherwise (or if the nearest is the local player) vanilla. (First version required exactly one player within 1.5 blocks, which failed in melee fights.)
  - *Entity path* (`ClientWorld#playSoundFromEntity`): maker known exactly.
  - Replacement keeps position, category and seed, and applies the assigned volume/pitch/jitter. Replacement ids are not in the whitelist, so there is no loop.
- `RemoteSyntheticDriver` — AMBIENT and JUMP for players within 24 blocks, produced by the listener from a timer and observed movement. Jump detection for other players is experimental.
- Everything falls back to vanilla on any exception (one logged warning).

**Open questions the first real test answers** (use `/playersound remote debug true`):
1. Do other players' hurt/death/fall/swim sounds arrive on the position path, the entity path, or both? The log prints which, and how many candidate players matched.
2. Is the 1.5/3.0 block tolerance enough while the other player is running or falling?
3. Does remote jump detection fire at a sensible rate?

### Phase 3 test plan (needs a second player on the same server, or a second client on LAN)
1. Both join the same server. You: `/playersound remote debug true`, `/playersound remote enable`, `/playersound remote set <friend> enderman`.
2. Friend takes damage near you (fall, hit): you should hear enderman hurt. Friend `/kill`: enderman death. Friend swims / lands from a height: swim, splash, fall (fall only if the mob has a fall sound; otherwise vanilla).
3. Stand still near the friend for ~20 s: ambient plays. Friend jumps repeatedly: jump sound if the mob has one (e.g. `slime`).
4. Ambiguity: both players stand on the same block and the friend takes damage: expect vanilla (log shows 2 candidates).
5. A third player or a mob next to the friend: their sounds stay vanilla; a zombie's hurt sound is unchanged.
6. `/playersound remote disable`: everything vanilla again. `/playersound remote clear <friend>`: same for that player.
7. Send me the `[remote]` lines from `latest.log` from steps 2–4 so the tolerance and path questions can be settled.

### Findings from the first two-player test (LAN, host + one friend)
- Other players' hurt, death, fall and swim sounds arrive on the **position path**; no entity-attributed packets were seen. The entity path stays as a harmless fallback.
- The old "exactly one player within 1.5 blocks" rule marked roughly half of fight sounds ambiguous (attacker and victim close together) and a few as zero candidates (the other player's client position lagging the server's). Replaced by nearest-with-margin and wider tolerances; debug lines now print distances.
- The local player's own `big_fall`/`small_fall` also arrive as position packets, so with a FALL replacement set locally a vanilla fall sound may play in addition to the replaced one. To be checked by ear (see test plan).
- Phase 3 only changes what *you* hear; it sends nothing. Your friend hears your sounds only if they assign a profile to you on their own client (`/playersound remote set <your name> <mob>`) until Phase 4 syncs profiles.
