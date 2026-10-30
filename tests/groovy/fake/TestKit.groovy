package fake

// What the runner hands every test file: the compiled shared functions, the
// built workflows of this variant, and the registry of tests.
class TestKit {
    final String variant
    final Class sharedClass
    final Map<String, BpmnRunner> workflows
    final List<Map> tests = []
    String currentFile

    TestKit(String variant, Class sharedClass, Map<String, BpmnRunner> workflows) {
        this.variant = variant
        this.sharedClass = sharedClass
        this.workflows = workflows
    }

    void register(String name, Map opts, Closure body) {
        if (tests.any { it.file == currentFile && it.name == name }) {
            throw new IllegalArgumentException("${currentFile}: two tests named '${name}'")
        }
        tests << [file: currentFile, name: name, opts: opts, body: body]
    }

    /** Run one registered test in a fresh World. Returns [status:, message:, ms:]. */
    Map runOne(Map t) {
        def only = t.opts.variant
        if (only && only != variant) { return [status: 'skipped', message: "only in the ${only} edition", ms: 0] }
        long t0 = System.nanoTime()
        try {
            def world = new World(this)
            Closure body = (Closure) t.body.rehydrate(world, t.body.owner, t.body.thisObject)
            body.resolveStrategy = Closure.DELEGATE_FIRST
            body.call(world)
            return [status: 'passed', message: '', ms: (System.nanoTime() - t0).intdiv(1_000_000)]
        } catch (Throwable ex) {
            def sw = new StringWriter()
            if (ex instanceof AssertionError) {
                sw << ex.message
            } else {
                ex.printStackTrace(new PrintWriter(sw))
            }
            return [status: 'failed', message: sw.toString(), ms: (System.nanoTime() - t0).intdiv(1_000_000)]
        }
    }
}
