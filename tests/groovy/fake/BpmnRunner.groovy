package fake

import groovy.json.JsonSlurper
import groovy.xml.XmlSlurper

// Walks a BUILT workflow (the .bpmn and forms from the deployable ZIP, scripts
// already injected) the way Flowable runs it in Collibra, against FakeCollibra
// and FakeDataXray. Covers the elements the DataXray workflows use: start and
// end events, script tasks, External API tasks (serviceTask flowable:type=http),
// exclusive gateways and user tasks. Anything else stops the run with an error.
//
// Flowable behaviour reproduced:
//   - Start-event form properties seed process variables with their defaults;
//     values passed to start() override them (like REST formProperties).
//   - An expression naming an unset variable fails ("Unknown property used in
//     expression") — in an External API field, a gateway condition or a user
//     task's name. This is how the 29 Oct 2026 headless-abort bug showed.
//   - Gateway conditions use JUEL equality: a Boolean on either side coerces
//     the other ('true' == true). Only `${var}`, `${var == literal}` and
//     `${var != literal}` are supported; a richer expression fails loudly.
//   - An exclusive gateway takes the first outgoing flow whose condition holds,
//     else its default flow, else the run fails.
//   - A script task that throws leaves the instance in error, and its own
//     changes (Collibra writes and variables) are rolled back.
//   - The External API task: method/URL/headers/body from expressions; the
//     response lands in responseVariableName and <prefix>ResponseStatusCode /
//     ResponseReason / ResponseHeaders; with ignoreException a failure sets
//     <prefix>ErrorMessage and the run continues.
// A user task stops the run (state 'waiting'); complete() continues it.
class BpmnRunner {
    final String processId
    final String workflow
    final Map<String, Map> elements = [:]                 // id → [kind:, node:]
    final Map<String, List<Map>> outgoing = [:].withDefault { [] }
    final Map<String, Map> forms = [:]                    // form key → parsed form
    final Map<String, String> formTexts = [:]
    final GroovyShell shell
    String startId
    static final Map<String, Class> COMPILED = [:]        // script text → class, for the whole JVM

    BpmnRunner(String workflow, File bpmnFile, List<File> formFiles, GroovyShell shell) {
        this.workflow = workflow
        this.shell = shell
        def xml = new XmlSlurper(false, false).parse(bpmnFile)
        def process = xml.'**'.find { it.name() == 'process' || it.name() == 'bpmn:process' }
        processId = process.@id.text()
        process.children().each { node ->
            String kind = node.name().replaceFirst(/^bpmn:/, '')
            String id = node.@id.text()
            if (kind == 'sequenceFlow') {
                def cond = node.children().find { it.name().endsWith('conditionExpression') }
                outgoing[node.@sourceRef.text()] << [id: id, target: node.@targetRef.text(),
                                                     condition: cond ? cond.text().trim() : null]
            } else if (id) {
                elements[id] = [kind: kind, node: node]
                if (kind == 'startEvent') { startId = id }
            }
        }
        formFiles.each { f ->
            def text = f.getText('UTF-8')
            def form = new JsonSlurper().parseText(text)
            forms[form.metadata.key.toString()] = form
            formTexts[form.metadata.key.toString()] = text
        }
    }

    // --- running -------------------------------------------------------------------

    /**
     * ctx: fake (FakeCollibra), dataxray (FakeDataXray, Edge only), logger
     * (FakeLogger), item (UUID of the business item, or null), vars (Map: form
     * properties), initiator (String), maxSteps (int).
     */
    RunResult start(Map ctx) {
        def run = new RunResult(runner: this, ctx: ctx, execution: new FakeExecution())
        def start = elements[startId].node
        start.'**'.findAll { it.name() == 'flowable:formProperty' }.each { fp ->
            if (fp.@default.text()) { run.execution.setVariable(fp.@variable.text() ?: fp.@id.text(), fp.@default.text()) }
        }
        if (start.@'flowable:initiator'.text()) {
            run.execution.setVariable(start.@'flowable:initiator'.text(), ctx.initiator ?: 'test-user')
        }
        (ctx.vars ?: [:]).each { k, v -> run.execution.setVariable(k.toString(), v) }
        run.path << startId
        return advance(run, startId)
    }

