package fake

// The `execution` (DelegateExecution) a script task sees: process variables.
class FakeExecution {
    Map<String, Object> vars = [:]

    Object getVariable(String name) { vars[name] }

    void setVariable(String name, Object value) { vars[name] = value }

    boolean hasVariable(String name) { vars.containsKey(name) }

    void removeVariable(String name) { vars.remove(name) }

    Map<String, Object> getVariables() { new LinkedHashMap(vars) }

    Map<String, Object> snapshot() { new LinkedHashMap(vars) }

    void restore(Map<String, Object> snap) { vars = new LinkedHashMap(snap) }
}
