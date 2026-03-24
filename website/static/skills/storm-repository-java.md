Help the user write a Storm repository using Java.

Fetch https://orm.st/llms-full.txt for complete reference.

Ask: which entity, what custom queries?

Detect the project's framework from its build file (pom.xml or build.gradle.kts): look for `storm-spring-boot-starter` or `spring-boot-starter` (Spring Boot) or neither (standalone). Use the detected framework to suggest the appropriate repository registration pattern.

```java
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
```

Key rules:
1. ALL query methods have EXPLICIT BODIES with `default` keyword. Storm does NOT derive queries from method names.
2. Inherited CRUD: insert, insertAndFetch, update, delete, findById, getById, findBy(Key), count, existsById, selectAll, page, scroll.
3. Descriptive variable names: `var users = orm.entity(User.class)`, not `var repo`.
4. QueryBuilder is IMMUTABLE. Always chain or capture the return value.
5. Streaming: `selectAll()` returns a `Stream`. ALWAYS use try-with-resources to avoid connection leaks.
6. DELETE/UPDATE without WHERE throws. Use `unsafe()` for intentional bulk ops.
7. Spring Boot: define a `RepositoryBeanFactoryPostProcessor` with `repositoryBasePackages` to auto-register repos as beans.
8. Pagination: `page(0, 20)` for offset-based. `scroll(User_.id, 20)` for keyset on large tables.
9. **Use `Ref` for map keys and set membership**: Prefer `Ref<Entity>` (via `.ref()`) for map keys, set membership, and identity-based lookups. `Ref` provides identity-based `equals`/`hashCode` on the primary key. When a projection already returns `Ref<T>`, use it directly without calling `.ref()` again.

## CRUD Operations

```java
// Insert
User user = users.insertAndFetch(new User(null, "alice@example.com", "Alice", city));

// Read
Optional<User> found = users.findById(user.id());        // nullable via Optional
User fetched = users.getById(user.id());                  // throws NoResultException

// Read by predicate
Optional<User> alice = users.select().where(User_.email, EQUALS, "alice@example.com").getOptionalResult();

// Update
users.update(new User(user.id(), user.email(), "Alice Johnson", user.city()));

// Delete
users.delete(user);
users.deleteById(user.id());
```

Java records are immutable. For convenient copy-with-modification, consider Lombok `@Builder(toBuilder = true)` or define a `with` method.

## Upsert (insert or update)

```java
users.upsert(new User(1, "alice@example.com", "Alice", city));
User upserted = users.upsertAndFetch(new User(1, "alice@example.com", "Alice", city));
```

## Ref-Based Operations

```java
Ref<User> ref = user.ref();
Optional<User> found = users.findByRef(ref);
User fetched = users.getByRef(ref);
users.deleteByRef(ref);
```

## Batch and Streaming

```java
// Batch insert/update/delete with iterables
users.insert(List.of(user1, user2, user3));
users.update(List.of(user1, user2));
users.delete(List.of(user1, user2));

// Stream-based operations (ALWAYS use try-with-resources)
try (var stream = users.selectAll()) {
    stream.forEach(System.out::println);
}

// Stream operations with batch size
users.insert(userStream, 100);
users.update(userStream, 100);
```

## Count, Exists, Unique Key Lookups

```java
long count = users.count();
boolean exists = users.existsById(userId);
boolean existsByRef = users.existsByRef(userRef);

// Unique key lookups
Optional<User> user = users.findBy(User_.email, "alice@example.com");
User fetched = users.getBy(User_.email, "alice@example.com");   // throws if not found
```

After writing repository methods, offer to write a test using `SqlCapture` to verify the generated SQL matches the user's intent:
```java
@StormTest(scripts = {"schema.sql", "data.sql"})
class UserRepositoryTest {
    @Test
    void findByCity(ORMTemplate orm, SqlCapture capture) {
        var userRepository = orm.repository(UserRepository.class);
        City city = orm.entity(City.class).findById(1).orElseThrow();
        List<User> users = capture.execute(() -> userRepository.findByCity(city));
        String sql = capture.statements().getFirst().statement();
        assertTrue(sql.contains("WHERE"));
        assertFalse(users.isEmpty());
    }
}
```
