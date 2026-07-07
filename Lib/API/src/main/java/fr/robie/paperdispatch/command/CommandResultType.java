package fr.robie.paperdispatch.command;

/**
 * Result of a command execution, mapped to Brigadier's integer return values:
 * {@link #SUCCESS} produces {@code 1}, {@link #FAILURE} produces {@code 0}.
 */
public enum CommandResultType {
    /** The command completed successfully. */
    SUCCESS,
    /** The command failed. */
    FAILURE
}
