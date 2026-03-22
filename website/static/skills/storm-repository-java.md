Help the user write a Storm repository using Java.

Fetch https://orm.st/llms-full.txt for complete reference.

Ask: which entity, what custom queries, using Spring Boot?

\`\`\`java
interface UserRepository extends EntityRepository<User, Integer> {
    default Optional<User> findByEmail(String email) {
        return select().where(User_.email, EQUALS, email).getOptionalResult();
    }
    default List<User> findByCity(City city) {
        return select().where(User_.city, EQUALS, city).getResultList();
    }
}

// Obtain the repository
UserRepository userRepository = orm.repository(UserRepository.class);

// Or use the generic entity repository for simple CRUD
var users = orm.entity(User.class);
\`\`\`

Key rules:
1. ALL query methods have EXPLICIT BODIES with \`default\` keyword. Storm does NOT derive queries from method names.
2. Inherited CRUD: insert, insertAndFetch, update, delete, findById, findBy(Key), count, existsById, selectAll, page, scroll.
3. Descriptive variable names: \`var users = orm.entity(User.class)\`, not \`var repo\`.
4. QueryBuilder is IMMUTABLE. Always chain or capture the return value.
5. Streaming: \`selectAll()\` returns a \`Stream\`. ALWAYS use try-with-resources to avoid connection leaks.
6. DELETE/UPDATE without WHERE throws. Use \`unsafe()\` for intentional bulk ops.
7. Spring Boot: define a \`RepositoryBeanFactoryPostProcessor\` with \`repositoryBasePackages\` to auto-register repos as beans.
8. Pagination: \`page(0, 20)\` for offset-based. \`scroll(User_.id, 20)\` for keyset on large tables.

CRUD examples:
\`\`\`java
User user = users.insertAndFetch(new User(null, "alice@example.com", "Alice", city));
Optional<User> found = users.select().where(User_.id, EQUALS, user.id()).getOptionalResult();
users.update(new User(user.id(), user.email(), "Alice Johnson", user.city()));
users.delete(user);
\`\`\`

Java records are immutable. For convenient copy-with-modification, consider Lombok \`@Builder(toBuilder = true)\` or define a \`with\` method.
