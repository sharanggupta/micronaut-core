/*
 * Copyright 2017-2026 original authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.micronaut.python.annotation.processing.test

import io.micronaut.data.annotation.GeneratedValue
import io.micronaut.data.annotation.Id
import io.micronaut.data.annotation.MappedEntity
import io.micronaut.data.annotation.Query
import io.micronaut.data.model.runtime.RuntimePersistentEntity
import io.micronaut.core.beans.BeanIntrospector
import io.micronaut.context.ApplicationContext
import io.micronaut.context.RuntimeBeanDefinition
import io.micronaut.context.python.PythonContextRuntime
import io.micronaut.inject.qualifiers.Qualifiers
import io.micronaut.python.compiler.PyronautCompiler
import io.micronaut.validation.validator.Validator
import org.h2.jdbcx.JdbcDataSource
import spock.lang.TempDir
import javax.sql.DataSource
import java.util.function.Supplier

class DataclassFieldDataSpec extends AbstractPythonTypeElementSpec {

    @TempDir
    File classesDirectory

    void "stdlib data metadata emits existing Micronaut Data annotations"() {
        given:
        def context = buildContext('''
from dataclasses import dataclass, field

@dataclass
class Fruit:
    name: str
    id: int | None = field(default=None, metadata={"data": {"id": True, "generated_value": True}})
''')
        def introspection = getBeanIntrospection(context, "python.Fruit")
        def id = introspection.getRequiredProperty("id", Integer)

        expect:
        id.hasAnnotation(Id)
        id.hasAnnotation(GeneratedValue)
        introspection.annotationMetadata.hasAnnotation(MappedEntity)
        introspection.instantiate("Apple", null).asPolyglotValue().getMember("id").isNull()

        cleanup:
        context?.close()
    }

    void "inferred entity retains identity indexes and Python property writeback with validation"() {
        given:
        def context = buildContext('''
import dataclasses as dc
from dataclasses import field as f

@dc.dataclass
class Fruit:
    name: str = f(metadata={"validation": {"not_blank": True}})
    id: int | None = dc.field(default=None, metadata={"data": {"id": True, "generated_value": True}})
''', true)
        def introspection = getBeanIntrospection(context, "python.Fruit")
        def entity = new RuntimePersistentEntity(introspection)
        def fruit = introspection.instantiate("Apple", null)

        expect:
        entity.hasIdentity()
        entity.identity.name == "id"
        entity.identity.generated
        introspection.getIndexedProperty(Id).get().name == "id"
        context.getBean(Validator).validate(introspection, fruit).isEmpty()

        when:
        entity.identity.property.set(fruit, 42)
        fruit.name = " "

        then:
        fruit.id == 42
        fruit.asPolyglotValue().getMember("id").asInt() == 42
        context.getBean(Validator).validate(introspection, fruit).size() == 1

        cleanup:
        context?.close()
    }

    void "generation strategy and existing member options survive canonical metadata (#strategy)"() {
        given:
        def context = buildContext("""
from dataclasses import dataclass, field

@dataclass
class Fruit:
    id: int | None = field(default=None, metadata={"data": {"id": True, "generated_value": {
        "value": "$strategy", "definition": "CREATE SEQUENCE fruit_seq", "ref": "fruit_seq"}}})
""")
        def id = getBeanIntrospection(context, "python.Fruit").getRequiredProperty("id", Integer)

        expect:
        id.annotationMetadata.enumValue(GeneratedValue, GeneratedValue.Type).get().name() == strategy
        id.stringValue(GeneratedValue, "definition").get() == "CREATE SEQUENCE fruit_seq"
        id.stringValue(GeneratedValue, "ref").get() == "fruit_seq"

        cleanup:
        context?.close()

        where:
        strategy << ["AUTO", "SEQUENCE", "IDENTITY", "UUID"]
    }

    void "configured entity introspection and DTO controls remain unchanged"() {
        given:
        def context = buildContext('''
from dataclasses import dataclass, field
from micronaut.data.annotation import MappedEntity
from micronaut.core.annotation import Introspected

@MappedEntity("fruit_rows")
@Introspected(excludes=["secret"])
@dataclass
class Configured:
    id: int | None = field(default=None, metadata={"data": {"id": True}})
    secret: str = "hidden"
    tags: list[str] = field(default_factory=list)

@MappedEntity("composed_rows")
def Entity():
    def decorator(target):
        return target
    return decorator

@Entity
@dataclass
class Composed:
    id: int = field(metadata={"data": {"id": True}})

@dataclass
class Plain:
    id: int

@dataclass
class Validated:
    name: str = field(metadata={"validation": {"not_blank": True}})

@dataclass
class Disabled:
    id: int = field(metadata={"data": {"id": False, "generated_value": False}})

@dataclass
class Empty:
    id: int = field(metadata={"data": {}, "third_party": object()})
''')
        def configured = getBeanIntrospection(context, "python.Configured")

        expect:
        configured.stringValue(MappedEntity).get() == "fruit_rows"
        configured.propertyNames.toList() == ["id", "tags"]
        !configured.getRequiredProperty("id", Integer).hasAnnotation(GeneratedValue)
        getBeanIntrospection(context, "python.Composed").annotationMetadata.hasStereotype(MappedEntity)
        !getBeanIntrospection(context, "python.Composed").hasAnnotation(MappedEntity)
        getBeanIntrospection(context, "python.Composed").stringValue(MappedEntity).get() == "composed_rows"
        ["Plain", "Validated", "Disabled", "Empty"].every {
            !getBeanIntrospection(context, "python.$it").hasAnnotation(MappedEntity)
        }

        when:
        def first = configured.instantiate()
        def second = configured.instantiate()
        first.tags.add("fresh")

        then:
        first.id == null
        second.tags.isEmpty()

        cleanup:
        context?.close()
    }

    void "inferred entity generates JDBC queries and saves and finds through H2"() {
        given:
        // Compile to disk so normal service discovery sees the real introspection and
        // bean-definition indexes; this exercises the unmodified Data JDBC runtime.
        PythonContextRuntime.resetContext()
        def compiler = PyronautCompiler.builder().pythonCode('''
from abc import ABC, abstractmethod
from dataclasses import dataclass, field
from micronaut.data.jdbc.annotation import JdbcRepository
from micronaut.data.repository import CrudRepository

@dataclass
class Fruit:
    name: str
    id: int | None = field(default=None, metadata={"data": {"id": True, "generated_value": True}})

@JdbcRepository(dialect="H2")
class FruitRepository(CrudRepository[Fruit, int], ABC):
    @abstractmethod
    def save(self, fruit: Fruit) -> Fruit:
        pass

''').targetDir(classesDirectory).build()
        compiler.compile()
        def classLoader = new URLClassLoader([classesDirectory.toURI().toURL()] as URL[], this.class.classLoader)
        def dataSource = new JdbcDataSource()
        dataSource.setURL("jdbc:h2:mem:dataclass_field_${UUID.randomUUID()}")
        def schemaConnection = dataSource.connection
        def context = ApplicationContext.builder()
            .classLoader(classLoader)
            .environments("test")
            .properties(["datasources.default.dialect": "H2"])
            .beanDefinitions(RuntimeBeanDefinition.builder(DataSource, { dataSource } as Supplier<DataSource>)
                .qualifier(Qualifiers.byName("default")).singleton(true).build())
            .start()
        def fruitType = classLoader.loadClass("python.Fruit")
        def introspection = BeanIntrospector.forClassLoader(classLoader).getIntrospection(fruitType)
        def repository = context.getBean(classLoader.loadClass("python.FruitRepository"))
        def definition = context.getBeanDefinition(classLoader.loadClass("python.FruitRepository"))
        def save = definition.getRequiredMethod("save", fruitType)
        def find = definition.executableMethods.find { it.methodName == "findById" && it.arguments.length == 1 }
        schemaConnection.createStatement().withCloseable { statement ->
            statement.execute('CREATE TABLE fruit (id INTEGER GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY, name VARCHAR(255) NOT NULL)')
        }

        expect:
        save.stringValue(Query).get() == 'INSERT INTO `fruit` (`name`) VALUES (?)'
        find.stringValue(Query).get() == 'SELECT fruit_.`id`,fruit_.`name` FROM `fruit` fruit_ WHERE (fruit_.`id` = ?)'

        when:
        def original = introspection.instantiate("Apple", null)
        def saved = repository.save(original)
        def found = repository.findById(saved.id).orElseThrow()

        then:
        saved.id != null
        saved.asPolyglotValue().getMember("id").asInt() == saved.id
        found.name == "Apple"
        found.id == saved.id
        repository.findById(saved.id + 1).isEmpty()

        cleanup:
        context?.close()
        schemaConnection?.close()
        classLoader?.close()
        PythonContextRuntime.resetContext()
    }
}
