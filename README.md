# [![](https://jitpack.io/v/1robie/paper-dispatch.svg)](https://jitpack.io/#1robie/paper-dispatch)
![MIT License](https://img.shields.io/badge/license-MIT-green.svg)
![Java 21](https://img.shields.io/badge/java-21-blue.svg)
[![Build Status](https://github.com/1robie/paper-dispatch/actions/workflows/build.yml/badge.svg)](https://github.com/1robie/paper-dispatch/actions/workflows/build.yml)

# 📦 Paper-Dispatch

A Maven library for PaperMC plugins that wraps Brigadier in a declarative command API:
nested sub-commands, typed arguments, GNU-style flags, and permission requirements — without
hand-building command trees.

---

## 🧩 Modules

| Module           | Description                            |
|------------------|----------------------------------------|
| `Lib-API`        | Public API interfaces and abstractions |
| `paper-dispatch` | Full implementation (includes Lib-API) |
| `Exemple-Plugin` | Example plugin using the library       |

---

## 📥 Installation (via JitPack)

### 1. Add the JitPack repository

```xml
<repositories>
    <repository>
        <id>jitpack.io</id>
        <url>https://jitpack.io</url>
    </repository>
</repositories>
```

### 2. Add the dependency

```xml
<dependency>
    <groupId>com.github.1robie.Paper-Dispatch</groupId>
    <artifactId>paper-dispatch</artifactId>
    <version>1.0.3</version>
</dependency>
```

> Replace `1.0.3` with the [latest release tag](https://github.com/1robie/paper-dispatch/releases).

### 3. Shade **and relocate**

Paper-Dispatch is a library, not a plugin — it must be bundled into your jar. Relocate it while
you do so. Two plugins that shade the same unrelocated `fr.robie.paperdispatch` package can pick
up each other's classes, and `OfflinePlayerCache`'s global instance is `static`, so which plugin
owns it becomes classloader-dependent.

```xml
<plugin>
    <groupId>org.apache.maven.plugins</groupId>
    <artifactId>maven-shade-plugin</artifactId>
    <version>3.6.2</version>
    <executions>
        <execution>
            <phase>package</phase>
            <goals><goal>shade</goal></goals>
            <configuration>
                <relocations>
                    <relocation>
                        <pattern>fr.robie.paperdispatch</pattern>
                        <shadedPattern>com.yourplugin.libs.paperdispatch</shadedPattern>
                    </relocation>
                </relocations>
            </configuration>
        </execution>
    </executions>
</plugin>
```

---

## 🚀 Quick start

Register a manager, track commands, then flush them to the server:

```java
public class MyPlugin extends JavaPlugin {

    private final ICommandManager<MyPlugin> commands = new CommandManager<>(this);

    @Override
    public void onEnable() {
        this.commands.trackCommand(new HealCommand(this));
        this.commands.flushRegistrations();
    }

    @Override
    public void onDisable() {
        this.commands.unregisterAll(this);
    }
}
```

Registration is two-phase: `trackCommand` records a command, `flushRegistrations` pushes the
tracked set to the server. Commands tracked after the flush are still registered, so the
ordering is not load-bearing.

> The older names `registerCommand` / `registerCommands` / `unregisterCommands()` still work but
> are deprecated. They were easy to mix up: `registerCommand` never touched the server, and
> `unregisterCommands()` removed only *reloadable* commands despite its name. Use
> `trackCommand` / `flushRegistrations` / `unregisterReloadableCommands` instead.

### A command with arguments and flags

```java
public class HealCommand extends BaseCommand<MyPlugin> {

    public HealCommand(MyPlugin plugin) {
        super(plugin, "heal", "h");           // name + aliases
        this.setDescription("Heal a player");
        this.setPermission("myplugin.heal");
        this.setReloadable(true);             // removed by unregisterReloadableCommands()

        this.addOptionalArgument("target", ArgumentTypes.player());
        this.addFlag(Flags.boolFlag("silent").alias("s"));
        this.addFlag(Flags.doubleFlag("amount", 0.0, 20.0).defaultTo(20.0));
    }

    @Override
    protected CommandResultType perform(CommandDispatch<MyPlugin> dispatch) {
        // resolvePlayer reads the "target" ARGUMENT; getSenderAsPlayer reads the SENDER.
        Player target = dispatch.resolvePlayer("target")
                .orElse(dispatch.getSenderAsPlayer());

        if (target == null) {
            dispatch.getSender().sendMessage("Specify a player when running this from console.");
            return CommandResultType.FAILURE;
        }

        target.setHealth(dispatch.getFlagValue("amount", Double.class));

        if (!dispatch.hasFlag("silent")) {
            target.sendMessage("You have been healed.");
        }
        return CommandResultType.SUCCESS;
    }
}
```

Usage: `/heal Notch --amount 10 -s`

### Without subclassing

```java
BaseCommand<MyPlugin> ping = BaseCommand.builder(this, "ping")
        .description("Check latency")
        .permission("myplugin.ping")
        .executes(dispatch -> {
            dispatch.getSender().sendMessage("Pong!");
            return CommandResultType.SUCCESS;
        })
        .build();

this.commands.trackCommand(ping);
```

Or let the manager build it for you — `trackCommand` also accepts a builder and returns the
constructed command:

```java
BaseCommand<MyPlugin> ping = this.commands.trackCommand(
        BaseCommand.builder(this, "ping")
                .description("Check latency")
                .executes(dispatch -> {
                    dispatch.getSender().sendMessage("Pong!");
                    return CommandResultType.SUCCESS;
                }));
```

### Sub-commands

```java
super(plugin, "guild");
this.addSubCommand(new GuildCreateSubCommand(plugin));   // /guild create <name>
this.addSubCommand(new GuildInviteSubCommand(plugin));   // /guild invite <player>
```

### Reading the command source

`CommandDispatch` exposes the whole `CommandSourceStack`, and the three "who ran this" concepts
are deliberately distinct:

```java
dispatch.getSender();           // who typed it — use for feedback and permission messages
dispatch.getSenderAsPlayer();   // the same, as a Player, or null from console
dispatch.getExecutor();         // the entity acting, which /execute as can change
dispatch.getLocation();         // where it runs, which /execute positioned can change
```

---

## 🚩 Flags

`Flags` covers Brigadier primitives and Paper's argument types — `intFlag`, `stringFlag`,
`worldFlag`, `playerFlag`, `itemStackFlag`, `componentFlag`, and many more. Anything not
covered has `Flags.argFlag(name, argumentType)`.

```java
Flags.intFlag("count", 1, 64).alias("c").defaultTo(1).suggests("1", "16", "64");
```

Suggestions can carry hover tooltips, the same way vanilla annotates registry keys:

```java
Flags.stringFlag("mode").suggests(new LinkedHashMap<>() {{
    put("fast", Component.text("Skips validation"));
    put("safe", Component.text("Checks every entry"));
}});
```

Read them back through the dispatch:

```java
dispatch.hasFlag("verbose");                                  // explicitly provided?
dispatch.getFlagValue("count", Integer.class);                // value or default
dispatch.getFlagValue("count", Integer.class, 5);             // value or fallback
dispatch.getOptionalFlagValue("message", String.class);       // Optional
```

### Two limits worth knowing

1. **Flags must come last.** Flag nodes branch only into other flag nodes, so
   `/cmd sub --verbose` parses and `/cmd --verbose sub` does not.
2. **Keep the flag count low.** Every ordered subset of the flag set becomes a real Brigadier
   node so that flags can be given in any order, and that tree is sent to every client:

   | flags | nodes (with 2 positional args) |
   |------:|-------------------------------:|
   | 3     | 33                             |
   | 4     | 131                            |
   | 5     | 653                            |
   | 6     | ~3900                          |

   Past 4 flags the library logs a warning at startup. Prefer a single `argFlag` carrying
   several options over many boolean flags.

---

## 👤 Offline player cache

Optional UUID ↔ name cache backing `OfflinePlayerArgument`'s tab-completion.

```java
@Override
public void onEnable() {
    OfflinePlayerCache.install(this);        // simplest form
}

@Override
public void onDisable() {
    OfflinePlayerCache.uninstall(this);      // shuts down its HttpClient
}
```

Or hold your own instance — in which case **you** own its lifecycle:

```java
OfflinePlayerCache cache = OfflinePlayerCache.builder(this)
        .maximumSize(5000)
        .maxSuggestions(50)
        .expireAfterAccess(Duration.ofMinutes(10))
        .buildAndRegister();

// onDisable
cache.close();
```

Everything degrades gracefully when no cache is installed: commands still work, they just
won't complete player names.

---

## ⚠️ Known limitation: no bootstrap registration

Paper lets you register commands from a `PluginBootstrap`, which is its preferred path for
`paper-plugin.yml` plugins and the only way commands become available to **datapack command
functions**. Paper-Dispatch does not support this today.

The blocker is ordering: `PluginBootstrap#bootstrap` runs *before* `createPlugin`, so no plugin
instance exists yet — while `BaseCommand<T extends Plugin>` requires one at construction. Lifting
that means threading a deferred plugin reference through `SubCommand`, `CommandDispatch` and
`CommandRequirement`, which is a larger change than it first appears.

If you need datapack function parsing, register those commands directly against
`context.getLifecycleManager()` in your bootstrap and use this library for the rest.

---

## ⚙️ Requirements

- **To build:** JDK **25**. `paper-api` is compiled for Java 25 (class file major 69) and an
  older `javac` cannot read it.
- **To consume:** Java **21** or newer. The published jars are built with `--release 21`.
- PaperMC — built against `paper-api:26.1.2.build.53-stable`
- Maven **3.6+** (a wrapper is included: `./mvnw`)

---

## 🏗️ Building from source

```bash
./mvnw clean verify
```

The output JARs land in each module's `target/` directory, alongside `-sources` and `-javadoc`.

---

## 📄 License

This project is licensed under the [MIT License](LICENSE)
