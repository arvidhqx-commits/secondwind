# SecondWind

**A downed state instead of instant death — bleed out, or get revived by a teammate. Paper 1.21+ and 26.x.**

---

## What it does

When a hit would kill you, you do not die. You go **down**: slowed, glowing, bleeding out on a timer. A
teammate can sneak next to you and hold still to revive you. Attackers can walk over and finish you off.

It turns every fight into a rescue situation, and it works with vanilla clients.

## Why this plugin exists

The best-known plugin for this mechanic moved to a paid model — which proves people will pay for it, and
leaves the *free* slot in the niche empty. SecondWind fills that slot.

## Features

- **Downed state** with a bleed-out timer instead of instant death
- **Revive by sneaking** next to a downed player for a configurable number of seconds, with live progress
- **Execute rules** — decide whether attackers may finish a downed player off
- **Disconnect handling** — optionally kill players who log out while downed
- **Glowing** downed players so rescuers can find them
- **Fully configurable**: bleed-out seconds, revive seconds, revive range, downed health, revived health
- **Every message is MiniMessage** and editable, per-world enable/disable
- No dependencies, one jar

## Commands

| Command | What it does |
|---|---|
| `/sw giveup` (alias of `/secondwind`) | Stop waiting and respawn right away |
| `/secondwind reload` | Reload the config |

| Permission | Default |
|---|---|
| `secondwind.revive` | true |
| `secondwind.reload` | op |

## Configuration highlights

```yaml
bleed-seconds: 30       # until a downed player dies
revive-seconds: 4       # sneaking time needed to revive
revive-range: 3.0       # blocks
downed-health: 4.0      # 2 hearts while downed
revived-health: 8.0     # health after a rescue
allow-execute: true     # attackers may finish you off
die-on-quit: true       # logging out while downed kills you
```

## Compatibility

Built for the Paper API 1.21 and up. Every release is started on a **live Paper 1.21.11 server and a live
Paper 26.2 server** and the actual behaviour is checked — not just "the plugin loads".

## Source & licence

MIT licensed, source on [GitHub](https://github.com/arvidhqx-commits/secondwind).

## Development note

This project is **AI-assisted**: the code is written with Claude under the direction, testing and release
approval of the maintainer. Every release is run against a live Paper server before it ships.
