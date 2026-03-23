Help the user write a Storm repository using Kotlin.

Fetch https://orm.st/llms-full.txt for complete reference.

Ask: which entity, what custom queries, using Spring Boot?

\`\`\`kotlin
interface UserRepository : EntityRepository<User, Int> {
    fun findByEmail(email: String): User? = find { User_.email eq email }
    fun findByCity(city: City): List<User> = findAll { User_.city eq city }
    fun findActiveInCity(city: City): List<User> =
        findAll((User_.city eq city) and (User_.active eq true))
}

// Obtain the repository
val userRepository: UserRepository = orm.repository<UserRepository>()

// Or use the generic entity repository for simple CRUD
val users = orm.entity(User::class)
\`\`\`

Key rules:
1. ALL query methods have EXPLICIT BODIES. Storm does NOT derive queries from method names.
2. Inherited CRUD: insert, update, delete, findById, findBy(Key), count, existsById, selectAll, page, scroll.
3. Descriptive variable names: \`val users = orm.entity(User::class)\`, not \`val repo\`.
4. QueryBuilder is IMMUTABLE. Always chain or capture the return value.
5. Streaming: \`selectAll()\` returns a \`Flow\` with automatic resource cleanup.
6. DELETE/UPDATE without WHERE throws. Use \`unsafe()\` for intentional bulk ops.
7. Spring Boot: define a \`RepositoryBeanFactoryPostProcessor\` with \`repositoryBasePackages\` to auto-register repos as beans.
8. Pagination: \`page(0, 20)\` for offset-based. \`scroll(User_.id, 20)\` for keyset on large tables.
9. **Use `Ref` for map keys and set membership**: Prefer `Ref<Entity>` (via `.ref()`) for map keys, set membership, and identity-based lookups. `Ref` provides identity-based `equals`/`hashCode` on the primary key. When a projection already returns `Ref<T>`, use it directly without calling `.ref()` again.

CRUD examples:
\`\`\`kotlin
val user = orm insert User(email = "alice@example.com", name = "Alice", city = city)
val found: User? = orm.find { User_.id eq user.id }
orm update user.copy(name = "Alice Johnson")
orm delete user
orm.delete<User> { User_.city eq city }
\`\`\`

After writing repository methods, offer to write a test using `SqlCapture` to verify the generated SQL matches the user's intent:
```kotlin
@StormTest(scripts = ["schema.sql", "data.sql"])
class UserRepositoryTest {
    @Test
    fun findByCity(orm: ORMTemplate, capture: SqlCapture) {
        val userRepository = orm.repository<UserRepository>()
        val city = orm.findById<City>(1)!!
        val users = capture.execute { userRepository.findByCity(city) }
        // Verify the SQL structure matches the intent.
        val sql = capture.statements().first().statement()
        assertContains(sql, "WHERE")
        assertFalse(users.isEmpty())
    }
}
```