    /** Complete the user task `run` is waiting at with these form values, and go on. */
    RunResult complete(RunResult run, Map formValues = [:]) {
        if (run.state != 'waiting') { throw new IllegalStateException("not waiting at a user task (state ${run.state})") }
        formValues.each { k, v -> run.execution.setVariable(k.toString(), v) }
        run.state = 'running'
        String at = run.task.id
        run.task = null
        return advance(run, at)
    }

    private RunResult advance(RunResult run, String fromId) {
        String current
        try {
            current = next(fromId, null)
        } catch (Exception ex) {
            return fail(run, fromId, ex)
        }
        int max = (run.ctx.maxSteps ?: 5000) as int
        while (true) {
            if (run.path.size() > max) { return fail(run, current, new IllegalStateException("more than ${max} steps: a loop that never ends")) }
            run.path << current
            def el = elements[current]
            if (el == null) { return fail(run, current, new IllegalStateException("no element '${current}'")) }
            try {
                String chosen = null
                switch (el.kind) {
                    case 'scriptTask': runScript(run, current, el.node); break
                    case 'serviceTask': runHttpTask(run, el.node); break
                    case 'exclusiveGateway': chosen = choose(run, current, el.node); break
                    case 'userTask': return waitAt(run, current, el.node)
                    case 'endEvent': run.state = 'completed'; return run
                    default: throw new IllegalStateException("BpmnRunner: element kind '${el.kind}' not supported")
                }
                current = next(current, chosen)
            } catch (ScriptTaskFailed failed) {
                return fail(run, current, failed.cause)
            } catch (Exception ex) {
                return fail(run, current, ex)
            }
        }
    }

    private String next(String fromId, String chosenFlowId) {
        def flows = outgoing[fromId]
        if (chosenFlowId != null) { return flows.find { it.id == chosenFlowId }.target }
        if (flows.size() != 1) { throw new IllegalStateException("'${fromId}' has ${flows.size()} outgoing flows and is not a gateway") }
        return flows[0].target
    }

    private RunResult fail(RunResult run, String at, Throwable ex) {
        run.state = 'error'
        run.failedAt = at
        run.error = ex
        return run
    }

    private void runScript(RunResult run, String id, node) {
        String text = node.children().find { it.name() == 'script' }.text()
        Class cls = COMPILED.computeIfAbsent(text) { shell.classLoader.parseClass(text, "${processId}_${id}.groovy".toString()) }
        Script script = (Script) cls.getDeclaredConstructor().newInstance()
        script.binding = run.ctx.bindingFor.call(run.execution, run.ctx.item)
        def fakeSnap = run.ctx.fake.snapshot()
        def varSnap = run.execution.snapshot()
        run.scriptRuns << id
        try {
            script.run()
        } catch (Throwable ex) {
            run.ctx.fake.restore(fakeSnap)
            run.execution.restore(varSnap)
            throw new ScriptTaskFailed(ex)
        }
    }

    private void runHttpTask(RunResult run, node) {
        if (node.@'flowable:type'.text() != 'http') { throw new IllegalStateException("serviceTask type '${node.@'flowable:type'}' not supported") }
        def fields = [:]
        node.'**'.findAll { it.name() == 'flowable:field' }.each { fld ->
            def expr = fld.children().find { it.name() == 'flowable:expression' }
            def str = fld.children().find { it.name() == 'flowable:string' }
            fields[fld.@name.text()] = expr ? interpolate(run, expr.text().trim()) : str?.text()?.trim()
        }
        if (run.ctx.dataxray == null) { throw new IllegalStateException('an External API task ran but the test gave no FakeDataXray') }
        String prefix = fields.resultVariablePrefix ?: ''
        Map r = run.ctx.dataxray.handle(fields.requestMethod, fields.requestUrl, fields.requestHeaders, fields.requestBody)
        run.httpCalls << [method: fields.requestMethod, url: fields.requestUrl]
        if (r.error != null) {
            if (fields.ignoreException != 'true') { throw new IllegalStateException("External API task failed: ${r.error}") }
            run.execution.setVariable("${prefix}ErrorMessage".toString(), r.error)
            return
        }
        run.execution.setVariable(fields.responseVariableName.toString(), r.body)
        if (fields.saveResponseParameters == 'true') {
            run.execution.setVariable("${prefix}ResponseStatusCode".toString(), r.status as Integer)
            run.execution.setVariable("${prefix}ResponseReason".toString(), r.reason ?: '')
            run.execution.setVariable("${prefix}ResponseHeaders".toString(), 'Content-Type: application/json')
        }
    }

