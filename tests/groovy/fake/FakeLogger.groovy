package fake

// loggerApi: keeps every line; set GROOVY_TEST_VERBOSE=1 to echo them.
class FakeLogger {
    static final boolean ECHO = System.getenv('GROOVY_TEST_VERBOSE') == '1'
    List<List<String>> lines = []

    void info(Object m) { log('INFO', m) }
    void warn(Object m) { log('WARN', m) }
    void error(Object m) { log('ERROR', m) }
    void debug(Object m) { log('DEBUG', m) }

    private void log(String level, Object m) {
        lines << [level, String.valueOf(m)]
        if (ECHO) { System.err.println("    ${level.padRight(5)} ${m}") }
    }

    List<String> at(String level) { lines.findAll { it[0] == level }.collect { it[1] } }

    boolean has(String level, String fragment) { at(level).any { it.contains(fragment) } }
}
