# Prior art: can you already drive herdr from a phone?

Yes. This was checked before writing any code, and the answer changed the design.

## What herdrm is

[missuo/herdrm](https://github.com/missuo/herdrm) is a SwiftUI macOS console for
[herdr](https://herdr.dev). herdr is the runtime: a background server, written in Rust, that
owns every coding agent's PTY, persists spaces and workspaces, and answers on a Unix socket.
herdrm is a window onto it, not a reimplementation of it. The herdr team has said publicly
that herdr "isn't an app, by design", and points people at the socket API to build their own
clients.

So a mobile equivalent of herdrm is a mobile client for herdr. The agents keep running on
your machines; the phone is a control surface.

## What already exists

| Project | Platform | Needs on the host | Notes |
|---|---|---|---|
| [deex2/herdroid](https://github.com/deex2/herdroid) | Kotlin, Compose, Hilt, Room/SQLCipher, SSHJ | Rust stdio bridge | Closest to this project's stack. 2 stars, 18 commits, prereleases only |
| [mohamed-essam/herdr-mobile](https://github.com/mohamed-essam/herdr-mobile) | Kotlin + Compose, Go daemon | `herdr-mobiled` | WebSocket over Tailscale, UnifiedPush, Termux VT. The v1 API has no authentication and can send input to your terminals |
| [tuan3w/herdr-mobile](https://github.com/tuan3w/herdr-mobile) | Flutter | python3, falling back to socat | SSH only, no relay. Terminal is read-only, 1000-row scrollback |
| [benkraus/herdr-mobile](https://github.com/benkraus/herdr-mobile) | React Native / Expo | Herdr Mobile Relay | HTTPS REST + WebSocket over a tailnet |
| [cobanov/herdrchat](https://github.com/cobanov/herdrchat) | Expo RN | — | Chat metaphor over SSH. Android is experimental |
| [AltanS/collie](https://github.com/AltanS/collie) | PWA | bridge | Self-hosted, MIT, also does tmux and zellij |
| [michaelhelvey/herdr-mobile](https://github.com/michaelhelvey/herdr-mobile) | PWA | bridge | Home-screen install, no app store |
| [devalexsegault/herdr-mobile-app](https://github.com/devalexsegault/herdr-mobile-app) | Mobile web | relay | Merges several computers, push notifications |
| [herdrup](https://apps.apple.com/us/app/herdrup-agents-in-your-pocket/id6798087089), [Heeler](https://github.com/ZingerLittleBee/Heeler) | iOS | — | iOS only |
| Termius, JuiceSSH, Termux | any | — | Works today. herdr's TUI reflows for narrow terminals |

If the goal is only to unblock an agent from a train, **an SSH client already does it**. That
is the honest baseline, and it is worth trying before installing anything else.

## The gap this project fills

Two native Kotlin/Compose clients already exist. Both make you deploy and version a
companion binary on every machine: a Rust bridge for herdroid, a Go daemon for
mohamed-essam's. Nobody has a native Android client that talks to **stock herdr with nothing
extra installed**.

That constraint is the reason to build, and it drives the whole transport design:

- herdr listens on a Unix domain socket with no auth token and no TCP listener, so SSH is
  the only way in from a phone.
- SSHJ does not implement `direct-streamlocal@openssh.com`. Checking the library's
  `connection/channel/direct` package turns up `DirectTCPIPChannel` and `LocalPortForwarder`
  and no streamlocal class at all, so `ssh -L` style Unix socket forwarding is out.
- What remains is an SSH **exec channel** running a pipe between stdio and the socket, built
  from whatever is already on the box: `python3`, `socat`, or `nc -U`.

See [protocol.md](protocol.md) for the socket details and
[architecture.md](architecture.md) for how the relay is probed and cached.

## What is out of reach

herdrm attaches to a real PTY. That rides a **separate binary protocol** reserved for
same-install clients and internal operations such as live handoff; it is not part of the
JSON socket API. No SSH-only client can reach it, which is why tuan3w's terminal is
read-only and why the other projects ship a bridge.

Via the JSON API a usable terminal is still possible: read the composed screen with
`pane.read`, follow `pane_output_changed`, and write back with `pane.send_text`,
`pane.send_keys`, or `agent.prompt`. Enough to answer a blocked agent. Not a full TUI, and
this project does not pretend otherwise.

Push notifications are the other casualty. With nothing running on the host, there is
nothing to wake the phone when an agent blocks. That is the price of the bridge-free
constraint, and it is tracked as a phase 2 decision in [plan.md](../plan.md).
