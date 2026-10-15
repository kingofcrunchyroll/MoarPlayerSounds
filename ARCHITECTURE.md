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
1. **Local replacement + mob presets** — implemented (this repo), uncompiled.
2. Per-slot volume/pitch, imported `.ogg` + validator.
3. Remote attribution (section 3), still local profiles for test.
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
