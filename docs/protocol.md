# herdr socket protocol, as measured

Everything here was verified against a running `herdr 0.9.0` on macOS, protocol **22**,
using `herdr api schema --json` and a live relay into `~/.config/herdr/herdr.sock`. Where
the published docs and the binary's own schema disagree, the schema wins and the difference
is called out.

Regenerate the schema any time herdr updates:

```sh
herdr api schema --json > herdr-api.schema.json
herdr api snapshot            # live session.snapshot, useful for fixtures
```

## Transport

Newline-delimited JSON over a Unix domain socket. One JSON object per line.

```
request   {"id":"req_1","method":"ping","params":{}}
success   {"id":"req_1","result":{"type":"pong","version":"0.9.0","protocol":22,...}}
error     {"id":"req_1","error":{"code":"invalid_request","message":"missing field `source`"}}
```

Socket discovery, in resolution order: an explicit session name, `HERDR_SOCKET_PATH`,
`HERDR_SESSION=<name>`, then `$HOME/.config/herdr/herdr.sock`. A named session lives at
`~/.config/herdr/sessions/<name>/herdr.sock`.

There is **no authentication**. Access control is the socket's filesystem permissions
(`srw-------`). Any process running as you can drive your agents, which is why the only
remote path is SSH and why this client refuses unknown host keys.

## One request per connection

This is the single most important property and it is easy to get wrong. Two requests
written down one pipe produce **one** response:

```sh
printf '{"id":"a","method":"ping","params":{}}\n{"id":"b","method":"agent.list","params":{}}\n' \
  | python3 relay.py | wc -l
# 1
```

So every RPC needs its own connection, which over SSH means its own exec channel.
`events.subscribe` is the exception: it keeps the connection open and streams.

## Errors echo the id, except when they cannot

Responses echo the request `id`. A request that fails to parse, or has no unambiguous
top-level string id, comes back with `"id":""`. Any response model has to tolerate an empty
id rather than keying strictly on correlation.

## Events

102 methods, 26 event types. Both envelopes look the same:

```json
{"event":"pane_created","data":{"type":"pane_created","pane":{...}}}
```

### The dotted/underscored trap

The string you **subscribe** with is dotted. The string that comes **back** is underscored.

```json
subscribe  {"type":"pane.agent_status_changed","pane_id":"w1:p1"}
delivered  {"event":"pane_agent_status_changed","data":{"type":"pane_agent_status_changed",...}}
```

Sending the underscored form is rejected outright:

```
{"id":"","error":{"code":"invalid_request","message":"invalid request: unknown variant
 `pane_agent_status_changed`, expected one of `workspace.created`, ..."}}
```

### Per-pane filters have no wildcard

Most filters need no extra fields and cover every pane:

```
workspace.created  workspace.updated  workspace.metadata_updated  workspace.renamed
workspace.moved    workspace.reordered workspace.closed           workspace.focused
worktree.created   worktree.opened    worktree.removed
tab.created        tab.closed         tab.focused  tab.renamed  tab.moved
pane.created       pane.closed        pane.updated pane.focused pane.moved
pane.exited        pane.agent_detected layout.updated
```

Three require fields, and one of them is the event this app cares most about:

| Filter | Required |
|---|---|
| `pane.agent_status_changed` | `pane_id` |
| `pane.scroll_changed` | `pane_id` |
| `pane.output_matched` | `pane_id`, `source`, `match` |

There is no wildcard for agent status. A client has to subscribe to every pane by id, plus
the global `pane.created` that announces new ones, then re-subscribe when the set grows.
`events.subscribe` takes an array, so one connection carries all of them:

```json
{"id":"s","method":"events.subscribe","params":{"subscriptions":[
  {"type":"pane.created"}, {"type":"pane.closed"}, {"type":"pane.updated"},
  {"type":"pane.agent_status_changed","pane_id":"w3:p1"},
  {"type":"pane.agent_status_changed","pane_id":"w3:p4"}
]}}
```

```json
{"id":"s","result":{"type":"subscription_started"}}
```

Verified with 15 subscriptions on one connection.

### Payloads worth knowing

`pane_agent_status_changed` carries `agent_status` inline, so a status change needs no
follow-up fetch. It does **not** carry `agent`, so a client merging it into cached state
must preserve the existing agent name.

`pane_output_changed` carries `pane_id`, `workspace_id` and `revision`. Re-read the pane
only when the revision moves, instead of polling.

## Methods used here

| Method | Params | Notes |
|---|---|---|
| `ping` | none | Returns version, `protocol`, capabilities. Gate everything on this |
| `session.snapshot` | none | Result is wrapped: `result.snapshot.{workspaces,tabs,panes}` |
| `pane.read` | `pane_id`, **`source`** (required), `format`, `strip_ansi`, `lines` | Result wrapped in `result.read` |
| `pane.send_text` | `pane_id`, `text` | |
| `pane.send_keys` | `pane_id`, `keys` | Named keys the soft keyboard cannot type |
| `agent.prompt` | `target`, `text`, optional `wait` | Note `target`, not `pane_id` |
| `events.subscribe` | `subscriptions[]` | Long-lived |

`source` is required on `pane.read` and its enum is **not** what you would guess:
`visible`, `recent`, `recent_unwrapped`, `detection`. Passing `screen` fails. `format` is
`text` or `ansi`.

Results are wrapped one level deep. `pane.read` returns
`{"type":...,"read":{"pane_id","workspace_id","tab_id","source","format","text","revision","truncated"}}`,
not the read object directly.

## Agent status

`idle`, `working`, `blocked`, `done`, `unknown`. Panes with no detected agent report
`unknown`; those are plain shells, not broken agents.

This client sorts blocked first, then done, working, idle, unknown. Blocked is the only
status that means a human has to act.

## Versioning

`ping` returns `protocol: 22` on herdr 0.9.0. The client refuses anything older and accepts
anything newer, because the JSON codec ignores unknown fields and herdr's own guidance is to
tolerate additive change rather than pin an exact build. `SessionState.reduce` returns state
unchanged for event types it does not recognise, so a newer server adding events cannot
break an older client.

A separate numbered **binary** protocol handles direct terminal attach and live handoff. It
is same-install only and out of reach for this app. See [research.md](research.md).
