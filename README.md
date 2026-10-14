# ServerStats

A Paper plugin that shows player statistics in Minecraft's native **Dialog screens** (blurred background, centered text, buttons), not chest menus.

## Features

- `/stats [player]` shows **Money, Kills, Deaths and Playtime** for any player (online or offline)
- **View Full Profile** button with K/D ratio, mobs killed, damage dealt/taken, distance walked, jumps, first joined and last seen
- `/leaderboard` shows the top 10 with 4 tabs: **Money, Kills, Playtime, Deaths**. Click a tab to switch.
- Compact number format (`89.2B`, `3.6K`) and playtime like `160d 4h`

## Requirements

- **Paper 26.2** (built against the 26.2 API) (the Dialog API does not exist on older versions or on Spigot)
- Players must also use a **modern client** (Dialogs need 1.21.6+)
- Java 25
- [Vault](https://www.spigotmc.org/resources/vault.34315/) and an economy plugin (e.g. EssentialsX) for the money stat. Without them, money shows as 0.

## Commands and permissions

| Command | Alias | Permission | Description |
|---|---|---|---|
| `/stats [player]` | | `serverstats.stats` | View your own or another player's stats |
| `/leaderboard` | `/lb`, `/top` | `serverstats.leaderboard` | View the leaderboards |

Both permissions are granted to everyone by default.

## Installation

1. Download `ServerStats.jar` from the [Releases](../../releases) page (or from the latest **Actions** run under *Artifacts*).
2. Put it in your server's `plugins` folder.
3. Install Vault and an economy plugin.
4. Restart the server.

## Building from source

```bash
git clone https://github.com/YOUR_USERNAME/ServerStats.git
cd ServerStats
mvn package
```

The jar is created at `target/ServerStats.jar`.

## Notes

- Kills counts **player kills**. To count mob kills too, change `Statistic.PLAYER_KILLS` in `ServerStats.java`.
- The leaderboard is cached for 60 seconds to keep it fast.

## License

MIT, see [LICENSE](LICENSE).
