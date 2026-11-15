# IPTree

**IPTree** enables Minecraft server owners to enact scary Palantir-like surveillance upon their players. This plugin is meant as an advanced sockpuppet and ban-evasion detection plugin for Spigot servers. Unlike standard moderation plugins that only check a player's *current* IP address, IPTree reconstructs complex historical account-to-IP graphs from compressed server logs and live sessions while trying to avoid dynamic-IP false positives. Running IPTree queries is very performance intensive, therefore this plugin does not offer any functionality to automatically punish ban evaders. IPTree is meant to assist moderators with their work. Use IPTree responsibly as it is a very powerful tool.

---

## Why IPTree?

Traditional IP-ban plugins miss rotating or dynamic IPs:
1. `AccountA` logs in from `203.0.113.10`.
2. One hour later, `AccountB` logs in from `203.0.113.10`.
3. The next day, `AccountA`'s router gets a new IP like `198.51.100.42`, they cheat, and get IP-banned.

A normal ban plugin only blocks `198.51.100.42` and never connects `AccountB` to `AccountA`. IPTree parses your historical `/logs` directory and live joins/quits, links accounts that shared an IP and surfaces the full tree of related accounts & IPs per command. If you made backups of your server logs for many years, cheer up. Now you can actually use them for something. On the other hand, if you do not have a large collection of logs from your server, you will need to wait a few months after installation before IPTree reaches its potential. IPTree is especially powerful when used on anticheat testing servers, as their players usually join with their main account as well as various alt accounts.

---

## Features

- **Retroactive Log Parsing:** Reads both `.log.gz` archives and `.log` files directly from the server's `/logs` directory.
- **Time-Bounded Graph Analysis:** Connects accounts sharing an IP within `AccountIPRelationHours` to prevent false links when ISPs reassign dynamic IPs weeks or months later.
- **1-Hop Cluster Merging (`joinclusters`):** Merge disjoint time clusters on a queried IP or across a player's direct IPs to catch static-IP ban evaders returning months later.
- **Real-Time Session Recording (`LiveMode`):** Log player join and quit timestamps directly to MySQL in real time.
- **Multi-Server Network Support:** Aggregate, deduplicate, and chronologically merge historical logs across multiple backend servers (e.g., Lobby, Survival, Creative).
- **Dirty IP Filtering (`SkipDirtyData`):** Automatically skips private, loopback, link-local, and unspecified IPv4/IPv6 ranges (`127.0.0.0/8`, `10.0.0.0/8`, `172.16.0.0/12`, `192.168.0.0/16`, `::1`, `fc00::/7`, `fe80::/10`, etc.).
- **Interactive Chat Output:** Clickable usernames, UUIDs, and IPv4/IPv6 addresses in chat (`raw`, `bundle`, or `single` IP display modes) with full subcommand and dynamic config-key tab completion.

---

## Requirements

- **Server:** Spigot Version 1.8.8
  - IPTree may work on other Spigot versions, however no support is given for that. 
- **Java:** Java 8 (`1.8`) or newer
- **Database:** MySQL / MariaDB

---

## Installation & Setup

### Single-Server Setup

1. Place `IPTree.jar` into your server's `plugins/` folder and start the server once to generate `plugins/IPTree/config.yml`.
2. Configure your MySQL database credentials (`SQLHost`, `SQLPort`, `SQLDatabase`, `SQLUsername`, `SQLPassword`) in `plugins/IPTree/config.yml` and leave `LiveMode: false` for now. If you currently do not have a database set up or wish to use a separate database for this plugin, you will have to create the database first.
3. Restart the server so IPTree connects to MySQL and creates the `iptree` table.
   - If the database cannot be reached on startup, IPTree enters Sleep Mode until a valid database connection is configured and the server is restarted.
