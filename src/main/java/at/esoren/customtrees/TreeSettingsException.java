package at.esoren.customtrees;

/** A user-correctable settings validation failure with localized message data. */
final class TreeSettingsException extends IllegalArgumentException {
    private final String languageKey;
    private final Object[] arguments;

    TreeSettingsException(String languageKey, Object... arguments) {
        super(languageKey);
        this.languageKey = languageKey;
        this.arguments = arguments.clone();
    }

    String languageKey() {
        return languageKey;
    }

    Object[] arguments() {
        return arguments.clone();
    }
}
