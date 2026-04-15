package fr.robie.paperdispatch.command;

import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public abstract class BaseCommand<T extends Plugin> extends SubCommand<T> {
    @Nullable
    private String description = null;

    public BaseCommand(@NotNull T plugin, @NotNull String name) {
        super(plugin, name);
    }

    public BaseCommand(@NotNull T plugin, @NotNull String name, @NotNull String... aliases) {
        super(plugin, name, aliases);
    }

    @Nullable
    public String getDescription() {
        return this.description;
    }

    public BaseCommand<T> setDescription(@Nullable String description) {
        this.description = description;
        return this;
    }
}
