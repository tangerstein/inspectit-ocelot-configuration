package rocks.inspectit.ocelot.config.utils;

import java.util.Collection;
import java.util.Set;
import java.util.stream.Stream;

/**
 * A generic utility class.
 */
public class ConfigUtils {

    public static boolean contentsEqual(Set<?> setA, Set<?> setB) {
        if (setA.size() != setB.size()) {
            return false;
        }
        return !setA.stream().anyMatch(elem -> !setB.contains(elem));
    }

    /**
     * Tries to find the given type in the given classloader or one of its parents.
     * Hereby, the given imported packages are taken into account.
     * In addition, java.lang is assumed to be an implicit import.
     *
     * @param typename the name of the type, can also be fully qualified
     * @param context  the classloader to search in
     * @param packages the imported packages, e.g. "java.util", "javax.servlet"
     * @return the Class if it was found, null otherwise
     */
    public static Class<?> locateTypeWithinImports(String typename, ClassLoader context, Collection<String> packages) {
        if (AutoboxingHelper.isPrimitiveType(typename)) {
            return AutoboxingHelper.getPrimitiveClass(typename);
        } else {
            return Stream.concat(
                    Stream.concat(
                            Stream.of(""),
                            packages.stream().map(s -> s + ".")
                    ),
                    Stream.of("java.lang.")
            ).flatMap(prefix -> {
                try {
                    return Stream.of(Class.forName(prefix + typename, false, context));
                } catch (Exception e) {
                    return Stream.empty();
                }
            }).findFirst().orElse(null);
        }
    }

}
