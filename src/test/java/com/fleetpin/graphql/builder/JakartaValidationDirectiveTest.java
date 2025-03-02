/*
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */
package com.fleetpin.graphql.builder;

import graphql.ExecutionInput;
import graphql.ExecutionResult;
import graphql.GraphQL;
import graphql.introspection.IntrospectionWithDirectivesSupport;
import graphql.schema.FieldCoordinates;
import graphql.schema.GraphQLSchema;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class JakartaValidationDirectiveTest {
	@Test
	public void testJakartaArgumentAnnotationChangedToConstraint() {
		GraphQL schema = GraphQL.newGraphQL(SchemaBuilder.build("com.fleetpin.graphql.builder.type.directive")).build();
		var name = schema.getGraphQLSchema().getFieldDefinition(FieldCoordinates.coordinates(schema.getGraphQLSchema().getMutationType(), "setName"));
		var constraint = name.getArgument("name").getAppliedDirective("Constraint");
		var argument = constraint.getArgument("min");
		var min = argument.getValue();
		assertEquals(3, min);
	}

	@Test
	public void testDirectiveArgumentDefinition() {
		Map<String, Object> response = execute("query IntrospectionQuery { __schema { directives { name locations args { name } } } }", null).getData();
		List<LinkedHashMap<String, Object>> dir = (List<LinkedHashMap<String, Object>>) ((Map<String, Object>) response.get("__schema")).get("directives");
		LinkedHashMap<String, Object> constraint = dir.stream().filter(map -> map.get("name").equals("Constraint")).collect(Collectors.toList()).get(0);

		assertEquals(9, dir.size());
		assertEquals("ARGUMENT_DEFINITION", ((List<String>) constraint.get("locations")).get(0));
		assertEquals(1, ((List<Object>) constraint.get("args")).size());
		assertEquals("{name=validatedBy}", ((List<Object>) constraint.get("args")).getFirst().toString());
		//setName(name: String! @Size(min : 3)): Int!
		//directive @Constraint(name: String!) on ARGUMENT_DEFINITION
	}

	private ExecutionResult execute(String query, Map<String, Object> variables) {
		GraphQLSchema preSchema = SchemaBuilder.builder().classpath("com.fleetpin.graphql.builder.type.directive").build().build();
		GraphQL schema = GraphQL.newGraphQL(new IntrospectionWithDirectivesSupport().apply(preSchema)).build();

		var input = ExecutionInput.newExecutionInput();
		input.query(query);
		if (variables != null) {
			input.variables(variables);
		}
		ExecutionResult result = schema.execute(input);
		return result;
	}
}
