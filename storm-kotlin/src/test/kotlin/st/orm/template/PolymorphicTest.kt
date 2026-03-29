package st.orm.template

import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.context.ContextConfiguration
import org.springframework.test.context.jdbc.Sql
import org.springframework.test.context.junit.jupiter.SpringExtension
import st.orm.Metamodel
import st.orm.Operator.*
import st.orm.Ref
import st.orm.template.model.*

@ExtendWith(SpringExtension::class)
@ContextConfiguration(classes = [IntegrationConfig::class])
@Sql("/data.sql")
open class PolymorphicTest(
    @Autowired val orm: ORMTemplate,
) {

    // Single-Table Inheritance Tests (Animal/Cat/Dog)

    @Test
    fun `select all animals`() {
        val animals = orm.entity(Animal::class)
        val result = animals.select().resultList
        result shouldHaveSize 4
        result[0].shouldBeInstanceOf<Cat>().name shouldBe "Whiskers"
        result[0].shouldBeInstanceOf<Cat>().indoor shouldBe true
        result[1].shouldBeInstanceOf<Cat>()
        result[2].shouldBeInstanceOf<Dog>().name shouldBe "Rex"
        result[2].shouldBeInstanceOf<Dog>().weight shouldBe 30
        result[3].shouldBeInstanceOf<Dog>()
    }

    @Test
    fun `insert cat`() {
        val animals = orm.entity(Animal::class)
        val before = animals.count()
        animals.insert(Cat(name = "Bella", indoor = true))
        animals.count() shouldBe before + 1
        val result = animals.select().resultList
        val last = result.last()
        last.shouldBeInstanceOf<Cat>()
        (last as Cat).name shouldBe "Bella"
        last.indoor shouldBe true
    }

    @Test
    fun `insert dog`() {
        val animals = orm.entity(Animal::class)
        val before = animals.count()
        animals.insert(Dog(name = "Buddy", weight = 25))
        animals.count() shouldBe before + 1
        val result = animals.select().resultList
        val last = result.last()
        last.shouldBeInstanceOf<Dog>()
        (last as Dog).name shouldBe "Buddy"
        last.weight shouldBe 25
    }

    @Test
    fun `update cat`() {
        val animals = orm.entity(Animal::class)
        val result = animals.select().resultList
        val whiskers = result[0] as Cat
        whiskers.name shouldBe "Whiskers"
        animals.update(Cat(id = whiskers.id, name = "Sir Whiskers", indoor = true))
        val updated = animals.select().resultList
        (updated[0] as Cat).name shouldBe "Sir Whiskers"
    }

    @Test
    fun `remove animal`() {
        val animals = orm.entity(Animal::class)
        animals.insert(Cat(name = "Temp", indoor = false))
        val before = animals.count()
        val result = animals.select().resultList
        animals.remove(result.last())
        animals.count() shouldBe before - 1
    }

    @Test
    fun `select animal count`() {
        val animals = orm.entity(Animal::class)
        animals.count() shouldBe 4
    }

    @Test
    fun `insertAndFetchId cat`() {
        val animals = orm.entity(Animal::class)
        val id = animals.insertAndFetchId(Cat(name = "Mittens", indoor = true))
        id.shouldNotBeNull()
        id shouldBeGreaterThan 0
    }

    @Test
    fun `select adoption with animal ref`() {
        val adoptions = orm.entity(Adoption::class)
        val result = adoptions.select().resultList
        result shouldHaveSize 2
        result[0].animal.shouldNotBeNull()
        result[1].animal.shouldNotBeNull()
    }

    // Polymorphic FK Tests (Post/Photo/Comment)

    @Test
    fun `select post`() {
        val posts = orm.entity(Post::class)
        val result = posts.select().resultList
        result shouldHaveSize 2
        result[0].title shouldBe "Hello World"
        result[1].title shouldBe "Second Post"
    }

    @Test
    fun `select photo`() {
        val photos = orm.entity(Photo::class)
        val result = photos.select().resultList
        result shouldHaveSize 2
        result[0].url shouldBe "photo1.jpg"
    }

    @Test
    fun `insert post`() {
        val posts = orm.entity(Post::class)
        val before = posts.count()
        posts.insert(Post(title = "New Post"))
        posts.count() shouldBe before + 1
    }

    @Test
    fun `insert photo`() {
        val photos = orm.entity(Photo::class)
        val before = photos.count()
        photos.insert(Photo(url = "new_photo.png"))
        photos.count() shouldBe before + 1
    }

    // Joined Table Inheritance Tests (JoinedAnimal/JoinedCat/JoinedDog)

    @Test
    fun `select all joined animals`() {
        val animals = orm.entity(JoinedAnimal::class)
        val result = animals.select().resultList
        result shouldHaveSize 3
        result[0].shouldBeInstanceOf<JoinedCat>().name shouldBe "Whiskers"
        result[0].shouldBeInstanceOf<JoinedCat>().indoor shouldBe true
        result[1].shouldBeInstanceOf<JoinedCat>().name shouldBe "Luna"
        result[1].shouldBeInstanceOf<JoinedCat>().indoor shouldBe false
        result[2].shouldBeInstanceOf<JoinedDog>().name shouldBe "Rex"
        result[2].shouldBeInstanceOf<JoinedDog>().weight shouldBe 30
    }

    @Test
    fun `select joined animal count`() {
        val animals = orm.entity(JoinedAnimal::class)
        animals.count() shouldBe 3
    }

    @Test
    fun `insert joined cat`() {
        val animals = orm.entity(JoinedAnimal::class)
        val before = animals.count()
        animals.insert(JoinedCat(name = "Bella", indoor = true))
        animals.count() shouldBe before + 1
        val result = animals.select().resultList
        val last = result.last()
        last.shouldBeInstanceOf<JoinedCat>()
        (last as JoinedCat).name shouldBe "Bella"
        last.indoor shouldBe true
    }

    @Test
    fun `insert joined dog`() {
        val animals = orm.entity(JoinedAnimal::class)
        val before = animals.count()
        animals.insert(JoinedDog(name = "Buddy", weight = 25))
        animals.count() shouldBe before + 1
        val result = animals.select().resultList
        val last = result.last()
        last.shouldBeInstanceOf<JoinedDog>()
        (last as JoinedDog).name shouldBe "Buddy"
        last.weight shouldBe 25
    }

    @Test
    fun `insertAndFetchId joined cat`() {
        val animals = orm.entity(JoinedAnimal::class)
        val id = animals.insertAndFetchId(JoinedCat(name = "Mittens", indoor = true))
        id.shouldNotBeNull()
        id shouldBeGreaterThan 0
    }

    @Test
    fun `update joined cat`() {
        val animals = orm.entity(JoinedAnimal::class)
        val result = animals.select().resultList
        val whiskers = result[0] as JoinedCat
        whiskers.name shouldBe "Whiskers"
        animals.update(JoinedCat(id = whiskers.id, name = "Sir Whiskers", indoor = true))
        val updated = animals.select().resultList
        (updated[0] as JoinedCat).name shouldBe "Sir Whiskers"
    }

    @Test
    fun `update joined cat to dog`() {
        val animals = orm.entity(JoinedAnimal::class)
        val id = animals.insertAndFetchId(JoinedCat(name = "Morpheus", indoor = true))
        animals.update(JoinedDog(id = id, name = "Morpheus", weight = 42))
        val result = animals.select().resultList
        val morpheus = result.first { it.id() == id }
        morpheus.shouldBeInstanceOf<JoinedDog>()
        (morpheus as JoinedDog).name shouldBe "Morpheus"
        morpheus.weight shouldBe 42
    }

    @Test
    fun `update joined dog to cat`() {
        val animals = orm.entity(JoinedAnimal::class)
        val id = animals.insertAndFetchId(JoinedDog(name = "Shifter", weight = 20))
        animals.update(JoinedCat(id = id, name = "Shifter", indoor = false))
        val result = animals.select().resultList
        val shifter = result.first { it.id() == id }
        shifter.shouldBeInstanceOf<JoinedCat>()
        (shifter as JoinedCat).name shouldBe "Shifter"
        shifter.indoor shouldBe false
    }

    @Test
    fun `remove joined animal`() {
        val animals = orm.entity(JoinedAnimal::class)
        animals.insert(JoinedCat(name = "Temp", indoor = false))
        val before = animals.count()
        val result = animals.select().resultList
        animals.remove(result.last())
        animals.count() shouldBe before - 1
    }

    @Test
    fun `removeById joined animal`() {
        val animals = orm.entity(JoinedAnimal::class)
        val id = animals.insertAndFetchId(JoinedDog(name = "TempDog", weight = 10))
        val before = animals.count()
        animals.removeById(id)
        animals.count() shouldBe before - 1
    }

    @Test
    fun `select joined adoption with animal ref`() {
        val adoptions = orm.entity(JoinedAdoption::class)
        val result = adoptions.select().resultList
        result shouldHaveSize 2
        result[0].animal.shouldNotBeNull()
        result[1].animal.shouldNotBeNull()
    }

    // Joined Table Inheritance without @Discriminator (NodscAnimal)

    @Test
    fun `select all nodsc animals`() {
        val animals = orm.entity(NodscAnimal::class)
        val result = animals.select().resultList
        result shouldHaveSize 4
        result[0].shouldBeInstanceOf<NodscCat>().name shouldBe "Whiskers"
        result[0].shouldBeInstanceOf<NodscCat>().indoor shouldBe true
        result[1].shouldBeInstanceOf<NodscCat>()
        result[2].shouldBeInstanceOf<NodscDog>().name shouldBe "Rex"
        result[2].shouldBeInstanceOf<NodscDog>().weight shouldBe 30
        result[3].shouldBeInstanceOf<NodscBird>().name shouldBe "Tweety"
    }

    @Test
    fun `nodsc animal count`() {
        val animals = orm.entity(NodscAnimal::class)
        animals.count() shouldBe 4
    }

    @Test
    fun `insert nodsc cat`() {
        val animals = orm.entity(NodscAnimal::class)
        val before = animals.count()
        animals.insert(NodscCat(name = "Bella", indoor = true))
        animals.count() shouldBe before + 1
        val result = animals.select().resultList
        val last = result.last()
        last.shouldBeInstanceOf<NodscCat>()
        (last as NodscCat).name shouldBe "Bella"
        last.indoor shouldBe true
    }

    @Test
    fun `insert nodsc dog`() {
        val animals = orm.entity(NodscAnimal::class)
        val before = animals.count()
        animals.insert(NodscDog(name = "Buddy", weight = 25))
        animals.count() shouldBe before + 1
        val result = animals.select().resultList
        val last = result.last()
        last.shouldBeInstanceOf<NodscDog>()
        (last as NodscDog).name shouldBe "Buddy"
        last.weight shouldBe 25
    }

    @Test
    fun `insert nodsc bird`() {
        val animals = orm.entity(NodscAnimal::class)
        val before = animals.count()
        animals.insert(NodscBird(name = "Polly"))
        animals.count() shouldBe before + 1
        val result = animals.select().resultList
        val last = result.last()
        last.shouldBeInstanceOf<NodscBird>()
        (last as NodscBird).name shouldBe "Polly"
    }

    @Test
    fun `update nodsc cat`() {
        val animals = orm.entity(NodscAnimal::class)
        val result = animals.select().resultList
        val whiskers = result[0] as NodscCat
        whiskers.name shouldBe "Whiskers"
        animals.update(NodscCat(id = whiskers.id, name = "Sir Whiskers", indoor = true))
        val updated = animals.select().resultList
        (updated[0] as NodscCat).name shouldBe "Sir Whiskers"
    }

    @Test
    fun `update nodsc bird`() {
        val animals = orm.entity(NodscAnimal::class)
        val result = animals.select().resultList
        val tweety = result[3] as NodscBird
        tweety.name shouldBe "Tweety"
        animals.update(NodscBird(id = tweety.id, name = "Tweety Bird"))
        val updated = animals.select().resultList
        (updated[3] as NodscBird).name shouldBe "Tweety Bird"
    }

    @Test
    fun `update nodsc cat to dog`() {
        val animals = orm.entity(NodscAnimal::class)
        val id = animals.insertAndFetchId(NodscCat(name = "Morpheus", indoor = true))
        animals.update(NodscDog(id = id, name = "Morpheus", weight = 42))
        val result = animals.select().resultList
        val morpheus = result.first { it.id() == id }
        morpheus.shouldBeInstanceOf<NodscDog>()
        (morpheus as NodscDog).name shouldBe "Morpheus"
        morpheus.weight shouldBe 42
    }

    @Test
    fun `update nodsc cat to bird`() {
        val animals = orm.entity(NodscAnimal::class)
        val id = animals.insertAndFetchId(NodscCat(name = "Shifter", indoor = true))
        animals.update(NodscBird(id = id, name = "Shifter"))
        val result = animals.select().resultList
        val shifter = result.first { it.id() == id }
        shifter.shouldBeInstanceOf<NodscBird>()
        (shifter as NodscBird).name shouldBe "Shifter"
    }

    @Test
    fun `update nodsc bird to cat`() {
        val animals = orm.entity(NodscAnimal::class)
        val id = animals.insertAndFetchId(NodscBird(name = "Transformer"))
        animals.update(NodscCat(id = id, name = "Transformer", indoor = false))
        val result = animals.select().resultList
        val transformer = result.first { it.id() == id }
        transformer.shouldBeInstanceOf<NodscCat>()
        (transformer as NodscCat).name shouldBe "Transformer"
        transformer.indoor shouldBe false
    }

    @Test
    fun `remove nodsc animal`() {
        val animals = orm.entity(NodscAnimal::class)
        animals.insert(NodscCat(name = "Temp", indoor = false))
        val before = animals.count()
        val result = animals.select().resultList
        animals.remove(result.last())
        animals.count() shouldBe before - 1
    }

    @Test
    fun `remove nodsc bird`() {
        val animals = orm.entity(NodscAnimal::class)
        animals.insert(NodscBird(name = "TempBird"))
        val before = animals.count()
        val result = animals.select().resultList
        result.last().shouldBeInstanceOf<NodscBird>()
        animals.remove(result.last())
        animals.count() shouldBe before - 1
    }

    @Test
    fun `removeById nodsc animal`() {
        val animals = orm.entity(NodscAnimal::class)
        val id = animals.insertAndFetchId(NodscDog(name = "TempDog", weight = 10))
        val before = animals.count()
        animals.removeById(id)
        animals.count() shouldBe before - 1
    }

    // Batch DML Tests for Joined Table Inheritance

    @Test
    fun `batch insert joined animals`(): Unit = runBlocking {
        transaction {
            val animals = orm.entity(JoinedAnimal::class)
            val before = animals.count()
            animals.insert(
                listOf(
                    JoinedCat(name = "Bella", indoor = true),
                    JoinedDog(name = "Max", weight = 20),
                    JoinedCat(name = "Cleo", indoor = false),
                ),
            )
            animals.count() shouldBe before + 3
            val result = animals.select().resultList
            val bella = result[result.size - 3]
            val max = result[result.size - 2]
            val cleo = result[result.size - 1]
            bella.shouldBeInstanceOf<JoinedCat>()
            (bella as JoinedCat).name shouldBe "Bella"
            bella.indoor shouldBe true
            max.shouldBeInstanceOf<JoinedDog>()
            (max as JoinedDog).name shouldBe "Max"
            max.weight shouldBe 20
            cleo.shouldBeInstanceOf<JoinedCat>()
            (cleo as JoinedCat).name shouldBe "Cleo"
            cleo.indoor shouldBe false
        }
    }

    @Test
    fun `batch insertAndFetchIds joined animals`() {
        val animals = orm.entity(JoinedAnimal::class)
        val ids = animals.insertAndFetchIds(
            listOf(
                JoinedCat(name = "Sid", indoor = true),
                JoinedDog(name = "Bud", weight = 15),
            ),
        )
        ids shouldHaveSize 2
        ids.forEach { id ->
            id.shouldNotBeNull()
            id shouldBeGreaterThan 0
        }
    }

    @Test
    fun `batch update joined animals`(): Unit = runBlocking {
        transaction {
            val animals = orm.entity(JoinedAnimal::class)
            animals.insert(
                listOf(
                    JoinedCat(name = "UpdCat", indoor = true),
                    JoinedDog(name = "UpdDog", weight = 10),
                ),
            )
            val result = animals.select().resultList
            val cat = result[result.size - 2] as JoinedCat
            val dog = result[result.size - 1] as JoinedDog
            animals.update(
                listOf(
                    JoinedCat(id = cat.id, name = "UpdatedCat", indoor = false),
                    JoinedDog(id = dog.id, name = "UpdatedDog", weight = 99),
                ),
            )
            val updated = animals.select().resultList
            val updCat = updated[updated.size - 2] as JoinedCat
            val updDog = updated[updated.size - 1] as JoinedDog
            updCat.name shouldBe "UpdatedCat"
            updCat.indoor shouldBe false
            updDog.name shouldBe "UpdatedDog"
            updDog.weight shouldBe 99
        }
    }

    @Test
    fun `batch update joined animals with type change`(): Unit = runBlocking {
        transaction {
            val animals = orm.entity(JoinedAnimal::class)
            val catId = animals.insertAndFetchId(JoinedCat(name = "SwapCat", indoor = true))
            val dogId = animals.insertAndFetchId(JoinedDog(name = "SwapDog", weight = 15))
            animals.update(
                listOf(
                    JoinedDog(id = catId, name = "SwapCat", weight = 33),
                    JoinedCat(id = dogId, name = "SwapDog", indoor = false),
                ),
            )
            val result = animals.select().resultList
            val formerCat = result.first { it.id() == catId }
            val formerDog = result.first { it.id() == dogId }
            formerCat.shouldBeInstanceOf<JoinedDog>()
            (formerCat as JoinedDog).name shouldBe "SwapCat"
            formerCat.weight shouldBe 33
            formerDog.shouldBeInstanceOf<JoinedCat>()
            (formerDog as JoinedCat).name shouldBe "SwapDog"
            formerDog.indoor shouldBe false
        }
    }

    @Test
    fun `batch remove joined animals`(): Unit = runBlocking {
        transaction {
            val animals = orm.entity(JoinedAnimal::class)
            animals.insert(
                listOf(
                    JoinedCat(name = "DelCat", indoor = true),
                    JoinedDog(name = "DelDog", weight = 5),
                ),
            )
            val before = animals.count()
            val result = animals.select().resultList
            val cat = result[result.size - 2]
            val dog = result[result.size - 1]
            animals.remove(listOf(cat, dog))
            animals.count() shouldBe before - 2
        }
    }

    @Test
    fun `batch removeByRef joined animals`() {
        val animals = orm.entity(JoinedAnimal::class)
        val ids = animals.insertAndFetchIds(
            listOf(
                JoinedCat(name = "RefCat", indoor = true),
                JoinedDog(name = "RefDog", weight = 7),
            ),
        )
        val before = animals.count()
        val refs = ids.map { id -> Ref.of(JoinedAnimal::class.java, id) }
        animals.removeByRef(refs)
        animals.count() shouldBe before - 2
    }

    // Batch DML Tests for Joined Table Inheritance without @Discriminator

    @Test
    fun `batch insert nodsc animals`(): Unit = runBlocking {
        transaction {
            val animals = orm.entity(NodscAnimal::class)
            val before = animals.count()
            animals.insert(
                listOf(
                    NodscCat(name = "BatchCat", indoor = true),
                    NodscDog(name = "BatchDog", weight = 22),
                    NodscBird(name = "BatchBird"),
                ),
            )
            animals.count() shouldBe before + 3
            val result = animals.select().resultList
            val cat = result[result.size - 3]
            val dog = result[result.size - 2]
            val bird = result[result.size - 1]
            cat.shouldBeInstanceOf<NodscCat>()
            (cat as NodscCat).name shouldBe "BatchCat"
            dog.shouldBeInstanceOf<NodscDog>()
            (dog as NodscDog).name shouldBe "BatchDog"
            dog.weight shouldBe 22
            bird.shouldBeInstanceOf<NodscBird>()
            (bird as NodscBird).name shouldBe "BatchBird"
        }
    }

    @Test
    fun `batch update nodsc animals`(): Unit = runBlocking {
        transaction {
            val animals = orm.entity(NodscAnimal::class)
            animals.insert(
                listOf(
                    NodscCat(name = "UpdCat", indoor = true),
                    NodscBird(name = "UpdBird"),
                ),
            )
            val result = animals.select().resultList
            val cat = result[result.size - 2] as NodscCat
            val bird = result[result.size - 1] as NodscBird
            animals.update(
                listOf(
                    NodscCat(id = cat.id, name = "UpdatedCat", indoor = false),
                    NodscBird(id = bird.id, name = "UpdatedBird"),
                ),
            )
            val updated = animals.select().resultList
            val updCat = updated[updated.size - 2] as NodscCat
            val updBird = updated[updated.size - 1] as NodscBird
            updCat.name shouldBe "UpdatedCat"
            updCat.indoor shouldBe false
            updBird.name shouldBe "UpdatedBird"
        }
    }

    @Test
    fun `batch update nodsc animals with type change`(): Unit = runBlocking {
        transaction {
            val animals = orm.entity(NodscAnimal::class)
            val catId = animals.insertAndFetchId(NodscCat(name = "SwapCat", indoor = true))
            val birdId = animals.insertAndFetchId(NodscBird(name = "SwapBird"))
            animals.update(
                listOf(
                    NodscBird(id = catId, name = "SwapCat"),
                    NodscDog(id = birdId, name = "SwapBird", weight = 18),
                ),
            )
            val result = animals.select().resultList
            val formerCat = result.first { it.id() == catId }
            val formerBird = result.first { it.id() == birdId }
            formerCat.shouldBeInstanceOf<NodscBird>()
            (formerCat as NodscBird).name shouldBe "SwapCat"
            formerBird.shouldBeInstanceOf<NodscDog>()
            (formerBird as NodscDog).name shouldBe "SwapBird"
            formerBird.weight shouldBe 18
        }
    }

    @Test
    fun `batch remove nodsc animals`(): Unit = runBlocking {
        transaction {
            val animals = orm.entity(NodscAnimal::class)
            animals.insert(
                listOf(
                    NodscCat(name = "DelCat", indoor = false),
                    NodscDog(name = "DelDog", weight = 3),
                    NodscBird(name = "DelBird"),
                ),
            )
            val before = animals.count()
            val result = animals.select().resultList
            val cat = result[result.size - 3]
            val dog = result[result.size - 2]
            val bird = result[result.size - 1]
            animals.remove(listOf(cat, dog, bird))
            animals.count() shouldBe before - 3
        }
    }

    // INTEGER Discriminator Tests

    @Test
    fun `select all integer discriminator animals`() {
        val animals = orm.entity(IntDiscAnimal::class)
        val result = animals.select().resultList
        result shouldHaveSize 4
        result[0].shouldBeInstanceOf<IntDiscCat>().name shouldBe "Whiskers"
        result[0].shouldBeInstanceOf<IntDiscCat>().indoor shouldBe true
        result[1].shouldBeInstanceOf<IntDiscCat>()
        result[2].shouldBeInstanceOf<IntDiscDog>().name shouldBe "Rex"
        result[2].shouldBeInstanceOf<IntDiscDog>().weight shouldBe 30
        result[3].shouldBeInstanceOf<IntDiscDog>()
    }

    @Test
    fun `insert integer discriminator cat`() {
        val animals = orm.entity(IntDiscAnimal::class)
        val before = animals.count()
        animals.insert(IntDiscCat(name = "Bella", indoor = true))
        animals.count() shouldBe before + 1
        val result = animals.select().resultList
        val last = result.last()
        last.shouldBeInstanceOf<IntDiscCat>()
        (last as IntDiscCat).name shouldBe "Bella"
        last.indoor shouldBe true
    }

    @Test
    fun `insert integer discriminator dog`() {
        val animals = orm.entity(IntDiscAnimal::class)
        val before = animals.count()
        animals.insert(IntDiscDog(name = "Buddy", weight = 25))
        animals.count() shouldBe before + 1
        val result = animals.select().resultList
        val last = result.last()
        last.shouldBeInstanceOf<IntDiscDog>()
        (last as IntDiscDog).name shouldBe "Buddy"
        last.weight shouldBe 25
    }

    @Test
    fun `update integer discriminator cat`() {
        val animals = orm.entity(IntDiscAnimal::class)
        val result = animals.select().resultList
        val whiskers = result[0] as IntDiscCat
        whiskers.name shouldBe "Whiskers"
        animals.update(IntDiscCat(id = whiskers.id, name = "Sir Whiskers", indoor = true))
        val updated = animals.select().resultList
        (updated[0] as IntDiscCat).name shouldBe "Sir Whiskers"
    }

    @Test
    fun `remove integer discriminator animal`() {
        val animals = orm.entity(IntDiscAnimal::class)
        animals.insert(IntDiscCat(name = "Temp", indoor = false))
        val before = animals.count()
        val result = animals.select().resultList
        animals.remove(result.last())
        animals.count() shouldBe before - 1
    }

    // CHAR Discriminator Tests

    @Test
    fun `select all char discriminator animals`() {
        val animals = orm.entity(CharDiscAnimal::class)
        val result = animals.select().resultList
        result shouldHaveSize 4
        result[0].shouldBeInstanceOf<CharDiscCat>().name shouldBe "Whiskers"
        result[0].shouldBeInstanceOf<CharDiscCat>().indoor shouldBe true
        result[1].shouldBeInstanceOf<CharDiscCat>()
        result[2].shouldBeInstanceOf<CharDiscDog>().name shouldBe "Rex"
        result[2].shouldBeInstanceOf<CharDiscDog>().weight shouldBe 30
        result[3].shouldBeInstanceOf<CharDiscDog>()
    }

    @Test
    fun `insert char discriminator cat`() {
        val animals = orm.entity(CharDiscAnimal::class)
        val before = animals.count()
        animals.insert(CharDiscCat(name = "Bella", indoor = true))
        animals.count() shouldBe before + 1
        val result = animals.select().resultList
        val last = result.last()
        last.shouldBeInstanceOf<CharDiscCat>()
        (last as CharDiscCat).name shouldBe "Bella"
        last.indoor shouldBe true
    }

    @Test
    fun `insert char discriminator dog`() {
        val animals = orm.entity(CharDiscAnimal::class)
        val before = animals.count()
        animals.insert(CharDiscDog(name = "Buddy", weight = 25))
        animals.count() shouldBe before + 1
        val result = animals.select().resultList
        val last = result.last()
        last.shouldBeInstanceOf<CharDiscDog>()
        (last as CharDiscDog).name shouldBe "Buddy"
        last.weight shouldBe 25
    }

    @Test
    fun `update char discriminator cat`() {
        val animals = orm.entity(CharDiscAnimal::class)
        val result = animals.select().resultList
        val whiskers = result[0] as CharDiscCat
        whiskers.name shouldBe "Whiskers"
        animals.update(CharDiscCat(id = whiskers.id, name = "Sir Whiskers", indoor = true))
        val updated = animals.select().resultList
        (updated[0] as CharDiscCat).name shouldBe "Sir Whiskers"
    }

    @Test
    fun `remove char discriminator animal`() {
        val animals = orm.entity(CharDiscAnimal::class)
        animals.insert(CharDiscCat(name = "Temp", indoor = false))
        val before = animals.count()
        val result = animals.select().resultList
        animals.remove(result.last())
        animals.count() shouldBe before - 1
    }

    // Comment CRUD Tests (Polymorphic FK)

    @Test
    fun `select all comments`() {
        val comments = orm.entity(Comment::class)
        val result = comments.select().resultList
        result shouldHaveSize 3
        result[0].text shouldBe "Nice post!"
        result[0].target.shouldNotBeNull()
        result[0].target.id() shouldBe 1
        result[1].text shouldBe "Great photo!"
        result[1].target.shouldNotBeNull()
        result[1].target.id() shouldBe 1
        result[2].text shouldBe "Love it!"
        result[2].target.shouldNotBeNull()
        result[2].target.id() shouldBe 2
    }

    @Test
    fun `insert comment`() {
        val comments = orm.entity(Comment::class)
        val before = comments.count()

        @Suppress("UNCHECKED_CAST")
        val postRef = Ref.of(Post::class.java, 1) as Ref<Commentable>
        comments.insert(Comment(text = "Kotlin comment", target = postRef))
        comments.count() shouldBe before + 1
        val result = comments.select().resultList
        val last = result.last()
        last.text shouldBe "Kotlin comment"
        last.target.shouldNotBeNull()
        last.target.id() shouldBe 1
    }

    @Test
    fun `select comment count`() {
        val comments = orm.entity(Comment::class)
        comments.count() shouldBe 3
    }

    @Test
    fun `remove comment`() {
        val comments = orm.entity(Comment::class)

        @Suppress("UNCHECKED_CAST")
        val postRef = Ref.of(Post::class.java, 1) as Ref<Commentable>
        comments.insert(Comment(text = "To delete", target = postRef))
        val before = comments.count()
        comments.removeById(before.toInt())
        comments.count() shouldBe before - 1
    }

    // findById Tests

    @Test
    fun `findById single table returns correct subtype`() {
        val animals = orm.entity(Animal::class)
        val result = animals.findById(1)
        result.shouldNotBeNull()
        result.shouldBeInstanceOf<Cat>()
        (result as Cat).name shouldBe "Whiskers"
    }

    @Test
    fun `findById joined table returns correct subtype`() {
        val animals = orm.entity(JoinedAnimal::class)
        val result = animals.findById(1)
        result.shouldNotBeNull()
        result.shouldBeInstanceOf<JoinedCat>()
        (result as JoinedCat).name shouldBe "Whiskers"
    }

    @Test
    fun `findById nodsc returns correct subtype`() {
        val animals = orm.entity(NodscAnimal::class)
        val result = animals.findById(4)
        result.shouldNotBeNull()
        result.shouldBeInstanceOf<NodscBird>()
        (result as NodscBird).name shouldBe "Tweety"
    }

    @Test
    fun `findById non-existent returns null`() {
        val animals = orm.entity(Animal::class)
        val result = animals.findById(999)
        result.shouldBeNull()
    }

    // Where Clause Tests

    @Test
    fun `where clause by id on polymorphic entity`() {
        val animals = orm.entity(Animal::class)
        val result = animals.findById(3)
        result.shouldNotBeNull()
        result.shouldBeInstanceOf<Dog>()
        (result as Dog).name shouldBe "Rex"
    }

    // NodscAnimal MetamodelFactory Tests (joined without @Discriminator)

    @Test
    fun `select nodsc animal by name using MetamodelFactory`() {
        val animals = orm.entity(NodscAnimal::class)
        val name = Metamodel.of<NodscAnimal, String>(NodscAnimal::class.java, "name")
        val result = animals.select().where(name, EQUALS, "Whiskers").resultList
        result shouldHaveSize 1
        result[0].shouldBeInstanceOf<NodscCat>()
        (result[0] as NodscCat).name shouldBe "Whiskers"
    }

    @Test
    fun `select nodsc animal by id using MetamodelFactory`() {
        val animals = orm.entity(NodscAnimal::class)
        val id = Metamodel.of<NodscAnimal, Int>(NodscAnimal::class.java, "id")
        val result = animals.select().where(id, EQUALS, 4).resultList
        result shouldHaveSize 1
        result[0].shouldBeInstanceOf<NodscBird>()
        (result[0] as NodscBird).name shouldBe "Tweety"
    }

    @Test
    fun `select nodsc animal by name like using MetamodelFactory`() {
        val animals = orm.entity(NodscAnimal::class)
        val name = Metamodel.of<NodscAnimal, String>(NodscAnimal::class.java, "name")
        val result = animals.select().where(name, LIKE, "%e%").resultList
        result shouldHaveSize 3
        result[0].shouldBeInstanceOf<NodscCat>()
        result[1].shouldBeInstanceOf<NodscDog>()
        result[2].shouldBeInstanceOf<NodscBird>()
    }

    @Test
    fun `select nodsc animal by name in using MetamodelFactory`() {
        val animals = orm.entity(NodscAnimal::class)
        val name = Metamodel.of<NodscAnimal, String>(NodscAnimal::class.java, "name")
        val result = animals.select().where(name, IN, listOf("Luna", "Tweety")).resultList
        result shouldHaveSize 2
        result[0].shouldBeInstanceOf<NodscCat>()
        result[1].shouldBeInstanceOf<NodscBird>()
    }

    @Test
    fun `count nodsc animal by name using MetamodelFactory`() {
        val animals = orm.entity(NodscAnimal::class)
        val name = Metamodel.of<NodscAnimal, String>(NodscAnimal::class.java, "name")
        animals.select().where(name, EQUALS, "Rex").resultCount shouldBe 1
    }

    // Animal MetamodelFactory Tests (single table with @Discriminator)

    @Test
    fun `select animal by name using MetamodelFactory`() {
        val animals = orm.entity(Animal::class)
        val name = Metamodel.of<Animal, String>(Animal::class.java, "name")
        val result = animals.select().where(name, EQUALS, "Whiskers").resultList
        result shouldHaveSize 1
        result[0].shouldBeInstanceOf<Cat>()
        (result[0] as Cat).name shouldBe "Whiskers"
    }

    @Test
    fun `select animal by id using MetamodelFactory`() {
        val animals = orm.entity(Animal::class)
        val id = Metamodel.of<Animal, Int>(Animal::class.java, "id")
        val result = animals.select().where(id, EQUALS, 3).resultList
        result shouldHaveSize 1
        result[0].shouldBeInstanceOf<Dog>()
        (result[0] as Dog).name shouldBe "Rex"
    }

    // Type Change STI Tests

    @Test
    fun `type change single table cat to dog`() {
        val animals = orm.entity(Animal::class)
        val catId = animals.insertAndFetchId(Cat(name = "Morpheus", indoor = true))
        animals.update(Dog(id = catId, name = "Morpheus", weight = 42))
        val result = animals.findById(catId)
        result.shouldNotBeNull()
        result.shouldBeInstanceOf<Dog>()
        (result as Dog).weight shouldBe 42
    }

    // insertAndFetch / updateAndFetch for JTI

    @Test
    fun `insertAndFetch joined cat`() {
        val animals = orm.entity(JoinedAnimal::class)
        val insertedCat = animals.insertAndFetch(JoinedCat(name = "Fetchy", indoor = true))
        insertedCat.shouldBeInstanceOf<JoinedCat>()
        (insertedCat as JoinedCat).name shouldBe "Fetchy"
        insertedCat.indoor shouldBe true
        insertedCat.id shouldBeGreaterThan 0
    }

    @Test
    fun `updateAndFetch joined dog`() {
        val animals = orm.entity(JoinedAnimal::class)
        val id = animals.insertAndFetchId(JoinedDog(name = "Buster", weight = 20))
        val updatedDog = animals.updateAndFetch(JoinedDog(id = id, name = "Buster Updated", weight = 25))
        updatedDog.shouldBeInstanceOf<JoinedDog>()
        (updatedDog as JoinedDog).name shouldBe "Buster Updated"
        updatedDog.weight shouldBe 25
    }

    // Adoption Ref Concrete Type

    @Test
    fun `adoption ref resolves to concrete type`() {
        val adoptions = orm.entity(Adoption::class)
        val result = adoptions.select().resultList
        result shouldHaveSize 2
        result[0].animal.shouldNotBeNull()
        result[0].animal.id() shouldBe 1
        result[1].animal.shouldNotBeNull()
        result[1].animal.id() shouldBe 3
    }

    // Batch STI Tests

    @Test
    fun `batch insert single table animals`() {
        val animals = orm.entity(Animal::class)
        val before = animals.count()
        animals.insert(
            listOf(
                Cat(name = "BatchCat", indoor = true),
                Dog(name = "BatchDog", weight = 22),
            ),
        )
        animals.count() shouldBe before + 2
    }

    @Test
    fun `batch update single table animals`() {
        val animals = orm.entity(Animal::class)
        val result = animals.select().resultList
        val whiskers = result[0] as Cat
        val rex = result[2] as Dog
        animals.update(
            listOf(
                Cat(id = whiskers.id, name = "UpdatedWhiskers", indoor = false),
                Dog(id = rex.id, name = "UpdatedRex", weight = 35),
            ),
        )
        val updated = animals.select().resultList
        (updated[0] as Cat).name shouldBe "UpdatedWhiskers"
        (updated[0] as Cat).indoor shouldBe false
        (updated[2] as Dog).name shouldBe "UpdatedRex"
        (updated[2] as Dog).weight shouldBe 35
    }

    @Test
    fun `batch remove single table animals`() {
        val animals = orm.entity(Animal::class)
        animals.insert(
            listOf(
                Cat(name = "DelCat", indoor = false),
                Dog(name = "DelDog", weight = 5),
            ),
        )
        val before = animals.count()
        val result = animals.select().resultList
        val cat = result[result.size - 2]
        val dog = result[result.size - 1]
        animals.remove(listOf(cat, dog))
        animals.count() shouldBe before - 2
    }
}
