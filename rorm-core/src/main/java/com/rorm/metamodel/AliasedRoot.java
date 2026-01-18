package com.rorm.metamodel;

/**
 * Represents a joined root entity with an alias for use in query paths.
 * This allows referencing joined tables by alias in path expressions.
 *
 * <p>The alias is mandatory but can be implicitly set to the root's primary table name
 * when using the factory method {@link #of(Root)}.
 *
 * @param root  the underlying root entity
 * @param alias the alias for this joined root (must not be null or blank)
 */
public record AliasedRoot(
    Root root,
    String alias
) implements PathTarget {

    public AliasedRoot {
        if (root == null) {
            throw new IllegalArgumentException("Root cannot be null");
        }
        if (alias == null || alias.isBlank()) {
            throw new IllegalArgumentException("Alias cannot be null or blank");
        }
    }

    /**
     * Creates a JoinedRoot with an explicit alias.
     *
     * @param root  the underlying root entity
     * @param alias the alias for this joined root
     * @return a new JoinedRoot instance
     */
    public static AliasedRoot of(Root root, String alias) {
        return new AliasedRoot(root, alias);
    }

    /**
     * Creates a JoinedRoot with the alias defaulting to the root's primary table name.
     *
     * @param root the underlying root entity
     * @return a new JoinedRoot instance with alias set to root's primary table name
     */
    public static AliasedRoot of(Root root) {
        return new AliasedRoot(root, root.primaryTableName());
    }
}
