# PocketHerd plan

A native Android client for [herdr](https://herdr.dev): every coding agent on every machine
in one list, blocked ones first, close enough to a terminal to unblock them from a phone.

The constraint that shapes everything: **nothing gets installed on your machines**. No Rust
bridge, no Go daemon, no relay service. Stock herdr, an SSH key, and whatever interpreter
the box already has.

Prior art and why this is still worth building: [docs/research.md](docs/research.md).
Protocol facts: [docs/protocol.md](docs/protocol.md). Module layout and the reasoning behind
it: [docs/architecture.md](docs/architecture.md).

## Status

Phases 0 and 1 are done. The app builds, tests pass, and the full transport is
proven against a live herdr 0.9.0.

```
./gradlew build        # 46 tests, no warnings
./gradlew :app:installDebug
```

| | |
|---|---|
| Modules | 11, layer-separated |
| Tests | 46, all pure JVM |
| Protocol | 22, verified against herdr 0.9.0 |
| Min SDK | 29 (Android 10) |
| Kotlin / AGP / Gradle | 2.3.21 / 9.4.1 / 9.8.1 |

## Phase 0 — prove the transport (done)

Everything here was run against a live socket before any Kotlin was written, because the
whole project is void if the transport does not work.

- [x] herdr listens on `~/.config/herdr/herdr.sock`, protocol 22, no auth token
- [x] SSHJ has no `direct-streamlocal@openssh.com`, so `ssh -L` to a Unix socket is out
- [x] An SSH exec channel running `python3` / `socat` / `nc -U` reaches the socket, verified
      through `sh -c` exactly as sshd invokes it
- [x] herdr answers **one request per connection**, measured, not assumed
- [x] A combined subscription of 15 filters on one connection returns `subscription_started`
- [x] `pane.read(source="visible", format="ansi")` returns truecolor SGR plus a `revision`

## Phase 1 — the four things that make it useful

- [x] Agent list across machines, sorted blocked → done → working → idle
- [x] Live updates from `events.subscribe`, including resubscribe when a pane appears
- [x] Pane view: ANSI-rendered screen, re-read on `pane_output_changed`
- [x] Send input: reply box, plus Enter / Esc / Ctrl-C chips
- [x] Multi-machine: one list, one session per machine, independent reconnect
- [x] **Window titles as the headline.** Agents set the terminal title to the task they are
      on ("GitHub token retirement"). With nineteen panes all running `claude`, that title
      is the only thing that distinguishes them, so it outranks the agent name
- [x] **Group by space**, with spaces ordered by their most urgent agent so grouping does
      not bury a blocked agent in an alphabetically-last space. Toggleable back to the flat
      urgency list
- [x] **SSH jump hosts (ProxyJump).** A machine can be reached *through* another. The hop
      carries the TCP connection only and needs no herdr on it. Added because a corporate
      tailnet ACL left the phone able to reach one box directly and another only via it
- [x] Keystore SSH identity, non-exportable, with a copyable public key
- [x] Host key verification with explicit first-use approval
- [x] **Host key approval UI.** A dialog shows the fingerprint with the `ssh-keygen` command
      to check it against. Approving stores it and wakes the parked session
- [x] **Connection state in the UI.** Machines blocked on a changed host key or a failed
      connect show an error card; an empty list now distinguishes "no machines" from
      "connected, nothing running"
- [x] **The SSH path end to end.** Verified against this Mac over Tailscale: `ping` returned
      protocol 22, `session.snapshot` returned 8 panes and 2 workspaces, a 5-filter
      `events.subscribe` returned `subscription_started`, and the `nc -U` fallback also
      worked. See "Exec channel PATH" below for the one surprise

Phase 1 is complete and **verified end to end from a real phone**: a Pixel on wifi lists
the Mac's 4 live agents, over SSH, with no bridge installed on the host.

```
macbook: relay=python3, hops=0
macbook: snapshot 8 panes (4 agents), 2 workspaces
desktop: relay=python3, hops=1
desktop: snapshot 26 panes (15 agents), 7 workspaces
```

19 agents across 9 spaces and two machines, one of them reached by tunnelling through the
other.

### Four things broke between "TCP connects" and "agents appear"

All found on a real Pixel against a real Mac. None were visible from unit tests, and each
one masqueraded as a network fault.

1. **`ACCESS_LOCAL_NETWORK`.** Android 16 gates local-subnet addresses behind a runtime
   permission. Without it, connections fail as plain socket timeouts; the only evidence is
   an `ACCESS_LOCAL_NETWORK ... rejectTime` entry in `adb shell appops get <pkg>`. Reaching
   dev boxes on the LAN is the whole app, so this is now declared and requested.

2. **Android's stripped `BC` provider.** Android registers a cut-down provider named `BC`
   with no X25519. SSHJ asks for `BC` by name and modern OpenSSH negotiates
   `curve25519-sha256`, so key exchange dies *after* TCP succeeds. Fixed by replacing that
   provider with the full BouncyCastle in the APK.

3. **BouncyCastle cannot sign with a Keystore key.** Having fixed (2), SSHJ then routed the
   auth signature to BouncyCastle, which wants private key bytes an Android Keystore key
   will never give: `InvalidKeyException: no encoding for EC private key`. Fixed by
   swapping the `ecdsa-sha2-nistp256` `KeyAlgorithm` for one whose signer names the
   `AndroidKeyStore` provider explicitly. See `KeystoreEcdsaSignature`.

4. **`Pong.capabilities` typed as `Map<String, Boolean>`.** herdr 0.9.0 sends
   `endpoint_protocol_generation` as a *number* among booleans, so every ping threw. The
   relay probe treats any failure as "this relay does not work", so the symptom was
   "No usable relay" on a host whose relay was fine. Now raw `JsonObject`, with a test
   built from the real payload.

A fifth, caught by the same session: `Snapshot` was declared `@Serializable` in `:data`,
which does not apply the kotlinx-serialization compiler plugin. It compiled and then failed
at runtime with "Serializer for class 'Snapshot' is not found". It lives in
`:core:protocol` now, next to the other protocol types.

### Android VPNs capture app traffic, and `adb shell` does not

A Pixel on wifi with Tailscale active could not reach either machine from the app, while
`adb shell` reached them fine. Android exempts the shell uid from VPN routing but not apps,
so the two disagree:

| Target | app uid (`run-as`) | shell uid |
|---|---|---|
| `10.0.1.20` (LAN) | connection abort | `SSH-2.0-OpenSSH_10.3` |
| `100.x.y.z` (tailnet) | timeout | timeout |

Tailscale for Android drops local-subnet traffic unless **Allow LAN access** is enabled.
Testing with `adb shell nc` alone is misleading; use
`adb shell run-as <pkg> nc -w 5 <host> 22` to test from the app's own uid.

Separately, this Mac's tailnet address is unreachable from the phone even inside the tunnel,
while the Mac can `tailscale ping` the phone. ShieldsUp is false and both nodes are online,
so this looks like a tailnet ACL rather than anything the app controls.

### Live terminal is possible, by attaching rather than reading

The pane view polls `pane.read` every 2s because herdr's JSON API has no change
notification: `pane.output_changed` has no subscription filter, `pane.output_matched`
fires once at subscribe time, `pane.wait_for_output` long-polls but only until a pattern
matches and a catch-all matches the current screen, and `pane.read` reports revision 0.

herdrm does not use that API for its terminal at all. It spawns `herdr agent attach
<paneId> --takeover` as a subprocess and renders the resulting PTY with libghostty.
`pane.read` appears twice in the whole app, in neither case the terminal view.

The same is open to this app: `ssh host 'herdr agent attach <paneId> --takeover'` with a
PTY allocated on the channel. herdr is already on the host, so it needs nothing installed,
and the binary protocol is herdr's problem rather than the client's.

What it costs:

- **A terminal emulator.** A PTY carries cursor addressing, scroll regions and the alt
  screen. `AnsiParser` handles SGR only, on purpose, because `pane.read(source=visible)`
  returns a screen herdr has already composed. Raw PTY bytes through it would be confetti.
- **`--takeover` steals the attach** from whoever else is looking at that pane.
- **PTY sizing** has to track the view, and resize on fold.

Worth doing if the pane view should be a terminal rather than a window onto one. The
design brief explicitly asks for the opposite today: "don't add anything that looks
character-by-character live".

### Jump hosts

`SshConnection.openTransport` is deliberately separate from `connect`: a jump host is a
transport and must not be relay-probed, because it may not run herdr at all. Hops form a
linked `SshTransport` chain so closing the far end tears down everything behind it, rather
than leaking an SSH session on the intermediate box.

Each hop's host key is verified against that hop's own stored fingerprint. Tunnelling does
not make the far end's identity somebody else's problem.

`openTransport` carries a `visited` set because two machines each set as the other's jump
host is easy to configure by accident and would otherwise recurse until the stack blew.

### herdr does not need to be on PATH

The relay talks to `~/.config/herdr/herdr.sock` using python3/socat/nc. The `herdr` binary
itself is never invoked, so `herdr: command not found` over SSH says nothing about whether
this app can reach that machine. What matters is whether the socket exists. On macOS herdr
installs to `~/.local/bin`, which a non-interactive SSH session does not have on PATH.

### Exec channel PATH

In a non-interactive SSH exec channel on macOS, `python3` resolves to `/usr/bin/python3`
(Command Line Tools), **not** Homebrew's `/opt/homebrew/bin/python3`. The relay works either
way, so the ladder needs no change, but any future relay that depends on a Homebrew-only
tool will not find it. `socat` is absent on this Mac; `/usr/bin/nc` is the BSD build and
supports `-U`.

## Phase 2 — decisions, not yet commitments

### Notifications

The bridge-free constraint means nothing on the host can wake the phone. The options, worst
to best:

1. **Foreground service** holding the subscription open. Works today with no host changes.
   Costs battery and shows a permanent notification. Android will still doze the socket.
2. **WorkManager poll** every 15 minutes. Cheap, and 15 minutes late is useless for a
   blocked agent.
3. **A small daemon on the host.** Real push, real battery life, and exactly the thing this
   project set out not to require.

Nothing here is obviously right. Live with phase 1 first and find out whether the absence
actually hurts.

### Other candidates

- **Multiplexing relay** if per-request latency on cellular is bad. Measure before building.
- **Workspace and tab grouping** once the flat list gets long enough to be annoying.
- **`agent.prompt` with `wait`** instead of `pane.send_text`, for a proper request/response
  turn rather than typing into a screen.
- **File browsing over SFTP.** herdrm has it; SSHJ already has the transport.
- **Password auth.** Modelled in `AuthMethod`, currently throws.

## Deliberate omissions

Each of these was considered and skipped. Marked `ponytail:` in code where it matters.

| Skipped | Why | Add when |
|---|---|---|
| DTO layer with mappers | One API, one UI, one shape | Protocol and UI shapes diverge |
| Terminal emulator library | `pane.read` returns a composed screen; SGR is all that is left | A full-screen TUI needs cursor addressing |
| SQLCipher | The table holds hostnames and usernames, no secrets | An actual secret gets stored |
| Gradle convention plugins | 11 modules, config fits in each file | Changing a version means editing 11 files |
| Channel pooling | Writes are rare; events carry the traffic | Latency is measured and bad |
| PTY attach | Needs herdr's same-install binary protocol | Never, over SSH |

## Open questions

These are the ones a design review should push on.

1. **Is bridge-free worth losing push?** It is the entire differentiator against herdroid
   and mohamed-essam's client, and it costs the one feature that makes a phone client
   genuinely useful: knowing an agent blocked without opening the app.
2. **Does resubscribe thrash?** A machine churning panes reopens the subscription channel on
   every new one. Debouncing is obvious in hindsight and absent today.
3. **Is one SSH connection per machine right?** Many machines means many persistent TCP
   connections on a phone that sleeps. Untested beyond two.
4. **Does the reconnect loop drain the battery?** 1s doubling to 30s, forever, with no
   network-availability awareness. It should probably listen to connectivity callbacks
   instead of guessing.
5. **Who is this for?** If the answer is one person with three dev boxes, phases 2 and 3 are
   mostly imaginary.
