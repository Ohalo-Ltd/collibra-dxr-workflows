package fake

// Base of every generated fake request DTO: the builder's calls, by method name.
// A builder method called once stores its argument; an add* method (e.g.
// addBusinessItemId) appends to a list under its own name.
class Dto {
    Map<String, Object> props = [:]

    Object prop(String name) { props[name] }

    String toString() { "${getClass().simpleName}${props}" }
}
