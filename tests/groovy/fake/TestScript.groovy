package fake

// Base class of every *Test.groovy file. A test file only registers tests:
//
//   test('a file no search returns is parked') { … }
//   test('…', variant: 'edge') { … }                 // one edition only
//   table('parkFileAsset', [[case: 'Candidate', …], …]) { row -> … }
//
// Each body later runs in its own fresh World (see World.groovy).
//
// Groovy passes named arguments as a leading Map: a helper called as
// helper(world, x, flag: true) must be declared helper(Map opts, World world, x).
abstract class TestScript extends Script {
    TestKit getKit() { (TestKit) binding.getVariable('kit') }

    void test(String name, Closure body) { kit.register(name, [:], body) }

    void test(Map opts, String name, Closure body) { kit.register(name, opts, body) }

    /** One test per row, named "<name> / <row.case>". */
    void table(String name, List<Map> rows, Closure body) { table([:], name, rows, body) }

    void table(Map opts, String name, List<Map> rows, Closure body) {
        rows.each { row ->
            if (!row.case) { throw new IllegalArgumentException("${name}: every row needs a 'case'") }
            kit.register("${name} / ${row.case}".toString(), opts,
                         { World w -> Closure b = body.rehydrate(w, body.owner, body.thisObject); b.resolveStrategy = Closure.DELEGATE_FIRST; b.call(row) })
        }
    }
}