    private String choose(RunResult run, String id, node) {
        String dflt = node.@default.text()
        for (flow in outgoing[id]) {
            if (flow.id == dflt) { continue }
            if (flow.condition == null) { throw new IllegalStateException("flow '${flow.id}' from gateway '${id}' has no condition and is not the default") }
            if (evaluateCondition(run, flow.condition)) { return flow.id }
        }
        if (dflt) { return dflt }
        throw new IllegalStateException("No outgoing sequence flow of element '${id}' could be selected for continuing the process")
    }

    private RunResult waitAt(RunResult run, String id, node) {
        String formKey = node.@'flowable:formKey'.text()
        String name = interpolate(run, node.@name.text())
        def missing = formVariables(formKey).findAll { run.execution.getVariable(it) == null }
        run.task = [id: id, name: name, formKey: formKey, missingFormVariables: missing]
        run.tasks << run.task
        run.state = 'waiting'
        return run
    }

    /** Every {{variable}} a form shows. */
    List<String> formVariables(String formKey) {
        def text = formTexts[formKey]
        if (text == null) { return [] }
        return (text =~ /\{\{([A-Za-z_]\w*)\}\}/).collect { it[1] }.unique()
    }

    // --- expressions (the JUEL subset the workflows use) ------------------------------

    static final java.util.regex.Pattern SEGMENT = ~/\$\{([^}]*)\}/
    static final java.util.regex.Pattern COMPARISON = ~/^\s*(\w+)\s*(==|!=)\s*(true|false|'[^']*'|"[^"]*"|-?\d+)\s*$/
    static final java.util.regex.Pattern NAME = ~/^\s*(\w+)\s*$/

    String interpolate(RunResult run, String text) {
        return text.replaceAll(SEGMENT) { all, expr ->
            def m = NAME.matcher(expr)
            if (!m.matches()) { throw new IllegalStateException("BpmnRunner: expression not supported: \${${expr}}") }
            String.valueOf(lookup(run, m.group(1), "\${${expr}}"))
        }
    }

    boolean evaluateCondition(RunResult run, String text) {
        def whole = (text =~ /^\$\{(.*)\}$/)
        if (!whole.matches()) { throw new IllegalStateException("BpmnRunner: condition not supported: ${text}") }
        def m = COMPARISON.matcher(whole.group(1))
        if (!m.matches()) { throw new IllegalStateException("BpmnRunner: condition not supported: ${text}") }
        Object left = lookup(run, m.group(1), text)
        String lit = m.group(3)
        Object right = lit == 'true' ? true : lit == 'false' ? false
            : (lit.startsWith("'") || lit.startsWith('"')) ? lit[1..-2] : (lit as long)
        boolean eq = juelEquals(left, right)
        return m.group(2) == '==' ? eq : !eq
    }

    static boolean juelEquals(Object a, Object b) {
        if (a == null || b == null) { return a == null && b == null }
        if (a instanceof Boolean || b instanceof Boolean) { return toBool(a) == toBool(b) }
        if (a instanceof Number || b instanceof Number) {
            try { return new BigDecimal(a.toString()) == new BigDecimal(b.toString()) } catch (NumberFormatException ignored) { return false }
        }
        return a.toString() == b.toString()
    }

    static boolean toBool(Object v) { v instanceof Boolean ? v : Boolean.parseBoolean(v.toString()) }

    private static Object lookup(RunResult run, String name, String expr) {
        if (!run.execution.hasVariable(name)) {
            throw new IllegalStateException("Unknown property used in expression: ${expr}")
        }
        return run.execution.getVariable(name)
    }
}

class ScriptTaskFailed extends RuntimeException {
    ScriptTaskFailed(Throwable cause) { super(cause) }
}

class RunResult {
    BpmnRunner runner
    Map ctx
    FakeExecution execution
    String state = 'running'
    String failedAt
    Throwable error
    Map task
    List<Map> tasks = []
    List<String> path = []
    List<String> scriptRuns = []
    List<Map> httpCalls = []

    Object var(String name) { execution.getVariable(name) }

    Map getVars() { execution.vars }

    String toString() {
        "${runner.workflow} ${state}${failedAt ? ' at ' + failedAt : ''}${error ? ': ' + error : ''}${task ? ' at task ' + task.name : ''}"
    }
}