4. Run `/iptree db create` once to parse historical `/logs` into your database. Depending on the amount of available log files, this process can take anywhere from a few minutes to multiple hours. Ensure your Minecraft server has enough storage as IPTree decompresses every log file during this process. For example, if you have 300 MB of compressed log files, the uncompressed log files might require 15 GB of space.
5. Once the IPTree database has been created, stop the server, set `LiveMode: true` in `plugins/IPTree/config.yml`, and only then start the server again. Following this sequence ensures your database has the least amount of possible gaps.

### Multi-Server Network Setup (Contributor / Architect)

When aggregating logs from multiple backend servers into one shared MySQL database:

**Proxy / BungeeCord / Velocity Note:** Ensure IP forwarding is properly enabled (`ip_forward: true` on BungeeCord / forwarding enabled on Velocity, and `bungeecord: true` in `spigot.yml`). Without IP forwarding, all players appear to connect from `127.0.0.1`.

1. **On every Contributor server:**
   - Set `MultiServer: true` and `MultiServerRole: contributor` in `config.yml`, then restart or run `/iptree reload`.
   - Run `/iptree preparemultiserver` to export parsed logs to `logs/iptree_multisrvcache/iptree_logdata_<ServerIdentifier>.yml`.
2. **On the central Architect server:**
   - Set `MultiServer: true` and `MultiServerRole: architect` in `config.yml`, then restart.
   - Run `/iptree preparemultiserver` (or let `/iptree db create` generate local cache data automatically).
   - Copy all `iptree_logdata_<ServerIdentifier>.yml` files from your Contributor servers into the Architect server's `logs/iptree_multisrvcache/` folder.
   - Run `/iptree db create` on the Architect server to deduplicate, resolve conflicts, sort chronologically, and populate the SQL table.

3. **Now enable LiveMode on all servers and restart them at the same time**.

---

## Commands & Permissions

**Permission:** `iptree.admin` *(default: `op`)*

| Command | Description |
| :--- | :--- |
| `/iptree get <uuid/username/ip> [joinclusters (true/false)]` | Look up a player (UUID or username) or IPv4/IPv6 address. Defaults `joinclusters` to `false`. |
| `/iptree massget <joinclusters (true/false)> <target1,target2,...>` | Sequentially look up a comma-separated list of UUIDs, usernames, or IPs (silently skips targets with no data). |
| `/iptree recentget <num> [joinclusters (true/false)]` | Look up the `num` players who most recently played on the server (deduplicated, with the most recent player printed last for easy console viewing). |
| `/iptree db create` | Parse server logs (or MultiServer cache files) into an empty `iptree` table. |
| `/iptree db stats` | Display total log entries, unique IPs, and unique accounts in the database. |
| `/iptree db conflicts` | List recorded sessions where no matching exit timestamp was found (`exittime IS NULL`). |
| `/iptree cfg <config-option-name> [value]` | View or update permitted configuration options in-game. |
| `/iptree reload` | Reload `config.yml` from disk. |
| `/iptree preparemultiserver` | Export local historical logs to `logs/iptree_multisrvcache/` for MultiServer setups. |

### How `joinclusters` Works

- `false` (Time-Bounded):
  - **IP lookup:** Splits sessions on that IP separated by more than `AccountIPRelationHours` into separate groups.
  - **Player lookup:** Returns only accounts linked within `AccountIPRelationHours`.
- `true` (Merge Disjoint Clusters):
  - **IP lookup:** Merges all disjoint time clusters on the queried IP into one combined group.
  - **Player lookup:** Performs a 1-hop IP merge across the queried player's direct IPs. This pulls in any accounts and their time-valid groups that ever used the player's IPs regardless of time gap, without chaining infinitely through unrelated dynamic ISP pools.

**Important Remark:** IPTree can produce false positives due to dynamic IP pools (e.g., ISP address rotation, CGNAT, or shared VPNs). The chance of false positives increases when `AccountIPRelationHours` or `JoinLeaveRelationHours` is set to a high value and when `joinclusters` is activated. You can use IPTree query results as an indication of ban evasion, not as definitive proof.

