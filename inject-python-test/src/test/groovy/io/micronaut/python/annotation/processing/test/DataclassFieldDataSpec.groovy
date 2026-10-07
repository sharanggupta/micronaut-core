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

class DataclassFieldDataSpec extends AbstractPythonTypeElementSpec {

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
}
