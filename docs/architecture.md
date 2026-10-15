# Architecture

MVVM, Hilt for DI, Compose for UI, modularised by layer.

## Modules

```
:app                  Application, MainActivity, NavHost. Wiring only.

:feature:agents       Dashboard: every agent on every machine, blocked first
:feature:pane         One pane: rendered screen, reply box, key chips
:feature:machines     Add and remove machines, show the device public key

:domain               Repository interfaces, ObserveAllAgentsUseCase. Pure Kotlin
:data                 Repository implementations, HerdrClient, MachineSession, SessionState

:core:protocol        Envelope, codec, subscription filters, event types. Pure Kotlin
:core:model           Domain types. Pure Kotlin
:core:ssh             SSHJ transport, relay ladder, Keystore identity, host key verification
:core:database        Room: saved machines, remembered relay, trusted host keys
:core:ui              Theme, status components, ANSI renderer
```

Dependency direction is one-way. `:feature:*` sees `:domain`, `:core:model` and `:core:ui`
and never sees `:data`, `:core:ssh` or `:core:protocol`. A ViewModel cannot reach SSHJ even
by accident.

`:core:model`, `:core:protocol` and `:domain` are plain `kotlin-jvm` modules with no Android
dependency, so their tests run on the JVM with no emulator and no Robolectric.

## Flow of data

```
Compose screen
  ↕ StateFlow
ViewModel                         (:feature:*)
  ↕ Flow
ObserveAllAgentsUseCase           (:domain)   merge machines, sort by attention
  ↕
HerdrRepository                   (:domain interface, :data impl)
  ↕
MachineSession                    (:data)     snapshot + event reducer + reconnect
  ↕
HerdrClient                       (:data)     NDJSON over an exec channel
  ↕
SshConnection                     (:core:ssh) SSHJ, relay probe
  ↕ ssh exec: python3 | socat | nc -U
herdr.sock on the host
```

## Decisions and their costs

### Domain types are the wire format

`:core:model` types are `@Serializable` and decode herdr's JSON directly. There is no DTO
layer and no mappers.

The app has exactly one data source. A mapping layer between one API and one UI is ceremony
that doubles the type count and buys nothing until the two shapes diverge. If protocol 23
moves away from what the UI wants, add mappers in `:data` at that point.

Cost: a protocol change touches domain types. Mitigated by `ignoreUnknownKeys`, so additive
changes are free.

### One SSH exec channel per request

herdr answers one request per connection ([protocol.md](protocol.md)), so each RPC opens its
own exec channel. A subscription holds one open for its lifetime.

Channel setup is cheap once the SSH transport is up, and the event stream carries nearly all
the traffic; writes are user-initiated and rare. The alternative, a multiplexing relay
script that frames requests over one channel, is more code on a host where the whole point
is to install nothing.

Cost: per-request latency on a slow link. Marked `ponytail:` in `SshConnection`. The upgrade
path is the multiplexing relay, once latency is measured rather than assumed.

### Relay ladder, probed once and remembered

`python3`, then `socat`, then `nc -U`. python3 leads because it is on effectively every
Linux and macOS dev box and its behaviour does not vary; `nc -U` is last because GNU
netcat's traditional build lacks `-U`.

The probe doubles as a health check. A candidate only wins if `ping` returns a parseable
pong, which proves the relay works, the socket path is right, and herdr is running. The
winner is written to the database and tried first next time.

### Resubscribe instead of per-pane connections

`pane.agent_status_changed` has no wildcard, so the subscription carries one filter per
known pane. When `pane_created` announces a pane outside that set, `MachineSession.follow`
throws `ResubscribeSignal`, caught in its own loop, and reopens **only the subscription
channel** on the live SSH connection. No reconnect, no new snapshot, no backoff.

An earlier version let that exception reach the outer reconnect loop, which turned every new
pane into a failed-connection state and a backoff delay.

### Sessions are application-scoped

`HerdrRepositoryImpl` holds one `MachineSession` per machine in a `@Singleton`, not in a
ViewModel. Rebuilding a session means an SSH handshake, a relay probe and a full snapshot.
Tying that to a ViewModel would redo all of it on every rotation and every back-navigation
from the terminal.

ViewModels use `SharingStarted.WhileSubscribed(5_000)` so a rotation does not even pause
collection.

### SGR parsing, not a terminal emulator

`pane.read(source = "visible")` returns a screen herdr has already composed. There is no
cursor to track, no scroll region, no alt screen. `AnsiParser` handles SGR colour and text
attributes and drops every other escape sequence.

Cost: no cursor addressing, so a full-screen TUI will not render faithfully. Marked
`ponytail:`. The upgrade is a real terminal emulator library, not a bigger parser.

### Security is not where the laziness goes

- The SSH private key is generated in the **Android Keystore**, marked non-exportable, and
  never leaves the device. SSHJ only asks it to sign. ECDSA P-256 rather than Ed25519
  because Keystore Ed25519 starts at API 33 and this targets API 29.
- The key deliberately does **not** set `setUserAuthenticationRequired`. The reconnect loop
  runs with the screen off, and a key needing a fingerprint per use would make background
  reconnects impossible.
- Unknown host keys are a hard stop. `StoredHostKeyVerifier` throws
  `UnknownHostKeyException` carrying the fingerprint so the UI can ask; a key that changes
  later throws `HostKeyChangedException` and does not re-prompt. Accepting any host key
  would hand agent input to whoever answers on port 22.
- Room is plain, not SQLCipher. The table holds hostnames and usernames, the same things
  already in `~/.ssh/config`. No secret is stored, so there is nothing to encrypt.
- Status is never signalled by colour alone; every dot is paired with a text label.

## Testing

Logic that can break without a network lives in pure functions and is tested there.

- `SessionStateTest` (9 tests) drives the event reducer with wire payloads copied from the
  real schema, covering add, remove, in-place status change, revision bumps, cascading
  workspace close, unknown event types, and the resubscribe trigger.
- `AnsiParserTest` (10 tests) includes a line captured verbatim from a live `pane.read`,
  plus truncated escapes, OSC with both terminators, and the xterm-256 cube boundaries.

No emulator, no Robolectric, no mocking framework. `MachineSession` takes its connector as a
function parameter, so a fake is a lambda.

## Known gaps

- No push notifications. Nothing runs on the host to send them. See [plan.md](../plan.md).
- Password auth is modelled but throws; only Keystore keys are wired up.
- The debug APK is around 72 MB, mostly SSHJ and Bouncy Castle. Release builds enable
  minification; this has not been measured yet.
- No instrumented or screenshot tests.