---

## Configuration (`config.yml`)

```yaml
SQLHost: 127.0.0.1
SQLPort: 3306
SQLDatabase: mcserver
SQLUsername: mcserver
SQLPassword: your-secure-password
JoinLeaveRelationHours: 24
AccountIPRelationHours: 24
SkipDirtyData: true
LiveMode: false
IPDisplayMode: raw
ServerIdentifier: undefined
MultiServer: false
MultiServerRole: none
```

| Option | Default | `/iptree cfg` | Description |
| :--- | :--- | :---: | :--- |
| `SQLHost` | `127.0.0.1` | Hidden | MySQL server hostname or IP. |
| `SQLPort` | `3306` | Hidden | MySQL server port. |
| `SQLDatabase` | `mcserver` | Hidden | MySQL database name. |
| `SQLUsername` | `mcserver` | Hidden | MySQL username. |
| `SQLPassword` | `your-secure-password` | Hidden | MySQL password. |
| `JoinLeaveRelationHours` | `24` | Read / Write | Maximum hours between a log join line and leave line to pair them as one session (if no leave is found, `exittime` is stored as `NULL` and treated as a 30-minute session during relation checks). |
| `AccountIPRelationHours` | `24` | Read / Write | Maximum hours between two sessions on the same IP for two accounts to be automatically linked. |
| `SkipDirtyData` | `true` | Read / Write | Skip private, loopback, link-local, and unspecified IPs during log import and `LiveMode`. |
| `LiveMode` | `false` | Read / Write* | Record live player join/quit events to MySQL (*requires a server restart when toggled*). |
| `IPDisplayMode` | `raw` | Read / Write | Format for IP lists in lookup output: `raw` (comma/space-separated), `bundle` (4 IPv4s or 2 IPv6s per line), or `single` (one IP per line). |
| `ServerIdentifier` | Auto-generated | Read-only | Unique 10-character hex identifier for this server instance. |
| `MultiServer` | `false` | Read-only | Enable MultiServer Contributor/Architect mode. |
| `MultiServerRole` | `none` | Read-only | Role when `MultiServer` is `true`: `contributor` or `architect`. |

---

## Building from Source

IPTree uses a zero-dependency raw Java 8 build script (`build.bat`):

1. Build or obtain `spigot-1.8.8.jar` and place it in the project root directory.
2. Ensure a JDK 8 installation is available.
3. Run `build.bat` on your Windows PC.

---

## Privacy & Security Notice

IPTree stores player UUIDs, usernames, session timestamps, and IP addresses in your MySQL database for server moderation and ban-evasion prevention. Server administrators are responsible for securing access to their SQL database and disclosing IP logging in their server's privacy policy where required by applicable laws such as the GDPR.

---

## Disclaimer

The source code of IPTree is predominantly LLM-generated. 
Used models: Gemini 3.1 Pro, Gemini 4 Carbon.

This plugin is not associated with or endorsed by Palantir Technologies Inc.

---

## License

Licensed under the [MIT License](LICENSE.txt)

Copyright (c) 2026 [tree.ac](https://tree.ac)

Claims that *"all Spigot plugins must be GPL-licensed because Spigot is GPL-licensed"* are incorrect. Spigot is built on top of Bukkit and the rights to the Bukkit source code were [secretly bought by Mojang](https://jwheel.org/blog/2020/04/open-source-minecraft-bukkit-gpl/) many years ago. Bukkit/Spigot maintainers can attach whatever license they want to their source code, though that does not make it legally valid or enforceable. In fact, Bukkit & Spigot were taken down from GitHub via a [DMCA takedown notice](https://github.com/github/dmca/blob/master/2014/2014-09-05-CraftBukkit.md) back in 2014. This is also the reason why Spigot cannot provide direct .jar downloads and requires users to deal with BuildTools instead.
