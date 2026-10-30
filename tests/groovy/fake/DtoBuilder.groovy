package fake

// The builder() of a generated fake DTO: accepts only the methods Collibra's Javadoc lists.
class DtoBuilder {
    final Class target
    final Set<String> allowed
    final Map<String, Object> props = [:]

    DtoBuilder(Class target, Set<String> allowed) {
        this.target = target
        this.allowed = allowed
    }

    def methodMissing(String name, Object args) {
        Object[] argv = args as Object[]
        if (!allowed.contains(name)) {
            throw new MissingMethodException(name, target, argv)
        }
        Object value = argv.length == 1 ? argv[0] : argv.toList()
        if (name.startsWith('add') && name.length() > 3) {
            ((List) props.computeIfAbsent(name) { [] }) << value
        } else {
            props[name] = value
        }
        return this
    }

    Object build() {
        Dto dto = (Dto) target.getDeclaredConstructor().newInstance()
        dto.props = new LinkedHashMap(props)
        return dto
    }
}
