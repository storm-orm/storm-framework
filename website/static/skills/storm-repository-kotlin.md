Help the user write a Storm repository using Kotlin.

Fetch https://orm.st/llms-full.txt for complete reference.

Ask: which entity, what custom queries?

Detect the project's framework from its build file (pom.xml or build.gradle.kts): look for `storm-kotlin-spring-boot-starter` or `spring-boot-starter` (Spring Boot), `storm-ktor` or `ktor-server-core` (Ktor), or neither (standalone). Use the detected framework to suggest the appropriate repository registration pattern below.

```kotlin
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
```

Key rules:
1. ALL query methods have EXPLICIT BODIES. Storm does NOT derive queries from method names.
2. Inherited CRUD: insert, update, delete, findById, findBy(Key), count, existsById, selectAll, page, scroll.
3. Descriptive variable names: `val users = orm.entity(User::class)`, not `val repo`.
4. QueryBuilder is IMMUTABLE. Always chain or capture the return value.
5. Streaming: `selectAll()` returns a `Flow` with automatic resource cleanup.
6. DELETE/UPDATE without WHERE throws. Use `unsafe()` for intentional bulk ops.
7. Pagination: `page(0, 20)` for offset-based. `scroll(User_.id, 20)` for keyset on large tables.
8. **Use `Ref` for map keys and set membership**: Prefer `Ref<Entity>` (via `.ref()`) for map keys, set membership, and identity-based lookups. `Ref` provides identity-based `equals`/`hashCode` on the primary key. When a projection already returns `Ref<T>`, use it directly without calling `.ref()` again.

## CRUD Operations

```kotlin
// Insert (infix, returns inserted entity with generated ID)
val user = orm insert User(email = "alice@example.com", name = "Alice", city = city)

// Read
val found: User? = orm.entity<User>().findById(user.id)    // nullable
val fetched: User = orm.entity<User>().getById(user.id)     // throws NoResultException
val alice: User? = orm.find { User_.name eq "Alice" }       // by predicate
val all: List<User> = orm.findAll { User_.city eq city }     // list by predicate

// Update (infix)
orm update user.copy(name = "Alice Johnson")

// Delete
orm delete user
orm.delete<User> { User_.city eq city }
```

## Upsert (insert or update)

```kotlin
orm upsert User(id = 1, email = "alice@example.com", name = "Alice", city = city)
```

## Ref-Based Operations

```kotlin
val ref: Ref<User> = user.ref()
val found: User? = users.findByRef(ref)
val fetched: User = users.getByRef(ref)
users.deleteByRef(ref)
orm deleteByRef ref   // infix
```

## Batch and Streaming

```kotlin
// Batch insert/update/delete with iterables
orm insert listOf(user1, user2, user3)
orm update listOf(user1, user2)
orm delete listOf(user1, user2)

// Flow-based streaming (suspending, with automatic resource cleanup)
val allUsers: Flow<User> = users.selectAll()

// Batch operations on Flow with chunk size
users.insert(userFlow, chunkSize = 100)
users.update(userFlow, chunkSize = 100)
users.delete(userFlow, chunkSize = 100)
```

## Count, Exists, Delete by ID

```kotlin
val count: Long = users.count()
val exists: Boolean = users.existsById(userId)
users.deleteById(userId)
users.deleteAll()   // deletes all entities
```

## Unique Key Lookups

```kotlin
val user: User? = users.findBy(User_.email, "alice@example.com")
val user: User = users.getBy(User_.email, "alice@example.com")   // throws if not found
```

## Framework-Specific Repository Registration

Detect the project's framework from its build file and dependencies, then suggest the appropriate pattern:

### Spring Boot
Define a `RepositoryBeanFactoryPostProcessor` with `repositoryBasePackages` to auto-register repos as beans. Or use the Spring Boot Starter which auto-discovers them.
```kotlin
@Service
class UserService(private val userRepository: UserRepository) {
    fun findUser(email: String) = userRepository.findByEmail(email)
}
```

### Ktor
Register repositories via `stormRepositories { }`, then access them in routes:
```kotlin
fun Application.module() {
    install(Storm)
    stormRepositories {
        register(UserRepository::class)       // register individually
        // or: register("com.myapp.repository") // register all in package
    }
    routing {
        get("/users/{email}") {
            val users = call.repository<UserRepository>()
            call.respond(users.findByEmail(call.parameters.getOrFail("email")))
        }
    }
}
```

### Standalone
Create repositories directly from the `ORMTemplate`:
```kotlin
val userRepository = orm.repository<UserRepository>()
```

After writing repository methods, offer to write a test using `SqlCapture` to verify the generated SQL matches the user's intent:
```kotlin
@StormTest(scripts = ["schema.sql", "data.sql"])
class UserRepositoryTest {
    @Test
    fun findByCity(orm: ORMTemplate, capture: SqlCapture) {
        val userRepository = orm.repository<UserRepository>()
        val city = orm.entity<City>().getById(1)
        val users = capture.execute { userRepository.findByCity(city) }
        val sql = capture.statements().first().statement()
        assertContains(sql, "WHERE")
        assertFalse(users.isEmpty())
    }
}
```
