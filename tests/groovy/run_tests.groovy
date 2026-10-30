// Offline test runner for the DataXray workflows (no Collibra, no DataXray).
//
//   groovy -cp tests/groovy tests/groovy/run_tests.groovy \
//       --fixture <dir> --variant <onprem|edge> [--report out.json] [--filter text] <test files…>
//
// <dir> is written by tests/groovy_suite.py: shared.groovy (every shared/ file,
// includes expanded, {{pack:…}} filled in for the variant) and
// workflows/<workflow>/ with the .bpmn and forms of the built ZIP. Normally run
// through pytest (tests/test_groovy.py) or `python tests/groovy_suite.py`.

import fake.BpmnRunner
import fake.TestKit
import groovy.json.JsonOutput
import org.codehaus.groovy.control.CompilerConfiguration

def opts = [files: []]
for (int i = 0; i < args.length; i++) {
    switch (args[i]) {
        case '--fixture': opts.fixture = args[++i]; break
        case '--variant': opts.variant = args[++i]; break
        case '--report': opts.report = args[++i]; break
        case '--filter': opts.filter = args[++i]; break
        default: opts.files << args[i]
    }
}
if (!opts.fixture || !opts.variant || !opts.files) {
    System.err.println('usage: run_tests.groovy --fixture DIR --variant NAME [--report FILE] [--filter TEXT] TEST_FILE…')
    System.exit(2)
}

def loader = this.class.classLoader
def shell = new GroovyShell(loader)
def fixture = new File(opts.fixture)
Class sharedClass = shell.classLoader.parseClass(new File(fixture, 'shared.groovy').getText('UTF-8'), 'SharedFunctions.groovy')

def workflows = [:]
new File(fixture, 'workflows').eachDir { dir ->
    def bpmn = dir.listFiles().find { it.name.endsWith('.bpmn') }
    def forms = dir.listFiles().findAll { it.name.endsWith('.form') }.sort { it.name }
    workflows[dir.name] = new BpmnRunner(dir.name, bpmn, forms, shell)
}

def kit = new TestKit(opts.variant, sharedClass, workflows)
def config = new CompilerConfiguration()
config.scriptBaseClass = 'fake.TestScript'
opts.files.each { path ->
    def file = new File(path)
    kit.currentFile = file.name
    def testShell = new GroovyShell(shell.classLoader, new Binding([kit: kit]), config)
    testShell.parse(file).run()
}

def results = []
kit.tests.each { t ->
    if (opts.filter && !"${t.file} ${t.name}".contains(opts.filter)) { return }
    def r = kit.runOne(t)
    results << [file: t.file, name: t.name] + r
    def mark = r.status == 'passed' ? 'ok  ' : r.status == 'skipped' ? 'skip' : 'FAIL'
    println "${mark} [${opts.variant}] ${t.file} :: ${t.name}${r.status == 'passed' ? " (${r.ms} ms)" : ''}"
    if (r.status == 'failed') { r.message.readLines().take(60).each { println "       ${it}" } }
}
int failed = results.count { it.status == 'failed' }
println "\n${results.count { it.status == 'passed' }} passed, ${failed} failed, ${results.count { it.status == 'skipped' }} skipped [${opts.variant}]"
if (opts.report) { new File(opts.report).setText(JsonOutput.prettyPrint(JsonOutput.toJson([variant: opts.variant, results: results])), 'UTF-8') }
System.exit(failed ? 1 : 0)
