# PocketHerd

A native Android client for [herdr](https://herdr.dev). Every coding agent on every machine
you own, in one list, blocked ones first, with enough of a terminal to answer them from a
phone.

Nothing gets installed on your machines. No bridge, no daemon, no relay. PocketHerd reaches
herdr's Unix socket over an ordinary SSH exec channel using `python3`, `socat`, or `nc -U`,
whichever the host already has.

## Before you install this

A plain SSH client already works. herdr's TUI reflows for narrow terminals, so Termius or
JuiceSSH will get you to a blocked agent today. Several other mobile clients exist too, some
more finished than this one. [docs/research.md](docs/research.md) lays them out honestly.

Build this one if you want a list rather than a terminal, and you do not want to deploy a
companion binary to every box.

## Requirements

- Android 10 (API 29) or newer
- herdr 0.9.0+ running on each machine, protocol 22 or newer
- SSH access to those machines, and `python3`, `socat`, or a `-U`-capable `nc` on them
- JDK 17 and the Android SDK to build

## Setup

```sh
./gradlew :app:installDebug
```

1. Open the app, tap **+**, and copy the device public key.
2. Add it to `~/.ssh/authorized_keys` on each machine. The private half is generated in the
   Android Keystore and cannot leave the phone.
3. Add a machine: host, port, username. Leave the socket path blank unless you run a named
   herdr session.

## What works

Agent list across machines with live status, pane output rendered with colour, replies and
Enter/Esc/Ctrl-C back to the agent, per-machine reconnect, and SSH jump hosts for boxes the
phone cannot reach directly.

## What does not

There are no push notifications, because nothing runs on your host to send them. The terminal is a
screen snapshot, not a PTY attach; herdr reserves real attach for its own binary protocol.

[plan.md](plan.md) tracks all of it, including the decisions that are still open.

## Layout

```
:app  :feature:{agents,pane,machines}  :domain  :data
:core:{model,protocol,ssh,database,ui}
```

MVVM, Hilt, Compose, one-way dependencies. See
[docs/architecture.md](docs/architecture.md) for why each boundary is where it is, and
[docs/protocol.md](docs/protocol.md) for the herdr protocol details this is built on,
including the ones the published docs get wrong.

## Tests

```sh
./gradlew build     # 46 tests, pure JVM, no emulator
```

## Licence

MIT. See [LICENSE](LICENSE).

This project is independent of both [herdr](https://herdr.dev) (Apache-2.0) and
[herdrm](https://github.com/missuo/herdrm) (PolyForm Noncommercial), the macOS console it
takes its shape from. It is a clean-room client against herdr's documented socket API.
